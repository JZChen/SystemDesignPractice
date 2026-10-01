package com.googledocs.ot;

import java.util.HashMap;
import java.util.Map;

/**
 * Client-side OT synchronization: the Jupiter / Google Wave "ACK queue" state machine, with a
 * send gate so local edits are only sent when the host's typing policy says so.
 *
 * <pre>
 *   Synchronized        -- local A       --> Buffering            (buffer = A, no send)
 *   Buffering           -- local B       --> Buffering            (buffer = compose(buffer, B))
 *   Buffering           -- requestSend   --> AwaitingConfirm      (send buffer)
 *   AwaitingConfirm     -- local C       --> AwaitingWithBuffer   (buffer = C)
 *   AwaitingWithBuffer  -- requestSend   --> AwaitingWithBuffer   (sendRequested = true)
 *   AwaitingConfirm     -- ACK           --> Synchronized
 *   AwaitingWithBuffer  -- ACK           --> AwaitingConfirm      (send buffer)  if sendRequested
 *                                        --> Buffering            (wait)         otherwise
 *   any                 -- remote R      --> same state           (transform R through inflight, then buffer)
 * </pre>
 *
 * <p>Invariants:
 * <ul>
 *   <li>{@link #applyLocal} never sends: it only records the edit, so remote ops are always
 *       transformed against the exact local document.</li>
 *   <li>Only {@link #requestSend} (or an ACK while {@code sendRequested}) puts an op on the wire.
 *       New local typing clears {@code sendRequested}: the host must request again after the next pause.</li>
 *   <li>At most one operation is in flight; it is based on server revision {@code serverRevision}.</li>
 *   <li>{@code buffer} is based on the state after {@code inflight}.</li>
 *   <li>Server events are processed strictly in revision order; out-of-order events wait in a small
 *       reorder buffer, duplicates (revision already applied) are ignored.</li>
 *   <li>The ACK for our in-flight op is the server event carrying our session id and in-flight
 *       {@code clientOpId}. Because the server broadcasts in revision order, every concurrent remote
 *       op that precedes our op has already been transformed into {@code inflight} when the ACK lands.</li>
 * </ul>
 */
public final class ClientSyncState {

    /** Callbacks to the host (network + editor). */
    public interface Listener {
        /** Send {@code op} (based on {@code baseRevision}) to the server tagged with {@code clientOpId}. */
        void sendToServer(TextOperation op, long baseRevision, String clientOpId);

        /** Apply a remote op that has been transformed to fit the user's current (local) document. */
        void applyToEditor(TextOperation op);

        /** Local state can no longer be reconciled; the host must reload a snapshot. */
        void resyncRequired(String reason);
    }

    /** Beyond this many out-of-order events, assume a gap and resync. */
    static final int MAX_REORDER = 256;

    private final String sessionId;
    private final String idPrefix;
    private final Listener listener;

    private long serverRevision;
    private int serverLength;          // document length at serverRevision (sanity check)
    private TextOperation inflight;    // null => Synchronized
    private String inflightId;
    private TextOperation buffer;      // local edits not yet sent (based on server ∘ inflight)
    private boolean sendRequested;     // host asked to send; honor it when the in-flight op is ACKed
    private int opCounter;
    private boolean failed;

    private final Map<Long, ServerEvent> reorder = new HashMap<>();

    /**
     * @param sessionId      this client's session id (used to recognize our own ACKs)
     * @param idPrefix       unique per ClientSyncState instance so ids never collide across resyncs
     * @param serverRevision revision of the snapshot the client starts from
     * @param serverLength   length of that snapshot's text
     */
    public ClientSyncState(String sessionId, String idPrefix, long serverRevision, int serverLength, Listener listener) {
        this.sessionId = sessionId;
        this.idPrefix = idPrefix;
        this.serverRevision = serverRevision;
        this.serverLength = serverLength;
        this.listener = listener;
    }

    // ------------------------------------------------------------------------------------------
    // Local edits
    // ------------------------------------------------------------------------------------------

    /**
     * Record a local op (based on the current local document = server ∘ inflight ∘ buffer).
     * Never sends; new typing also cancels a pending {@link #requestSend}.
     */
    public void applyLocal(TextOperation op) {
        if (failed || op.isNoop()) {
            return;
        }
        buffer = (buffer == null) ? op : buffer.compose(op);
        sendRequested = false;
    }

    /**
     * The host's typing policy says "send now" (e.g. 2 s pause). Sends the buffer immediately if
     * nothing is in flight, otherwise as soon as the in-flight op is ACKed.
     */
    public void requestSend() {
        if (failed || buffer == null) {
            return;
        }
        if (inflight == null) {
            sendBuffer();
        } else {
            sendRequested = true;
        }
    }

    private void sendBuffer() {
        inflight = buffer;
        buffer = null;
        sendRequested = false;
        inflightId = nextId();
        listener.sendToServer(inflight, serverRevision, inflightId);
    }

    // ------------------------------------------------------------------------------------------
    // Server events (SSE stream or catch-up fetch), in any order
    // ------------------------------------------------------------------------------------------

    /**
     * @param revision       revision this op produced on the server
     * @param author         session id that authored it
     * @param clientOpId     client op id attached by the author (may be null/empty)
     * @param op             the committed op, based on {@code revision - 1}
     * @param contentLength  server document length after this revision, or -1 if unknown
     */
    public void onServerEvent(long revision, String author, String clientOpId, TextOperation op, int contentLength) {
        if (failed || revision <= serverRevision || reorder.containsKey(revision)) {
            return;
        }
        reorder.put(revision, new ServerEvent(revision, author, clientOpId, op, contentLength));

        while (!failed && reorder.containsKey(serverRevision + 1)) {
            ServerEvent e = reorder.remove(serverRevision + 1);
            try {
                if (isOurAck(e)) {
                    handleAck(e);
                } else {
                    handleRemote(e);
                }
            } catch (OtException ex) {
                fail("could not apply revision " + e.revision + ": " + ex.getMessage());
                return;
            }
        }
        if (!failed && reorder.size() > MAX_REORDER) {
            fail("revision gap after " + serverRevision);
        }
    }

    /** The HTTP send for {@code clientOpId} failed (4xx/5xx/network): state is unknown, resync. */
    public void onSendFailed(String clientOpId) {
        if (!failed && clientOpId != null && clientOpId.equals(inflightId)) {
            fail("send failed");
        }
    }

    private boolean isOurAck(ServerEvent e) {
        return inflight != null
                && sessionId.equals(e.author)
                && inflightId.equals(e.clientOpId);
    }

    private void handleAck(ServerEvent e) {
        // inflight has been transformed against every earlier remote op, so it is based on e.revision-1.
        serverLength = inflight.getTargetLength();
        serverRevision = e.revision;
        checkLength(e);

        inflight = null;
        inflightId = null;
        if (buffer != null && sendRequested) {
            sendBuffer();
        }
        // else: Synchronized, or Buffering until the host's next requestSend()
    }

    private void handleRemote(ServerEvent e) {
        TextOperation remote = e.op;
        if (remote.getBaseLength() != serverLength) {
            throw new OtException("remote op base length " + remote.getBaseLength()
                    + " != known server length " + serverLength);
        }
        serverLength = remote.getTargetLength();
        serverRevision = e.revision;
        checkLength(e);

        // Merge the ACK queue with the server update:
        //   (A', R')  = transform(inflight, R)   -> R' is based on server∘A
        //   (B', R'') = transform(buffer,   R')  -> R'' is based on server∘A∘B (what the user sees)
        if (inflight != null) {
            TextOperation[] pair = TextOperation.transform(inflight, remote);
            inflight = pair[0];
            remote = pair[1];
        }
        if (buffer != null) {
            TextOperation[] pair = TextOperation.transform(buffer, remote);
            buffer = pair[0];
            remote = pair[1];
        }
        listener.applyToEditor(remote);
    }

    private void checkLength(ServerEvent e) {
        if (e.contentLength >= 0 && e.contentLength != serverLength) {
            throw new OtException("content length diverged: server " + e.contentLength + ", client " + serverLength);
        }
    }

    private void fail(String reason) {
        failed = true;
        reorder.clear();
        listener.resyncRequired(reason);
    }

    private String nextId() {
        opCounter++;
        return idPrefix + "-" + opCounter;
    }

    // ------------------------------------------------------------------------------------------
    // Introspection (Inspector UI, tests)
    // ------------------------------------------------------------------------------------------

    /** "Synchronized" | "Buffering" | "AwaitingConfirm" | "AwaitingWithBuffer" | "Resyncing". */
    public String getStateName() {
        if (failed) {
            return "Resyncing";
        }
        if (inflight == null) {
            return buffer == null ? "Synchronized" : "Buffering";
        }
        return buffer == null ? "AwaitingConfirm" : "AwaitingWithBuffer";
    }

    public boolean isSendRequested() {
        return sendRequested;
    }

    public long getServerRevision() {
        return serverRevision;
    }

    public TextOperation getInflight() {
        return inflight;
    }

    public String getInflightId() {
        return inflightId;
    }

    public TextOperation getBuffer() {
        return buffer;
    }

    public boolean isFailed() {
        return failed;
    }

    private static final class ServerEvent {
        final long revision;
        final String author;
        final String clientOpId;
        final TextOperation op;
        final int contentLength;

        ServerEvent(long revision, String author, String clientOpId, TextOperation op, int contentLength) {
            this.revision = revision;
            this.author = author == null ? "" : author;
            this.clientOpId = clientOpId == null ? "" : clientOpId;
            this.op = op;
            this.contentLength = contentLength;
        }
    }
}

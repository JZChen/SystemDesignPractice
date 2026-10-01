package com.googledocs.client;

import com.googledocs.ot.ClientSyncState;
import com.googledocs.ot.InputCoalescer;
import com.googledocs.ot.OperationDiff;
import com.googledocs.ot.OtException;
import com.googledocs.ot.TextOperation;
import jsinterop.annotations.JsFunction;
import jsinterop.annotations.JsType;

/**
 * JavaScript-facing facade of the collaboration client, compiled by J2CL and exported as
 * {@code window.OtClient} (see entry.js).
 *
 * <p>Responsibilities kept in Java:
 * <ul>
 *   <li>Input coalescing window: editor change events -> one {@link TextOperation}</li>
 *   <li>ACK queue ({@link ClientSyncState}): one op in flight + composed buffer</li>
 *   <li>Transforming remote ops through unacknowledged local work, and caret mapping</li>
 * </ul>
 * The JS host only owns the DOM, timers and the network, and calls back in through this class.
 *
 * <p>Invariant: {@code shadow} is the document the ACK queue believes the user sees
 * (server ∘ inflight ∘ buffer). Every entry point first captures the editor text into the queue's
 * buffer (without sending), so a remote op is never applied against stale local state. Only the
 * typing window (2 s pause / 10 s cap) or {@link #flushNow} puts edits on the network.
 */
@JsType
public final class OtClient {

    /** host: POST the op to the server. */
    @JsFunction
    public interface SendFn {
        void send(Object[] ops, double baseRevision, String clientOpId);
    }

    /** host: replace editor text and restore the (transformed) selection. */
    @JsFunction
    public interface RenderFn {
        void render(String text, double selectionStart, double selectionEnd);
    }

    /** host: reload a fresh snapshot, local state is unrecoverable. */
    @JsFunction
    public interface ResyncFn {
        void resync(String reason);
    }

    private final ClientSyncState sync;
    private final InputCoalescer coalescer = new InputCoalescer();
    private final SendFn sendFn;
    private final RenderFn renderFn;
    private final ResyncFn resyncFn;

    private String shadow;
    private int selStart;
    private int selEnd;
    private boolean dirty;

    public OtClient(String sessionId, String instanceId, double serverRevision, String initialText,
                    SendFn sendFn, RenderFn renderFn, ResyncFn resyncFn) {
        this.shadow = initialText == null ? "" : initialText;
        this.sendFn = sendFn;
        this.renderFn = renderFn;
        this.resyncFn = resyncFn;
        this.sync = new ClientSyncState(sessionId, instanceId, (long) serverRevision, shadow.length(),
                new ClientSyncState.Listener() {
                    @Override
                    public void sendToServer(TextOperation op, long baseRevision, String clientOpId) {
                        OtClient.this.sendFn.send(op.toWire(), (double) baseRevision, clientOpId);
                    }

                    @Override
                    public void applyToEditor(TextOperation op) {
                        shadow = op.apply(shadow);
                        selStart = op.transformIndex(selStart);
                        selEnd = op.transformIndex(selEnd);
                        dirty = true;
                    }

                    @Override
                    public void resyncRequired(String reason) {
                        OtClient.this.resyncFn.resync(reason);
                    }
                });
    }

    // ------------------------------------------------------------------------------------------
    // Typing window: edits are buffered locally and sent only after a 2 s pause (10 s cap)
    // ------------------------------------------------------------------------------------------

    /**
     * Record an editor input event: the change is diffed into the local buffer immediately, but no
     * network call happens here. During an IME composition the text is not captured yet.
     *
     * @return absolute time (performance.now() clock) when {@link #maybeFlush} should be called,
     *     or -1 while an IME composition is in progress.
     */
    public double onInput(String editorText, double nowMs, boolean composing) {
        if (!composing) {
            capture(editorText);
        }
        return coalescer.onInput(nowMs, composing);
    }

    /**
     * Timer callback. Sends the buffered edits if the user paused long enough (or hit the cap).
     *
     * @return the next deadline to re-arm the timer for, or -1 if nothing is pending.
     */
    public double maybeFlush(String editorText, double nowMs) {
        if (coalescer.shouldFlush(nowMs)) {
            captureAndSend(editorText);
            return -1;
        }
        return coalescer.nextDeadline();
    }

    /** Explicit "send now", bypassing the typing window (e.g. programmatic initial content). */
    public void flushNow(String editorText) {
        captureAndSend(editorText);
    }

    private void captureAndSend(String editorText) {
        coalescer.onFlushed();
        capture(editorText);
        sync.requestSend();
    }

    /** Diff the editor into the ACK queue's buffer. Never sends and leaves the typing window running. */
    private void capture(String editorText) {
        String text = editorText == null ? "" : editorText;
        TextOperation op = OperationDiff.diff(shadow, text);
        if (op.isNoop()) {
            return;
        }
        shadow = text;
        sync.applyLocal(op);
    }

    // ------------------------------------------------------------------------------------------
    // Server events
    // ------------------------------------------------------------------------------------------

    /**
     * Feed one committed server operation (from SSE or catch-up). Pending keystrokes are captured
     * into the buffer first (not sent), so the remote op is transformed against exactly what the
     * user sees; if the document changes, {@code render} is called once with the merged text and
     * the transformed selection.
     */
    public void onServerEvent(String editorText, double selectionStart, double selectionEnd,
                              double revision, String author, String clientOpId,
                              Object[] ops, double contentLength) {
        capture(editorText);
        selStart = (int) selectionStart;
        selEnd = (int) selectionEnd;
        dirty = false;

        TextOperation op;
        try {
            if (ops == null) {
                throw new OtException("missing ops");
            }
            op = TextOperation.fromWire(ops);
        } catch (OtException e) {
            resyncFn.resync("malformed server op");
            return;
        }
        sync.onServerEvent((long) revision, author, clientOpId, op, (int) contentLength);

        if (dirty) {
            dirty = false;
            renderFn.render(shadow, selStart, selEnd);
        }
    }

    public void onSendFailed(String clientOpId) {
        sync.onSendFailed(clientOpId);
    }

    // ------------------------------------------------------------------------------------------
    // Inspector
    // ------------------------------------------------------------------------------------------

    /** "Synchronized" | "Buffering" | "AwaitingConfirm" | "AwaitingWithBuffer" | "Resyncing". */
    public String getStateName() {
        return sync.getStateName();
    }

    public double getServerRevision() {
        return (double) sync.getServerRevision();
    }

    /** Human-readable in-flight / buffer summary for the Concurrency Inspector. */
    public String describeQueue() {
        TextOperation inflight = sync.getInflight();
        TextOperation buffer = sync.getBuffer();
        return "inflight: " + (inflight == null ? "-" : sync.getInflightId() + " " + inflight)
                + "\nbuffer:   " + (buffer == null ? "-" : buffer.toString())
                + (sync.isSendRequested() ? "  (send on ACK)" : "");
    }
}

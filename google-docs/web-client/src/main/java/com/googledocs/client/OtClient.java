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
 * (server ∘ inflight ∘ buffer). Every entry point first flushes the editor text into the queue,
 * so a remote op is never applied against stale local state.
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
    // Typing window
    // ------------------------------------------------------------------------------------------

    /**
     * Record an editor input event.
     *
     * @return absolute time (performance.now() clock) when {@link #maybeFlush} should be called,
     *     or -1 while an IME composition is in progress.
     */
    public double onInput(String editorText, double nowMs, boolean composing) {
        return coalescer.onInput(nowMs, composing);
    }

    /**
     * Timer callback. Flushes if the window policy says so.
     *
     * @return the next deadline to re-arm the timer for, or -1 if nothing is pending.
     */
    public double maybeFlush(String editorText, double nowMs) {
        if (coalescer.shouldFlush(nowMs)) {
            flush(editorText);
            return -1;
        }
        return coalescer.nextDeadline();
    }

    /** Forced flush (before applying remote ops, on blur, on initial content). */
    public void flushNow(String editorText) {
        flush(editorText);
    }

    private void flush(String editorText) {
        coalescer.onFlushed();
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
     * Feed one committed server operation (from SSE or catch-up). Pending keystrokes are flushed
     * first; if remote ops change the document, {@code render} is called once with the merged
     * text and the transformed selection.
     */
    public void onServerEvent(String editorText, double selectionStart, double selectionEnd,
                              double revision, String author, String clientOpId,
                              Object[] ops, double contentLength) {
        flush(editorText);
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

    /** "Synchronized" | "AwaitingConfirm" | "AwaitingWithBuffer" | "Resyncing". */
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
                + "\nbuffer:   " + (buffer == null ? "-" : buffer.toString());
    }
}

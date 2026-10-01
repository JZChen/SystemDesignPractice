package com.googledocs.ot;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class ClientSyncStateTest {

    // ------------------------------------------------------------------------------------------
    // Deterministic state-machine walkthrough
    // ------------------------------------------------------------------------------------------

    @Test
    void stateTransitionsAndMergeWithRemote() {
        RecordingListener l = new RecordingListener();
        ClientSyncState s = new ClientSyncState("me", "t", 0, 5, l); // server doc "hello" at rev 0
        String local = "hello";

        // Synchronized -> Buffering: local edits never send by themselves
        TextOperation a = OperationDiff.diff(local, "hello!");
        local = a.apply(local);
        s.applyLocal(a);
        assertEquals("Buffering", s.getStateName());
        assertEquals(0, l.sent.size());

        // Buffering -> AwaitingConfirm on requestSend (the 2 s pause)
        s.requestSend();
        assertEquals("AwaitingConfirm", s.getStateName());
        assertEquals(1, l.sent.size());
        assertEquals(0L, l.sentBase.get(0));

        // AwaitingConfirm -> AwaitingWithBuffer (and composing further local ops)
        TextOperation b = OperationDiff.diff(local, "hello!!");
        local = b.apply(local);
        s.applyLocal(b);
        TextOperation c = OperationDiff.diff(local, "hello!!?");
        local = c.apply(local);
        s.applyLocal(c);
        assertEquals("AwaitingWithBuffer", s.getStateName());
        s.requestSend(); // pause while A is in flight: remembered, not sent
        assertEquals(1, l.sent.size(), "only one op may be in flight");
        assertTrue(s.isSendRequested());

        // Remote op from someone else, based on rev 0: insert ">> " at the start of "hello"
        TextOperation remote = TextOperation.ofSpan(5, 0, 0, ">> ");
        s.onServerEvent(1, "peer", "p-1", remote, 8);
        local = l.applied.get(0).apply(local);
        assertEquals(">> hello!!?", local);

        // ACK for our in-flight op arrives as revision 2 -> requested buffer is sent, based on rev 2
        s.onServerEvent(2, "me", l.sentIds.get(0), null, 9);
        assertEquals("AwaitingConfirm", s.getStateName());
        assertEquals(2, l.sent.size());
        assertEquals(2L, l.sentBase.get(1));
        assertEquals(">> hello!!?", l.sent.get(1).apply(">> hello!"));

        // ACK for the buffer -> Synchronized
        s.onServerEvent(3, "me", l.sentIds.get(1), null, 11);
        assertEquals("Synchronized", s.getStateName());
        assertNull(l.resyncReason);
    }

    @Test
    void localEditsAreBufferedUntilRequestSend() {
        RecordingListener l = new RecordingListener();
        ClientSyncState s = new ClientSyncState("me", "t", 0, 0, l);
        String local = "";
        for (String next : new String[] {"H", "He", "Hel", "Hell", "Hello"}) {
            TextOperation op = OperationDiff.diff(local, next);
            local = next;
            s.applyLocal(op);
        }
        assertEquals(0, l.sent.size(), "typing alone must not hit the network");
        assertEquals("Buffering", s.getStateName());

        s.requestSend();
        assertEquals(1, l.sent.size(), "all buffered keystrokes go out as one op");
        assertEquals("Hello", l.sent.get(0).apply(""));
        assertNull(s.getBuffer());
    }

    @Test
    void requestSendWhileInflightSendsBufferOnAck() {
        RecordingListener l = new RecordingListener();
        ClientSyncState s = new ClientSyncState("me", "t", 0, 0, l);
        s.applyLocal(new TextOperation().insert("a"));
        s.requestSend();
        s.applyLocal(new TextOperation().retain(1).insert("b"));
        s.requestSend();
        assertEquals(1, l.sent.size());

        s.onServerEvent(1, "me", l.sentIds.get(0), null, 1);
        assertEquals(2, l.sent.size());
        assertEquals(1L, l.sentBase.get(1));
        assertEquals("AwaitingConfirm", s.getStateName());
    }

    @Test
    void typingAfterRequestHoldsBufferPastAck() {
        RecordingListener l = new RecordingListener();
        ClientSyncState s = new ClientSyncState("me", "t", 0, 0, l);
        s.applyLocal(new TextOperation().insert("a"));
        s.requestSend();                                        // A in flight
        s.applyLocal(new TextOperation().retain(1).insert("b"));
        s.requestSend();                                        // pause...
        s.applyLocal(new TextOperation().retain(2).insert("c")); // ...but the user resumed typing
        assertFalse(s.isSendRequested());

        s.onServerEvent(1, "me", l.sentIds.get(0), null, 1);
        assertEquals(1, l.sent.size(), "buffer must wait for the next pause");
        assertEquals("Buffering", s.getStateName());

        s.requestSend();
        assertEquals(2, l.sent.size());
        assertEquals("abc", l.sent.get(1).apply("a"));
    }

    @Test
    void remoteOpWhileBufferingIsTransformedAndNotSent() {
        RecordingListener l = new RecordingListener();
        ClientSyncState s = new ClientSyncState("me", "t", 0, 5, l); // "hello"
        s.applyLocal(OperationDiff.diff("hello", "hello world"));
        s.onServerEvent(1, "peer", "p-1", TextOperation.ofSpan(5, 0, 0, ">> "), 8);

        assertEquals(0, l.sent.size(), "remote ops must not trigger a send");
        assertEquals("Buffering", s.getStateName());
        assertEquals(">> hello world", l.applied.get(0).apply("hello world"));

        s.requestSend();
        assertEquals(1L, l.sentBase.get(0));
        assertEquals(">> hello world", l.sent.get(0).apply(">> hello"));
    }

    @Test
    void outOfOrderEventsAreReorderedAndDuplicatesIgnored() {
        RecordingListener l = new RecordingListener();
        ClientSyncState s = new ClientSyncState("me", "t", 0, 0, l);
        TextOperation r1 = new TextOperation().insert("a");
        TextOperation r2 = new TextOperation().retain(1).insert("b");

        s.onServerEvent(2, "peer", "x-2", r2, 2); // arrives early: buffered
        assertTrue(l.applied.isEmpty());
        s.onServerEvent(1, "peer", "x-1", r1, 1);
        assertEquals(2, l.applied.size());
        s.onServerEvent(1, "peer", "x-1", r1, 1); // duplicate
        assertEquals(2, l.applied.size());
        assertEquals(2, s.getServerRevision());
    }

    @Test
    void divergenceTriggersResync() {
        RecordingListener l = new RecordingListener();
        ClientSyncState s = new ClientSyncState("me", "t", 0, 3, l);
        s.onServerEvent(1, "peer", "x", new TextOperation().retain(3).insert("z"), 99);
        assertNotNull(l.resyncReason);
        assertEquals("Resyncing", s.getStateName());
    }

    @Test
    void sendFailureTriggersResync() {
        RecordingListener l = new RecordingListener();
        ClientSyncState s = new ClientSyncState("me", "t", 0, 0, l);
        s.applyLocal(new TextOperation().insert("x"));
        s.requestSend();
        s.onSendFailed(l.sentIds.get(0));
        assertNotNull(l.resyncReason);
    }

    // ------------------------------------------------------------------------------------------
    // Randomized multi-client simulation with delayed / reordered delivery
    // ------------------------------------------------------------------------------------------

    @Test
    void randomizedClientsConvergeWithServer() {
        for (int seed = 0; seed < 200; seed++) {
            runSimulation(new Random(seed), 3, 60);
        }
    }

    private void runSimulation(Random rnd, int clientCount, int steps) {
        SimServer server = new SimServer("seed text");
        List<SimClient> clients = new ArrayList<>();
        for (int i = 0; i < clientCount; i++) {
            clients.add(new SimClient("sess-" + i, server));
        }

        for (int step = 0; step < steps; step++) {
            SimClient c = clients.get(rnd.nextInt(clientCount));
            switch (rnd.nextInt(4)) {
                case 0:
                    c.localEdit(RandomOps.randomOp(rnd, c.doc));
                    break;
                case 1:
                    c.deliverOneSend();
                    break;
                case 2:
                    c.sync.requestSend(); // the user paused typing
                    break;
                default:
                    c.deliverOneEvent(rnd);
                    break;
            }
        }
        // Drain everything (every client eventually pauses, so buffers get sent)
        boolean progress = true;
        while (progress) {
            progress = false;
            for (SimClient c : clients) {
                c.sync.requestSend();
                progress |= c.deliverOneSend();
                progress |= c.deliverOneEvent(rnd);
            }
        }
        for (SimClient c : clients) {
            assertNull(c.listener.resyncReason, "unexpected resync: " + c.listener.resyncReason);
            assertEquals("Synchronized", c.sync.getStateName());
            assertEquals(server.doc, c.doc, "client " + c.sessionId + " diverged");
        }
    }

    /** Minimal in-memory OT server identical in logic to the Spring OtEngine. */
    static final class SimServer {
        String doc;
        final List<TextOperation> history = new ArrayList<>();
        final List<SimClient> subscribers = new ArrayList<>();

        SimServer(String initial) {
            doc = initial;
        }

        void receive(TextOperation op, long baseRevision, String author, String clientOpId) {
            for (int i = (int) baseRevision; i < history.size(); i++) {
                op = TextOperation.transform(op, history.get(i))[0];
            }
            doc = op.apply(doc);
            history.add(op);
            long revision = history.size();
            for (SimClient s : subscribers) {
                s.inbox.add(new Object[] {revision, author, clientOpId, op, doc.length()});
            }
        }
    }

    static final class SimClient {
        final String sessionId;
        final SimServer server;
        final RecordingListener listener = new RecordingListener();
        final ClientSyncState sync;
        final List<Object[]> outbox = new ArrayList<>();
        final List<Object[]> inbox = new ArrayList<>();
        String doc;

        SimClient(String sessionId, SimServer server) {
            this.sessionId = sessionId;
            this.server = server;
            this.doc = server.doc;
            server.subscribers.add(this);
            listener.onSend = (op, base, id) -> outbox.add(new Object[] {op, base, id});
            listener.onApply = op -> doc = op.apply(doc);
            sync = new ClientSyncState(sessionId, "i", server.history.size(), doc.length(), listener);
        }

        void localEdit(TextOperation op) {
            doc = op.apply(doc);
            sync.applyLocal(op);
        }

        boolean deliverOneSend() {
            if (outbox.isEmpty()) {
                return false;
            }
            Object[] m = outbox.remove(0);
            server.receive((TextOperation) m[0], (Long) m[1], sessionId, (String) m[2]);
            return true;
        }

        boolean deliverOneEvent(Random rnd) {
            if (inbox.isEmpty()) {
                return false;
            }
            // Deliver out of order sometimes to exercise the reorder buffer
            int idx = rnd.nextInt(Math.min(3, inbox.size()));
            Object[] e = inbox.remove(idx);
            sync.onServerEvent((Long) e[0], (String) e[1], (String) e[2], (TextOperation) e[3], (Integer) e[4]);
            return true;
        }
    }

    interface SendHook {
        void send(TextOperation op, long base, String id);
    }

    interface ApplyHook {
        void apply(TextOperation op);
    }

    static final class RecordingListener implements ClientSyncState.Listener {
        final List<TextOperation> sent = new ArrayList<>();
        final List<Long> sentBase = new ArrayList<>();
        final List<String> sentIds = new ArrayList<>();
        final List<TextOperation> applied = new ArrayList<>();
        String resyncReason;
        SendHook onSend;
        ApplyHook onApply;

        @Override
        public void sendToServer(TextOperation op, long baseRevision, String clientOpId) {
            sent.add(op);
            sentBase.add(baseRevision);
            sentIds.add(clientOpId);
            if (onSend != null) {
                onSend.send(op, baseRevision, clientOpId);
            }
        }

        @Override
        public void applyToEditor(TextOperation op) {
            applied.add(op);
            if (onApply != null) {
                onApply.apply(op);
            }
        }

        @Override
        public void resyncRequired(String reason) {
            resyncReason = reason;
        }
    }
}

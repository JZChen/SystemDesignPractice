package com.googledocs;

import com.googledocs.model.CommittedOperation;
import com.googledocs.model.DocumentResponse;
import com.googledocs.model.EngineType;
import com.googledocs.model.OperationRequest;
import com.googledocs.ot.ClientSyncState;
import com.googledocs.ot.TextOperation;
import com.googledocs.service.BroadcastService;
import com.googledocs.service.CrdtEngine;
import com.googledocs.service.DocumentService;
import com.googledocs.service.OtEngine;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end check of the client protocol against the real server logic:
 * several {@link ClientSyncState} ACK queues talk to a real {@link DocumentService}; sends and
 * server events are delivered with random delay and reordering. Server events are pulled through
 * the catch-up endpoint logic ({@code getOperationsSince}). At the end every client matches the server.
 */
class OtEndToEndTest {

    @Test
    void clientsConvergeWithRealDocumentService() {
        for (int seed = 0; seed < 50; seed++) {
            run(new Random(seed));
        }
    }

    private void run(Random rnd) {
        DocumentService service = new DocumentService(new OtEngine(), new CrdtEngine(), new BroadcastService());
        DocumentResponse created = service.createDocument("E2E", EngineType.OT);
        String docId = created.getDocId();

        List<Client> clients = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            clients.add(new Client(service, docId, "sess-" + i));
        }

        for (int step = 0; step < 80; step++) {
            Client c = clients.get(rnd.nextInt(clients.size()));
            switch (rnd.nextInt(3)) {
                case 0 -> c.localEdit(rnd);
                case 1 -> c.deliverOneSend();
                default -> c.pullOneEvent(rnd);
            }
        }
        boolean progress = true;
        while (progress) {
            progress = false;
            for (Client c : clients) {
                progress |= c.deliverOneSend();
                progress |= c.pullOneEvent(rnd);
            }
        }

        String serverText = service.getRawDocument(docId).getContent();
        for (Client c : clients) {
            assertNull(c.resyncReason, "unexpected resync: " + c.resyncReason);
            assertEquals("Synchronized", c.sync.getStateName());
            assertEquals(serverText, c.doc, c.sessionId + " diverged from server");
        }
    }

    private static final class Client implements ClientSyncState.Listener {
        final DocumentService service;
        final String docId;
        final String sessionId;
        final ClientSyncState sync;
        final List<Object[]> outbox = new ArrayList<>();
        final List<CommittedOperation> pending = new ArrayList<>();
        long pulledUpTo;
        String doc = "";
        String resyncReason;

        Client(DocumentService service, String docId, String sessionId) {
            this.service = service;
            this.docId = docId;
            this.sessionId = sessionId;
            this.sync = new ClientSyncState(sessionId, "e2e", 0, 0, this);
        }

        void localEdit(Random rnd) {
            int pos = rnd.nextInt(doc.length() + 1);
            TextOperation op;
            if (doc.isEmpty() || rnd.nextBoolean()) {
                op = TextOperation.ofSpan(doc.length(), pos, 0, "" + (char) ('a' + rnd.nextInt(26)));
            } else {
                int len = Math.min(1 + rnd.nextInt(3), doc.length() - Math.min(pos, doc.length() - 1));
                pos = Math.min(pos, doc.length() - len);
                op = TextOperation.ofSpan(doc.length(), pos, len, "");
            }
            doc = op.apply(doc);
            sync.applyLocal(op);
        }

        boolean deliverOneSend() {
            if (outbox.isEmpty()) {
                return false;
            }
            Object[] m = outbox.remove(0);
            service.applyOperation(docId, OperationRequest.ofOps(
                sessionId, (Long) m[1], ((TextOperation) m[0]).toWireList(), (String) m[2]));
            return true;
        }

        boolean pullOneEvent(Random rnd) {
            // Fetch newly committed ops via the catch-up path, then deliver one, possibly out of order.
            for (CommittedOperation op : service.getOperationsSince(docId, pulledUpTo)) {
                pending.add(op);
                pulledUpTo = op.getRevision();
            }
            if (pending.isEmpty()) {
                return false;
            }
            CommittedOperation e = pending.remove(rnd.nextInt(Math.min(3, pending.size())));
            sync.onServerEvent(e.getRevision(), e.getSessionId(), e.getClientOpId(), e.getOperation(), -1);
            return true;
        }

        @Override
        public void sendToServer(TextOperation op, long baseRevision, String clientOpId) {
            outbox.add(new Object[] {op, baseRevision, clientOpId});
        }

        @Override
        public void applyToEditor(TextOperation op) {
            doc = op.apply(doc);
        }

        @Override
        public void resyncRequired(String reason) {
            resyncReason = reason;
        }
    }
}

package com.googledocs.service;

import com.googledocs.model.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class DocumentService {

    private final Map<String, Document> documents = new ConcurrentHashMap<>();
    private final OtEngine otEngine;
    private final CrdtEngine crdtEngine;
    private final BroadcastService broadcastService;

    @Value("${app.document.max-length:2097152}")
    private int maxDocumentLength = 2097152;

    public DocumentService(OtEngine otEngine, CrdtEngine crdtEngine, BroadcastService broadcastService) {
        this.otEngine = otEngine;
        this.crdtEngine = crdtEngine;
        this.broadcastService = broadcastService;
    }

    public DocumentResponse createDocument(String title, EngineType engineType) {
        String docId = UUID.randomUUID().toString();
        String sessionId = "sess-" + UUID.randomUUID();

        Document doc = new Document(docId, title, engineType);
        doc.registerSession(sessionId);
        documents.put(docId, doc);

        return DocumentResponse.from(doc, sessionId, "");
    }

    public DocumentResponse getDocument(String docId, String sessionId) {
        Document doc = documents.get(docId);
        if (doc == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found: " + docId);
        }

        String effectiveSessionId = (sessionId != null && !sessionId.isBlank())
            ? sessionId
            : "sess-" + UUID.randomUUID();

        Session session = doc.registerSession(effectiveSessionId);
        session.touch();

        // Broadcast presence update to peers
        broadcastService.broadcastPresence(docId, doc.getSessions().values());

        return DocumentResponse.from(doc, effectiveSessionId, "");
    }

    public OperationResult applyOperation(String docId, OperationRequest request) {
        Document doc = documents.get(docId);
        if (doc == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found: " + docId);
        }

        Session session = doc.getSession(request.getSessionId());
        if (session == null) {
            session = doc.registerSession(request.getSessionId());
        }
        session.touch();

        // Check document maximum capacity (DoS guard)
        int incomingTextLen = request.getText() != null ? request.getText().length() : 0;
        if (doc.getContent().length() + incomingTextLen > maxDocumentLength) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Document length exceeds maximum allowed limit");
        }

        doc.getRwLock().writeLock().lock();
        OperationResult result;
        try {
            CollaborativeEngine engine = (doc.getEngineType() == EngineType.CRDT) ? crdtEngine : otEngine;
            result = engine.applyOperation(doc, request);
        } finally {
            doc.getRwLock().writeLock().unlock();
        }

        // Broadcast to all connected SSE clients
        broadcastService.broadcastOperation(docId, result, request.getSessionId());

        return result;
    }

    public List<CommittedOperation> getOperationsSince(String docId, long sinceRevision) {
        Document doc = documents.get(docId);
        if (doc == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found: " + docId);
        }

        List<CommittedOperation> history = doc.getOtHistory();
        synchronized (history) {
            int startIdx = (int) Math.max(0, sinceRevision);
            if (startIdx >= history.size()) {
                return Collections.emptyList();
            }
            return new ArrayList<>(history.subList(startIdx, history.size()));
        }
    }

    public Document getRawDocument(String docId) {
        return documents.get(docId);
    }
}

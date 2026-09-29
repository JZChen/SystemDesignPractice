package com.googledocs;

import com.googledocs.model.*;
import com.googledocs.service.BroadcastService;
import com.googledocs.service.CrdtEngine;
import com.googledocs.service.DocumentService;
import com.googledocs.service.OtEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DocumentServiceTest {

    private DocumentService documentService;

    @BeforeEach
    void setUp() {
        OtEngine otEngine = new OtEngine();
        CrdtEngine crdtEngine = new CrdtEngine();
        BroadcastService broadcastService = new BroadcastService();
        documentService = new DocumentService(otEngine, crdtEngine, broadcastService);
    }

    @Test
    @DisplayName("Create & Read Document: Returns correct initial state and session token")
    void testCreateAndReadDocument() {
        DocumentResponse created = documentService.createDocument("Design Notes", EngineType.OT);
        assertNotNull(created.getDocId());
        assertNotNull(created.getSessionId());
        assertEquals("Design Notes", created.getTitle());
        assertEquals("", created.getContent());
        assertEquals(0, created.getRevision());

        // Read by ID
        DocumentResponse read = documentService.getDocument(created.getDocId(), created.getSessionId());
        assertEquals(created.getDocId(), read.getDocId());
        assertEquals("Design Notes", read.getTitle());
        assertEquals(1, read.getActiveUsers().size());
    }

    @Test
    @DisplayName("Concurrent Operations: 10 threads appending simultaneously preserve lock safety")
    void testConcurrentOperations() throws InterruptedException {
        DocumentResponse doc = documentService.createDocument("Concurrent Test", EngineType.OT);
        String docId = doc.getDocId();

        int numThreads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(numThreads);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < numThreads; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    String sessionId = "thread-" + threadId;
                    OperationRequest req = new OperationRequest(
                        sessionId, 0, OperationType.INSERT, 0, "A", 0
                    );
                    documentService.applyOperation(docId, req);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        executor.shutdown();

        assertEquals(numThreads, successCount.get());
        DocumentResponse updated = documentService.getDocument(docId, null);
        assertEquals(numThreads, updated.getContent().length());
        assertEquals(numThreads, updated.getRevision());
    }
}

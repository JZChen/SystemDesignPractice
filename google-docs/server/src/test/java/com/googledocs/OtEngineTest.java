package com.googledocs;

import com.googledocs.model.*;
import com.googledocs.service.OtEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OtEngineTest {

    private OtEngine otEngine;

    @BeforeEach
    void setUp() {
        otEngine = new OtEngine();
    }

    @Test
    @DisplayName("INSERT vs INSERT: Incoming insert after committed insert shifts forward")
    void testInsertVsInsertShiftForward() {
        Document doc = new Document("doc-1", "Test", EngineType.OT);
        doc.getContentBuffer().append("Hello World");
        doc.setRevision(0);

        // Op 1 (committed as Rev 1): Insert " Beautiful" at position 5
        OperationRequest op1 = new OperationRequest("sess-A", 0, OperationType.INSERT, 5, " Beautiful", 0);
        OperationResult res1 = otEngine.applyOperation(doc, op1);
        assertEquals("Hello Beautiful World", res1.getContent());
        assertEquals(1, res1.getRevision());

        // Op 2 (generated against Rev 0): User wanted to insert "!" at position 11 ("World" + 5)
        // With Op 1 inserting 10 chars at pos 5, pos 11 should transform to 11 + 10 = 21
        OperationRequest op2 = new OperationRequest("sess-B", 0, OperationType.INSERT, 11, "!", 0);
        OperationResult res2 = otEngine.applyOperation(doc, op2);

        assertEquals("Hello Beautiful World!", res2.getContent());
        assertEquals(2, res2.getRevision());
        assertEquals(21, res2.getPosition());
    }

    @Test
    @DisplayName("INSERT vs INSERT: Incoming insert before committed insert does not shift")
    void testInsertVsInsertBefore() {
        Document doc = new Document("doc-1", "Test", EngineType.OT);
        doc.getContentBuffer().append("World");
        doc.setRevision(0);

        // Committed Rev 1: Insert "!" at pos 5
        OperationRequest op1 = new OperationRequest("sess-A", 0, OperationType.INSERT, 5, "!", 0);
        otEngine.applyOperation(doc, op1);
        assertEquals("World!", doc.getContent());

        // Concurrent Op 2: Insert "Hello " at pos 0 based on Rev 0
        OperationRequest op2 = new OperationRequest("sess-B", 0, OperationType.INSERT, 0, "Hello ", 0);
        OperationResult res2 = otEngine.applyOperation(doc, op2);

        assertEquals("Hello World!", res2.getContent());
        assertEquals(0, res2.getPosition()); // Position 0 remains 0
    }

    @Test
    @DisplayName("INSERT vs DELETE: Insert after deleted region shifts backwards by deleted length")
    void testInsertVsDeleteShiftBack() {
        Document doc = new Document("doc-1", "Test", EngineType.OT);
        doc.getContentBuffer().append("The quick brown fox");
        doc.setRevision(0);

        // Committed Rev 1: Delete "quick " (pos 4, len 6)
        OperationRequest op1 = new OperationRequest("sess-A", 0, OperationType.DELETE, 4, "", 6);
        otEngine.applyOperation(doc, op1);
        assertEquals("The brown fox", doc.getContent());

        // Concurrent Op 2 (based on Rev 0): Insert "red " before "fox" at pos 16
        // Since pos 4..10 was deleted (6 chars), pos 16 becomes 16 - 6 = 10
        OperationRequest op2 = new OperationRequest("sess-B", 0, OperationType.INSERT, 16, "red ", 0);
        OperationResult res2 = otEngine.applyOperation(doc, op2);

        assertEquals("The brown red fox", res2.getContent());
        assertEquals(10, res2.getPosition());
    }

    @Test
    @DisplayName("DELETE vs DELETE: Concurrent overlapping deletions do not duplicate delete")
    void testDeleteVsDeleteOverlap() {
        Document doc = new Document("doc-1", "Test", EngineType.OT);
        doc.getContentBuffer().append("0123456789");
        doc.setRevision(0);

        // Committed Rev 1: Delete "234" (pos 2, len 3)
        OperationRequest op1 = new OperationRequest("sess-A", 0, OperationType.DELETE, 2, "", 3);
        otEngine.applyOperation(doc, op1);
        assertEquals("0156789", doc.getContent());

        // Concurrent Op 2 (based on Rev 0): Delete "3456" (pos 3, len 4)
        // Part of it ("34") was already deleted by Op 1! Only "56" remains to be deleted.
        OperationRequest op2 = new OperationRequest("sess-B", 0, OperationType.DELETE, 3, "", 4);
        OperationResult res2 = otEngine.applyOperation(doc, op2);

        assertEquals("01789", res2.getContent());
    }

    @Test
    @DisplayName("Insert inside a concurrently deleted range survives in both arrival orders")
    void testInsertInsideDeleteConvergesBothOrders() {
        // Order 1: delete committed first, then the concurrent insert
        Document d1 = new Document("doc-1", "Test", EngineType.OT);
        d1.getContentBuffer().append("0123456789");
        otEngine.applyOperation(d1, new OperationRequest("sess-A", 0, OperationType.DELETE, 2, "", 6));
        otEngine.applyOperation(d1, new OperationRequest("sess-B", 0, OperationType.INSERT, 5, "X", 0));

        // Order 2: insert committed first, then the concurrent delete
        Document d2 = new Document("doc-2", "Test", EngineType.OT);
        d2.getContentBuffer().append("0123456789");
        otEngine.applyOperation(d2, new OperationRequest("sess-B", 0, OperationType.INSERT, 5, "X", 0));
        otEngine.applyOperation(d2, new OperationRequest("sess-A", 0, OperationType.DELETE, 2, "", 6));

        assertEquals("01X89", d1.getContent());
        assertEquals(d1.getContent(), d2.getContent());
    }

    @Test
    @DisplayName("TextOperation request with clientOpId is applied and echoed")
    void testOpsRequest() {
        Document doc = new Document("doc-1", "Test", EngineType.OT);
        doc.getContentBuffer().append("Hello World");

        OperationResult res = otEngine.applyOperation(doc,
            OperationRequest.ofOps("sess-A", 0, List.of(6, "Brave ", 5), "c-1"));

        assertEquals("Hello Brave World", res.getContent());
        assertEquals("c-1", res.getClientOpId());
        assertEquals(List.of(6, "Brave ", 5), res.getOps());
        assertEquals(OperationType.INSERT, res.getType());
        assertEquals(6, res.getPosition());
        assertEquals("c-1", doc.getOtHistory().get(0).getClientOpId());
    }

    @Test
    @DisplayName("Rejects base revision ahead of server, base length mismatch and malformed ops")
    void testValidation() {
        Document doc = new Document("doc-1", "Test", EngineType.OT);
        doc.getContentBuffer().append("abc");

        assertThrows(ResponseStatusException.class, () -> otEngine.applyOperation(doc,
            OperationRequest.ofOps("sess-A", 5, List.of(3, "x"), "c-1")));
        assertThrows(ResponseStatusException.class, () -> otEngine.applyOperation(doc,
            OperationRequest.ofOps("sess-A", 0, List.of(10, "x"), "c-2")));
        assertThrows(ResponseStatusException.class, () -> otEngine.applyOperation(doc,
            OperationRequest.ofOps("sess-A", 0, List.of(1.5, "x"), "c-3")));
        assertThrows(ResponseStatusException.class, () -> otEngine.applyOperation(doc,
            OperationRequest.ofOps("sess-A", 0, List.of(3, true), "c-4")));

        assertEquals("abc", doc.getContent());
        assertEquals(0, doc.getRevision());
    }
}

package com.googledocs;

import com.googledocs.model.*;
import com.googledocs.service.CrdtEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CrdtEngineTest {

    private CrdtEngine crdtEngine;

    @BeforeEach
    void setUp() {
        crdtEngine = new CrdtEngine();
    }

    @Test
    @DisplayName("Fractional Indexing: Inserts characters in correct total order")
    void testFractionalOrdering() {
        Document doc = new Document("crdt-1", "CRDT Test", EngineType.CRDT);

        // Insert "AC" at position 0
        OperationRequest req1 = new OperationRequest("sess-1", 0, OperationType.INSERT, 0, "AC", 0);
        crdtEngine.applyOperation(doc, req1);
        assertEquals("AC", doc.getContent());

        // Insert "B" between 'A' and 'C' (position 1)
        OperationRequest req2 = new OperationRequest("sess-2", 1, OperationType.INSERT, 1, "B", 0);
        crdtEngine.applyOperation(doc, req2);
        assertEquals("ABC", doc.getContent());

        // Insert "12" at position 0
        OperationRequest req3 = new OperationRequest("sess-1", 2, OperationType.INSERT, 0, "12", 0);
        crdtEngine.applyOperation(doc, req3);
        assertEquals("12ABC", doc.getContent());
    }

    @Test
    @DisplayName("Tombstones: Deletions preserve character positioning")
    void testTombstones() {
        Document doc = new Document("crdt-2", "CRDT Tombstone Test", EngineType.CRDT);

        // Insert "HELLO"
        OperationRequest req1 = new OperationRequest("sess-1", 0, OperationType.INSERT, 0, "HELLO", 0);
        crdtEngine.applyOperation(doc, req1);
        assertEquals("HELLO", doc.getContent());

        // Delete "ELL" (pos 1, len 3) -> should yield "HO"
        OperationRequest req2 = new OperationRequest("sess-1", 1, OperationType.DELETE, 1, "", 3);
        OperationResult res2 = crdtEngine.applyOperation(doc, req2);

        assertEquals("HO", res2.getContent());
        assertEquals(3, doc.getCrdtCharacters().stream().filter(CrdtCharacter::isDeleted).count());
        assertEquals(5, doc.getCrdtCharacters().size()); // 5 total tokens, 3 tombstones, 2 visible
    }

    @Test
    @DisplayName("Fractional position generator produces strictly monotonic identifier between two keys")
    void testPositionGeneratorStrictOrdering() {
        List<Integer> before = List.of(10);
        List<Integer> after = List.of(20);

        List<Integer> mid = crdtEngine.generatePositionBetween(before, after);
        assertEquals(List.of(15), mid);

        // Deepening test when diff is 1
        List<Integer> tightBefore = List.of(10);
        List<Integer> tightAfter = List.of(11);
        List<Integer> tightMid = crdtEngine.generatePositionBetween(tightBefore, tightAfter);
        assertTrue(tightMid.size() > 1);
        assertEquals(10, tightMid.get(0));
    }
}

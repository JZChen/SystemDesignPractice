package com.googledocs.ot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OperationDiffTest {

    @Test
    void typingAppendsInsert() {
        TextOperation op = OperationDiff.diff("Hello", "Hello!");
        assertEquals("[5, \"!\"]", op.toString());
    }

    @Test
    void backspaceDeletes() {
        TextOperation op = OperationDiff.diff("Hello", "Helo");
        assertEquals("Helo", op.apply("Hello"));
        assertEquals(1, op.getBaseLength() - op.getTargetLength());
    }

    @Test
    void selectionReplace() {
        TextOperation op = OperationDiff.diff("The quick fox", "The slow fox");
        assertEquals("The slow fox", op.apply("The quick fox"));
    }

    @Test
    void noChangeIsNoop() {
        assertTrue(OperationDiff.diff("same", "same").isNoop());
        assertTrue(OperationDiff.diff("", "").isNoop());
    }

    @Test
    void doesNotSplitSurrogatePairs() {
        String smile = "\uD83D\uDE00"; // 😀
        String grin = "\uD83D\uDE01";  // 😁 (same high surrogate)
        TextOperation op = OperationDiff.diff("a" + smile + "b", "a" + grin + "b");
        assertEquals("a" + grin + "b", op.apply("a" + smile + "b"));
        // The inserted text must be the whole emoji, not a lone low surrogate
        for (Object c : op.getOps()) {
            if (c instanceof String) {
                assertEquals(grin, c);
            }
        }
    }
}

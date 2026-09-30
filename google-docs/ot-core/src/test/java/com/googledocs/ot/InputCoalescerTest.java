package com.googledocs.ot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InputCoalescerTest {

    @Test
    void flushesAfterIdle() {
        InputCoalescer c = new InputCoalescer(80, 400);
        assertEquals(80, c.onInput(0, false));
        assertFalse(c.shouldFlush(50));
        assertTrue(c.shouldFlush(80));
    }

    @Test
    void continuousTypingFlushesAtMaxWindow() {
        InputCoalescer c = new InputCoalescer(80, 400);
        double deadline = 0;
        for (int t = 0; t <= 380; t += 20) {
            deadline = c.onInput(t, false);
            assertFalse(c.shouldFlush(t));
        }
        assertEquals(400, deadline);   // min(380 + 80, 0 + 400)
        assertTrue(c.shouldFlush(400));
        c.onFlushed();
        assertFalse(c.hasPending());
    }

    @Test
    void neverFlushesWhileComposing() {
        InputCoalescer c = new InputCoalescer(80, 400);
        assertEquals(-1, c.onInput(0, true));
        assertFalse(c.shouldFlush(1000));
        // composition ends after the max window already elapsed: flush is due immediately
        assertTrue(c.onInput(1000, false) <= 1000);
        assertTrue(c.shouldFlush(1000));
    }

    @Test
    void rejectsInvalidConfig() {
        assertThrows(IllegalArgumentException.class, () -> new InputCoalescer(0, 10));
        assertThrows(IllegalArgumentException.class, () -> new InputCoalescer(100, 10));
    }
}

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

    @Test
    void defaultsWaitForTwoSecondPause() {
        InputCoalescer c = new InputCoalescer();
        c.onInput(0, false);
        assertEquals(3500, c.onInput(1500, false)); // timer restarts on every keystroke
        assertFalse(c.shouldFlush(3000));
        assertFalse(c.shouldFlush(3499));
        assertTrue(c.shouldFlush(3500));
    }

    @Test
    void defaultsCapContinuousTypingAtTenSeconds() {
        InputCoalescer c = new InputCoalescer();
        for (int t = 0; t < 10000; t += 500) {
            c.onInput(t, false);
            assertFalse(c.shouldFlush(t), "no send while typing at t=" + t);
        }
        assertEquals(10000, c.nextDeadline()); // min(9500 + 2000, 0 + 10000)
        assertTrue(c.shouldFlush(10000));
    }
}

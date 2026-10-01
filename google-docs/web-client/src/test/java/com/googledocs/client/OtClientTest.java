package com.googledocs.client;

import com.googledocs.ot.TextOperation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JVM tests of the OtClient facade with a fake clock: typing is buffered locally and only sent
 * after a 2 s pause (or a 10 s continuous-typing cap); remote ops never trigger a send.
 */
class OtClientTest {

    private final List<Object[]> sends = new ArrayList<>();
    private final List<String> renders = new ArrayList<>();
    private String resync;

    private OtClient newClient(String initialText) {
        return new OtClient("me", "t", 0, initialText,
                (ops, base, id) -> sends.add(new Object[] {ops, base, id}),
                (text, s, e) -> renders.add(text),
                reason -> resync = reason);
    }

    /** Simulates the JS host: each keystroke calls onInput, then the timer calls maybeFlush. */
    private static void type(OtClient c, String text, double now) {
        c.onInput(text, now, false);
        c.maybeFlush(text, now);
    }

    @Test
    void noSendWhileTypingThenOneSendAfterTwoSecondPause() {
        OtClient c = newClient("");
        String[] steps = {"H", "He", "Hel", "Hell", "Hello"};
        for (int i = 0; i < steps.length; i++) {
            type(c, steps[i], i * 300);              // t = 0 .. 1200 ms
        }
        assertEquals(3200.0, c.maybeFlush("Hello", 1900)); // still waiting; next check at 1200 + 2000
        assertEquals(0, sends.size(), "no network call while typing");
        assertEquals("Buffering", c.getStateName());

        assertEquals(-1, c.maybeFlush("Hello", 3200)); // 2 s after the last keystroke
        assertEquals(1, sends.size(), "all keystrokes go out as one request");
        assertEquals("Hello", TextOperation.fromWire((Object[]) sends.get(0)[0]).apply(""));
        assertEquals("AwaitingConfirm", c.getStateName());
    }

    @Test
    void continuousTypingIsCappedAtTenSeconds() {
        OtClient c = newClient("");
        StringBuilder text = new StringBuilder();
        for (int t = 0; t < 10000; t += 500) {
            text.append('x');
            type(c, text.toString(), t);
        }
        assertEquals(0, sends.size());
        c.maybeFlush(text.toString(), 10000);
        assertEquals(1, sends.size(), "safety cap sends after 10 s of continuous typing");
    }

    @Test
    void remoteOpWhileBufferingRendersMergedTextWithoutSending() {
        OtClient c = newClient("hello");
        type(c, "hello world", 0);

        // peer inserted ">> " at the start, based on rev 0 ("hello")
        Object[] remote = TextOperation.ofSpan(5, 0, 0, ">> ").toWire();
        c.onServerEvent("hello world", 11, 11, 1, "peer", "p-1", remote, 8);

        assertEquals(0, sends.size(), "remote ops must not trigger a send");
        assertEquals(List.of(">> hello world"), renders);
        assertNull(resync);

        c.maybeFlush(">> hello world", 2000);
        assertEquals(1, sends.size());
        assertEquals(1.0, (double) sends.get(0)[1], "buffer is rebased onto the remote revision");
        assertEquals(">> hello world",
                TextOperation.fromWire((Object[]) sends.get(0)[0]).apply(">> hello"));
    }

    @Test
    void typingDuringInflightWaitsForNextPauseAfterAck() {
        OtClient c = newClient("");
        type(c, "a", 0);
        c.maybeFlush("a", 2000);                      // send #1
        type(c, "ab", 2100);                          // buffered while #1 in flight
        String ackId = (String) sends.get(0)[2];
        c.onServerEvent("ab", 2, 2, 1, "me", ackId, new TextOperation().insert("a").toWire(), 1);
        assertEquals(1, sends.size(), "ACK alone must not send typing that hasn't paused");
        assertEquals("Buffering", c.getStateName());

        c.maybeFlush("ab", 4100);                     // 2 s after "b"
        assertEquals(2, sends.size());
    }
}

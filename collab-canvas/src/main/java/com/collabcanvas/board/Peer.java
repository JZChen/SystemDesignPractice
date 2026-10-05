package com.collabcanvas.board;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One connected client.
 *
 * <p>Outgoing messages go through a bounded queue drained by a dedicated virtual thread, which is
 * the only thread that ever writes to the socket. The board enqueues while holding its lock, so
 * every peer receives committed ops in the same (seq) order, but a slow peer never blocks the
 * board: if its queue fills up it is disconnected and resyncs from a fresh snapshot on reconnect.
 */
public final class Peer {

    /** Transport abstraction (WebSocket in production, a list in tests). */
    public interface Sink {
        void send(String message) throws Exception;

        void close(int code, String reason);
    }

    public static final int QUEUE_CAPACITY = 1024;
    public static final int CLOSE_TOO_SLOW = 1013;    // "try again later"
    public static final int CLOSE_POLICY = 1008;      // policy violation

    private static final Logger log = LoggerFactory.getLogger(Peer.class);
    private static final String POISON = "\u0000close";

    private final String userId;
    private final String name;
    private final String color;
    private final Sink sink;
    private final BlockingQueue<String> outbound = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Thread sender;

    /** 50 ops/s (burst 200, so an eraser batch of 100 fits); 60 previews+cursors/s (burst 120). */
    private final RateLimiter opLimiter = new RateLimiter(50, 200);
    private final RateLimiter ephemeralLimiter = new RateLimiter(60, 120);
    private final java.util.concurrent.atomic.AtomicInteger invalidMessages =
            new java.util.concurrent.atomic.AtomicInteger();

    public boolean tryAcquireOps(int n) {
        return opLimiter.tryAcquire(n);
    }

    public boolean tryAcquireEphemeral() {
        return ephemeralLimiter.tryAcquire(1);
    }

    /** @return the number of invalid messages so far, including this one. */
    public int countInvalid() {
        return invalidMessages.incrementAndGet();
    }

    public int invalidCount() {
        return invalidMessages.get();
    }

    public Peer(String userId, String name, String color, Sink sink) {
        this.userId = userId;
        this.name = name;
        this.color = color;
        this.sink = sink;
        this.sender = Thread.ofVirtual().name("peer-" + userId.substring(0, 8)).start(this::drain);
    }

    public String userId() {
        return userId;
    }

    public String name() {
        return name;
    }

    public String color() {
        return color;
    }

    public boolean isClosed() {
        return closed.get();
    }

    /** Non-blocking. Returns false (and disconnects this peer) if it can't keep up. */
    public boolean enqueue(String message) {
        if (closed.get()) {
            return false;
        }
        if (!outbound.offer(message)) {
            close(CLOSE_TOO_SLOW, "too slow");
            return false;
        }
        return true;
    }

    public void close(int code, String reason) {
        if (closed.compareAndSet(false, true)) {
            outbound.clear();
            outbound.offer(POISON);
            try {
                sink.close(code, reason);
            } catch (RuntimeException e) {
                log.debug("close failed for peer {}", userId);
            }
        }
    }

    private void drain() {
        try {
            while (true) {
                String msg = outbound.poll(30, TimeUnit.SECONDS);
                if (closed.get() && (msg == null || msg == POISON)) {
                    return;
                }
                if (msg == null || msg == POISON) {
                    continue;
                }
                sink.send(msg);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // Socket broken: the transport's close callback removes the peer from its board.
            log.debug("send failed for peer {}", userId);
            close(CLOSE_POLICY, "send failed");
        }
    }

    /** For tests: wait until everything queued so far has been handed to the sink. */
    boolean awaitDrained(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!outbound.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(2);
        }
        return outbound.isEmpty();
    }

    Thread senderThread() {
        return sender;
    }
}

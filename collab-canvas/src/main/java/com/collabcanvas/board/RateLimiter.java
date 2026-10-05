package com.collabcanvas.board;

/** Token bucket: {@code ratePerSec} tokens refill continuously up to {@code burst}. Thread-safe. */
public final class RateLimiter {

    private final double ratePerNano;
    private final double burst;
    private double tokens;
    private long last;

    public RateLimiter(double ratePerSec, double burst) {
        this.ratePerNano = ratePerSec / 1_000_000_000.0;
        this.burst = burst;
        this.tokens = burst;
        this.last = System.nanoTime();
    }

    public synchronized boolean tryAcquire(int n) {
        long now = System.nanoTime();
        tokens = Math.min(burst, tokens + (now - last) * ratePerNano);
        last = now;
        if (tokens >= n) {
            tokens -= n;
            return true;
        }
        return false;
    }
}

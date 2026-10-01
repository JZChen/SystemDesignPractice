package com.googledocs.ot;

/**
 * Pure time-window policy that decides when buffered keystrokes are sent as one operation.
 *
 * <p>Holds no timers (the JS host owns setTimeout); it only answers "when should I check next?"
 * and "should I send now?". A pending window is sent when:
 * <ul>
 *   <li>the user has paused typing for {@code idleMs} (default 2 s), or</li>
 *   <li>the window has been open for {@code maxWindowMs} (default 10 s: continuous typing still
 *       ships, which bounds data loss and request size), and</li>
 *   <li>no IME composition is in progress (never send half-composed characters).</li>
 * </ul>
 * Remote ops never trigger a send: the client only captures pending edits into its buffer so the
 * remote op can be transformed against them.
 */
public final class InputCoalescer {

    /** Send only after the user pauses typing this long. */
    public static final int DEFAULT_IDLE_MS = 2000;
    /** Safety cap: continuous typing still sends after this long. */
    public static final int DEFAULT_MAX_WINDOW_MS = 10000;

    private final double idleMs;
    private final double maxWindowMs;

    private double windowStart = -1;
    private double lastInput = -1;
    private boolean composing;

    public InputCoalescer() {
        this(DEFAULT_IDLE_MS, DEFAULT_MAX_WINDOW_MS);
    }

    public InputCoalescer(double idleMs, double maxWindowMs) {
        if (idleMs <= 0 || maxWindowMs < idleMs) {
            throw new IllegalArgumentException("require 0 < idleMs <= maxWindowMs");
        }
        this.idleMs = idleMs;
        this.maxWindowMs = maxWindowMs;
    }

    /**
     * Records an input event.
     *
     * @return the absolute time (same clock as {@code nowMs}) at which {@link #shouldFlush} should
     *     next be consulted, or -1 while composing (the host re-arms on composition end).
     */
    public double onInput(double nowMs, boolean isComposing) {
        if (windowStart < 0) {
            windowStart = nowMs;
        }
        lastInput = nowMs;
        composing = isComposing;
        return nextDeadline();
    }

    public boolean hasPending() {
        return windowStart >= 0;
    }

    public boolean isComposing() {
        return composing;
    }

    /** Earliest time a flush could be due, or -1 if nothing is pending or composing. */
    public double nextDeadline() {
        if (!hasPending() || composing) {
            return -1;
        }
        return Math.min(lastInput + idleMs, windowStart + maxWindowMs);
    }

    public boolean shouldFlush(double nowMs) {
        if (!hasPending() || composing) {
            return false;
        }
        return nowMs - lastInput >= idleMs || nowMs - windowStart >= maxWindowMs;
    }

    /** Called after any flush (policy-driven or forced) to close the window. */
    public void onFlushed() {
        windowStart = -1;
        lastInput = -1;
    }
}

package com.googledocs.ot;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Randomized (seeded, reproducible) checks of the OT algebra. */
class TransformPropertyTest {

    private static final int ITERATIONS = 10_000;

    @Test
    void transformSatisfiesTp1() {
        Random rnd = new Random(42);
        for (int i = 0; i < ITERATIONS; i++) {
            String s = RandomOps.randomString(rnd, 20);
            TextOperation a = RandomOps.randomOp(rnd, s);
            TextOperation b = RandomOps.randomOp(rnd, s);
            TextOperation[] p = TextOperation.transform(a, b);
            assertEquals(p[1].apply(a.apply(s)), p[0].apply(b.apply(s)),
                    "TP1 violated for s=" + s + " a=" + a + " b=" + b);
        }
    }

    @Test
    void composeEqualsSequentialApply() {
        Random rnd = new Random(7);
        for (int i = 0; i < ITERATIONS; i++) {
            String s = RandomOps.randomString(rnd, 20);
            TextOperation a = RandomOps.randomOp(rnd, s);
            String afterA = a.apply(s);
            TextOperation b = RandomOps.randomOp(rnd, afterA);
            assertEquals(b.apply(afterA), a.compose(b).apply(s),
                    "compose mismatch for s=" + s + " a=" + a + " b=" + b);
        }
    }

    @Test
    void diffReproducesTarget() {
        Random rnd = new Random(99);
        for (int i = 0; i < ITERATIONS; i++) {
            String s = RandomOps.randomString(rnd, 20);
            String t = RandomOps.randomOp(rnd, s).apply(s);
            assertEquals(t, OperationDiff.diff(s, t).apply(s));
        }
    }

    @Test
    void transformIndexStaysInBounds() {
        Random rnd = new Random(3);
        for (int i = 0; i < ITERATIONS; i++) {
            String s = RandomOps.randomString(rnd, 20);
            TextOperation op = RandomOps.randomOp(rnd, s);
            int idx = rnd.nextInt(s.length() + 1);
            int mapped = op.transformIndex(idx);
            if (mapped < 0 || mapped > op.getTargetLength()) {
                throw new AssertionError("caret out of bounds: " + mapped + " for op " + op);
            }
        }
    }
}

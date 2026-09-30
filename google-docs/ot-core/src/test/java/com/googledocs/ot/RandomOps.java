package com.googledocs.ot;

import java.util.Random;

/** Random document / operation generators shared by property tests. */
final class RandomOps {

    private static final String ALPHABET = "abcdefgh XYZ\n";

    private RandomOps() {
    }

    static String randomString(Random rnd, int maxLen) {
        int len = rnd.nextInt(maxLen + 1);
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(ALPHABET.charAt(rnd.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    /** Random multi-component operation applicable to {@code doc}. */
    static TextOperation randomOp(Random rnd, String doc) {
        TextOperation op = new TextOperation();
        int remaining = doc.length();
        while (remaining > 0) {
            int chunk = 1 + rnd.nextInt(Math.min(remaining, 5));
            int kind = rnd.nextInt(4);
            if (kind == 0) {
                op.insert(randomString(rnd, 4));
            } else if (kind == 1) {
                op.delete(chunk);
                remaining -= chunk;
            } else {
                op.retain(chunk);
                remaining -= chunk;
            }
        }
        if (rnd.nextInt(3) == 0) {
            op.insert(randomString(rnd, 4));
        }
        return op;
    }
}

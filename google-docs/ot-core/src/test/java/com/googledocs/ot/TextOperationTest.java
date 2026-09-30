package com.googledocs.ot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class TextOperationTest {

    @Test
    void builderNormalizesComponents() {
        TextOperation op = new TextOperation().retain(2).retain(3).delete(1).insert("ab").insert("c").retain(0);
        // insert is moved before the delete and adjacent components merge
        assertEquals(Arrays.asList(5, "abc", -1), op.getOps());
        assertEquals(6, op.getBaseLength());
        assertEquals(8, op.getTargetLength());
    }

    @Test
    void applyInsertDeleteRetain() {
        TextOperation op = new TextOperation().retain(6).insert("Brave ").retain(5);
        assertEquals("Hello Brave World", op.apply("Hello World"));

        StringBuilder sb = new StringBuilder("Hello World");
        op.applyTo(sb);
        assertEquals("Hello Brave World", sb.toString());
    }

    @Test
    void applyRejectsBaseLengthMismatch() {
        TextOperation op = new TextOperation().retain(3);
        assertThrows(OtException.class, () -> op.apply("abcd"));
    }

    @Test
    void composeMatchesSequentialApply() {
        String s = "abcdef";
        TextOperation a = TextOperation.ofSpan(6, 2, 2, "XYZ"); // ab XYZ ef
        TextOperation b = TextOperation.ofSpan(7, 0, 1, "");    // bXYZef
        assertEquals(b.apply(a.apply(s)), a.compose(b).apply(s));
    }

    @Test
    @DisplayName("Insert inside a concurrently deleted range survives, regardless of order (TP1)")
    void insertInsideConcurrentDeleteConverges() {
        String s = "0123456789";
        TextOperation del = TextOperation.ofSpan(10, 2, 6, "");   // delete "234567"
        TextOperation ins = TextOperation.ofSpan(10, 5, 0, "X");  // insert X between 4 and 5

        TextOperation[] p1 = TextOperation.transform(ins, del);
        TextOperation[] p2 = TextOperation.transform(del, ins);

        String viaDelFirst = p1[0].apply(del.apply(s));
        String viaInsFirst = p1[1].apply(ins.apply(s));
        assertEquals(viaDelFirst, viaInsFirst);
        assertEquals("01X89", viaDelFirst);

        // Swapping operand order still converges to the same text
        assertEquals("01X89", p2[1].apply(del.apply(s)));
        assertEquals("01X89", p2[0].apply(ins.apply(s)));
    }

    @Test
    @DisplayName("Tie-break: first operand's insert goes first at the same index")
    void insertTieBreak() {
        String s = "ab";
        TextOperation a = TextOperation.ofSpan(2, 1, 0, "A");
        TextOperation b = TextOperation.ofSpan(2, 1, 0, "B");
        TextOperation[] p = TextOperation.transform(a, b);
        assertEquals("aABb", p[1].apply(a.apply(s)));
        assertEquals("aABb", p[0].apply(b.apply(s)));
    }

    @Test
    void overlappingDeletesDoNotDoubleDelete() {
        String s = "0123456789";
        TextOperation a = TextOperation.ofSpan(10, 2, 3, ""); // 234
        TextOperation b = TextOperation.ofSpan(10, 3, 4, ""); // 3456
        TextOperation[] p = TextOperation.transform(b, a);
        assertEquals("01789", p[0].apply(a.apply(s)));
        assertEquals("01789", p[1].apply(b.apply(s)));
    }

    @Test
    void transformIndexShiftsCaret() {
        TextOperation ins = TextOperation.ofSpan(10, 3, 0, "abc");
        assertEquals(2, ins.transformIndex(2));
        assertEquals(6, ins.transformIndex(3)); // insert at caret pushes it right
        assertEquals(8, ins.transformIndex(5));

        TextOperation del = TextOperation.ofSpan(10, 2, 4, "");
        assertEquals(1, del.transformIndex(1));
        assertEquals(2, del.transformIndex(4)); // caret inside deleted range snaps to start
        assertEquals(4, del.transformIndex(8));
    }

    @Test
    void wireRoundTripAndValidation() {
        TextOperation op = TextOperation.fromWire(Arrays.asList(5, "abc", -2, 3L));
        assertEquals(Arrays.asList(5, "abc", -2, 3), op.getOps());
        assertEquals(op, TextOperation.fromWire(op.toWire()));

        assertThrows(OtException.class, () -> TextOperation.fromWire(Arrays.asList(1.5)));
        assertThrows(OtException.class, () -> TextOperation.fromWire(Arrays.asList(0)));
        assertThrows(OtException.class, () -> TextOperation.fromWire(Arrays.asList(true)));
        assertThrows(OtException.class, () -> TextOperation.fromWire((java.util.List<?>) null));
    }

    @Test
    void transformRejectsDifferentBases() {
        assertThrows(OtException.class,
                () -> TextOperation.transform(new TextOperation().retain(2), new TextOperation().retain(3)));
    }
}

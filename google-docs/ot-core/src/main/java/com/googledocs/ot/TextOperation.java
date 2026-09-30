package com.googledocs.ot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A text operation in the standard retain / insert / delete model (Google Wave, ot.js, ShareDB).
 *
 * <p>An operation walks the whole base document exactly once:
 * <ul>
 *   <li>{@code retain(n)}: keep the next n characters (stored as a positive Integer)</li>
 *   <li>{@code insert(s)}: insert s at the current position (stored as a String)</li>
 *   <li>{@code delete(n)}: remove the next n characters (stored as a negative Integer)</li>
 * </ul>
 * so {@code baseLength} is the document length it applies to and {@code targetLength} the length after.
 *
 * <p>Unlike a single-span INSERT/DELETE/REPLACE model, this representation makes {@link #compose}
 * and {@link #transform} total functions, and transform satisfies TP1:
 * {@code apply(apply(S, a), b') == apply(apply(S, b), a')} where {@code [a', b'] = transform(a, b)}.
 *
 * <p>Written against the Java 11 subset supported by J2CL: no reflection, no String.format.
 */
public final class TextOperation {

    private final List<Object> ops = new ArrayList<>();
    private int baseLength;
    private int targetLength;

    // ------------------------------------------------------------------------------------------
    // Builders (normalizing: adjacent components merge, zero-length components are dropped,
    // and an insert directly after a delete is moved before it so equal ops have equal form)
    // ------------------------------------------------------------------------------------------

    public TextOperation retain(int n) {
        if (n < 0) {
            throw new OtException("retain expects a non-negative count");
        }
        if (n == 0) {
            return this;
        }
        baseLength += n;
        targetLength += n;
        int last = ops.size() - 1;
        if (last >= 0 && isRetain(ops.get(last))) {
            ops.set(last, (Integer) ops.get(last) + n);
        } else {
            ops.add(n);
        }
        return this;
    }

    public TextOperation insert(String s) {
        if (s == null || s.isEmpty()) {
            return this;
        }
        targetLength += s.length();
        int last = ops.size() - 1;
        if (last >= 0 && isInsert(ops.get(last))) {
            ops.set(last, (String) ops.get(last) + s);
        } else if (last >= 0 && isDelete(ops.get(last))) {
            // Keep inserts before deletes: "delete then insert" == "insert then delete".
            if (last >= 1 && isInsert(ops.get(last - 1))) {
                ops.set(last - 1, (String) ops.get(last - 1) + s);
            } else {
                ops.add(last, s);
            }
        } else {
            ops.add(s);
        }
        return this;
    }

    public TextOperation delete(int n) {
        if (n < 0) {
            n = -n; // accept the wire form (negative) as well
        }
        if (n == 0) {
            return this;
        }
        baseLength += n;
        int last = ops.size() - 1;
        if (last >= 0 && isDelete(ops.get(last))) {
            ops.set(last, (Integer) ops.get(last) - n);
        } else {
            ops.add(-n);
        }
        return this;
    }

    // ------------------------------------------------------------------------------------------
    // Accessors
    // ------------------------------------------------------------------------------------------

    public int getBaseLength() {
        return baseLength;
    }

    public int getTargetLength() {
        return targetLength;
    }

    /** Components: positive Integer = retain, negative Integer = delete, String = insert. */
    public List<Object> getOps() {
        return Collections.unmodifiableList(ops);
    }

    /** True if applying this op leaves any document unchanged. */
    public boolean isNoop() {
        return ops.isEmpty() || (ops.size() == 1 && isRetain(ops.get(0)));
    }

    /** Total characters inserted by this op (used for payload caps). */
    public int insertedChars() {
        int total = 0;
        for (Object op : ops) {
            if (isInsert(op)) {
                total += ((String) op).length();
            }
        }
        return total;
    }

    public static boolean isRetain(Object op) {
        return op instanceof Integer && (Integer) op > 0;
    }

    public static boolean isDelete(Object op) {
        return op instanceof Integer && (Integer) op < 0;
    }

    public static boolean isInsert(Object op) {
        return op instanceof String;
    }

    // ------------------------------------------------------------------------------------------
    // Apply
    // ------------------------------------------------------------------------------------------

    public String apply(String doc) {
        if (doc.length() != baseLength) {
            throw new OtException("base length mismatch: op expects " + baseLength + ", document has " + doc.length());
        }
        StringBuilder out = new StringBuilder(targetLength);
        int idx = 0;
        for (Object op : ops) {
            if (isRetain(op)) {
                int n = (Integer) op;
                out.append(doc, idx, idx + n);
                idx += n;
            } else if (isInsert(op)) {
                out.append((String) op);
            } else {
                idx += -(Integer) op;
            }
        }
        return out.toString();
    }

    /** Applies this op in place to a buffer whose length must equal {@link #getBaseLength()}. */
    public void applyTo(StringBuilder buffer) {
        if (buffer.length() != baseLength) {
            throw new OtException("base length mismatch: op expects " + baseLength + ", document has " + buffer.length());
        }
        int idx = 0;
        for (Object op : ops) {
            if (isRetain(op)) {
                idx += (Integer) op;
            } else if (isInsert(op)) {
                String s = (String) op;
                buffer.insert(idx, s);
                idx += s.length();
            } else {
                int n = -(Integer) op;
                buffer.delete(idx, idx + n);
            }
        }
    }

    /**
     * Maps a caret index in the base document to the target document. An insert exactly at the
     * index pushes the caret to the right (remote text typed at my cursor appears before it).
     */
    public int transformIndex(int index) {
        int oldPos = 0;
        int newIndex = index;
        for (Object op : ops) {
            if (oldPos > index) {
                break;
            }
            if (isRetain(op)) {
                oldPos += (Integer) op;
            } else if (isInsert(op)) {
                newIndex += ((String) op).length();
            } else {
                int n = -(Integer) op;
                newIndex -= Math.min(n, index - oldPos);
                oldPos += n;
            }
        }
        return newIndex;
    }

    // ------------------------------------------------------------------------------------------
    // Compose: (this then b) as a single operation
    // ------------------------------------------------------------------------------------------

    public TextOperation compose(TextOperation b) {
        if (this.targetLength != b.baseLength) {
            throw new OtException("compose: first op target length " + targetLength
                    + " != second op base length " + b.baseLength);
        }
        TextOperation result = new TextOperation();
        List<Object> ops1 = this.ops;
        List<Object> ops2 = b.ops;
        int i1 = 0;
        int i2 = 0;
        Object op1 = next(ops1, i1++);
        Object op2 = next(ops2, i2++);

        while (op1 != null || op2 != null) {
            if (isDelete(op1)) {
                result.delete((Integer) op1);
                op1 = next(ops1, i1++);
                continue;
            }
            if (isInsert(op2)) {
                result.insert((String) op2);
                op2 = next(ops2, i2++);
                continue;
            }
            if (op1 == null) {
                throw new OtException("compose: first operation is too short");
            }
            if (op2 == null) {
                throw new OtException("compose: first operation is too long");
            }

            if (isRetain(op1) && isRetain(op2)) {
                int a = (Integer) op1;
                int c = (Integer) op2;
                if (a > c) {
                    result.retain(c);
                    op1 = a - c;
                    op2 = next(ops2, i2++);
                } else if (a == c) {
                    result.retain(a);
                    op1 = next(ops1, i1++);
                    op2 = next(ops2, i2++);
                } else {
                    result.retain(a);
                    op2 = c - a;
                    op1 = next(ops1, i1++);
                }
            } else if (isInsert(op1) && isDelete(op2)) {
                String s = (String) op1;
                int d = -(Integer) op2;
                if (s.length() > d) {
                    op1 = s.substring(d);
                    op2 = next(ops2, i2++);
                } else if (s.length() == d) {
                    op1 = next(ops1, i1++);
                    op2 = next(ops2, i2++);
                } else {
                    op2 = -(d - s.length());
                    op1 = next(ops1, i1++);
                }
            } else if (isInsert(op1) && isRetain(op2)) {
                String s = (String) op1;
                int r = (Integer) op2;
                if (s.length() > r) {
                    result.insert(s.substring(0, r));
                    op1 = s.substring(r);
                    op2 = next(ops2, i2++);
                } else if (s.length() == r) {
                    result.insert(s);
                    op1 = next(ops1, i1++);
                    op2 = next(ops2, i2++);
                } else {
                    result.insert(s);
                    op2 = r - s.length();
                    op1 = next(ops1, i1++);
                }
            } else if (isRetain(op1) && isDelete(op2)) {
                int r = (Integer) op1;
                int d = -(Integer) op2;
                if (r > d) {
                    result.delete(d);
                    op1 = r - d;
                    op2 = next(ops2, i2++);
                } else if (r == d) {
                    result.delete(d);
                    op1 = next(ops1, i1++);
                    op2 = next(ops2, i2++);
                } else {
                    result.delete(r);
                    op2 = -(d - r);
                    op1 = next(ops1, i1++);
                }
            } else {
                throw new OtException("compose: incompatible components");
            }
        }
        return result;
    }

    // ------------------------------------------------------------------------------------------
    // Transform: a and b are concurrent (same base). Returns {a', b'} such that
    // apply(apply(S, a), b') == apply(apply(S, b), a').
    // Tie-break: when both insert at the same position, a's insert goes first. Both server and
    // client always pass the *client* operation as `a`, so the tie-break is identical everywhere.
    // ------------------------------------------------------------------------------------------

    public static TextOperation[] transform(TextOperation a, TextOperation b) {
        if (a.baseLength != b.baseLength) {
            throw new OtException("transform: both operations must have the same base length ("
                    + a.baseLength + " vs " + b.baseLength + ")");
        }
        TextOperation aPrime = new TextOperation();
        TextOperation bPrime = new TextOperation();
        List<Object> ops1 = a.ops;
        List<Object> ops2 = b.ops;
        int i1 = 0;
        int i2 = 0;
        Object op1 = next(ops1, i1++);
        Object op2 = next(ops2, i2++);

        while (op1 != null || op2 != null) {
            if (isInsert(op1)) {
                String s = (String) op1;
                aPrime.insert(s);
                bPrime.retain(s.length());
                op1 = next(ops1, i1++);
                continue;
            }
            if (isInsert(op2)) {
                String s = (String) op2;
                aPrime.retain(s.length());
                bPrime.insert(s);
                op2 = next(ops2, i2++);
                continue;
            }
            if (op1 == null) {
                throw new OtException("transform: first operation is too short");
            }
            if (op2 == null) {
                throw new OtException("transform: first operation is too long");
            }

            int minl;
            if (isRetain(op1) && isRetain(op2)) {
                int r1 = (Integer) op1;
                int r2 = (Integer) op2;
                if (r1 > r2) {
                    minl = r2;
                    op1 = r1 - r2;
                    op2 = next(ops2, i2++);
                } else if (r1 == r2) {
                    minl = r2;
                    op1 = next(ops1, i1++);
                    op2 = next(ops2, i2++);
                } else {
                    minl = r1;
                    op2 = r2 - r1;
                    op1 = next(ops1, i1++);
                }
                aPrime.retain(minl);
                bPrime.retain(minl);
            } else if (isDelete(op1) && isDelete(op2)) {
                // Both delete the same characters: neither prime needs to delete them again.
                int d1 = -(Integer) op1;
                int d2 = -(Integer) op2;
                if (d1 > d2) {
                    op1 = -(d1 - d2);
                    op2 = next(ops2, i2++);
                } else if (d1 == d2) {
                    op1 = next(ops1, i1++);
                    op2 = next(ops2, i2++);
                } else {
                    op2 = -(d2 - d1);
                    op1 = next(ops1, i1++);
                }
            } else if (isDelete(op1) && isRetain(op2)) {
                int d1 = -(Integer) op1;
                int r2 = (Integer) op2;
                if (d1 > r2) {
                    minl = r2;
                    op1 = -(d1 - r2);
                    op2 = next(ops2, i2++);
                } else if (d1 == r2) {
                    minl = r2;
                    op1 = next(ops1, i1++);
                    op2 = next(ops2, i2++);
                } else {
                    minl = d1;
                    op2 = r2 - d1;
                    op1 = next(ops1, i1++);
                }
                aPrime.delete(minl);
            } else if (isRetain(op1) && isDelete(op2)) {
                int r1 = (Integer) op1;
                int d2 = -(Integer) op2;
                if (r1 > d2) {
                    minl = d2;
                    op1 = r1 - d2;
                    op2 = next(ops2, i2++);
                } else if (r1 == d2) {
                    minl = r1;
                    op1 = next(ops1, i1++);
                    op2 = next(ops2, i2++);
                } else {
                    minl = r1;
                    op2 = -(d2 - r1);
                    op1 = next(ops1, i1++);
                }
                bPrime.delete(minl);
            } else {
                throw new OtException("transform: incompatible components");
            }
        }
        return new TextOperation[] {aPrime, bPrime};
    }

    private static Object next(List<Object> ops, int i) {
        return i < ops.size() ? ops.get(i) : null;
    }

    // ------------------------------------------------------------------------------------------
    // Wire format: compact array [5, "abc", -2]
    // ------------------------------------------------------------------------------------------

    /**
     * Parses the compact wire form. Accepts any java.lang.Number (Jackson gives Integer/Long,
     * J2CL gives Double for JS numbers) and String. Rejects non-integral, zero and oversized numbers.
     */
    public static TextOperation fromWire(List<?> components) {
        if (components == null) {
            throw new OtException("ops must not be null");
        }
        TextOperation op = new TextOperation();
        for (Object c : components) {
            if (c instanceof String) {
                op.insert((String) c);
            } else if (c instanceof Double || c instanceof Number) {
                double d = ((Number) c).doubleValue();
                if (d != Math.floor(d) || Double.isInfinite(d) || Double.isNaN(d)
                        || Math.abs(d) > Integer.MAX_VALUE || d == 0) {
                    throw new OtException("op components must be non-zero integers");
                }
                int n = (int) d;
                if (n > 0) {
                    op.retain(n);
                } else {
                    op.delete(-n);
                }
            } else {
                throw new OtException("op components must be numbers or strings");
            }
        }
        return op;
    }

    /** Array form of {@link #fromWire(List)}, convenient for J2CL callers receiving JS arrays. */
    public static TextOperation fromWire(Object[] components) {
        if (components == null) {
            throw new OtException("ops must not be null");
        }
        List<Object> list = new ArrayList<>(components.length);
        for (Object c : components) {
            list.add(c);
        }
        return fromWire(list);
    }

    /** JS-friendly wire form: numbers as Double (JS number under J2CL), inserts as String. */
    public Object[] toWire() {
        Object[] out = new Object[ops.size()];
        for (int i = 0; i < ops.size(); i++) {
            Object op = ops.get(i);
            out[i] = isInsert(op) ? op : (Object) Double.valueOf((Integer) op);
        }
        return out;
    }

    /** JSON-friendly wire form for the server (numbers as Integer). */
    public List<Object> toWireList() {
        return new ArrayList<>(ops);
    }

    // ------------------------------------------------------------------------------------------
    // Construction helpers
    // ------------------------------------------------------------------------------------------

    /** Builds an op replacing {@code deleteCount} chars at {@code position} with {@code text}. */
    public static TextOperation ofSpan(int docLength, int position, int deleteCount, String text) {
        if (position < 0 || deleteCount < 0 || position + deleteCount > docLength) {
            throw new OtException("span out of bounds");
        }
        return new TextOperation()
                .retain(position)
                .insert(text)
                .delete(deleteCount)
                .retain(docLength - position - deleteCount);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TextOperation)) {
            return false;
        }
        TextOperation other = (TextOperation) o;
        return baseLength == other.baseLength && targetLength == other.targetLength && ops.equals(other.ops);
    }

    @Override
    public int hashCode() {
        return ops.hashCode() * 31 + baseLength;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < ops.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            Object op = ops.get(i);
            if (isInsert(op)) {
                sb.append('"').append((String) op).append('"');
            } else {
                sb.append(op);
            }
        }
        return sb.append(']').toString();
    }
}

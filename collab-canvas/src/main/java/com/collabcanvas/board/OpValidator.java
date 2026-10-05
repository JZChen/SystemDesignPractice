package com.collabcanvas.board;

import com.collabcanvas.model.Op;
import com.collabcanvas.model.Shape;
import com.collabcanvas.model.ShapePatch;
import com.collabcanvas.model.ShapeType;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Iterator;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns untrusted client JSON into typed ops / ephemeral messages, using allow-lists only.
 *
 * <p>Every field is checked for type, format and range; unknown fields are rejected (so a client
 * can never smuggle in {@code author} or {@code version}). Failures throw {@link InvalidMessage}
 * with a short generic reason code that is safe to send back to the client.
 */
public final class OpValidator {

    public static final double MAX_COORD = 100_000;
    public static final int MAX_POINT_COORDS = 10_000;     // 5,000 points per stroke
    public static final int MAX_PREVIEW_COORDS = 1_000;    // per preview chunk
    public static final int MAX_OPS_PER_MESSAGE = 100;
    public static final double MIN_WIDTH = 1;
    public static final double MAX_WIDTH = 50;

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9-]{1,48}");
    private static final Pattern COLOR = Pattern.compile("#[0-9a-fA-F]{6}");
    private static final Pattern Z_KEY = Pattern.compile("[0-9A-Za-z]{1,32}");

    private static final Set<String> OP_FIELDS_ADD = Set.of("opId", "kind", "shape");
    private static final Set<String> OP_FIELDS_UPDATE = Set.of("opId", "kind", "id", "set");
    private static final Set<String> OP_FIELDS_DELETE = Set.of("opId", "kind", "id");
    private static final Set<String> SHAPE_FIELDS =
            Set.of("id", "type", "x", "y", "w", "h", "points", "stroke", "fill", "width", "z");
    private static final Set<String> PATCH_FIELDS =
            Set.of("x", "y", "w", "h", "points", "stroke", "fill", "width", "z");
    private static final Set<String> PREVIEW_FIELDS = Set.of("t", "sid", "pts", "stroke", "width", "done");
    private static final Set<String> CURSOR_FIELDS = Set.of("t", "x", "y");

    private OpValidator() {
    }

    /** Short, generic reason code; never contains client data. */
    public static final class InvalidMessage extends RuntimeException {
        public InvalidMessage(String reason) {
            super(reason, null, false, false);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Ops
    // ------------------------------------------------------------------------------------------

    public static Op parseOp(JsonNode n) {
        if (n == null || !n.isObject()) {
            throw new InvalidMessage("invalid_op");
        }
        String kind = text(n, "kind");
        String opId = id(n, "opId");
        switch (kind) {
            case "add" -> {
                onlyFields(n, OP_FIELDS_ADD);
                return new Op.Add(opId, parseShape(n.get("shape")));
            }
            case "update" -> {
                onlyFields(n, OP_FIELDS_UPDATE);
                ShapePatch patch = parsePatch(n.get("set"));
                if (patch.isEmpty()) {
                    throw new InvalidMessage("empty_update");
                }
                return new Op.Update(opId, id(n, "id"), patch);
            }
            case "delete" -> {
                onlyFields(n, OP_FIELDS_DELETE);
                return new Op.Delete(opId, id(n, "id"));
            }
            default -> throw new InvalidMessage("invalid_kind");
        }
    }

    static Shape parseShape(JsonNode n) {
        if (n == null || !n.isObject()) {
            throw new InvalidMessage("invalid_shape");
        }
        onlyFields(n, SHAPE_FIELDS);
        ShapeType type;
        try {
            type = ShapeType.valueOf(text(n, "type"));
        } catch (IllegalArgumentException e) {
            throw new InvalidMessage("invalid_type");
        }
        double[] points = n.has("points") ? points(n.get("points"), MAX_POINT_COORDS) : null;
        if ((type == ShapeType.STROKE) != (points != null)) {
            throw new InvalidMessage("points_mismatch");
        }
        return new Shape(
                id(n, "id"), type,
                coord(n, "x"), coord(n, "y"), coord(n, "w"), coord(n, "h"),
                points,
                color(n, "stroke"), fill(n, "fill"), width(n, "width"), zKey(n, "z"),
                null, 0);
    }

    static ShapePatch parsePatch(JsonNode n) {
        if (n == null || !n.isObject()) {
            throw new InvalidMessage("invalid_patch");
        }
        onlyFields(n, PATCH_FIELDS);
        return new ShapePatch(
                n.has("x") ? coord(n, "x") : null,
                n.has("y") ? coord(n, "y") : null,
                n.has("w") ? coord(n, "w") : null,
                n.has("h") ? coord(n, "h") : null,
                n.has("points") ? points(n.get("points"), MAX_POINT_COORDS) : null,
                n.has("stroke") ? color(n, "stroke") : null,
                n.has("fill") ? fill(n, "fill") : null,
                n.has("width") ? width(n, "width") : null,
                n.has("z") ? zKey(n, "z") : null);
    }

    public static int checkOpCount(JsonNode ops) {
        if (ops == null || !ops.isArray() || ops.isEmpty() || ops.size() > MAX_OPS_PER_MESSAGE) {
            throw new InvalidMessage("invalid_ops");
        }
        return ops.size();
    }

    // ------------------------------------------------------------------------------------------
    // Ephemeral messages (relayed, never stored)
    // ------------------------------------------------------------------------------------------

    public record Preview(String sid, double[] pts, String stroke, double width, boolean done) {
    }

    public record Cursor(double x, double y) {
    }

    public static Preview parsePreview(JsonNode n) {
        onlyFields(n, PREVIEW_FIELDS);
        JsonNode done = n.get("done");
        if (done == null || !done.isBoolean()) {
            throw new InvalidMessage("invalid_preview");
        }
        double[] pts = points(n.get("pts"), MAX_PREVIEW_COORDS);
        return new Preview(id(n, "sid"), pts, color(n, "stroke"), width(n, "width"), done.booleanValue());
    }

    public static Cursor parseCursor(JsonNode n) {
        onlyFields(n, CURSOR_FIELDS);
        return new Cursor(coord(n, "x"), coord(n, "y"));
    }

    // ------------------------------------------------------------------------------------------
    // Field helpers
    // ------------------------------------------------------------------------------------------

    private static void onlyFields(JsonNode n, Set<String> allowed) {
        Iterator<String> names = n.fieldNames();
        while (names.hasNext()) {
            if (!allowed.contains(names.next())) {
                throw new InvalidMessage("unknown_field");
            }
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || !v.isTextual()) {
            throw new InvalidMessage("invalid_" + field);
        }
        return v.textValue();
    }

    private static String id(JsonNode n, String field) {
        String s = text(n, field);
        if (!ID.matcher(s).matches()) {
            throw new InvalidMessage("invalid_" + field);
        }
        return s;
    }

    private static String color(JsonNode n, String field) {
        String s = text(n, field);
        if (!COLOR.matcher(s).matches()) {
            throw new InvalidMessage("invalid_" + field);
        }
        return s.toLowerCase();
    }

    private static String fill(JsonNode n, String field) {
        String s = text(n, field);
        if (s.equals("none")) {
            return s;
        }
        if (!COLOR.matcher(s).matches()) {
            throw new InvalidMessage("invalid_" + field);
        }
        return s.toLowerCase();
    }

    private static String zKey(JsonNode n, String field) {
        String s = text(n, field);
        if (!Z_KEY.matcher(s).matches()) {
            throw new InvalidMessage("invalid_" + field);
        }
        return s;
    }

    private static double number(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || !v.isNumber()) {
            throw new InvalidMessage("invalid_" + field);
        }
        double d = v.doubleValue();
        if (!Double.isFinite(d)) {
            throw new InvalidMessage("invalid_" + field);
        }
        return d;
    }

    private static double coord(JsonNode n, String field) {
        double d = number(n, field);
        if (Math.abs(d) > MAX_COORD) {
            throw new InvalidMessage("invalid_" + field);
        }
        return d;
    }

    private static double width(JsonNode n, String field) {
        double d = number(n, field);
        if (d < MIN_WIDTH || d > MAX_WIDTH) {
            throw new InvalidMessage("invalid_" + field);
        }
        return d;
    }

    private static double[] points(JsonNode arr, int maxCoords) {
        if (arr == null || !arr.isArray() || arr.isEmpty() || arr.size() % 2 != 0 || arr.size() > maxCoords) {
            throw new InvalidMessage("invalid_points");
        }
        double[] out = new double[arr.size()];
        for (int i = 0; i < out.length; i++) {
            JsonNode v = arr.get(i);
            if (!v.isNumber() || !Double.isFinite(v.doubleValue()) || Math.abs(v.doubleValue()) > MAX_COORD) {
                throw new InvalidMessage("invalid_points");
            }
            out[i] = v.doubleValue();
        }
        return out;
    }
}

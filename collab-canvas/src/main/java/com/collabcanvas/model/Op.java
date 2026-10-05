package com.collabcanvas.model;

/**
 * A change to the board, addressed by shape id (never by position), so concurrent ops never need
 * to be transformed: the server applies them in arrival order and the last write wins per field.
 */
public sealed interface Op permits Op.Add, Op.Update, Op.Delete {

    /** Client-chosen id of this op; echoed back in {@code committed} / {@code rejected}. */
    String opId();

    /** Id of the shape this op targets. */
    String shapeId();

    record Add(String opId, Shape shape) implements Op {
        @Override
        public String shapeId() {
            return shape.id();
        }
    }

    record Update(String opId, String shapeId, ShapePatch set) implements Op {
    }

    record Delete(String opId, String shapeId) implements Op {
    }
}

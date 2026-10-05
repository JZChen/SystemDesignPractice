package com.collabcanvas.model;

/**
 * The mutable fields of a shape. {@code null} means "leave unchanged". {@code id}, {@code type},
 * {@code author} and {@code version} can never be patched.
 */
public record ShapePatch(
        Double x,
        Double y,
        Double w,
        Double h,
        double[] points,
        String stroke,
        String fill,
        Double width,
        String z) {

    public ShapePatch {
        points = points == null ? null : points.clone();
    }

    @Override
    public double[] points() {
        return points == null ? null : points.clone();
    }

    public boolean isEmpty() {
        return x == null && y == null && w == null && h == null && points == null
                && stroke == null && fill == null && width == null && z == null;
    }
}

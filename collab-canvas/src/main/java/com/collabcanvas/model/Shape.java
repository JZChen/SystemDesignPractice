package com.collabcanvas.model;

import java.util.Arrays;

/**
 * One object on the board. Immutable: every change produces a new instance.
 *
 * <p>Coordinates are canvas pixels. {@code points} is only used by {@link ShapeType#STROKE}: a flat
 * {@code [dx0, dy0, dx1, dy1, ...]} array relative to {@code (x, y)}. {@code fill} is a
 * {@code #rrggbb} color or {@code "none"}. {@code z} is a fractional index: shapes render in
 * ({@code z}, {@code id}) order using plain string comparison.
 *
 * <p>{@code author} (creator's user id) and {@code version} (seq of the last change) are always set
 * by the server; client-supplied values are never accepted.
 */
public record Shape(
        String id,
        ShapeType type,
        double x,
        double y,
        double w,
        double h,
        double[] points,
        String stroke,
        String fill,
        double width,
        String z,
        String author,
        long version) {

    public Shape {
        points = points == null ? null : points.clone();
    }

    @Override
    public double[] points() {
        return points == null ? null : points.clone();
    }

    /** Number of point coordinates this shape holds (for the per-board memory budget). */
    public int pointCount() {
        return points == null ? 0 : points.length;
    }

    public Shape withServerFields(String newAuthor, long newVersion) {
        return new Shape(id, type, x, y, w, h, points, stroke, fill, width, z, newAuthor, newVersion);
    }

    /** Last-writer-wins: every field present in the patch overwrites the current value. */
    public Shape patched(ShapePatch p, long newVersion) {
        return new Shape(
                id, type,
                p.x() != null ? p.x() : x,
                p.y() != null ? p.y() : y,
                p.w() != null ? p.w() : w,
                p.h() != null ? p.h() : h,
                p.points() != null ? p.points() : points,
                p.stroke() != null ? p.stroke() : stroke,
                p.fill() != null ? p.fill() : fill,
                p.width() != null ? p.width() : width,
                p.z() != null ? p.z() : z,
                author,
                newVersion);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Shape s)) {
            return false;
        }
        return id.equals(s.id) && type == s.type && x == s.x && y == s.y && w == s.w && h == s.h
                && Arrays.equals(points, s.points) && stroke.equals(s.stroke) && fill.equals(s.fill)
                && width == s.width && z.equals(s.z) && java.util.Objects.equals(author, s.author)
                && version == s.version;
    }

    @Override
    public int hashCode() {
        return id.hashCode() * 31 + Long.hashCode(version);
    }
}

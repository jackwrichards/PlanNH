package com.gtnhplanner.ui.tutorial;

/** A rectangle in GUI pixels. */
public record Rect(float x, float y, float w, float h) {

    public float cx() {
        return x + w / 2;
    }

    public float cy() {
        return y + h / 2;
    }

    public float right() {
        return x + w;
    }

    public float bottom() {
        return y + h;
    }

    public boolean contains(final float px, final float py) {
        return px >= x && py >= y && px < x + w && py < y + h;
    }

    public boolean overlaps(final Rect o) {
        return x < o.right() && o.x < right() && y < o.bottom() && o.y < bottom();
    }

    public Rect grow(final float by) {
        return new Rect(x - by, y - by, w + 2 * by, h + 2 * by);
    }

    /** The smallest rectangle around both. */
    public Rect union(final Rect o) {
        final float x0 = Math.min(x, o.x), y0 = Math.min(y, o.y);
        return new Rect(x0, y0, Math.max(right(), o.right()) - x0, Math.max(bottom(), o.bottom()) - y0);
    }

    /** Part way from this to {@code to}, {@code t} from 0 to 1. */
    public Rect lerp(final Rect to, final float t) {
        return new Rect(x + (to.x - x) * t, y + (to.y - y) * t, w + (to.w - w) * t, h + (to.h - h) * t);
    }

    public static Rect of(final int[] r) {
        return r == null ? null : new Rect(r[0], r[1], r[2], r[3]);
    }
}

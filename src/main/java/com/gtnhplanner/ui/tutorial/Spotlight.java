package com.gtnhplanner.ui.tutorial;

import com.gtnhplanner.ui.theme.Hyb;

/**
 * The spotlight: everything but the thing being talked about dims, and a thin gold frame sits round it. It glides
 * from one thing to the next and fades in and out, so the eye follows it.
 */
final class Spotlight {

    /** How dark the rest gets. */
    private static final int DIM = 0x9C;
    private static final float PAD = 3;

    private Rect shown;
    private Rect target;
    private Rect from;
    private long movedAt;
    private float level;
    /** Whether the rest dims (or only the frame shows, where a tip will open beside the thing lit). */
    private boolean dim = true;
    private float dimLevel;

    /** Lights {@code r} (null: nothing, the screen undimmed). */
    void on(final Rect r, final boolean dims) {
        dim = dims;
        if (r == null) {
            target = null;
            return;
        }
        final Rect padded = r.grow(PAD);
        if (target != null && near(target, padded)) {
            target = padded;
            return;
        }
        from = shown != null && level > 0.05f ? shown : padded;
        target = padded;
        movedAt = System.currentTimeMillis();
    }

    void off() {
        target = null;
    }

    boolean lit() {
        return target != null;
    }

    private static boolean near(final Rect a, final Rect b) {
        return Math.abs(a.x() - b.x()) < 1 && Math.abs(a.y() - b.y()) < 1
            && Math.abs(a.w() - b.w()) < 1
            && Math.abs(a.h() - b.h()) < 1;
    }

    /** Where the spotlight is now, or null. */
    Rect current() {
        return level > 0.05f ? shown : null;
    }

    void draw(final int screenW, final int screenH, final float dt) {
        level += ((target != null ? 1 : 0) - level) * Math.min(1, dt / 140f);
        dimLevel += ((target != null && dim ? 1 : 0) - dimLevel) * Math.min(1, dt / 160f);
        if (target != null) {
            final float t = Math.min(1, (System.currentTimeMillis() - movedAt) / 260f);
            final float e = t < 0.5f ? 4 * t * t * t : 1 - (float) Math.pow(-2 * t + 2, 3) / 2;
            shown = from == null ? target : from.lerp(target, e);
        }
        if (shown == null || level < 0.01f) return;
        final int a = Math.round(DIM * Math.min(level, dimLevel));
        final int shade = a << 24;
        // The dark stops a few pixels out and fades in over them, so the lit edge is soft.
        final int soft = 4;
        final float x0 = shown.x() - soft, y0 = shown.y() - soft, x1 = shown.right() + soft, y1 = shown.bottom() + soft;
        Hyb.beginBatch();
        Hyb.rect(0, 0, screenW, y0, shade);
        Hyb.rect(0, y1, screenW, screenH - y1, shade);
        Hyb.rect(0, y0, x0, y1 - y0, shade);
        Hyb.rect(x1, y0, screenW - x1, y1 - y0, shade);
        for (int i = 0; i < soft; i++) Hyb.ring(
            shown.x() - i,
            shown.y() - i,
            shown.w() + 2 * i,
            shown.h() + 2 * i,
            1,
            Math.round(a * (i + 1f) / (soft + 1)) << 24);
        final int gold = Math.round(230 * level) << 24 | 0xFFD257;
        final float sx = shown.x(), sy = shown.y();
        Hyb.ring(sx, sy, shown.w(), shown.h(), 1, gold);
        Hyb.endBatch();
    }
}

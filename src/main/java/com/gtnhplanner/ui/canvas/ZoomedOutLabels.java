package com.gtnhplanner.ui.canvas;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.gtnhplanner.ui.theme.Hyb;

/**
 * The names over cards and drawers when the board is zoomed far out, and on the minimap (the "Names when zoomed out"
 * setting), placed the way a map places its labels: drawn on top of everything in one pass, small (a screen pixel per
 * font pixel, so sharp at any zoom), no wider than what they name (cut short with "..."), each where it keeps clear of
 * the other names, the cards and the drawers, and left out where there is no such place. Zooming in makes room for the
 * rest. Cards' names go first, then the drawers', the busiest first.
 */
public final class ZoomedOutLabels {

    /** Where a name goes against its box, in order of preference. */
    enum Side {
        ABOVE,
        BELOW,
        LEFT,
        RIGHT
    }

    /**
     * A name for a box (world coordinates), the sides it may sit on, best first, how much it matters, and how wide it
     * may be (world units).
     */
    private record Request(String text, float x, float y, float w, float h, Side[] sides, double priority,
        float widest) {}

    private final List<Request> requests = new ArrayList<>();
    /** The cards' and drawers' boxes this frame: names keep off them. */
    private final List<float[]> boxes = new ArrayList<>();

    public void clear() {
        requests.clear();
        boxes.clear();
    }

    public void card(final String text, final float x, final float y, final float w, final float h) {
        boxes.add(new float[] { x, y, w, h });
        requests.add(new Request(text, x, y, w, h, new Side[] { Side.ABOVE, Side.BELOW }, 1e18, w));
    }

    /** A drawer: a source's name to its left (cards are to its right), a product's to its right. */
    public void drawer(final String text, final float x, final float y, final float w, final float h,
        final boolean source, final double rate) {
        final Side[] sides = source ? new Side[] { Side.LEFT, Side.ABOVE, Side.BELOW }
            : new Side[] { Side.RIGHT, Side.ABOVE, Side.BELOW };
        boxes.add(new float[] { x, y, w, h });
        requests.add(new Request(text, x, y, w, h, sides, Math.abs(rate), 2 * w));
    }

    /**
     * Places and draws the names in world space, the transform already applied ({@code zoom} GUI pixels to a world
     * unit).
     */
    public void draw(final float zoom) {
        if (requests.isEmpty()) return;
        // A screen pixel per font pixel (two at large GUI scales), in world units.
        final int gui = new net.minecraft.client.gui.ScaledResolution(
            net.minecraft.client.Minecraft.getMinecraft(),
            net.minecraft.client.Minecraft.getMinecraft().displayWidth,
            net.minecraft.client.Minecraft.getMinecraft().displayHeight).getScaleFactor();
        final float font = Math.max(1, Math.round(gui / 2f)) / (float) gui / zoom;
        final float s = 1 / zoom, gap = 3 * s, pad = 2 * font, lh = 8 * font + 2 * pad;
        // Room kept between two names side by side, so they never read as one.
        final float apart = 6 * s;
        final List<float[]> placed = new ArrayList<>();
        final List<Request> order = new ArrayList<>(requests);
        order.sort(Comparator.comparingDouble(r -> -r.priority()));
        for (final Request r : order) {
            final String text = Hyb.fit(r.text(), (int) ((r.widest() - 2 * pad) / font));
            if (text.isEmpty()) continue;
            final float lw = Hyb.width(text) * font + 2 * pad;
            for (final Side side : r.sides()) {
                final float lx = switch (side) {
                    case ABOVE, BELOW -> r.x() + (r.w() - lw) / 2f;
                    case LEFT -> r.x() - gap - lw;
                    case RIGHT -> r.x() + r.w() + gap;
                };
                final float ly = switch (side) {
                    case ABOVE -> r.y() - gap - lh;
                    case BELOW -> r.y() + r.h() + gap;
                    case LEFT, RIGHT -> r.y() + (r.h() - lh) / 2f;
                };
                final float[] box = { lx, ly, lw, lh };
                final float[] room = { lx - apart, ly - gap, lw + 2 * apart, lh + 2 * gap };
                if (overlapsAny(room, placed) || overlapsAny(box, boxes)) continue;
                placed.add(box);
                Hyb.rect(lx, ly, lw, lh, 0xC0101114);
                Hyb.text(text, lx + pad, ly + pad, font, Hyb.INK);
                break;
            }
        }
    }

    private static boolean overlapsAny(final float[] a, final List<float[]> others) {
        for (final float[] b : others) {
            if (a[0] < b[0] + b[2] && b[0] < a[0] + a[2] && a[1] < b[1] + b[3] && b[1] < a[1] + a[3]) return true;
        }
        return false;
    }
}

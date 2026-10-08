package com.gtnhplanner.ui.canvas;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.gtnhplanner.ui.theme.Hyb;

/**
 * The names over cards and drawers when the board is zoomed far out, and on the minimap (the "Names when zoomed out"
 * setting), placed the
 * way a map places its labels: drawn on top of everything in one pass, a GUI pixel per font pixel whatever the zoom,
 * each where it overlaps no other name and no card, and left out where there is no such place. Zooming in makes room
 * for the rest. Cards' names go first, then the drawers', the busiest first.
 */
public final class ZoomedOutLabels {

    /** Where a name goes against its box, in order of preference. */
    enum Side {
        ABOVE,
        BELOW,
        LEFT,
        RIGHT
    }

    /** A name for a box (world coordinates), the sides it may sit on, best first, and how much it matters. */
    private record Request(String text, float x, float y, float w, float h, Side[] sides, double priority) {}

    private final List<Request> requests = new ArrayList<>();
    /** The cards' boxes this frame: names keep off them. */
    private final List<float[]> boxes = new ArrayList<>();

    public void clear() {
        requests.clear();
        boxes.clear();
    }

    public void card(final String text, final float x, final float y, final float w, final float h) {
        boxes.add(new float[] { x, y, w, h });
        requests.add(new Request(text, x, y, w, h, new Side[] { Side.ABOVE, Side.BELOW }, 1e18));
    }

    /** A drawer: a source's name to its left (cards are to its right), a product's to its right. */
    public void drawer(final String text, final float x, final float y, final float w, final float h,
        final boolean source, final double rate) {
        final Side[] sides = source ? new Side[] { Side.LEFT, Side.ABOVE, Side.BELOW }
            : new Side[] { Side.RIGHT, Side.ABOVE, Side.BELOW };
        requests.add(new Request(text, x, y, w, h, sides, Math.abs(rate)));
    }

    /** Places and draws the names in world space, the canvas's transform already applied. */
    public void draw(final float zoom) {
        if (requests.isEmpty()) return;
        final float s = 1 / zoom, gap = 3 * s, lh = 10 * s;
        final List<float[]> placed = new ArrayList<>();
        final List<Request> order = new ArrayList<>(requests);
        order.sort(Comparator.comparingDouble(r -> -r.priority()));
        for (final Request r : order) {
            final float lw = Hyb.width(r.text()) * s + 2 * s;
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
                if (overlapsAny(box, placed) || overlapsAny(box, boxes)) continue;
                placed.add(box);
                Hyb.rect(lx, ly, lw, lh, 0xE0101114);
                Hyb.text(r.text(), lx + s, ly + s, s, Hyb.INK);
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

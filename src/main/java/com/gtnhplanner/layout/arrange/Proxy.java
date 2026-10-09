package com.gtnhplanner.layout.arrange;

/**
 * The arrange's stand-in for a routed wire, priced in the router's points: a path shaped like the router's (leave at
 * the rim point facing the far card, run straight for the clean cells, the shortest eight-direction way with one
 * centred diagonal, land straight), its length, its bends, the crossings between two of them, and a detour estimate for
 * each card one runs through. As Factory Flow's board-arrange-optimize.ts.
 *
 * <p>
 * A path is its corners as one array, x then y: {@code [x0, y0, x1, y1, ...]}.
 */
public final class Proxy {

    /** A box: its four edges, in world px. */
    public record Rect(double left, double top, double right, double bottom) {

        public static Rect of(final double x, final double y, final double width, final double height) {
            return new Rect(x, y, x + width, y + height);
        }
    }

    /** How far a wire runs straight out of a card and into one before it may bend. */
    private static final double CLEAN = 2 * Grid.CELL;

    private Proxy() {}

    /**
     * A wire's weight from its width, so busier wires cost more to lay long or bent: widths 4 px (quietest) to 16 px
     * (busiest) weigh 1 to 2.5. Length and bends count the weight times over in the points, a crossing the heavier
     * wire's.
     */
    public static double wireWeight(final Double width) {
        return 0.5 + (width == null ? 4 : width) / 8;
    }

    /**
     * The rim point of {@code rect} facing {@code other} and the outward normal there: {x, y, nx, ny}. The side is
     * chosen by the gap between the two rectangles, not by the other card's centre (a drawer beside a tall tower faces
     * it across the gap though the tower's centre lies far below); with no clear gap axis, by the centre.
     */
    static double[] exitPoint(final Rect rect, final Rect other) {
        final double toX = (other.left + other.right) / 2, toY = (other.top + other.bottom) / 2;
        final double clampX = Math.min(Math.max(toX, rect.left + Grid.CELL), rect.right - Grid.CELL);
        final double clampY = Math.min(Math.max(toY, rect.top + Grid.CELL), rect.bottom - Grid.CELL);
        final double gapX = Math.max(Math.max(other.left - rect.right, rect.left - other.right), 0);
        final double gapY = Math.max(Math.max(other.top - rect.bottom, rect.top - other.bottom), 0);
        final boolean vertical = gapY > gapX;
        if (vertical || gapX == 0 && gapY == 0 && (toY < rect.top || toY > rect.bottom)) {
            if (toY < rect.top) return new double[] { clampX, rect.top, 0, -1 };
            return new double[] { clampX, rect.bottom, 0, 1 };
        }
        if (toX < rect.left) return new double[] { rect.left, clampY, -1, 0 };
        return new double[] { rect.right, clampY, 1, 0 };
    }

    /** The proxy route from {@code source} to {@code target}: clean exit, shortest octilinear way, clean landing. */
    public static double[] path(final Rect source, final Rect target) {
        // Docking is free, so a wire leaves at the rim point facing its far end, never at a fixed port row.
        final double[] exit = exitPoint(source, target), entry = exitPoint(target, source);
        // Facing sides whose dock ranges overlap line up on one row (or column): the router's straight shot.
        if (exit[2] != 0 && exit[2] == -entry[2]) {
            final double lo = Math.max(source.top, target.top) + Grid.CELL;
            final double hi = Math.min(source.bottom, target.bottom) - Grid.CELL;
            if (lo <= hi) {
                final double y = Math.min(Math.max(entry[1], lo), hi);
                exit[1] = y;
                entry[1] = y;
            }
        } else if (exit[3] != 0 && exit[3] == -entry[3]) {
            final double lo = Math.max(source.left, target.left) + Grid.CELL;
            final double hi = Math.min(source.right, target.right) - Grid.CELL;
            if (lo <= hi) {
                final double x = Math.min(Math.max(entry[0], lo), hi);
                exit[0] = x;
                entry[0] = x;
            }
        }
        // The clean runs reach out along the normals, but only as far as half the room ahead: two cards a couple of
        // cells apart meet in the middle of the gap in a straight line, the way the router draws them.
        final double aheadOfExit = (entry[0] - exit[0]) * exit[2] + (entry[1] - exit[1]) * exit[3];
        final double aheadOfEntry = (exit[0] - entry[0]) * entry[2] + (exit[1] - entry[1]) * entry[3];
        final double stubA = Math.max(0, Math.min(CLEAN, aheadOfExit / 2));
        final double stubB = Math.max(0, Math.min(CLEAN, aheadOfEntry / 2));
        final double ax = exit[0] + exit[2] * stubA, ay = exit[1] + exit[3] * stubA;
        final double bx = entry[0] + entry[2] * stubB, by = entry[1] + entry[3] * stubB;
        final double dx = bx - ax, dy = by - ay, adx = Math.abs(dx), ady = Math.abs(dy);
        if (adx > ady) {
            final double straight = (adx - ady) / 2, sx = Math.signum(dx);
            final double m1x = ax + sx * straight, m1y = ay;
            final double m2x = m1x + sx * ady, m2y = by;
            return new double[] { exit[0], exit[1], ax, ay, m1x, m1y, m2x, m2y, bx, by, entry[0], entry[1] };
        }
        if (ady > adx) {
            final double straight = (ady - adx) / 2, sy = Math.signum(dy);
            final double m1x = ax, m1y = ay + sy * straight;
            final double m2x = bx, m2y = m1y + sy * adx;
            return new double[] { exit[0], exit[1], ax, ay, m1x, m1y, m2x, m2y, bx, by, entry[0], entry[1] };
        }
        return new double[] { exit[0], exit[1], ax, ay, bx, by, entry[0], entry[1] };
    }

    public static double length(final double[] path) {
        double length = 0;
        for (int i = 2; i < path.length; i += 2) length += hypot(path[i] - path[i - 2], path[i + 1] - path[i - 1]);
        return length;
    }

    /**
     * The length of (a, b) as the website's JavaScript works it out (each scaled by the larger, the squares
     * Kahan-summed, the root scaled back): it can differ from {@link Math#hypot} in the last bit, and a last bit can
     * tip the search one way or the other. Quicker, too.
     */
    public static double hypot(final double a, final double b) {
        final double x = Math.abs(a), y = Math.abs(b);
        if (x == Double.POSITIVE_INFINITY || y == Double.POSITIVE_INFINITY) return Double.POSITIVE_INFINITY;
        if (Double.isNaN(x) || Double.isNaN(y)) return Double.NaN;
        final double max = Math.max(x, y);
        if (max == 0) return 0;
        final double nx = x / max, ny = y / max;
        // Two terms: the Kahan correction after the first is exactly zero.
        return Math.sqrt(nx * nx + ny * ny) * max;
    }

    /** Bends along a path at the given prices: 45 degrees, 90, and sharper as three 90s. */
    public static double bends(final double[] path, final Prices prices) {
        double bends = 0;
        for (int i = 4; i < path.length; i += 2) {
            final double ax = path[i - 2] - path[i - 4], ay = path[i - 1] - path[i - 3];
            final double bx = path[i] - path[i - 2], by = path[i + 1] - path[i - 1];
            final double la = hypot(ax, ay), lb = hypot(bx, by);
            if (la < 1e-6 || lb < 1e-6) continue;
            final double cos = (ax * bx + ay * by) / (la * lb);
            if (cos > 0.99) continue;
            bends += cos > 0.5 ? prices.turn45() : cos > -0.5 ? prices.turn90() : 3 * prices.turn90();
        }
        return bends;
    }

    private static double cross(final double ox, final double oy, final double ax, final double ay, final double bx,
        final double by) {
        return (ax - ox) * (by - oy) - (ay - oy) * (bx - ox);
    }

    /** Proper crossings between two paths (touching ends do not count). */
    public static int crossings(final double[] p, final double[] q) {
        int count = 0;
        for (int i = 2; i < p.length; i += 2) {
            final double ax = p[i - 2], ay = p[i - 1], bx = p[i], by = p[i + 1];
            if (ax == bx && ay == by) continue;
            for (int j = 2; j < q.length; j += 2) {
                final double cx = q[j - 2], cy = q[j - 1], dx = q[j], dy = q[j + 1];
                if (cx == dx && cy == dy) continue;
                final double d1 = cross(ax, ay, bx, by, cx, cy), d2 = cross(ax, ay, bx, by, dx, dy);
                final double d3 = cross(cx, cy, dx, dy, ax, ay), d4 = cross(cx, cy, dx, dy, bx, by);
                final double eps = 0.5;
                if ((d1 > eps && d2 < -eps || d1 < -eps && d2 > eps)
                    && (d3 > eps && d4 < -eps || d3 < -eps && d4 > eps)) count++;
            }
        }
        return count;
    }

    /** Whether the segment enters the open rectangle (Liang-Barsky). */
    static boolean segmentEnters(final double ax, final double ay, final double bx, final double by, final Rect rect) {
        final double dx = bx - ax, dy = by - ay;
        final double[] t = { 0, 1 };
        return clip(-dx, ax - rect.left, t) && clip(dx, rect.right - ax, t)
            && clip(-dy, ay - rect.top, t)
            && clip(dy, rect.bottom - ay, t)
            && t[1] - t[0] > 1e-6;
    }

    private static boolean clip(final double p, final double q, final double[] t) {
        if (Math.abs(p) < 1e-9) return q > 0;
        final double r = q / p;
        if (p < 0) {
            if (r > t[1]) return false;
            if (r > t[0]) t[0] = r;
        } else {
            if (r < t[0]) return false;
            if (r < t[1]) t[1] = r;
        }
        return true;
    }

    /**
     * The detour estimate for every card (but the path's own two, {@code skipA} and {@code skipB}) the path runs
     * through: two right angles plus half the card's shorter side plus the margins, per card.
     */
    public static double blocked(final double[] path, final Rect[] rects, final int skipA, final int skipB,
        final Prices prices) {
        double cost = 0;
        // The path's box first: a card whose margin it does not reach cannot be run through (a quick no, and exact).
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY,
            maxY = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < path.length; i += 2) {
            minX = Math.min(minX, path[i]);
            maxX = Math.max(maxX, path[i]);
            minY = Math.min(minY, path[i + 1]);
            maxY = Math.max(maxY, path[i + 1]);
        }
        for (int r = 0; r < rects.length; r++) {
            if (r == skipA || r == skipB) continue;
            final Rect rect = rects[r];
            if (rect.right + Grid.CELL <= minX || rect.left - Grid.CELL >= maxX
                || rect.bottom + Grid.CELL <= minY
                || rect.top - Grid.CELL >= maxY) continue;
            final Rect inflated = new Rect(
                rect.left - Grid.CELL,
                rect.top - Grid.CELL,
                rect.right + Grid.CELL,
                rect.bottom + Grid.CELL);
            for (int i = 2; i < path.length; i += 2) {
                if (segmentEnters(path[i - 2], path[i - 1], path[i], path[i + 1], inflated)) {
                    final double shorter = Math.min(rect.right - rect.left, rect.bottom - rect.top);
                    cost += 2 * prices.turn90() + shorter / 2 + 2 * Grid.CELL;
                    break;
                }
            }
        }
        return cost;
    }
}

package com.sbancuz.plannh.layout;

import java.util.ArrayList;
import java.util.List;

/**
 * Hops: where a wire crosses one drawn behind it, it lifts over in a bump, the schematic jumper that makes a crossing
 * read as "these do not meet" instead of a flat X. Factory Flow's {@code wire-hops.ts}, ported.
 *
 * <p>
 * A hop is measured along the whole wire, not along one straight run: it starts where the wire first comes within
 * clearance of the line it crosses and ends where it is clear again, whichever runs those points are on, and the bump
 * is drawn across the chord between them. Built run by run, a crossing near a bend would kink at the corner, and a
 * bend exactly on the other wire would get no hop.
 */
public final class WireHops {

    /** Air between the two strokes at the top of a hop. Snug, not floating. */
    public static final float GAP = 3;
    /** Nothing sensible needs a bump taller than this, whatever the widths say. */
    public static final float MAX_RADIUS = 44;
    /**
     * The crossed line must overshoot the wire on both sides: a segment that merely ends a pixel or two past it (a
     * T-junction at a port, a turn beside a lane) reads as a touch, not a crossing.
     */
    private static final float OVERSHOOT = 4;
    /** A vertex this close to the crossed line is on it. */
    private static final float ON_LINE = 0.01f;
    /** A bump over a shorter chord than this is not worth drawing. */
    private static final float MIN_CHORD = 4;

    private WireHops() {}

    /** A stretch of a wire drawn behind this one, and how thick it is drawn. */
    public record Crossed(float sx, float sy, float ex, float ey, float width) {}

    /**
     * A bump: half an ellipse on the chord from {@code foot} to {@code landing}, rising {@code rise} toward the unit
     * normal ({@code nx}, {@code ny}).
     */
    public record Bump(float footX, float footY, float landX, float landY, float rise, float nx, float ny) {

        public float chord() {
            return (float) Math.hypot(landX - footX, landY - footY);
        }

        public float apexX() {
            return (footX + landX) / 2 + nx * rise;
        }

        public float apexY() {
            return (footY + landY) / 2 + ny * rise;
        }

        /** The curve as {@code steps + 1} points from the foot to the landing. */
        public List<float[]> points(final int steps) {
            final float ux = (landX - footX) / chord(), uy = (landY - footY) / chord(), half = chord() / 2;
            final float mx = (footX + landX) / 2, my = (footY + landY) / 2;
            final List<float[]> out = new ArrayList<>(steps + 1);
            for (int k = 0; k <= steps; k++) {
                final double t = Math.PI * k / steps;
                final float along = (float) -Math.cos(t) * half, up = (float) Math.sin(t) * rise;
                out.add(new float[] { mx + ux * along + nx * up, my + uy * along + ny * up });
            }
            return out;
        }
    }

    /**
     * The wire with its bumps: straight stretches (as points) and bumps, in order, and where the bumps are as arc
     * lengths {from, to} along the wire (so arrows can keep off them).
     */
    public record Hopped(List<List<float[]>> straights, List<Bump> bumps, List<float[]> spans) {

        /** Straight stretches and bumps alternate, starting and ending with a straight stretch (maybe one point). */
        public boolean plain() {
            return bumps.isEmpty();
        }
    }

    /**
     * How far a line must lift to clear the one it crosses: half of each stroke, plus a little air. Both the bump's
     * height and the clearance a hop's feet keep from the crossed line.
     */
    public static float radiusFor(final float ownWidth, final float otherWidth) {
        return Math.min(ownWidth / 2 + otherWidth / 2 + GAP, MAX_RADIUS);
    }

    /**
     * The wire with a bump over every crossing. A bump on one straight run is a half-circle when the wire crosses
     * square on and a flatter half-ellipse at 45 degrees, always rising the hop radius off the wire, toward the upper
     * side of the run (the right-hand side of a vertical one) so the same crossing always reads the same way. A bump
     * that spans a bend bulges round the outside of the corner it replaces.
     */
    public static Hopped build(final List<int[]> path, final List<Crossed> crossed, final float ownWidth) {
        final int count = path.size();
        final float[] xs = new float[count], ys = new float[count], arcs = new float[count];
        for (int i = 0; i < count; i++) {
            xs[i] = path.get(i)[0];
            ys[i] = path.get(i)[1];
            if (i > 0) arcs[i] = arcs[i - 1] + (float) Math.hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1]);
        }
        if (count < 2 || crossed.isEmpty() || arcs[count - 1] < MIN_CHORD) return plain(xs, ys);
        final float total = arcs[count - 1];

        final List<float[]> windows = new ArrayList<>(); // {from, to, height}
        final float[] dist = new float[count];
        for (final Crossed s : crossed) {
            final float vx = s.ex() - s.sx(), vy = s.ey() - s.sy(), length = (float) Math.hypot(vx, vy);
            if (length < 1) continue;
            final float ux = vx / length, uy = vy / length;
            // Signed distance of each vertex from the crossed line.
            for (int i = 0; i < count; i++) dist[i] = ux * (ys[i] - s.sy()) - uy * (xs[i] - s.sx());
            final float radius = radiusFor(ownWidth, s.width());
            for (int i = 0; i + 1 < count; i++) {
                final float here = dist[i], next = dist[i + 1];
                // Straight through the middle of a run.
                if (here > ON_LINE && next < -ON_LINE || here < -ON_LINE && next > ON_LINE) {
                    final float at = arcs[i] + here / (here - next) * (arcs[i + 1] - arcs[i]);
                    if (within(xs, ys, arcs, at, s, ux, uy, length))
                        windows.add(clearance(arcs, dist, at, i + 1, i, radius));
                    continue;
                }
                // Through a bend that sits on the line: arriving from one side, leaving on the other. Riding along
                // the line for a stretch is lane company, not a crossing.
                final int bend = i + 1;
                if (Math.abs(here) > ON_LINE && Math.abs(next) <= ON_LINE
                    && bend + 1 < count
                    && Math.abs(dist[bend + 1]) > ON_LINE
                    && Math.signum(dist[bend + 1]) != Math.signum(here)
                    && within(xs, ys, arcs, arcs[bend], s, ux, uy, length)) {
                    windows.add(clearance(arcs, dist, arcs[bend], bend + 1, bend - 1, radius));
                }
            }
        }
        if (windows.isEmpty()) return plain(xs, ys);

        windows.sort((a, b) -> Float.compare(a[0], b[0]));
        // Bumps closer together than a bump is tall run into one: two humps with a few pixels of straight between
        // them read as a wiggle, not two hops.
        final List<float[]> merged = new ArrayList<>();
        for (final float[] w : windows) {
            final float[] clamped = { Math.max(0.5f, w[0]), Math.min(total - 0.5f, w[1]), w[2] };
            final float[] previous = merged.isEmpty() ? null : merged.get(merged.size() - 1);
            if (previous != null && clamped[0] <= previous[1] + Math.min(previous[2], clamped[2])) {
                previous[1] = Math.max(previous[1], clamped[1]);
                previous[2] = Math.max(previous[2], clamped[2]);
            } else merged.add(clamped);
        }

        final List<List<float[]>> straights = new ArrayList<>();
        final List<Bump> bumps = new ArrayList<>();
        final List<float[]> spans = new ArrayList<>();
        List<float[]> straight = new ArrayList<>();
        straight.add(new float[] { xs[0], ys[0] });
        int vertex = 1;
        for (final float[] w : merged) {
            while (vertex < count && arcs[vertex] <= w[0]) {
                straight.add(new float[] { xs[vertex], ys[vertex] });
                vertex++;
            }
            final List<float[]> inside = new ArrayList<>();
            while (vertex < count && arcs[vertex] < w[1]) {
                inside.add(new float[] { xs[vertex], ys[vertex] });
                vertex++;
            }
            final float[] foot = pointAt(xs, ys, arcs, w[0]), landing = pointAt(xs, ys, arcs, w[1]);
            final Bump bump = bump(foot, landing, w[2], inside);
            if (bump == null) {
                // Too short to bump: the wire as it is.
                straight.addAll(inside);
                continue;
            }
            straight.add(foot);
            straights.add(straight);
            bumps.add(bump);
            spans.add(new float[] { w[0], w[1] });
            straight = new ArrayList<>();
            straight.add(landing);
        }
        while (vertex < count) {
            straight.add(new float[] { xs[vertex], ys[vertex] });
            vertex++;
        }
        straights.add(straight);
        return new Hopped(straights, bumps, spans);
    }

    private static Hopped plain(final float[] xs, final float[] ys) {
        final List<float[]> points = new ArrayList<>(xs.length);
        for (int i = 0; i < xs.length; i++) points.add(new float[] { xs[i], ys[i] });
        return new Hopped(List.of(points), List.of(), List.of());
    }

    /** Whether the point {@code at} along the wire is on the crossed segment, clear of its ends. */
    private static boolean within(final float[] xs, final float[] ys, final float[] arcs, final float at,
        final Crossed s, final float ux, final float uy, final float length) {
        final float[] p = pointAt(xs, ys, arcs, at);
        final float along = ux * (p[0] - s.sx()) + uy * (p[1] - s.sy());
        return along > OVERSHOOT && along < length - OVERSHOOT;
    }

    private static float[] pointAt(final float[] xs, final float[] ys, final float[] arcs, final float at) {
        for (int i = 1; i < xs.length; i++) {
            final float end = arcs[i];
            if (at <= end || i == xs.length - 1) {
                final float start = arcs[i - 1];
                final float t = end > start ? Math.min(Math.max((at - start) / (end - start), 0), 1) : 0;
                return new float[] { xs[i - 1] + (xs[i] - xs[i - 1]) * t, ys[i - 1] + (ys[i] - ys[i - 1]) * t };
            }
        }
        return new float[] { xs[xs.length - 1], ys[ys.length - 1] };
    }

    /**
     * The stretch around a crossing at arc length {@code at} where the wire is within {@code radius} of the crossed
     * line: walk back and forward from it until the wire is that far off the line. A square crossing reaches the
     * radius each way, a 45 degree one about 1.4 times it, and a crossing near a bend follows the wire round it. Never
     * more than twice the radius either way, so a wire that turns to run beside the line does not stretch a bump down
     * its length.
     */
    private static float[] clearance(final float[] arcs, final float[] dist, final float at, final int nextVertex,
        final int previousVertex, final float radius) {
        final float reach = radius * 2, total = arcs[arcs.length - 1];

        float to = Math.min(total, at + reach);
        float fromArc = at, fromDistance = 0;
        for (int i = nextVertex; i < arcs.length; i++) {
            final float arc = arcs[i], distance = dist[i];
            if (Math.abs(distance) >= radius && arc > fromArc) {
                to = Math.min(
                    to,
                    fromArc + (Math.signum(distance) * radius - fromDistance) / (distance - fromDistance)
                        * (arc - fromArc));
                break;
            }
            if (arc - at >= reach) break;
            fromArc = arc;
            fromDistance = distance;
        }

        float from = Math.max(0, at - reach);
        fromArc = at;
        fromDistance = 0;
        for (int i = previousVertex; i >= 0; i--) {
            final float arc = arcs[i], distance = dist[i];
            if (Math.abs(distance) >= radius && arc < fromArc) {
                from = Math.max(
                    from,
                    fromArc - (Math.signum(distance) * radius - fromDistance) / (distance - fromDistance)
                        * (fromArc - arc));
                break;
            }
            if (at - arc >= reach) break;
            fromArc = arc;
            fromDistance = distance;
        }
        return new float[] { from, to, radius };
    }

    /**
     * Half an ellipse on the chord from {@code foot} to {@code landing}, rising {@code height} off it toward the upper
     * side (the right-hand side of a vertical chord). When it replaces bends ({@code inside}), it bulges to their side
     * of the chord, the outside of the corner, and clears the corner by half a radius; bulging inward would fold the
     * wire back on itself. Null when the chord is too short to bother.
     */
    private static Bump bump(final float[] foot, final float[] landing, final float height,
        final List<float[]> inside) {
        final float dx = landing[0] - foot[0], dy = landing[1] - foot[1], chord = (float) Math.hypot(dx, dy);
        if (chord < MIN_CHORD) return null;
        final float ux = dx / chord, uy = dy / chord;
        // The upper normal of the chord (screen y runs down), or the right-hand one when it is vertical.
        float nx = -uy, ny = ux;
        if (ny > 1e-6f || Math.abs(ny) <= 1e-6f && nx < 0) {
            nx = -nx;
            ny = -ny;
        }
        float rise = height;
        if (!inside.isEmpty()) {
            float side = 0, deepest = 0;
            for (final float[] p : inside) {
                final float offset = ux * (p[1] - foot[1]) - uy * (p[0] - foot[0]);
                side += offset;
                deepest = Math.max(deepest, Math.abs(offset));
            }
            if (Math.abs(side) > 0.5f) {
                // (-uy, ux) is the side a positive offset lies on.
                nx = side > 0 ? -uy : uy;
                ny = side > 0 ? ux : -ux;
                rise = Math.max(height, deepest + height / 2);
            }
        }
        return new Bump(foot[0], foot[1], landing[0], landing[1], rise, nx, ny);
    }
}

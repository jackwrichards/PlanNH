package com.gtnhplanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.layout.WireRouter;
import com.gtnhplanner.layout.WireRouter.Box;
import com.gtnhplanner.layout.WireRouter.Wire;

/**
 * The wire router: straight where boxes face, eight directions only, clear of every box, on the boxes' edges, the same
 * answer every time; and routing again after a change touches only what the change did.
 */
class WireRouterTest {

    @Test
    void facingBoxesJoinWithOneStraightLine() {
        final List<Box> boxes = List.of(new Box(0, 0, 320, 200), new Box(500, 30, 320, 200));
        final Wire w = wire("a", 0, 1);
        final List<int[]> path = new WireRouter().route(boxes, List.of(w))
            .get(w.key());
        assertEquals(2, path.size(), () -> describe(path));
        assertEquals(320, path.getFirst()[0]);
        assertEquals(500, path.getLast()[0]);
        assertEquals(path.getFirst()[1], path.getLast()[1]);
    }

    @Test
    void aWireGoesRoundABoxInTheWayInTheEightDirections() {
        final List<Box> boxes = List
            .of(new Box(0, 0, 320, 200), new Box(900, 0, 320, 200), new Box(420, -60, 380, 320));
        final Wire w = wire("round", 0, 1);
        final Set<UUID> fellBack = new HashSet<>();
        final List<int[]> path = new WireRouter().route(boxes, List.of(w), fellBack, true)
            .get(w.key());
        assertTrue(fellBack.isEmpty());
        assertTrue(octilinear(path), () -> describe(path));
        assertTrue(clear(path, boxes), () -> "through a box: " + describe(path));
        assertTrue(onEdge(path.getFirst(), boxes.get(0)) && onEdge(path.getLast(), boxes.get(1)), () -> describe(path));
    }

    @Test
    void aLoopLeavesItsRightSideAndComesBackIntoItsLeft() {
        final List<Box> boxes = List.of(new Box(0, 0, 320, 200));
        final Wire w = new Wire(id("loop"), 0, 0, WireRouter.RIGHT, WireRouter.LEFT, 1);
        final List<int[]> path = new WireRouter().route(boxes, List.of(w))
            .get(w.key());
        assertEquals(320, path.getFirst()[0]);
        assertEquals(0, path.getLast()[0]);
        assertTrue(clear(path, boxes), () -> describe(path));
    }

    @Test
    void aBoxWalledInStillGetsItsWire() {
        // A drawer dropped on a card: walled in, routed through as lightly as it can, never left out.
        final List<Box> boxes = List.of(new Box(0, 0, 320, 300), new Box(80, 100, 136, 68), new Box(700, 0, 320, 200));
        final Wire w = wire("sealed", 1, 2);
        final Map<UUID, List<int[]>> routes = new WireRouter().route(boxes, List.of(w));
        assertNotNull(routes.get(w.key()));
        assertTrue(
            routes.get(w.key())
                .size() >= 2);
    }

    @Test
    void theSameBoardRoutesTheSameEveryTime() {
        final List<Box> boxes = grid();
        final List<Wire> wires = wiresOf(boxes.size());
        final Map<UUID, List<int[]>> a = new WireRouter().route(boxes, wires), b = new WireRouter().route(boxes, wires);
        for (final Wire w : wires) assertEquals(describe(a.get(w.key())), describe(b.get(w.key())));
    }

    @Test
    void movingABoxRoutesOnlyItsWiresAndThoseItCrosses() {
        final List<Box> boxes = grid();
        final List<Wire> wires = wiresOf(boxes.size());
        final WireRouter router = new WireRouter();
        final Map<UUID, List<int[]>> before = router.route(boxes, wires);
        // The last box down a little: its own wires route again, wires far from it keep theirs exactly.
        final List<Box> moved = new ArrayList<>(boxes);
        final Box last = boxes.getLast();
        moved.set(boxes.size() - 1, new Box(last.x(), last.y() + 40, last.w(), last.h()));
        final Map<UUID, List<int[]>> after = router.reroute(moved, wires, null, false);
        final Wire far = wires.getFirst();
        assertSame(before.get(far.key()), after.get(far.key()));
        assertTrue(router.statRouted < wires.size() / 2, () -> "routed " + router.statRouted);
        for (final Wire w : wires) {
            assertTrue(clear(after.get(w.key()), moved), () -> describe(after.get(w.key())));
            if (w.to() == boxes.size() - 1) assertTrue(
                onEdge(
                    after.get(w.key())
                        .getLast(),
                    moved.getLast()),
                () -> describe(after.get(w.key())));
        }
    }

    @Test
    void aNewWireRoutesAloneAndAGoneBoxTakesItsWiresWithIt() {
        final List<Box> boxes = grid();
        final List<Wire> wires = wiresOf(boxes.size());
        final List<UUID> keys = new ArrayList<>();
        for (int i = 0; i < boxes.size(); i++) keys.add(id("box" + i));
        final WireRouter router = new WireRouter();
        final Map<UUID, List<int[]>> before = router.route(boxes, keys, wires, null, true);

        final List<Wire> more = new ArrayList<>(wires);
        more.add(wire("new", 0, boxes.size() - 1));
        final Map<UUID, List<int[]>> added = router.reroute(boxes, keys, more, null, true);
        assertEquals(1, router.statRouted);
        for (final Wire w : wires) assertSame(before.get(w.key()), added.get(w.key()));
        assertNotNull(
            added.get(
                more.getLast()
                    .key()));

        // Box 4 goes, with every wire at it; the rest renumber, and keep their routes.
        final List<Box> fewer = new ArrayList<>(boxes);
        final List<UUID> fewerKeys = new ArrayList<>(keys);
        fewer.remove(4);
        fewerKeys.remove(4);
        final List<Wire> left = new ArrayList<>();
        for (final Wire w : more) {
            if (w.from() == 4 || w.to() == 4) continue;
            left.add(new Wire(w.key(), w.from() > 4 ? w.from() - 1 : w.from(), w.to() > 4 ? w.to() - 1 : w.to(), 1));
        }
        final Map<UUID, List<int[]>> removed = router.reroute(fewer, fewerKeys, left, null, true);
        assertEquals(left.size(), removed.size());
        for (final Wire w : left) assertTrue(clear(removed.get(w.key()), fewer), () -> describe(removed.get(w.key())));
        assertFalse(router.statRouted == left.size(), "everything routed again");
    }

    // region Helpers

    /** Three rows of four cards with room between. */
    private static List<Box> grid() {
        final List<Box> out = new ArrayList<>();
        for (int r = 0; r < 3; r++) for (int c = 0; c < 4; c++) out.add(new Box(c * 520, r * 380, 320, 200));
        return out;
    }

    /** Each card to the next in its row, and down each column. */
    private static List<Wire> wiresOf(final int n) {
        final List<Wire> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (i % 4 != 3) out.add(wire("r" + i, i, i + 1));
            if (i + 4 < n) out.add(wire("c" + i, i, i + 4));
        }
        return out;
    }

    private static Wire wire(final String name, final int from, final int to) {
        return new Wire(id(name), from, to, 1);
    }

    private static UUID id(final String name) {
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean octilinear(final List<int[]> path) {
        for (int i = 1; i < path.size(); i++) {
            final int dx = path.get(i)[0] - path.get(i - 1)[0], dy = path.get(i)[1] - path.get(i - 1)[1];
            if (dx != 0 && dy != 0 && Math.abs(dx) != Math.abs(dy)) return false;
        }
        return true;
    }

    /** Whether no point along the path lies strictly inside a box. */
    private static boolean clear(final List<int[]> path, final List<Box> boxes) {
        for (int i = 1; i < path.size(); i++) {
            final int[] a = path.get(i - 1), b = path.get(i);
            final int steps = Math.max(Math.abs(b[0] - a[0]), Math.abs(b[1] - a[1]));
            for (int k = 0; k <= steps; k++) {
                final double x = a[0] + (b[0] - a[0]) * (double) k / Math.max(1, steps),
                    y = a[1] + (b[1] - a[1]) * (double) k / Math.max(1, steps);
                for (final Box box : boxes)
                    if (x > box.x() && x < box.x() + box.w() && y > box.y() && y < box.y() + box.h()) return false;
            }
        }
        return true;
    }

    private static boolean onEdge(final int[] p, final Box b) {
        final boolean inX = p[0] >= b.x() && p[0] <= b.x() + b.w(), inY = p[1] >= b.y() && p[1] <= b.y() + b.h();
        return inX && (p[1] == b.y() || p[1] == b.y() + b.h()) || inY && (p[0] == b.x() || p[0] == b.x() + b.w());
    }

    private static String describe(final List<int[]> path) {
        final StringBuilder s = new StringBuilder();
        for (final int[] p : path) s.append('(')
            .append(p[0])
            .append(',')
            .append(p[1])
            .append(") ");
        return s.toString();
    }

    // endregion
}

package com.sbancuz.plannh;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.sbancuz.plannh.layout.ArrowRouter;
import com.sbancuz.plannh.layout.ArrowRouter.Rect;
import com.sbancuz.plannh.layout.ArrowRouter.Request;
import com.sbancuz.plannh.layout.PortGeometry;

/**
 * The wire router's invariants, ported from Factory Flow's src/components/flow/grid-edge-router.test.ts:
 * right angles only, straight where the ports face, clear of cards, the same answer every time,
 * and a reported fallback rather than nothing when a target is sealed off. The cheapest turn counts
 * asserted here also guard the A* heuristic: it counts the turns a route cannot avoid, and one that
 * overcounted would let A* settle for a route with more of them.
 */
class ArrowRouterTest {

    private static final int W = 160;
    private static final int H = 120;
    /** WireLayer's router: 6-unit cells, 12 units of clearance around a card. */
    private static final int CELL = 6;
    private static final int MARGIN = 12;

    @Test
    void facingPortsRouteStraight() {
        final int y = PortGeometry.portY(0);
        final Request request = request("e", W, y, 400, y);
        final Set<UUID> fellBack = new HashSet<>();

        final List<int[]> route = router()
            .route(List.of(card(0, 0), card(400, 0)), List.of(), List.of(request), fellBack)
            .get(request.key());

        assertArrayEquals(new int[] { W, y }, route.getFirst());
        assertArrayEquals(new int[] { 400, y }, route.getLast());
        for (final int[] point : route) {
            assertEquals(y, point[1], "no vertical wandering");
        }
        assertTrue(fellBack.isEmpty());
    }

    @Test
    void everySegmentIsStraightOrFortyFive() {
        // A forward wire down to a lower card, one up to a higher card, and one running back from
        // the lower card into the first card's second input, all routed together.
        final List<Rect> cards = List.of(card(0, 0), card(400, 200), card(400, -200));
        final List<Request> requests = List.of(
            request("down", W, PortGeometry.portY(0), 400, 200 + PortGeometry.portY(0)),
            request("up", W, PortGeometry.portY(1), 400, -200 + PortGeometry.portY(0)),
            request("back", 400 + W, 200 + PortGeometry.portY(0), 0, PortGeometry.portY(1)));

        final Map<UUID, List<int[]>> routes = router().route(cards, requests);

        for (final Request request : requests) {
            final List<int[]> route = routes.get(request.key());
            assertNotNull(route);
            assertTrue(octilinear(route), () -> "off the eight directions: " + describe(route));
        }
    }

    @Test
    void anOffsetPairTurnsHalfwayRoundInGentleBends() {
        // Leaving rightward and arriving rightward on another row: it turns down and back, 180 degrees in all and no
        // more, never sharper than a right angle, and the far ends run level out of and into the ports.
        final Request request = request("z", W, PortGeometry.portY(0), 400, 200 + PortGeometry.portY(0));

        final List<int[]> route = router().route(List.of(card(0, 0), card(400, 200)), List.of(request))
            .get(request.key());

        assertTrue(octilinear(route), () -> describe(route));
        assertEquals(180, totalTurning(route), () -> "turns: " + describe(route));
        assertTrue(sharpestTurn(route) <= 90, () -> "sharpest: " + describe(route));
        assertEquals(route.get(0)[1], route.get(1)[1], () -> "leaves level: " + describe(route));
        assertEquals(route.get(route.size() - 2)[1], route.getLast()[1], () -> "lands level: " + describe(route));
    }

    @Test
    void routesKeepTheirMarginAroundABlockingCard() {
        // The straight line runs through the blocker, so the wire goes round it and keeps the
        // router's clearance from it, and from its own two cards except where it docks.
        final Rect a = card(0, 0);
        final Rect blocker = new Rect(300, -20, W, H + 40);
        final Rect b = card(700, 0);
        final int y = PortGeometry.portY(0);
        final Request request = request("round", W, y, 700, y);
        final Set<UUID> fellBack = new HashSet<>();

        final List<int[]> route = router().route(List.of(a, blocker, b), List.of(), List.of(request), fellBack)
            .get(request.key());

        assertTrue(route.size() > 2, () -> "went round: " + describe(route));
        assertTrue(octilinear(route), () -> describe(route));
        assertTrue(fellBack.isEmpty(), "a real route, not the fallback");
        assertFalse(entersMargin(route, blocker, 0), () -> "into the blocker's margin: " + describe(route));
        assertFalse(entersMargin(route, a, 1), () -> "into its own source card: " + describe(route));
        assertFalse(entersMargin(route, b, 1), () -> "into its own target card: " + describe(route));
    }

    @Test
    void routingTwiceGivesTheSameRoutes() {
        final List<Rect> cards = List.of(card(0, 0), card(700, 300), card(200, 400));
        final List<Request> requests = List.of(
            request("e1", W, PortGeometry.portY(0), 700, 300 + PortGeometry.portY(0)),
            request("e2", W, PortGeometry.portY(1), 200, 400 + PortGeometry.portY(0)));
        final ArrowRouter reused = router();

        final Map<UUID, List<int[]>> first = reused.route(cards, requests);
        final Map<UUID, List<int[]>> second = reused.route(cards, requests);
        final Map<UUID, List<int[]>> fresh = router().route(cards, requests);

        for (final Request request : requests) {
            final String expected = describe(first.get(request.key()));
            assertEquals(expected, describe(second.get(request.key())), "the same router twice");
            assertEquals(expected, describe(fresh.get(request.key())), "a new router");
        }
    }

    @Test
    void aBoxedInTargetFallsBackAndSaysSo() {
        // The target port sits inside four walls that meet at the corners: no route exists, so the
        // router must still hand back a drawable polyline and report the request as a fallback.
        final List<Rect> cards = List.of(
            card(0, 0),
            new Rect(1880, 1880, 260, 20),
            new Rect(1880, 2120, 260, 20),
            new Rect(1880, 1880, 20, 260),
            new Rect(2120, 1880, 20, 260));
        final Request request = request("sealed", W, PortGeometry.portY(0), 2000, 2000);
        final Set<UUID> fellBack = new HashSet<>();

        final List<int[]> route = router().route(cards, List.of(), List.of(request), fellBack)
            .get(request.key());

        assertNotNull(route);
        assertTrue(route.size() >= 2);
        assertTrue(orthogonal(route), () -> describe(route));
        assertTrue(fellBack.contains(request.key()), "a sealed target is reported");
    }

    @Test
    void twoWiresBetweenTheSameCardsDoNotCross() {
        // Both wires drop 200 to the lower card, port 0 to port 0 and port 1 to port 1. Nested
        // corners route them without a crossing whichever is routed first, and the canvas routes
        // edges in id order, so either can be.
        final List<Rect> cards = List.of(card(0, 0), card(400, 200));
        final Request upper = request("upper", W, PortGeometry.portY(0), 400, 200 + PortGeometry.portY(0));
        final Request lower = request("lower", W, PortGeometry.portY(1), 400, 200 + PortGeometry.portY(1));

        for (final List<Request> order : List.of(List.of(upper, lower), List.of(lower, upper))) {
            final String first = order.getFirst() == upper ? "upper first" : "lower first";
            final Map<UUID, List<int[]>> routes = router().route(cards, order);
            final List<int[]> a = routes.get(upper.key());
            final List<int[]> b = routes.get(lower.key());
            assertEquals(0, crossings(a, b), () -> first + ": " + describe(a) + " / " + describe(b));
        }
    }

    @Test
    void aCardWiredToItselfLoopsRoundAtRightAngles() {
        // From the card's output back into its own input: the wire has to go round the card, and
        // round is four turns - out, over, back down and in - and no more.
        final Rect machine = card(0, 0);
        final int y = PortGeometry.portY(0);
        final Request request = request("loop", W, y, 0, y);
        final Set<UUID> fellBack = new HashSet<>();

        final List<int[]> route = router().route(List.of(machine), List.of(), List.of(request), fellBack)
            .get(request.key());

        assertArrayEquals(new int[] { W, y }, route.getFirst());
        assertArrayEquals(new int[] { 0, y }, route.getLast());
        assertTrue(orthogonal(route), () -> describe(route));
        assertEquals(6, route.size(), () -> "four corners: " + describe(route));
        assertFalse(entersMargin(route, machine, 1), () -> "through its own card: " + describe(route));
        assertTrue(fellBack.isEmpty(), "a real route, not the fallback");
    }

    // ── helpers ──

    private static ArrowRouter router() {
        return new ArrowRouter(CELL, MARGIN);
    }

    private static Rect card(final int x, final int y) {
        return new Rect(x, y, W, H);
    }

    private static Request request(final String name, final int sx, final int sy, final int dx, final int dy) {
        return new Request(UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)), sx, sy, dx, dy);
    }

    private static boolean orthogonal(final List<int[]> route) {
        for (int i = 1; i < route.size(); i++) {
            final int[] a = route.get(i - 1);
            final int[] b = route.get(i);
            if (a[0] != b[0] && a[1] != b[1]) return false;
        }
        return true;
    }

    /** Every segment runs along one of the eight directions: level, upright, or exactly 45 degrees. */
    private static boolean octilinear(final List<int[]> route) {
        for (int i = 1; i < route.size(); i++) {
            final int dx = Math.abs(route.get(i)[0] - route.get(i - 1)[0]);
            final int dy = Math.abs(route.get(i)[1] - route.get(i - 1)[1]);
            if (dx != 0 && dy != 0 && dx != dy) return false;
        }
        return true;
    }

    /** The heading change at each corner, in degrees (0 to 180). */
    private static List<Integer> turns(final List<int[]> route) {
        final List<Integer> out = new ArrayList<>();
        for (int i = 1; i + 1 < route.size(); i++) {
            final int[] a = route.get(i - 1), b = route.get(i), c = route.get(i + 1);
            final double h1 = Math.atan2(b[1] - a[1], b[0] - a[0]), h2 = Math.atan2(c[1] - b[1], c[0] - b[0]);
            double d = Math.abs(Math.toDegrees(h2 - h1)) % 360;
            if (d > 180) d = 360 - d;
            out.add((int) Math.round(d));
        }
        return out;
    }

    private static int totalTurning(final List<int[]> route) {
        return turns(route).stream()
            .mapToInt(Integer::intValue)
            .sum();
    }

    private static int sharpestTurn(final List<int[]> route) {
        return turns(route).stream()
            .mapToInt(Integer::intValue)
            .max()
            .orElse(0);
    }

    /**
     * Whether a segment, other than the {@code skipEnds} at either end (a wire's dock legitimately
     * crosses its own card's margin), runs through the card grown by the router's margin. Touching
     * the grown edge is allowed: the rectangle is open.
     */
    private static boolean entersMargin(final List<int[]> route, final Rect card, final int skipEnds) {
        final int left = card.x() - MARGIN, right = card.x() + card.w() + MARGIN;
        final int top = card.y() - MARGIN, bottom = card.y() + card.h() + MARGIN;
        for (int i = 1 + skipEnds; i < route.size() - skipEnds; i++) {
            final int[] a = route.get(i - 1);
            final int[] b = route.get(i);
            if (a[1] == b[1]) {
                if (a[1] > top && a[1] < bottom
                    && Math.max(Math.min(a[0], b[0]), left) < Math.min(Math.max(a[0], b[0]), right)) return true;
            } else if (a[0] > left && a[0] < right
                && Math.max(Math.min(a[1], b[1]), top) < Math.min(Math.max(a[1], b[1]), bottom)) {
                    return true;
                }
        }
        return false;
    }

    /** Proper crossings between two orthogonal routes; shared ends and touches do not count. */
    private static int crossings(final List<int[]> a, final List<int[]> b) {
        int count = 0;
        for (int i = 1; i < a.size(); i++) {
            for (int j = 1; j < b.size(); j++) {
                if (crosses(a.get(i - 1), a.get(i), b.get(j - 1), b.get(j))) count++;
            }
        }
        return count;
    }

    private static boolean crosses(final int[] p, final int[] q, final int[] r, final int[] s) {
        final boolean pqFlat = p[1] == q[1];
        final boolean rsFlat = r[1] == s[1];
        if (pqFlat == rsFlat) return false; // parallel runs never cross properly
        final int[] h0 = pqFlat ? p : r, h1 = pqFlat ? q : s;
        final int[] v0 = pqFlat ? r : p, v1 = pqFlat ? s : q;
        return v0[0] > Math.min(h0[0], h1[0]) && v0[0] < Math.max(h0[0], h1[0])
            && h0[1] > Math.min(v0[1], v1[1])
            && h0[1] < Math.max(v0[1], v1[1]);
    }

    private static String describe(final List<int[]> route) {
        final StringBuilder out = new StringBuilder();
        for (final int[] p : route) {
            out.append('(')
                .append(p[0])
                .append(',')
                .append(p[1])
                .append(')');
        }
        return out.toString();
    }

    // Docked wires, as Factory Flow routes them: either end anywhere on its box's edge.

    private static List<int[]> docked(final Rect from, final Rect to, final List<Rect> obstacles) {
        final UUID key = UUID.randomUUID();
        return new ArrowRouter(10, 12).route(
            obstacles,
            List.of(
                ArrowRouter.Request.docked(key, ArrowRouter.perimeterDocks(from), ArrowRouter.perimeterDocks(to), 4)))
            .get(key);
    }

    private static boolean onEdge(final int[] p, final Rect r) {
        final boolean inX = p[0] >= r.x() && p[0] <= r.x() + r.w(), inY = p[1] >= r.y() && p[1] <= r.y() + r.h();
        return inX && (p[1] == r.y() || p[1] == r.y() + r.h()) || inY && (p[0] == r.x() || p[0] == r.x() + r.w());
    }

    @Test
    void aDockedWireBetweenSideBySideBoxesJoinsTheirFacingSides() {
        final Rect a = new Rect(0, 0, 200, 120), b = new Rect(400, 0, 200, 120);
        final List<int[]> path = docked(a, b, List.of(a, b));
        assertEquals(200, path.getFirst()[0], "leaves the right side");
        assertEquals(400, path.getLast()[0], "lands on the left side");
        assertEquals(2, path.size(), "straight across, middle to middle");
        assertEquals(60, path.getFirst()[1]);
    }

    @Test
    void aDockedWireToABoxBelowLeavesTheBottomAndLandsOnTheTop() {
        final Rect a = new Rect(0, 0, 200, 120), b = new Rect(0, 400, 200, 120);
        final List<int[]> path = docked(a, b, List.of(a, b));
        assertEquals(120, path.getFirst()[1], "leaves the bottom");
        assertEquals(400, path.getLast()[1], "lands on the top");
        assertEquals(2, path.size(), "straight down, middle to middle");
    }

    @Test
    void aDockedWireStartsAndEndsOnItsBoxesAndNeverCrossesOne() {
        final Rect a = new Rect(0, 0, 200, 120), b = new Rect(500, 300, 200, 120), wall = new Rect(260, 100, 60, 260);
        final List<int[]> path = docked(a, b, List.of(a, b, wall));
        assertTrue(onEdge(path.getFirst(), a), "starts on the first box");
        assertTrue(onEdge(path.getLast(), b), "ends on the second");
        for (int i = 1; i < path.size(); i++) {
            final int[] p = path.get(i - 1), q = path.get(i);
            for (int k = 1; k < 20; k++) {
                final double x = p[0] + (q[0] - p[0]) * k / 20.0, y = p[1] + (q[1] - p[1]) * k / 20.0;
                for (final Rect r : List.of(a, b, wall)) {
                    assertFalse(
                        x > r.x() + 1 && x < r.x() + r.w() - 1 && y > r.y() + 1 && y < r.y() + r.h() - 1,
                        "runs through a box at " + x + "," + y);
                }
            }
        }
    }
}

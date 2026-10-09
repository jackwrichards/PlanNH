package com.gtnhplanner;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.data.flowchart.Edge;
import com.gtnhplanner.layout.AutoLayout;
import com.gtnhplanner.layout.AutoLayout.LayoutNode;

/**
 * The arrange button's invariants, ported from Factory Flow's src/lib/board-arrange.test.ts: wired
 * cards read left to right, the same board always arranges the same way, separate islands stay
 * apart, and no two cards overlap, loops and malformed links included.
 */
class AutoLayoutTest {

    private static final int W = 160;
    private static final int H = 120;
    /** Clearance kept around a card, as the old wire router kept it. */
    private static final int ROUTER_MARGIN = 12;

    private record N(UUID id, String machineName, int worldWidth, int worldHeight, int inputCount, int outputCount)
        implements LayoutNode {}

    @Test
    void aChainLaysOutLeftToRight() {
        final List<N> nodes = List.of(card("c"), card("a"), card("b")); // listed out of order on purpose
        final List<Edge> links = List.of(link("a", 0, "b", 0), link("b", 0, "c", 0));

        final Map<UUID, int[]> p = AutoLayout.layout(nodes, links);

        assertTrue(x(p, "a") + W < x(p, "b"), "a before b");
        assertTrue(x(p, "b") + W < x(p, "c"), "b before c");
        assertNoOverlaps(nodes, p);
    }

    @Test
    void theSameBoardAlwaysArrangesTheSameWay() {
        // A diamond of same-named machines, so the only thing telling b and c apart is their
        // wiring. Layered layout reads nodes and edges in model order, so a shuffled input is the
        // case that would move them if the canonical order did not hold.
        final List<N> nodes = List.of(
            new N(id("a"), "machine", W, H, 0, 1),
            new N(id("b"), "machine", W, H, 1, 1),
            new N(id("c"), "machine", W, H, 1, 1),
            new N(id("d"), "machine", W, H, 2, 0));
        final List<Edge> links = List
            .of(link("a", 0, "b", 0), link("a", 0, "c", 0), link("b", 0, "d", 0), link("c", 0, "d", 1));

        final Map<UUID, int[]> first = AutoLayout.layout(nodes, links);
        final Map<UUID, int[]> again = AutoLayout.layout(nodes, links);
        final List<N> shuffledNodes = new ArrayList<>(nodes);
        final List<Edge> shuffledLinks = new ArrayList<>(links);
        Collections.shuffle(shuffledNodes, new Random(7));
        Collections.reverse(shuffledLinks);
        final Map<UUID, int[]> shuffled = AutoLayout.layout(shuffledNodes, shuffledLinks);

        assertEquals(first.keySet(), again.keySet());
        assertEquals(first.keySet(), shuffled.keySet());
        for (final UUID id : first.keySet()) {
            assertArrayEquals(first.get(id), again.get(id), "the same input twice");
            assertArrayEquals(first.get(id), shuffled.get(id), "the same board in another order");
        }
        assertNoOverlaps(nodes, first);
    }

    @Test
    void separateIslandsNeverInterleave() {
        // Apart on some axis, side by side or stacked, either way round, never one inside the other.
        final List<N> nodes = islands();

        final Map<UUID, int[]> p = AutoLayout.layout(nodes, islandLinks());

        assertTrue(islandGap(p) > 0, "islands " + islandGap(p) + " apart");
        assertNoOverlaps(nodes, p);
    }

    @Test
    void separateIslandsStandAClearGapApart() {
        // A clear gap is at least the router's margin on both sides, so a wire passing between keeps
        // its clearance from both islands. It is also the least that reads as two islands: cards in
        // one column of an island already stand 25 apart.
        final Map<UUID, int[]> p = AutoLayout.layout(islands(), islandLinks());

        assertTrue(islandGap(p) >= 2 * ROUTER_MARGIN, "islands " + islandGap(p) + " apart");
    }

    @Test
    void aFanOutStacksWithoutOverlapsRightOfItsHub() {
        final List<N> nodes = new ArrayList<>();
        nodes.add(new N(id("hub"), "hub", W, H, 0, 1));
        final List<Edge> links = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            nodes.add(new N(id("c" + i), "consumer", W, H + i * 20, 1, 0)); // uneven heights
            links.add(link("hub", 0, "c" + i, 0));
        }

        final Map<UUID, int[]> p = AutoLayout.layout(nodes, links);

        assertNoOverlaps(nodes, p);
        for (int i = 1; i <= 5; i++) {
            assertTrue(x(p, "hub") + W < x(p, "c" + i), "c" + i + " right of the hub");
        }
    }

    @Test
    void aRecycleLoopLaysOutWithoutOverlaps() {
        // A loop has no left-to-right order to find, so the layout breaks it at one wire: three
        // columns, two wires reading forward and one running back.
        final List<N> nodes = List.of(card("a"), card("b"), card("c"));
        final List<Edge> links = List.of(link("a", 0, "b", 0), link("b", 0, "c", 0), link("c", 0, "a", 0));

        final Map<UUID, int[]> p = assertTimeoutPreemptively(
            Duration.ofSeconds(10),
            () -> AutoLayout.layout(nodes, links));

        assertNoOverlaps(nodes, p);
        assertEquals(
            3,
            p.values()
                .stream()
                .mapToInt(xy -> xy[0])
                .distinct()
                .count(),
            "three columns");
        int forward = 0;
        for (final Edge link : links) {
            if (p.get(link.sourceNodeId)[0] < p.get(link.targetNodeId)[0]) forward++;
        }
        assertEquals(2, forward, "one wire runs back");
    }

    @Test
    void anEmptyBoardAndStrayLinksDoNotThrow() {
        assertTrue(
            AutoLayout.layout(List.of(), List.of(link("x", 0, "y", 0)))
                .isEmpty(),
            "nothing to place");

        // A link to a card that is gone, one from a port the card does not have, and a card wired
        // to itself, which the planner allows.
        final List<N> nodes = List.of(card("a"), card("b"));
        final List<Edge> links = List
            .of(link("a", 0, "ghost", 0), link("ghost", 0, "b", 0), link("a", 5, "b", 0), link("b", 0, "b", 0));

        final Map<UUID, int[]> p = AutoLayout.layout(nodes, links);

        assertEquals(2, p.size());
        assertNoOverlaps(nodes, p);
    }

    // ── helpers ──

    private static UUID id(final String name) {
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
    }

    /** A plain card with one input and one output, named after its id. */
    private static N card(final String name) {
        return new N(id(name), name, W, H, 1, 1);
    }

    private static Edge link(final String from, final int out, final String to, final int in) {
        return new Edge(id(from + ">" + to + ":" + out + ":" + in), id(from), id(to), out, in);
    }

    private static int x(final Map<UUID, int[]> p, final String name) {
        return p.get(id(name))[0];
    }

    /** Two islands: a chain a1 > a2 > a3 and a pair b1 > b2, with nothing wired between them. */
    private static List<N> islands() {
        return List.of(card("a1"), card("a2"), card("a3"), card("b1"), card("b2"));
    }

    private static List<Edge> islandLinks() {
        return List.of(link("a1", 0, "a2", 0), link("a2", 0, "a3", 0), link("b1", 0, "b2", 0));
    }

    /** The clearance between the two islands' bounding boxes on whichever axis separates them. */
    private static int islandGap(final Map<UUID, int[]> p) {
        final int[] a = box(p, "a1", "a2", "a3");
        final int[] b = box(p, "b1", "b2");
        return Math.max(Math.max(b[0] - a[2], a[0] - b[2]), Math.max(b[1] - a[3], a[1] - b[3]));
    }

    /** {left, top, right, bottom} around the named cards. */
    private static int[] box(final Map<UUID, int[]> p, final String... names) {
        final int[] box = { Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE };
        for (final String name : names) {
            final int[] xy = p.get(id(name));
            box[0] = Math.min(box[0], xy[0]);
            box[1] = Math.min(box[1], xy[1]);
            box[2] = Math.max(box[2], xy[0] + W);
            box[3] = Math.max(box[3], xy[1] + H);
        }
        return box;
    }

    private static void assertNoOverlaps(final List<N> nodes, final Map<UUID, int[]> p) {
        for (int i = 0; i < nodes.size(); i++) {
            for (int j = i + 1; j < nodes.size(); j++) {
                final N a = nodes.get(i);
                final N b = nodes.get(j);
                final int[] pa = p.get(a.id());
                final int[] pb = p.get(b.id());
                final boolean overlap = pa[0] < pb[0] + b.worldWidth() && pa[0] + a.worldWidth() > pb[0]
                    && pa[1] < pb[1] + b.worldHeight()
                    && pa[1] + a.worldHeight() > pb[1];
                assertFalse(overlap, a.machineName() + " overlaps " + b.machineName());
            }
        }
    }
}

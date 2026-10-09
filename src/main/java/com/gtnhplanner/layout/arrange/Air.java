package com.gtnhplanner.layout.arrange;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToDoubleFunction;

/**
 * Air between strangers: the readability term the arrange adds on top of the router's points, and how islands emerge
 * without any island rule. As Factory Flow's board-arrange-air.ts.
 *
 * <p>
 * Strangers are cards three or more hops apart in the wire graph, or not connected. Each card pays {@code perPixel}
 * per px its nearest stranger stands closer than six cells; per card rather than per pair, so the dial means the same
 * on small and large boards. Wired cards attract through the wire's length; cards two hops apart are neutral. A dense
 * cluster hanging off the main body by one wire is pushed out until the bridge's extra length balances the air; a lone
 * card with one wire stays where its wire wants it. A drawer serving exactly one machine stands in for that machine in
 * the hop count, so two drawers on neighbouring machines are not strangers.
 */
public final class Air {

    /** Strangers owe each other this much air, in cells. */
    public static final int ISLAND_AIR_CELLS = 6;
    /** Cards this many hops apart, or further, are strangers. */
    public static final int ISLAND_STRANGER_HOPS = 3;

    /** From this many cards on, only strangers in neighbouring squares are compared. */
    private static final int CULL_FROM = 40;

    private Air() {}

    /** A card's square and index as one sortable number: the square's column, then its row, then the card. */
    private static long filing(final int cx, final int cy, final int index) {
        return (long) (cx + (1 << 23)) << 40 | (long) (cy + (1 << 23)) << 16 | index;
    }

    /** The gap between cards {@code i} and {@code k}: the nearest each has seen, if closer. */
    private static void closer(final int i, final int k, final double[] xs, final double[] ys, final double[] widths,
        final double[] heights, final double[] nearest) {
        final double gapX = Math.max(Math.max(xs[k] - (xs[i] + widths[i]), xs[i] - (xs[k] + widths[k])), 0);
        final double gapY = Math.max(Math.max(ys[k] - (ys[i] + heights[i]), ys[i] - (ys[k] + heights[k])), 0);
        final double gap = Math.max(gapX, gapY);
        if (gap < nearest[i]) nearest[i] = gap;
        if (gap < nearest[k]) nearest[k] = gap;
    }

    /**
     * The air term for a board: given every card's top-left, the price owed by every card whose nearest stranger
     * stands too close. Cards absent from the map (an island scored on its own) are skipped; the term depends only on
     * relative positions.
     */
    public static ToDoubleFunction<Map<String, Point>> term(final List<ArrangeCard> cards,
        final List<ArrangeWire> wires, final double perPixel) {
        if (perPixel <= 0 || cards.size() < 2) return positions -> 0;
        final int n = cards.size();
        final Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < n; i++) index.put(
            cards.get(i)
                .id(),
            i);
        final List<List<Integer>> partners = new ArrayList<>(n);
        for (int i = 0; i < n; i++) partners.add(new ArrayList<>());
        for (final ArrangeWire wire : wires) {
            final Integer a = index.get(wire.source()), b = index.get(wire.target());
            if (a == null || b == null || a.equals(b)) continue;
            partners.get(a)
                .add(b);
            partners.get(b)
                .add(a);
        }
        // A drawer with one partner is that partner for distance purposes.
        final int[] standIn = new int[n];
        for (int i = 0; i < n; i++) {
            standIn[i] = i;
            if (!cards.get(i)
                .storage()) continue;
            final Set<Integer> unique = new LinkedHashSet<>(partners.get(i));
            if (unique.size() == 1) standIn[i] = unique.iterator()
                .next();
        }
        final List<int[]> strangers = new ArrayList<>();
        final int[] distance = new int[n];
        final int[] queue = new int[n];
        for (int i = 0; i < n; i++) {
            Arrays.fill(distance, -1);
            int head = 0, tail = 0;
            queue[tail++] = standIn[i];
            distance[standIn[i]] = 0;
            while (head < tail) {
                final int here = queue[head++];
                for (final int next : partners.get(here)) {
                    final int who = standIn[next];
                    if (distance[who] < 0) {
                        distance[who] = distance[here] + 1;
                        queue[tail++] = who;
                    }
                }
            }
            for (int k = i + 1; k < n; k++) {
                final int d = distance[standIn[k]];
                if (standIn[i] == standIn[k]) continue;
                if (d < 0 || d >= ISLAND_STRANGER_HOPS) strangers.add(new int[] { i, k });
            }
        }
        final double air = ISLAND_AIR_CELLS * Grid.CELL;
        // The pairs as two arrays and the cards' ids and sizes by index, so a call looks each card up once (not once
        // per pair it is in); its scratch is its own, so threads may share the term.
        final int pairs = strangers.size();
        final int[] first = new int[pairs], second = new int[pairs];
        for (int s = 0; s < pairs; s++) {
            first[s] = strangers.get(s)[0];
            second[s] = strangers.get(s)[1];
        }
        final String[] ids = new String[n];
        final double[] widths = new double[n], heights = new double[n];
        double span = 0;
        for (int i = 0; i < n; i++) {
            final ArrangeCard card = cards.get(i);
            ids[i] = card.id();
            widths[i] = card.width();
            heights[i] = card.height();
            span = Math.max(span, Math.max(card.width(), card.height()));
        }
        // On a big board only strangers near each other are compared: cards are filed in squares this wide, and two
        // whose squares are not neighbours stand further apart than the air, so they owe nothing either way and the
        // sum comes out the same.
        final double square = span + air;
        final BitSet stranger = new BitSet(n * n);
        for (int s = 0; s < pairs; s++) stranger.set(first[s] * n + second[s]);
        return positions -> {
            final double[] xs = new double[n], ys = new double[n], nearest = new double[n];
            final boolean[] here = new boolean[n];
            for (int i = 0; i < n; i++) {
                final Point at = positions.get(ids[i]);
                if (at == null) continue;
                here[i] = true;
                xs[i] = at.x();
                ys[i] = at.y();
            }
            // Each card pays for its nearest stranger only, so the term means the same on nine cards as on ninety.
            Arrays.fill(nearest, Double.POSITIVE_INFINITY);
            if (n < CULL_FROM || n > 0xFFFF) {
                for (int s = 0; s < pairs; s++) {
                    final int i = first[s], k = second[s];
                    if (here[i] && here[k]) closer(i, k, xs, ys, widths, heights, nearest);
                }
            } else {
                // Every card's square and index packed into one sorted array: a column of three squares is then one
                // run of it, found by a binary search.
                final int[] cx = new int[n], cy = new int[n];
                final long[] filed = new long[n];
                int count = 0;
                for (int i = 0; i < n; i++) {
                    if (!here[i]) continue;
                    cx[i] = (int) Math.floor(xs[i] / square);
                    cy[i] = (int) Math.floor(ys[i] / square);
                    filed[count++] = filing(cx[i], cy[i], i);
                }
                Arrays.sort(filed, 0, count);
                for (int i = 0; i < n; i++) {
                    if (!here[i]) continue;
                    for (int dx = -1; dx <= 1; dx++) {
                        final long last = filing(cx[i] + dx, cy[i] + 1, 0xFFFF);
                        int j = Arrays.binarySearch(filed, 0, count, filing(cx[i] + dx, cy[i] - 1, 0));
                        if (j < 0) j = -j - 1;
                        for (; j < count && filed[j] <= last; j++) {
                            final int k = (int) (filed[j] & 0xFFFF);
                            if (k > i && stranger.get(i * n + k)) closer(i, k, xs, ys, widths, heights, nearest);
                        }
                    }
                }
            }
            double owed = 0;
            for (int i = 0; i < n; i++) if (nearest[i] < air) owed += (air - nearest[i]) * perPixel;
            return owed;
        };
    }
}

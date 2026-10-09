package com.gtnhplanner;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.layout.arrange.ArrangeCard;
import com.gtnhplanner.layout.arrange.ArrangeWire;
import com.gtnhplanner.layout.arrange.Grid;
import com.gtnhplanner.layout.arrange.Optimize;
import com.gtnhplanner.layout.arrange.Point;
import com.gtnhplanner.layout.arrange.Prices;

/**
 * The arrange's island search (Optimize, Factory Flow's board-arrange-optimize.ts): it keeps cards on the grid and
 * apart and drawers on their machine's side, never ends worse than it started, comes out the same every time, and lays
 * an island out exactly as the website does.
 */
class ArrangeOptimizeTest {

    /**
     * A small line: a washer with its water supply on its left, two mills, a centrifuge with two catches on its right,
     * an electrolyzer and a free output drawer. Four columns, two feeder sections in the mills' column, wires of mixed
     * widths and weights, one skipping a column.
     */
    private static final List<Optimize.Card> CARDS = List.of(
        machine("washer", 200, 0, 0, 0),
        riding("water", 68, "washer", 0, true),
        machine("mill-a", 160, 1, 0, 0),
        machine("mill-b", 160, 1, 220, 1),
        machine("spinner", 240, 2, 0, 0),
        riding("dust", 68, "spinner", 2, false),
        riding("slag", 128, "spinner", 2, false),
        machine("cell", 200, 3, 0, 0),
        new Optimize.Card("out", 136, 68, true, 3, 240, null, null));

    private static final List<ArrangeWire> WIRES = List.of(
        wire("water", "washer", null, 6.0),
        wire("washer", "mill-a", null, 10.0),
        wire("washer", "mill-b", null, 8.0),
        wire("mill-a", "spinner", 2.0, null),
        wire("mill-b", "spinner", 1.0, null),
        wire("mill-b", "cell", null, 4.0),
        wire("spinner", "dust", null, 4.0),
        wire("spinner", "slag", null, 4.0),
        wire("spinner", "cell", null, 12.0),
        wire("cell", "out", null, null),
        wire("mill-a", "cell", 0.5, null));

    private static Optimize.Card machine(final String id, final double height, final int layer, final double seq,
        final int section) {
        return new Optimize.Card(id, 320, height, false, layer, seq, section, null);
    }

    /** A drawer on a machine's side: it carries the machine's column, order and section, as the layered pass gives. */
    private static Optimize.Card riding(final String id, final double height, final String anchor, final int layer,
        final boolean left) {
        return new Optimize.Card(id, 136, height, true, layer, 0, 0, new Optimize.Satellite(anchor, left));
    }

    private static ArrangeWire wire(final String from, final String to, final Double weight, final Double width) {
        return new ArrangeWire(null, from, to, null, null, weight, width);
    }

    private static List<ArrangeCard> plain(final List<Optimize.Card> cards) {
        final List<ArrangeCard> out = new ArrayList<>();
        for (final Optimize.Card card : cards) {
            out.add(new ArrangeCard(card.id(), 0, 0, card.width(), card.height(), card.storage()));
        }
        return out;
    }

    private static Map<String, Point> byId(final List<Optimize.Card> cards, final List<Point> positions) {
        final Map<String, Point> out = new LinkedHashMap<>();
        int i = 0;
        for (final Optimize.Card card : cards) out.put(card.id(), positions.get(i++));
        return out;
    }

    private static List<Point> points(final double... xy) {
        final List<Point> out = new ArrayList<>();
        for (int i = 0; i < xy.length; i += 2) out.add(new Point(xy[i], xy[i + 1]));
        return out;
    }

    @Test
    void keepsCardsOnTheGridApartAndOnTheirSides() {
        final Optimize.Result result = Optimize.optimizeIslandLayout(CARDS, WIRES, null);
        final List<Point> at = result.positions();
        assertEquals(CARDS.size(), at.size());
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
        for (final Point p : at) {
            assertEquals(0, p.x() % Grid.CELL, "x on the grid: " + p);
            assertEquals(0, p.y() % Grid.CELL, "y on the grid: " + p);
            minX = Math.min(minX, p.x());
            minY = Math.min(minY, p.y());
        }
        assertEquals(0, minX, "normalised to the left edge");
        assertEquals(0, minY, "normalised to the top edge");
        for (int i = 0; i < CARDS.size(); i++) {
            for (int k = i + 1; k < CARDS.size(); k++) {
                final Optimize.Card a = CARDS.get(i), b = CARDS.get(k);
                final Point p = at.get(i), q = at.get(k);
                final boolean overlap = p.x() < q.x() + b.width() && q.x() < p.x() + a.width()
                    && p.y() < q.y() + b.height()
                    && q.y() < p.y() + a.height();
                assertFalse(overlap, a.id() + " at " + p + " overlaps " + b.id() + " at " + q);
            }
        }
        final Map<String, Point> placed = byId(CARDS, at);
        final Point water = placed.get("water"), washer = placed.get("washer");
        final Point spinner = placed.get("spinner"), dust = placed.get("dust"), slag = placed.get("slag");
        assertTrue(water.x() + 136 <= washer.x(), "the supply rides on the left");
        assertTrue(dust.x() >= spinner.x() + 320, "a catch rides on the right");
        assertTrue(slag.x() >= spinner.x() + 320, "a catch rides on the right");
        assertTrue(result.after() <= result.before(), "the search never ends worse than it started");
        assertNull(result.points(), "no judge, no points");
        assertEquals(List.of(), result.finalists(), "no judge, no finalists");
    }

    @Test
    void comesOutTheSameEveryTime() {
        final Optimize.Result first = Optimize.optimizeIslandLayout(CARDS, WIRES, null);
        final Optimize.Result second = Optimize.optimizeIslandLayout(CARDS, WIRES, Optimize.Options.defaults());
        assertEquals(first, second);
        final Optimize.Options judged = Optimize.Options.defaults()
            .withTrials(1500)
            .withJudge(p -> Optimize.scoreLayoutProxy(plain(CARDS), WIRES, p, Prices.DEFAULT, null));
        assertEquals(
            Optimize.optimizeIslandLayout(CARDS, WIRES, judged),
            Optimize.optimizeIslandLayout(CARDS, WIRES, judged));
    }

    /** The website's own optimizer, run on this island, gave these (Factory Flow 3.9.6). */
    @Test
    void laysTheIslandOutAsTheWebsiteDoes() {
        final Optimize.Result unjudged = Optimize.optimizeIslandLayout(CARDS, WIRES, null);
        assertEquals(
            points(180, 100, 0, 260, 560, 260, 560, 40, 940, 140, 1300, 160, 1300, 300, 1500, 100, 1580, 0),
            unjudged.positions());
        assertEquals(4607.568542494924, unjudged.before(), 1e-6);
        assertEquals(3011.2, unjudged.after(), 1e-6);

        final ToDoubleFunction<Map<String, Point>> judge = p -> Optimize
            .scoreLayoutProxy(plain(CARDS), WIRES, p, Prices.DEFAULT, null);
        final Optimize.Result judged = Optimize.optimizeIslandLayout(
            CARDS,
            WIRES,
            new Optimize.Options(1, 3, 2, 1, 1500, judge, Prices.DEFAULT, null, null));
        assertEquals(
            points(160, 100, 0, 260, 520, 220, 520, 0, 880, 120, 1220, 120, 1220, 260, 1400, 60, 1480, 320),
            judged.positions());
        assertEquals(1780, (double) judged.points(), 1e-6);
        // Three column arrangements made the final.
        assertArrayEquals(new double[] { 1780, 2380.284271247462, 3613.8528137423855 }, pointsOf(judged), 1e-6);
    }

    /** What the judge gave each finalist. */
    private static double[] pointsOf(final Optimize.Result result) {
        final List<Optimize.Finalist> finalists = result.finalists();
        final double[] out = new double[finalists.size()];
        int i = 0;
        for (final Optimize.Finalist finalist : finalists) out[i++] = finalist.points();
        return out;
    }

    @Test
    void theJudgePicksTheFinalistWithTheFewestPoints() {
        final List<ArrangeCard> plain = plain(CARDS);
        final ToDoubleFunction<Map<String, Point>> judge = p -> Optimize
            .scoreLayoutProxy(plain, WIRES, p, Prices.DEFAULT, null);
        final Optimize.Options options = Optimize.Options.defaults()
            .withTrials(2000)
            .withJudge(judge);
        final Optimize.Result result = Optimize.optimizeIslandLayout(CARDS, WIRES, options);
        final double[] points = pointsOf(result);
        assertTrue(points.length >= 1 && points.length <= Prices.DEFAULT.finalists(), points.length + " finalists");
        double fewest = Double.POSITIVE_INFINITY;
        for (final double p : points) fewest = Math.min(fewest, p);
        assertEquals(fewest, (double) result.points(), 0);
        // The judge's points depend only on where cards stand relative to each other, so the winner scores the same
        // where it is handed back.
        assertEquals(fewest, judge.applyAsDouble(byId(CARDS, result.positions())), 1e-6);
    }

    @Test
    void aLoneCardOrAnUnwiredIslandStaysPut() {
        final List<Optimize.Card> lone = List.of(new Optimize.Card("a", 100, 60, false, 0, 0, null, null));
        final Optimize.Result one = Optimize.optimizeIslandLayout(lone, List.of(), null);
        assertEquals(List.of(new Point(0, 0)), one.positions());
        assertEquals(one.before(), one.after());

        final List<Optimize.Card> two = List.of(
            new Optimize.Card("a", 100, 60, false, 0, 0, null, null),
            new Optimize.Card("b", 100, 60, false, 1, 0, null, null));
        // A wire to a card outside the island and a wire to itself do not count.
        final Optimize.Result unwired = Optimize
            .optimizeIslandLayout(two, List.of(wire("a", "elsewhere", null, null), wire("b", "b", null, null)), null);
        assertEquals(points(0, 0, 160, 0), unwired.positions(), "the layered pass's columns, three cells apart");
        assertEquals(unwired.before(), unwired.after());
    }

    @Test
    void aCrossingScoresWorseThanTheSameBoardUncrossed() {
        final List<ArrangeCard> cards = List.of(
            new ArrangeCard("a1", 0, 0, 100, 100, false),
            new ArrangeCard("a2", 0, 0, 100, 100, false),
            new ArrangeCard("b1", 0, 0, 100, 100, false),
            new ArrangeCard("b2", 0, 0, 100, 100, false));
        final List<ArrangeWire> wires = List.of(wire("a1", "b1", null, null), wire("a2", "b2", null, null));
        final Map<String, Point> straight = Map
            .of("a1", new Point(0, 0), "a2", new Point(0, 200), "b1", new Point(400, 0), "b2", new Point(400, 200));
        final Map<String, Point> crossed = Map
            .of("a1", new Point(0, 0), "a2", new Point(0, 200), "b1", new Point(400, 200), "b2", new Point(400, 0));
        final double uncrossedScore = Optimize.scoreLayoutProxy(cards, wires, straight, Prices.DEFAULT, null);
        final double crossedScore = Optimize.scoreLayoutProxy(cards, wires, crossed, Prices.DEFAULT, null);
        assertEquals(600, uncrossedScore, 1e-9, "two straight wires, 300 px each");
        assertTrue(
            crossedScore >= uncrossedScore + Prices.DEFAULT.crossing(),
            "crossed " + crossedScore + " against straight " + uncrossedScore);
        assertEquals(
            uncrossedScore + 25,
            Optimize.scoreLayoutProxy(cards, wires, straight, Prices.DEFAULT, p -> 25),
            1e-9,
            "the air is added on top");
    }
}

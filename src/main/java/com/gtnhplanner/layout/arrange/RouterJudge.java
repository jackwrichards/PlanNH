package com.gtnhplanner.layout.arrange;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.gtnhplanner.layout.RouteMetrics;
import com.gtnhplanner.layout.WireRouter;

/**
 * The arrange's judge on the board's own router: routes the real wires with every card at a layout's places (cards it
 * is not told about stay where they are) and scores them in {@link RouteMetrics}' points, so the arrange optimises the
 * picture the board will actually draw. A full verdict routes afresh (and is remembered by layout); a quick one keeps a
 * second router on the base layout and routes again only the wires the trial's moves touched.
 */
public final class RouterJudge implements Arrange.Judge {

    private final List<ArrangeCard> cards;
    private final List<ArrangeWire> given;
    private final List<String> keys = new ArrayList<>();
    private final List<WireRouter.Wire> wires = new ArrayList<>();
    private final List<String> wireIds = new ArrayList<>();
    private final WireRouter fresh = new WireRouter(), quick = new WireRouter();
    /** The layout the quick router stands on (as its key), or null before its first route. */
    private String quickAt;
    private final Map<String, Arrange.Verdict> verdicts = new LinkedHashMap<>(16, 0.75f, false) {

        @Override
        protected boolean removeEldestEntry(final Map.Entry<String, Arrange.Verdict> eldest) {
            return size() > 64;
        }
    };

    public RouterJudge(final List<ArrangeCard> cards, final List<ArrangeWire> wires) {
        this.cards = List.copyOf(cards);
        this.given = List.copyOf(wires);
        final Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < cards.size(); i++) {
            index.put(
                cards.get(i)
                    .id(),
                i);
            keys.add(
                cards.get(i)
                    .id());
        }
        for (final ArrangeWire w : wires) {
            final Integer a = index.get(w.source()), b = index.get(w.target());
            if (a == null || b == null) continue;
            final UUID key = key(w.id() != null ? w.id() : w.source() + ">" + w.target() + "#" + this.wires.size());
            final double weight = w.width() != null ? w.width() : 4;
            // A wire from a card back to itself (two recipes on one shared machine) leaves right and comes round left.
            this.wires.add(
                a.equals(b) ? new WireRouter.Wire(key, a, b, WireRouter.RIGHT, WireRouter.LEFT, weight)
                    : new WireRouter.Wire(key, a, b, weight));
            wireIds.add(w.id());
        }
    }

    @Override
    public Arrange.Judge fork() {
        return new RouterJudge(cards, given);
    }

    private static UUID key(final String id) {
        try {
            return UUID.fromString(id);
        } catch (final IllegalArgumentException e) {
            return UUID.nameUUIDFromBytes(id.getBytes(StandardCharsets.UTF_8));
        }
    }

    @Override
    public Arrange.Verdict judge(final Map<String, Point> positions, final boolean quickly,
        final Map<String, Point> base) {
        if (!quickly || base == null) return full(positions);
        final String baseKey = layoutKey(base);
        if (quickAt == null) quick.route(boxes(base), keys, wires, null, true);
        else if (!quickAt.equals(baseKey)) quick.reroute(boxes(base), keys, wires, null, true);
        final List<WireRouter.Box> trial = boxes(positions);
        final Map<UUID, List<int[]>> routes = quick.reroute(trial, keys, wires, null, true);
        quickAt = layoutKey(positions);
        return measure(trial, routes);
    }

    private Arrange.Verdict full(final Map<String, Point> positions) {
        final String layout = layoutKey(positions);
        final Arrange.Verdict known = verdicts.get(layout);
        if (known != null) return known;
        final List<WireRouter.Box> boxes = boxes(positions);
        final Arrange.Verdict v = measure(boxes, fresh.route(boxes, keys, wires, null, true));
        verdicts.put(layout, v);
        return v;
    }

    /** Every card's box at the layout (where it rests when the layout leaves it out), on whole px. */
    private List<WireRouter.Box> boxes(final Map<String, Point> positions) {
        final List<WireRouter.Box> boxes = new ArrayList<>(cards.size());
        for (final ArrangeCard card : cards) {
            final Point p = positions.get(card.id());
            boxes.add(
                new WireRouter.Box(
                    (int) Math.round(p != null ? p.x() : card.x()),
                    (int) Math.round(p != null ? p.y() : card.y()),
                    (int) Math.round(card.width()),
                    (int) Math.round(card.height())));
        }
        return boxes;
    }

    private String layoutKey(final Map<String, Point> positions) {
        final StringBuilder s = new StringBuilder(cards.size() * 12);
        for (final ArrangeCard card : cards) {
            final Point p = positions.get(card.id());
            s.append(Math.round(p != null ? p.x() : card.x()))
                .append(',')
                .append(Math.round(p != null ? p.y() : card.y()))
                .append(';');
        }
        return s.toString();
    }

    private Arrange.Verdict measure(final List<WireRouter.Box> boxes, final Map<UUID, List<int[]>> routes) {
        final List<RouteMetrics.Box> metricBoxes = new ArrayList<>(boxes.size());
        for (final WireRouter.Box b : boxes) metricBoxes.add(new RouteMetrics.Box(b.x(), b.y(), b.w(), b.h()));
        final List<RouteMetrics.Wire> metricWires = new ArrayList<>(wires.size());
        final List<Integer> measured = new ArrayList<>(wires.size());
        for (int i = 0; i < wires.size(); i++) {
            final List<int[]> path = routes.get(
                wires.get(i)
                    .key());
            if (path == null) continue;
            metricWires.add(
                new RouteMetrics.Wire(
                    wires.get(i)
                        .from(),
                    wires.get(i)
                        .to(),
                    path));
            measured.add(i);
        }
        final List<int[]> crossed = new ArrayList<>();
        final RouteMetrics.Result m = RouteMetrics.measure(metricBoxes, metricWires, crossed);
        final List<String[]> events = new ArrayList<>(crossed.size());
        for (final int[] pair : crossed) {
            final String a = wireIds.get(measured.get(pair[0])), b = wireIds.get(measured.get(pair[1]));
            if (a != null && b != null) events.add(new String[] { a, b });
        }
        return new Arrange.Verdict(m.crossings(), m.length(), m.points(), events);
    }
}

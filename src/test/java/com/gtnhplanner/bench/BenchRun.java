package com.gtnhplanner.bench;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.gtnhplanner.layout.RouteMetrics;
import com.gtnhplanner.layout.WireRouter;

/**
 * Routes and arranges a {@link BenchBoard} the way the board does (WireLayer, BoardCanvas.arrange), and measures it.
 */
public final class BenchRun {

    private BenchRun() {}

    /** A routed board: the wires' corners by link id, which fell back, how long it took, and what it measures. */
    public record Routed(BenchBoard board, Map<UUID, List<int[]>> routes, List<UUID> fellBack, double millis,
        RouteMetrics.Result metrics) {}

    private static final WireRouter NEW_ROUTER = new WireRouter();

    public static Routed route(final BenchBoard board) {
        return routeNew(board);
    }

    /** The board's router: boxes and wires box to box, a loop from a card back to itself right side to left side. */
    public static Routed routeNew(final BenchBoard board) {
        final List<WireRouter.Box> boxes = new ArrayList<>();
        final Map<UUID, Integer> index = new HashMap<>();
        for (final BenchBoard.Item i : board.items()) {
            index.put(i.id(), boxes.size());
            boxes.add(new WireRouter.Box(i.x(), i.y(), i.w(), i.h()));
        }
        final List<WireRouter.Wire> wires = new ArrayList<>();
        for (final BenchBoard.Link l : board.links()) {
            final int a = index.get(l.from()), b = index.get(l.to());
            wires.add(
                a == b ? new WireRouter.Wire(l.id(), a, b, WireRouter.RIGHT, WireRouter.LEFT, 0)
                    : new WireRouter.Wire(l.id(), a, b, 0));
        }
        final List<UUID> fellBack = new ArrayList<>();
        NEW_ROUTER.route(boxes, wires, new ArrayList<>(), true);
        final long t0 = System.nanoTime();
        final Map<UUID, List<int[]>> routes = NEW_ROUTER.route(boxes, wires, fellBack, true);
        final double ms = (System.nanoTime() - t0) / 1e6;
        System.out.printf(
            java.util.Locale.ROOT,
            "[router] %-40s %7.1f ms  wires %4d  searches %5d  widened %4d  failed %3d  rerouted %4d  sealed %3d  pops %,11d (first pass %,11d)  avg window %,7d  max %,8d  grid %,d%n",
            board.name()
                .length() > 40 ? board.name()
                    .substring(0, 40) : board.name(),
            ms,
            wires.size(),
            NEW_ROUTER.statSearches,
            NEW_ROUTER.statWidened,
            NEW_ROUTER.statFailed,
            NEW_ROUTER.statRerouted,
            NEW_ROUTER.statSealed,
            NEW_ROUTER.statPops,
            NEW_ROUTER.statFirstPassPops,
            NEW_ROUTER.statSearches == 0 ? 0 : NEW_ROUTER.statWindowCells / NEW_ROUTER.statSearches,
            NEW_ROUTER.statMaxWindow,
            NEW_ROUTER.gridCells());
        return new Routed(board, routes, fellBack, ms, measure(board, routes));
    }

    public static RouteMetrics.Result measure(final BenchBoard board, final Map<UUID, List<int[]>> routes) {
        final List<RouteMetrics.Box> boxes = new ArrayList<>();
        final Map<UUID, Integer> index = new HashMap<>();
        for (final BenchBoard.Item i : board.items()) {
            index.put(i.id(), boxes.size());
            boxes.add(new RouteMetrics.Box(i.x(), i.y(), i.w(), i.h()));
        }
        final List<RouteMetrics.Wire> wires = new ArrayList<>();
        for (final BenchBoard.Link l : board.links()) {
            final List<int[]> p = routes.get(l.id());
            if (p == null) continue;
            wires.add(new RouteMetrics.Wire(index.get(l.from()), index.get(l.to()), p));
        }
        return RouteMetrics.measure(boxes, wires);
    }

    private static final int[] STAGE = { -1 };
    private static final String[] STAGE_NAME = { "" };

    /**
     * The board as Arrange leaves it (layout/arrange, judged by the board's router), how long that took, and which
     * candidate won. -Pstages prints when each stage starts.
     */
    public static BenchBoard arrangeNew(final BenchBoard board, final double[] millis, final String[] chosen) {
        final List<com.gtnhplanner.layout.arrange.ArrangeCard> cards = new ArrayList<>();
        final Map<UUID, BenchBoard.Item> byId = board.byId();
        for (final BenchBoard.Item i : board.items()) cards.add(
            new com.gtnhplanner.layout.arrange.ArrangeCard(
                i.id()
                    .toString(),
                i.x(),
                i.y(),
                i.w(),
                i.h(),
                i.drawer()));
        final List<com.gtnhplanner.layout.arrange.ArrangeWire> wires = new ArrayList<>();
        for (final BenchBoard.Link l : board.links()) {
            final BenchBoard.Item a = byId.get(l.from()), b = byId.get(l.to());
            wires.add(
                new com.gtnhplanner.layout.arrange.ArrangeWire(
                    l.id()
                        .toString(),
                    l.from()
                        .toString(),
                    l.to()
                        .toString(),
                    a.drawer() ? null
                        : (double) (BenchBoard.RAILS_Y + l.fromPort() * BenchBoard.ROW + BenchBoard.ROW / 2),
                    b.drawer() ? null
                        : (double) (BenchBoard.RAILS_Y + l.toPort() * BenchBoard.ROW + BenchBoard.ROW / 2),
                    null,
                    null));
        }
        final boolean stages = Boolean.getBoolean("gtnhplanner.stages");
        final long t0 = System.nanoTime();
        final com.gtnhplanner.layout.arrange.Arrange.Result result = com.gtnhplanner.layout.arrange.Arrange.arrange(
            new com.gtnhplanner.layout.arrange.Arrange.Input(
                cards,
                wires,
                null,
                System.getProperty("gtnhplanner.spacing", "compact"),
                new com.gtnhplanner.layout.arrange.RouterJudge(cards, wires),
                null,
                null,
                p -> {
                    if (stages && (p.step() != STAGE[0] || !p.stage()
                        .equals(STAGE_NAME[0]))) {
                        STAGE[0] = p.step();
                        STAGE_NAME[0] = p.stage();
                        System.out.printf(
                            java.util.Locale.ROOT,
                            "[stage] %-40s step %d (%s) at %8.1f ms%n",
                            board.name(),
                            p.step(),
                            p.stage(),
                            (System.nanoTime() - t0) / 1e6);
                    }
                },
                null));
        STAGE[0] = -1;
        millis[0] = (System.nanoTime() - t0) / 1e6;
        chosen[0] = result.chosen();
        final Map<UUID, int[]> at = new HashMap<>();
        for (final Map.Entry<String, com.gtnhplanner.layout.arrange.Point> e : result.positions()
            .entrySet())
            at.put(
                UUID.fromString(e.getKey()),
                new int[] { (int) Math.round(
                    e.getValue()
                        .x()),
                    (int) Math.round(
                        e.getValue()
                            .y()) });
        return board.placed(at);
    }
}

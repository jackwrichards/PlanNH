package com.gtnhplanner.bench;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.gtnhplanner.data.flowchart.Edge;
import com.gtnhplanner.layout.AutoLayout;
import com.gtnhplanner.layout.BoardArrange;
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

    /** A board item for the layout: its size, its ports, and where on it each port sits (a card's rows). */
    private record Node(UUID id, String machineName, int worldWidth, int worldHeight, int inputCount, int outputCount)
        implements AutoLayout.LayoutNode {

        @Override
        public int portY(final boolean output, final int index) {
            return BenchBoard.RAILS_Y + index * BenchBoard.ROW + BenchBoard.ROW / 2;
        }
    }

    /** The board as Arrange leaves it (BoardCanvas.arrange), and how long that took. */
    public static BenchBoard arrange(final BenchBoard board, final double[] millis) {
        final List<BoardArrange.Box> boxes = new ArrayList<>();
        for (final BenchBoard.Item i : board.items()) boxes.add(
            new BoardArrange.Box(
                new Node(i.id(), i.label(), i.w(), i.h(), i.ins(), i.outs()),
                i.drawer(),
                i.drawer() && i.supplies()));
        final List<Edge> links = new ArrayList<>();
        for (final BenchBoard.Link l : board.links())
            links.add(new Edge(l.id(), l.from(), l.to(), l.fromPort(), l.toPort()));
        final long t0 = System.nanoTime();
        final Map<UUID, int[]> at = BoardArrange.arrange(boxes, links);
        millis[0] = (System.nanoTime() - t0) / 1e6;
        return board.placed(at);
    }
}

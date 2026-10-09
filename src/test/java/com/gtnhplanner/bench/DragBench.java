package com.gtnhplanner.bench;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.gtnhplanner.layout.RouteMetrics;
import com.gtnhplanner.layout.WireRouter;

/**
 * Drags a card across a board as the board does while the mouse moves: the busiest card, then a quiet one, forty steps,
 * each routed again incrementally ({@link WireRouter#reroute}), then dropped (routed again thoroughly); and compares
 * what that leaves with routing the final board afresh. {@code -Pbench=drag --tests '*DragBench*'}.
 */
@Tag("bench")
class DragBench {

    private static final String[] BOARDS = { "Farm Power", "FULL PLATLINE", "router-corpus/Untitled design", "HV Oil",
        "router-corpus/Platline", "LUV Superconductor" };

    @Test
    void drag() throws IOException {
        final List<BenchBoard> corpus = LayoutBenchmark.corpus();
        for (final String want : BOARDS) {
            BenchBoard board = null;
            for (final BenchBoard b : corpus) if (b.name()
                .contains(want)
                && b.items()
                    .stream()
                    .anyMatch(i -> i.x() != 0 || i.y() != 0)) {
                        board = b;
                        break;
                    }
            if (board == null) continue;
            // Wires per box, to pick the busiest and a quiet one.
            final Map<UUID, Integer> count = new HashMap<>();
            for (final BenchBoard.Link l : board.links()) {
                count.merge(l.from(), 1, Integer::sum);
                count.merge(l.to(), 1, Integer::sum);
            }
            final List<BenchBoard.Item> byCount = new ArrayList<>(board.items());
            byCount.sort((a, b) -> count.getOrDefault(b.id(), 0) - count.getOrDefault(a.id(), 0));
            for (final BenchBoard.Item moving : List.of(byCount.get(0), byCount.get(byCount.size() / 3))) {
                drag(board, moving, count.getOrDefault(moving.id(), 0));
            }
        }
    }

    private static void drag(final BenchBoard board, final BenchBoard.Item moving, final int wiresAt) {
        final WireRouter router = new WireRouter();
        final List<WireRouter.Wire> wires = wires(board);
        // Warm up, then the board as it opens.
        router.route(boxes(board, moving, 0, 0), wires, null, true);
        long t0 = System.nanoTime();
        router.route(boxes(board, moving, 0, 0), wires, null, true);
        final double openMs = (System.nanoTime() - t0) / 1e6;
        double worst = 0, total = 0;
        int redone = 0;
        final int steps = 40, dx = 14, dy = 6;
        for (int k = 1; k <= steps; k++) {
            t0 = System.nanoTime();
            router.reroute(boxes(board, moving, k * dx, k * dy), wires, null, false);
            final double ms = (System.nanoTime() - t0) / 1e6;
            worst = Math.max(worst, ms);
            total += ms;
            redone += router.statRouted;
        }
        t0 = System.nanoTime();
        final Map<UUID, List<int[]>> dropped = router
            .reroute(boxes(board, moving, steps * dx, steps * dy), wires, null, true);
        final double dropMs = (System.nanoTime() - t0) / 1e6;
        final int dropRedone = router.statRouted;
        final Map<UUID, int[]> at = new HashMap<>();
        at.put(moving.id(), new int[] { moving.x() + steps * dx, moving.y() + steps * dy });
        final BenchBoard after = board.placed(at);
        final RouteMetrics.Result kept = BenchRun.measure(after, dropped);
        final WireRouter fresh = new WireRouter();
        t0 = System.nanoTime();
        final Map<UUID, List<int[]>> afresh = fresh.route(boxes(after, null, 0, 0), wires, null, true);
        final double freshMs = (System.nanoTime() - t0) / 1e6;
        final RouteMetrics.Result clean = BenchRun.measure(after, afresh);
        System.out.printf(
            Locale.ROOT,
            "[drag] %-34s %3d wires at the card  open %7.1f ms  step avg %6.2f max %6.2f ms (%.1f wires a step)  drop %6.1f ms (%d wires)  points %8.0f vs afresh %8.0f (%5.1f ms)  crossings %d vs %d%n",
            board.name()
                .length() > 34 ? board.name()
                    .substring(0, 34) : board.name(),
            wiresAt,
            openMs,
            total / steps,
            worst,
            redone / (double) steps,
            dropMs,
            dropRedone,
            kept.points(),
            clean.points(),
            freshMs,
            kept.crossings(),
            clean.crossings());
    }

    /** The board's boxes, {@code moving} (if given) shifted by (dx, dy). */
    private static List<WireRouter.Box> boxes(final BenchBoard board, final BenchBoard.Item moving, final int dx,
        final int dy) {
        final List<WireRouter.Box> out = new ArrayList<>();
        for (final BenchBoard.Item i : board.items()) {
            final boolean m = moving != null && i.id()
                .equals(moving.id());
            out.add(new WireRouter.Box(i.x() + (m ? dx : 0), i.y() + (m ? dy : 0), i.w(), i.h()));
        }
        return out;
    }

    private static List<WireRouter.Wire> wires(final BenchBoard board) {
        final Map<UUID, Integer> index = new HashMap<>();
        for (int i = 0; i < board.items()
            .size(); i++)
            index.put(
                board.items()
                    .get(i)
                    .id(),
                i);
        final List<WireRouter.Wire> wires = new ArrayList<>();
        for (final BenchBoard.Link l : board.links()) {
            final int a = index.get(l.from()), b = index.get(l.to());
            wires.add(
                a == b ? new WireRouter.Wire(l.id(), a, b, WireRouter.RIGHT, WireRouter.LEFT, 0)
                    : new WireRouter.Wire(l.id(), a, b, 0));
        }
        return wires;
    }
}

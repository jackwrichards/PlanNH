package com.gtnhplanner.bench;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.gtnhplanner.layout.WireRouter;

/** Routes one board and prints its costliest searches: {@code -Pbench=trace -PprofileBoard=<name>}. */
@Tag("bench")
class RouterTrace {

    @Test
    void trace() throws IOException {
        final String want = System.getProperty("gtnhplanner.profile.board", "Farm Power");
        BenchBoard board = null;
        for (final BenchBoard b : LayoutBenchmark.corpus()) if (b.name()
            .contains(want)) board = b;
        if (board == null) throw new IllegalStateException("no board " + want);
        final List<String> lines = new ArrayList<>();
        WireRouter.trace = lines::add;
        final BenchRun.Routed routed;
        try {
            routed = BenchRun.routeNew(board);
        } finally {
            WireRouter.trace = null;
        }
        // The corners of every wire between the boxes named first in each crop pair.
        for (final String pair : System.getProperty("gtnhplanner.crop", "")
            .split(";")) {
            if (pair.isBlank()) continue;
            final String[] ab = pair.split(",");
            final java.util.UUID a = board.items()
                .get(Integer.parseInt(ab[0].trim()))
                .id(),
                b = board.items()
                    .get(Integer.parseInt(ab[1].trim()))
                    .id();
            for (final BenchBoard.Link l : board.links()) {
                if (!l.from()
                    .equals(a)
                    || !l.to()
                        .equals(b))
                    continue;
                final StringBuilder sb = new StringBuilder("[wire] " + pair + ":");
                final List<int[]> p = routed.routes()
                    .get(l.id());
                for (int k = 0; k < p.size(); k++) {
                    sb.append(' ')
                        .append(p.get(k)[0])
                        .append(',')
                        .append(p.get(k)[1]);
                    if (k > 0) sb.append(" (")
                        .append(p.get(k)[0] - p.get(k - 1)[0])
                        .append(',')
                        .append(p.get(k)[1] - p.get(k - 1)[1])
                        .append(')');
                }
                System.out.println(sb);
            }
        }
        // Close-ups round the boxes named in -Pcrop=a,b;c,d (box indexes).
        final String crops = System.getProperty("gtnhplanner.crop", "");
        for (final String pair : crops.split(";")) {
            if (pair.isBlank()) continue;
            int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE;
            for (final String i : pair.split(",")) {
                final BenchBoard.Item it = board.items()
                    .get(Integer.parseInt(i.trim()));
                x0 = Math.min(x0, it.x());
                y0 = Math.min(y0, it.y());
                x1 = Math.max(x1, it.x() + it.w());
                y1 = Math.max(y1, it.y() + it.h());
            }
            BenchRender.render(
                routed,
                board.name() + " " + pair,
                new java.io.File(
                    "build/bench/" + System.getProperty("gtnhplanner.bench", "trace")
                        + "/crop-"
                        + pair.replace(',', '-')
                        + ".png"),
                new int[] { x0 - 150, y0 - 150, x1 + 150, y1 + 150 });
        }
        // Both runs are traced; the second half is the timed one.
        final List<String> timed = lines.subList(lines.size() / 2, lines.size());
        final List<String> sorted = new ArrayList<>(timed);
        sorted.sort((a, b) -> Integer.compare(pops(b), pops(a)));
        long total = 0;
        for (final String l : timed) total += pops(l);
        System.out.println("[trace] " + timed.size() + " searches, " + total + " pops");
        for (int i = 0; i < Math.min(40, sorted.size()); i++) System.out.println("[trace] " + sorted.get(i));
    }

    private static int pops(final String l) {
        return Integer.parseInt(
            l.substring(4, 13)
                .trim());
    }
}

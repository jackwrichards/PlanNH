package com.gtnhplanner.bench;

import java.io.IOException;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Routes one big board over and over, for a profiler: {@code -Pbench=profile -Pjfr --tests '*RouterProfile*'}. */
@Tag("bench")
class RouterProfile {

    @Test
    void profile() throws IOException {
        final String want = System.getProperty("gtnhplanner.profile.board", "Farm Power");
        BenchBoard board = null;
        for (final BenchBoard b : LayoutBenchmark.corpus()) if (b.name()
            .contains(want)) board = b;
        if (board == null) throw new IllegalStateException("no board " + want);
        for (int i = 0; i < 6; i++) {
            final BenchRun.Routed r = BenchRun.routeNew(board);
            System.out.println("[profile] " + board.name() + " " + r.millis() + " ms");
        }
    }
}

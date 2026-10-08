package com.gtnhplanner.dev;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Frame times while sampling (harness {@code perf?start=1}, then {@code perf} for the report): every frame's length
 * from one render tick to the next, and the time spent in named sections ({@link #time}) the board reports, so a
 * slow frame can be put down to its part. Client thread only, apart from the section counters.
 */
public final class DevPerf {

    private static volatile boolean sampling;
    private static long last;
    private static long[] frames = new long[4096];
    private static int count;
    private static final Map<String, AtomicLong[]> SECTIONS = new ConcurrentHashMap<>();

    private DevPerf() {}

    static void start() {
        sampling = true;
        last = 0;
        count = 0;
        SECTIONS.clear();
    }

    /** Called at the start of every rendered frame. */
    static void frame() {
        if (!sampling) return;
        final long now = System.nanoTime();
        if (last != 0) {
            if (count == frames.length) frames = Arrays.copyOf(frames, count * 2);
            frames[count++] = now - last;
        }
        last = now;
    }

    /** Whether sections are being timed: callers skip the clock otherwise. */
    public static boolean on() {
        return sampling;
    }

    /** Adds one timed run of a section (nanoseconds). */
    public static void time(final String section, final long nanos) {
        if (!sampling) return;
        final AtomicLong[] slot = SECTIONS
            .computeIfAbsent(section, k -> new AtomicLong[] { new AtomicLong(), new AtomicLong(), new AtomicLong() });
        slot[0].addAndGet(nanos);
        slot[1].incrementAndGet();
        slot[2].accumulateAndGet(nanos, Math::max);
    }

    static Map<String, Object> stop() {
        sampling = false;
        final long[] sorted = Arrays.copyOf(frames, count);
        Arrays.sort(sorted);
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("frames", count);
        if (count > 0) {
            long total = 0;
            for (final long f : sorted) total += f;
            m.put("seconds", round(total / 1e9));
            m.put("fps", round(count / (total / 1e9)));
            m.put("avgMs", round(total / 1e6 / count));
            m.put("p50Ms", round(sorted[count / 2] / 1e6));
            m.put("p95Ms", round(sorted[Math.min(count - 1, count * 95 / 100)] / 1e6));
            m.put("p99Ms", round(sorted[Math.min(count - 1, count * 99 / 100)] / 1e6));
            m.put("maxMs", round(sorted[count - 1] / 1e6));
        }
        final Map<String, Object> sections = new LinkedHashMap<>();
        for (final Map.Entry<String, AtomicLong[]> e : SECTIONS.entrySet()) {
            final long n = e.getValue()[1].get();
            sections.put(
                e.getKey(),
                Map.of(
                    "runs",
                    n,
                    "avgMs",
                    round(e.getValue()[0].get() / 1e6 / Math.max(1, n)),
                    "maxMs",
                    round(e.getValue()[2].get() / 1e6),
                    "perFrameMs",
                    round(e.getValue()[0].get() / 1e6 / Math.max(1, count))));
        }
        m.put("sections", sections);
        return m;
    }

    private static double round(final double v) {
        return Math.round(v * 100) / 100.0;
    }
}

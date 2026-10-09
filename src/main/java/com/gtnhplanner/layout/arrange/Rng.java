package com.gtnhplanner.layout.arrange;

import java.util.Collection;

/**
 * A tiny deterministic random number generator (mulberry32), seeded from the ids being laid out, so the same board
 * always arranges the same way. Bit for bit the website's.
 */
public final class Rng {

    private int state;

    public Rng(final int seed) {
        this.state = seed;
    }

    /** The next number in [0, 1). */
    public double next() {
        state += 0x6d2b79f5;
        int t = (state ^ state >>> 15) * (1 | state);
        t = t + (t ^ t >>> 7) * (61 | t) ^ t;
        return ((t ^ t >>> 14) & 0xFFFFFFFFL) / 4294967296.0;
    }

    /** A seed from a list of ids (FNV-1a over their UTF-16 units, mixed per id). */
    public static int hashIds(final Collection<String> ids) {
        int hash = (int) 2166136261L;
        for (final String id : ids) {
            for (int i = 0; i < id.length(); i++) {
                hash ^= id.charAt(i);
                hash *= 16777619;
            }
            hash ^= 0x9e3779b9;
        }
        return hash;
    }
}

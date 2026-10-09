package com.gtnhplanner.layout.arrange;

/** The board's grid, as the arrange sees it: 20 px cells, and everything it places lands on a cell corner. */
public final class Grid {

    /** A cell, in world px. */
    public static final int CELL = 20;

    private Grid() {}

    /** {@code n} cells, in px. */
    public static double cells(final double n) {
        return n * CELL;
    }

    /** The nearest cell corner (halves round up, as JavaScript's Math.round does). */
    public static double snap(final double v) {
        return Math.floor(v / CELL + 0.5) * CELL;
    }
}

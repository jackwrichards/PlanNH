package com.gtnhplanner.ui.theme;

/**
 * The planner's small pictures, 9 by 9 in GUI pixels and one colour each (strokes two pixels thick, as the game's
 * own icons are, so they hold up beside its font). Drawn from rows of text: '#' in the colour, '.' left clear. On a
 * key, {@link #centred} puts them in its middle on whole pixels, level with a label's letters.
 */
public final class Icons {

    public static final int SIZE = 9;

    private Icons() {}

    /** A hooked arrow, its head pointing left and its tail curling under. */
    public static void undo(final int x, final int y, final int c) {
        draw(UNDO, x, y, c, false);
    }

    /** The undo arrow turned the other way. */
    public static void redo(final int x, final int y, final int c) {
        draw(UNDO, x, y, c, true);
    }

    /** A beetle: antennae, a head, a back split down the middle, three legs a side. */
    public static void bug(final int x, final int y, final int c) {
        draw(BUG, x, y, c, false);
    }

    /** A cog of eight teeth round a hub. */
    public static void gear(final int x, final int y, final int c) {
        draw(GEAR, x, y, c, false);
    }

    /** A question mark. */
    public static void help(final int x, final int y, final int c) {
        draw(HELP, x, y, c, false);
    }

    /** A light bulb on its screw. */
    public static void bulb(final int x, final int y, final int c) {
        draw(BULB, x, y, c, false);
    }

    /**
     * Where an icon goes on a key {@code w} by {@code h}: the middle of its lit face (the dark edge right and below
     * melts into the bar), its rows level with a label's letters. Exact on a key of even width and height.
     */
    public static int[] centred(final int x, final int y, final int w, final int h) {
        return new int[] { x + (w - 1 - SIZE) / 2, y + (h - 1 - SIZE) / 2 };
    }

    private static final String[] UNDO = { "..#......", ".##......", "#######..", "########.", ".##...##.", "..#...##.",
        "......##.", "..#####..", "..####..." };
    private static final String[] BUG = { ".#.....#.", "..#...#..", "...###...", "#.#####.#", ".###.###.", "#.##.##.#",
        ".###.###.", "#.##.##.#", "..##.##.." };
    private static final String[] GEAR = { "..##.##..", "..#####..", "#########", "###...###", ".##...##.", "###...###",
        "#########", "..#####..", "..##.##.." };
    private static final String[] HELP = { "..#####..", ".##...##.", "......##.", ".....##..", "....##...", "....##...",
        ".........", "....##...", "....##..." };
    private static final String[] BULB = { "...###...", "..##.##..", ".##...##.", ".##...##.", "..##.##..", "...###...",
        ".........", "...###...", "...###..." };

    private static void draw(final String[] rows, final int x, final int y, final int c, final boolean mirror) {
        final boolean own = !Hyb.batching();
        if (own) Hyb.beginBatch();
        for (int row = 0; row < rows.length; row++) {
            final String r = rows[row];
            // Runs of set pixels, one rectangle each.
            int col = 0;
            while (col < r.length()) {
                if (r.charAt(col) != '#') {
                    col++;
                    continue;
                }
                final int from = col;
                while (col < r.length() && r.charAt(col) == '#') col++;
                final int left = mirror ? r.length() - col : from;
                Hyb.rect(x + left, y + row, col - from, 1, c);
            }
        }
        if (own) Hyb.endBatch();
    }
}

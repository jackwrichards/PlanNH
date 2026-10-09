package com.gtnhplanner.ui.note;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.gtnhplanner.ui.theme.Hyb;

/**
 * A sticky note's colours, from Factory Flow's colour tags (the sixteen dyes and the app's own inks): the paper is
 * the tag's swatch lifted a quarter of the way to white, the ink dark or light by how bright the paper is, as the
 * website picks it.
 */
public final class NoteColors {

    private NoteColors() {}

    /** Every tag the website knows, with its swatch. */
    private static final Map<String, Integer> SWATCH = new LinkedHashMap<>();

    static {
        SWATCH.put("white", 0xF0F0F0);
        SWATCH.put("orange", 0xF9801D);
        SWATCH.put("magenta", 0xC74EBD);
        SWATCH.put("light_blue", 0x3AB3DA);
        SWATCH.put("yellow", 0xFED83D);
        SWATCH.put("lime", 0x80C71F);
        SWATCH.put("pink", 0xF38BAA);
        SWATCH.put("gray", 0x474F52);
        SWATCH.put("light_gray", 0x9D9D97);
        SWATCH.put("cyan", 0x169C9C);
        SWATCH.put("purple", 0x8932B8);
        SWATCH.put("blue", 0x3C44AA);
        SWATCH.put("brown", 0x835432);
        SWATCH.put("green", 0x5E7C16);
        SWATCH.put("red", 0xB02E26);
        SWATCH.put("black", 0x1D1D21);
        SWATCH.put("scarlet", 0xEF4444);
        SWATCH.put("amber", 0xE0A63A);
        SWATCH.put("emerald", 0x10B981);
        SWATCH.put("azure", 0x3B82F6);
        SWATCH.put("steel", 0x3C3E45);
        SWATCH.put("onyx", 0x303238);
    }

    /** The colours the note menu offers, in order, with their names. */
    public static final List<String[]> MENU = List.of(
        new String[] { "yellow", "Yellow" },
        new String[] { "orange", "Orange" },
        new String[] { "pink", "Pink" },
        new String[] { "scarlet", "Red" },
        new String[] { "lime", "Lime" },
        new String[] { "emerald", "Green" },
        new String[] { "light_blue", "Light blue" },
        new String[] { "azure", "Blue" },
        new String[] { "purple", "Purple" },
        new String[] { "white", "White" },
        new String[] { "steel", "Grey" });

    /** The tag's swatch, opaque; an unknown tag reads as yellow. */
    public static int swatch(final String tag) {
        final Integer rgb = SWATCH.get(tag);
        return 0xFF000000 | (rgb != null ? rgb : SWATCH.get("yellow"));
    }

    /** The paper: the swatch a quarter of the way to white. */
    public static int paper(final String tag) {
        return Hyb.mix(0xFFFFFFFF, swatch(tag), 0.25f);
    }

    /** The glued strip along the top, a shade darker than the paper. */
    public static int band(final String tag) {
        return Hyb.mix(0xFF000000, paper(tag), 0.07f);
    }

    /** The folded corner, darker still. */
    public static int fold(final String tag) {
        return Hyb.mix(0xFF000000, paper(tag), 0.2f);
    }

    /** The edge where the paper meets the board, faint. */
    public static int edge(final String tag) {
        return Hyb.mix(0xFF000000, paper(tag), 0.14f);
    }

    /** The ink: near black on bright paper, near white on dark, by the website's luminance test. */
    public static int ink(final String tag) {
        return luminance(paper(tag)) < 0.34 ? 0xFFF2F3F7 : 0xFF1E1E22;
    }

    /** Relative luminance (WCAG) of an RGB colour. */
    static double luminance(final int rgb) {
        return 0.2126 * linear(rgb >> 16 & 0xFF) + 0.7152 * linear(rgb >> 8 & 0xFF) + 0.0722 * linear(rgb & 0xFF);
    }

    private static double linear(final int channel) {
        final double c = channel / 255.0;
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }
}

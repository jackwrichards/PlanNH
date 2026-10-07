package com.sbancuz.plannh.ui.card;

import java.util.ArrayList;
import java.util.List;

import com.sbancuz.plannh.ui.theme.Hyb;

/**
 * Geometry of a recipe card in GUI pixels, shared by drawing, the child widgets and wire anchors. Factory Flow's card
 * proportions, fitted to the game's 16 px icons and 9 px font line. A port's name takes one line, or two when it does
 * not fit (then its row is taller), and never gets cut mid-word on the first line.
 */
public final class CardLayout {

    public static final int W = 320;
    public static final int PAD = 4;
    public static final int HEAD = 20;
    /** A one-line port row: slot beside the name, rate under it. */
    public static final int ROW = 20;
    /** A port row whose name wraps to two lines. */
    public static final int ROW_TALL = 29;
    public static final int ROW_GAP = 2;
    public static final int RAIL_W = 112;
    /** Room for the port name and rate, right of the slot. */
    public static final int TEXT_X = 21;
    public static final int TEXT_W = RAIL_W - TEXT_X;
    public static final int SETTING_ROW = 18;
    public static final int FOOT = 30;
    public static final int PICTURE_MIN = 72;

    public static final int RAILS_Y = PAD + HEAD + 4;
    public static final int IN_RAIL_X = PAD;
    public static final int OUT_RAIL_X = W - PAD - RAIL_W;
    public static final int PICTURE_X = IN_RAIL_X + RAIL_W + 4;
    public static final int PICTURE_W = OUT_RAIL_X - 4 - PICTURE_X;

    public final int railsH;
    public final int settingsY;
    public final int settingRows;
    public final int footY;
    public final int height;

    private final int[] inY, outY, inH, outH;
    private final List<List<String>> inNames, outNames;

    public CardLayout(final CardModel model) {
        inNames = names(model.inputs);
        outNames = names(model.outputs);
        inH = heights(inNames);
        outH = heights(outNames);
        inY = tops(inH);
        outY = tops(outH);
        railsH = Math.max(Math.max(railHeight(inH), railHeight(outH)), PICTURE_MIN);
        settingRows = model.usesHeat ? 1 : 0;
        settingsY = RAILS_Y + railsH + 6;
        footY = settingsY + settingRows * (SETTING_ROW + 2) + (settingRows > 0 ? 2 : 0);
        height = footY + FOOT + PAD;
    }

    /** One or two lines per port name; a name longer than two lines keeps "..." at the end of the second. */
    private static List<List<String>> names(final List<CardModel.PortView> ports) {
        final List<List<String>> out = new ArrayList<>(ports.size());
        for (final CardModel.PortView p : ports) {
            final String name = p.name() == null ? "" : p.name();
            if (Hyb.width(name) <= TEXT_W) {
                out.add(List.of(name));
                continue;
            }
            final List<String> wrapped = Hyb.font()
                .listFormattedStringToWidth(name, TEXT_W);
            if (wrapped.size() <= 2) {
                out.add(new ArrayList<>(wrapped));
                continue;
            }
            final String rest = name.substring(
                Math.min(
                    name.length(),
                    wrapped.get(0)
                        .length()))
                .trim();
            out.add(List.of(wrapped.get(0), Hyb.fit(rest, TEXT_W)));
        }
        return out;
    }

    private static int[] heights(final List<List<String>> names) {
        final int[] h = new int[names.size()];
        for (int i = 0; i < h.length; i++) h[i] = names.get(i)
            .size() > 1 ? ROW_TALL : ROW;
        return h;
    }

    private static int[] tops(final int[] heights) {
        final int[] y = new int[heights.length];
        int at = RAILS_Y;
        for (int i = 0; i < heights.length; i++) {
            y[i] = at;
            at += heights[i] + ROW_GAP;
        }
        return y;
    }

    private static int railHeight(final int[] heights) {
        int sum = 0;
        for (final int h : heights) sum += h + ROW_GAP;
        return Math.max(0, sum - ROW_GAP);
    }

    /** Top of a port row, card-local. */
    public int rowY(final boolean output, final int index) {
        final int[] y = output ? outY : inY;
        return index >= 0 && index < y.length ? y[index] : RAILS_Y;
    }

    public int rowH(final boolean output, final int index) {
        final int[] h = output ? outH : inH;
        return index >= 0 && index < h.length ? h[index] : ROW;
    }

    /** The port's name, one or two lines. */
    public List<String> nameLines(final boolean output, final int index) {
        final List<List<String>> names = output ? outNames : inNames;
        return index >= 0 && index < names.size() ? names.get(index) : List.of();
    }

    /** Where a wire meets the card: the card edge, level with the port's slot. */
    public int anchorY(final boolean output, final int index) {
        return rowY(output, index) + 1 + 9;
    }

    public static int railX(final boolean output) {
        return output ? OUT_RAIL_X : IN_RAIL_X;
    }

    public static int anchorX(final boolean output) {
        return output ? W : 0;
    }
}

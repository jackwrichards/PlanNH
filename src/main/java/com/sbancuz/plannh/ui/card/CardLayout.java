package com.sbancuz.plannh.ui.card;

import java.util.ArrayList;
import java.util.List;

import com.sbancuz.plannh.ui.theme.Hyb;

/**
 * Geometry of a recipe card in GUI pixels, shared by drawing, the child widgets and wire anchors. Factory Flow's card
 * (380 wide) at 320, with its chrome kept at one pixel: see {@code docs/design/ff-card-spec.md}. Every port is a tile
 * of the same height: the icon at one and a half times, the name on one or two lines, the rate under it.
 */
public final class CardLayout {

    public static final int W = 320;
    /** Where the content starts inside the frame. */
    public static final int PAD = 6;
    /** The head row: the actions key, the machine's name bar and the amps and tier chips. */
    public static final int HEAD_Y = 7;
    public static final int HEAD = 20;
    /** A port tile; tiles stack with no gap, their borders making the seam. */
    public static final int ROW = 34;
    public static final int RAIL_W = 100;
    /** The port's icon in its tile: 24 px, one and a half times the game's, so its pixels stay whole. */
    public static final int ICON = 24;
    public static final int ICON_X = 4;
    public static final int ICON_Y = (ROW - ICON) / 2;
    /** Room for the port name and rate, right of the icon. */
    public static final int TEXT_X = ICON_X + ICON + 4;
    public static final int TEXT_W = RAIL_W - TEXT_X - 3;
    /** A setting tile: its caption above a raised well. */
    public static final int SETTING_ROW = 33;
    public static final int FOOT = 30;
    public static final int PICTURE_MIN = 68;

    public static final int RAILS_Y = HEAD_Y + HEAD + 7;
    public static final int IN_RAIL_X = PAD;
    public static final int OUT_RAIL_X = W - PAD - RAIL_W;
    public static final int PICTURE_X = IN_RAIL_X + RAIL_W + 4;
    public static final int PICTURE_W = OUT_RAIL_X - 4 - PICTURE_X;

    public final int railsH;
    /** The hairline between the rails and the settings and footer. */
    public final int hairY;
    public final int settingsY;
    public final int settingRows;
    public final int footY;
    public final int height;

    private final int inRows, outRows;
    private final List<List<String>> inNames, outNames;

    public CardLayout(final CardModel model) {
        inNames = names(model.inputs);
        outNames = names(model.outputs);
        inRows = model.inputs.size();
        outRows = model.outputs.size();
        railsH = Math.max(Math.max(inRows, outRows) * ROW, PICTURE_MIN);
        settingRows = model.usesHeat ? 1 : 0;
        hairY = RAILS_Y + railsH + 5;
        settingsY = hairY + 6;
        footY = settingsY + settingRows * (SETTING_ROW + 4);
        height = footY + FOOT + 6;
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

    /** Top of a port tile, card-local. */
    public int rowY(final boolean output, final int index) {
        final int rows = output ? outRows : inRows;
        return RAILS_Y + Math.max(0, Math.min(index, Math.max(0, rows - 1))) * ROW;
    }

    public int rowH(final boolean output, final int index) {
        return ROW;
    }

    /** The port's name, one or two lines. */
    public List<String> nameLines(final boolean output, final int index) {
        final List<List<String>> names = output ? outNames : inNames;
        return index >= 0 && index < names.size() ? names.get(index) : List.of();
    }

    /** Where a wire meets the card: the card edge, level with the middle of the port's tile. */
    public int anchorY(final boolean output, final int index) {
        return rowY(output, index) + ROW / 2;
    }

    public static int railX(final boolean output) {
        return output ? OUT_RAIL_X : IN_RAIL_X;
    }

    public static int anchorX(final boolean output) {
        return output ? W : 0;
    }
}

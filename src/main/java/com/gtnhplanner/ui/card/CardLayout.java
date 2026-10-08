package com.gtnhplanner.ui.card;

import java.util.ArrayList;
import java.util.List;

import com.gtnhplanner.ui.theme.Hyb;

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

    /** A power card's tiles, two to a row: its settings, then its readings. Empty on a recipe card. */
    public final List<PowerTiles.Tile> powerTiles;
    /** A power card's warnings, wrapped to the card, under its tiles. */
    public final List<String> warningLines;
    /** A power tile's size, and where the warnings start. */
    public static final int TILE_W = (W - 2 * PAD - 4) / 2;
    public final int warningsY;

    /** On a shared machine, the rule row over each recipe's rails, and the gap before every recipe after the first. */
    public static final int SECTION_RULE = 16, SECTION_GAP = 8;

    /** Each recipe on the card: one, or several on a shared machine. */
    private final int sections;
    private final int[] inRows, outRows, ruleY, railsY;
    private final List<List<List<String>>> inNames, outNames;
    /** Where the rails end: below the last recipe's. */
    public final int railsEnd;

    public CardLayout(final CardModel model) {
        this(List.of(model));
    }

    /**
     * A card for its recipes, top first. A shared machine gives each recipe a rule row (its share on the left, its
     * circuit and keys on the right) over its rails, which are as tall as its longer side so its inputs and outputs
     * face
     * each other, as Factory Flow draws it.
     */
    public CardLayout(final List<CardModel> models) {
        sections = models.size();
        final boolean shared = sections > 1;
        inRows = new int[sections];
        outRows = new int[sections];
        ruleY = new int[sections];
        railsY = new int[sections];
        inNames = new ArrayList<>(sections);
        outNames = new ArrayList<>(sections);
        int y = RAILS_Y;
        for (int s = 0; s < sections; s++) {
            final CardModel model = models.get(s);
            inNames.add(names(model.inputs));
            outNames.add(names(model.outputs));
            inRows[s] = model.inputs.size();
            outRows[s] = model.outputs.size();
            if (shared) {
                if (s > 0) y += SECTION_GAP;
                ruleY[s] = y;
                y += SECTION_RULE;
            }
            railsY[s] = y;
            y += Math.max(shared ? 1 : 0, Math.max(inRows[s], outRows[s])) * ROW;
        }
        railsEnd = y;
        railsH = Math.max(railsEnd - RAILS_Y, PICTURE_MIN);
        final CardModel first = models.get(0);
        powerTiles = first.isPower() ? PowerTiles.of(first) : List.of();
        warningLines = new ArrayList<>();
        if (first.isPower() && first.power.model() != null) {
            for (final String warning : first.power.model()
                .warnings())
                warningLines.addAll(
                    Hyb.font()
                        .listFormattedStringToWidth(warning, W - 2 * PAD - 4));
        }
        settingRows = first.isPower() ? (powerTiles.size() + 1) / 2 : first.usesHeat ? 1 : 0;
        hairY = RAILS_Y + railsH + 5;
        settingsY = hairY + 6;
        warningsY = settingsY + settingRows * (SETTING_ROW + 4);
        footY = warningsY + (warningLines.isEmpty() ? 0 : warningLines.size() * 9 + 4);
        height = footY + FOOT + 6;
    }

    /** Card-local {x, y} of a power tile. */
    public int[] tileAt(final int index) {
        return new int[] { PAD + (index % 2) * (TILE_W + 4), settingsY + (index / 2) * (SETTING_ROW + 4) };
    }

    /** One or two lines per port name; a name longer than two lines keeps "..." at the end of the second. */
    private static List<List<String>> names(final List<CardModel.PortView> ports) {
        final List<List<String>> out = new ArrayList<>(ports.size());
        for (final CardModel.PortView p : ports) out.add(nameLines(p.name()));
        return out;
    }

    /** A port name on one or two lines of a port tile; longer keeps "..." at the end of the second. */
    static List<String> nameLines(final String text) {
        final String name = text == null ? "" : text;
        if (Hyb.width(name) <= TEXT_W) return List.of(name);
        final List<String> wrapped = Hyb.font()
            .listFormattedStringToWidth(name, TEXT_W);
        if (wrapped.size() <= 2) return new ArrayList<>(wrapped);
        final String rest = name.substring(
            Math.min(
                name.length(),
                wrapped.get(0)
                    .length()))
            .trim();
        return List.of(wrapped.get(0), Hyb.fit(rest, TEXT_W));
    }

    public int sections() {
        return sections;
    }

    /** Top of a shared machine's rule row over a recipe. */
    public int ruleY(final int section) {
        return ruleY[clampSection(section)];
    }

    /** Top of a recipe's rails. */
    public int railsY(final int section) {
        return railsY[clampSection(section)];
    }

    public int rows(final int section, final boolean output) {
        return (output ? outRows : inRows)[clampSection(section)];
    }

    private int clampSection(final int section) {
        return Math.max(0, Math.min(section, sections - 1));
    }

    /** Top of a port tile, card-local. */
    public int rowY(final int section, final boolean output, final int index) {
        final int s = clampSection(section), rows = (output ? outRows : inRows)[s];
        return railsY[s] + Math.max(0, Math.min(index, Math.max(0, rows - 1))) * ROW;
    }

    public int rowY(final boolean output, final int index) {
        return rowY(0, output, index);
    }

    public int rowH(final boolean output, final int index) {
        return ROW;
    }

    /** The port's name, one or two lines. */
    public List<String> nameLines(final int section, final boolean output, final int index) {
        final List<List<String>> names = (output ? outNames : inNames).get(clampSection(section));
        return index >= 0 && index < names.size() ? names.get(index) : List.of();
    }

    public List<String> nameLines(final boolean output, final int index) {
        return nameLines(0, output, index);
    }

    /** Where a wire meets the card: the card edge, level with the middle of the port's tile. */
    public int anchorY(final int section, final boolean output, final int index) {
        return rowY(section, output, index) + ROW / 2;
    }

    public int anchorY(final boolean output, final int index) {
        return anchorY(0, output, index);
    }

    public static int railX(final boolean output) {
        return output ? OUT_RAIL_X : IN_RAIL_X;
    }

    public static int anchorX(final boolean output) {
        return output ? W : 0;
    }
}

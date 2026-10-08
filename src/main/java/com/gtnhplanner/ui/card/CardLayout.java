package com.gtnhplanner.ui.card;

import java.util.ArrayList;
import java.util.List;

import com.gtnhplanner.ui.theme.Hyb;

/**
 * Geometry of a card in GUI pixels, shared by drawing, the child widgets and wire anchors: the clean card
 * ({@code docs/design/card-redesign.md}). One surface, no boxes inside it: a header band (keys, the machine's name,
 * the amps and tier chips), inputs against the left edge and outputs against the right where their wires meet the card,
 * the machine's picture in the middle, and a strip of setting chips along the bottom. A port row is its icon at the
 * edge, how much in large type and its name small under it. Power drawn, and the circuit when set so, are one more row
 * after a recipe's inputs.
 */
public final class CardLayout {

    public static final int W = 320;
    /** Where the content starts inside the frame. */
    public static final int PAD = 6;
    /** The header band: its chips (and keys) {@link #HEAD} high, as far from its top as from the card's right edge. */
    public static final int HEAD_Y = 6;
    public static final int HEAD = 20;
    public static final int HEADER = HEAD_Y * 2 + HEAD;
    /** A port row: its icon 24 px (one and a half times the game's, so its pixels stay whole) at the card's edge. */
    public static final int ROW = 32;
    public static final int ICON = 24;
    public static final int ICON_X = 4;
    public static final int ICON_Y = (ROW - ICON) / 2;
    /** A side's column, and where a port's words start (from the side's outer edge) and how wide they may be. */
    public static final int RAIL_W = 104;
    public static final int TEXT_X = ICON_X + ICON + 6;
    public static final int TEXT_W = RAIL_W - TEXT_X - 2;
    /** The machine's picture: square, in the middle column. */
    public static final int PICTURE = 88;
    public static final int PICTURE_MIN = PICTURE;

    public static final int RAILS_Y = HEADER + 6;
    public static final int IN_RAIL_X = 4;
    public static final int OUT_RAIL_X = W - 4 - RAIL_W;
    public static final int PICTURE_X = IN_RAIL_X + RAIL_W;
    public static final int PICTURE_W = OUT_RAIL_X - PICTURE_X;

    /** The settings strip: a chip's height, a row's, and the room inside a chip. */
    public static final int CHIP = 18, CHIP_ROW = 22, CHIP_PAD = 5;

    /** On a shared machine, the rule row over each recipe's rails, and the gap before every recipe after the first. */
    public static final int SECTION_RULE = 16, SECTION_GAP = 8;

    public final int railsH;
    /** Where the settings strip starts (its hairline), the chips under it, each {x, y, w}. */
    public final int stripY;
    public final List<CardChips.Chip> chips;
    private final int[][] chipAt;
    /** A power card's warnings, wrapped to the card, under the strip. */
    public final List<String> warningLines;
    public final int warningsY;
    public final int height;
    /** The power row (what the machine draws) and the circuit's row among the inputs, card-local y; -1 for none. */
    public final int powerRowY, circuitRowY;
    /** Whether the circuit is a row of its own (the setting), so the layout is redone when it changes. */
    public final boolean circuitAsInput;

    /** Each recipe on the card: one, or several on a shared machine. */
    private final int sections;
    private final int[] inRows, outRows, ruleY, railsY;
    /** Where the rails end: below the last recipe's (and the power row). */
    public final int railsEnd;

    public CardLayout(final CardModel model) {
        this(List.of(model));
    }

    /**
     * A card for its recipes, top first. A shared machine gives each recipe a rule row (its share on the left, its
     * circuit and keys on the right) over its rails, which are as tall as its longer side so its inputs and outputs
     * face each other; its power is one row after them all.
     */
    public CardLayout(final List<CardModel> models) {
        sections = models.size();
        final boolean shared = sections > 1;
        final CardModel first = models.get(0);
        circuitAsInput = com.gtnhplanner.ui.PlannerSettings.circuitAsInput();
        final boolean circuitRow = !shared && !first.isPower() && first.circuit != null && circuitAsInput;
        final boolean powerRow = !first.isPower() && first.euPerTick > 0;
        inRows = new int[sections];
        outRows = new int[sections];
        ruleY = new int[sections];
        railsY = new int[sections];
        int y = RAILS_Y, circuitY = -1, powerY = -1;
        for (int s = 0; s < sections; s++) {
            final CardModel model = models.get(s);
            inRows[s] = model.inputs.size();
            outRows[s] = model.outputs.size();
            if (shared) {
                if (s > 0) y += SECTION_GAP;
                ruleY[s] = y;
                y += SECTION_RULE;
            }
            railsY[s] = y;
            int extra = 0;
            if (!shared) {
                if (circuitRow) circuitY = y + (inRows[s] + extra++) * ROW;
                if (powerRow) powerY = y + (inRows[s] + extra++) * ROW;
            }
            y += Math.max(shared ? 1 : 0, Math.max(inRows[s] + extra, outRows[s])) * ROW;
        }
        if (shared && powerRow) {
            powerY = y + 4;
            y += 4 + ROW;
        }
        railsEnd = y;
        circuitRowY = circuitY;
        powerRowY = powerY;
        railsH = Math.max(railsEnd - RAILS_Y, PICTURE_MIN);
        // The strip: chips left to right, a new row when one would pass the edge.
        chips = CardChips.of(first);
        chipAt = new int[chips.size()][];
        stripY = RAILS_Y + railsH + 6;
        int cx = PAD, cy = stripY + 4;
        for (int i = 0; i < chips.size(); i++) {
            final int w = chipW(chips.get(i));
            if (cx > PAD && cx + w > W - PAD) {
                cx = PAD;
                cy += CHIP_ROW;
            }
            chipAt[i] = new int[] { cx, cy, w };
            cx += w + 4;
        }
        final int stripEnd = chips.isEmpty() ? RAILS_Y + railsH + 2 : cy + CHIP + 4;
        warningLines = new ArrayList<>();
        if (first.isPower() && first.power.model() != null) {
            for (final String warning : first.power.model()
                .warnings())
                warningLines.addAll(
                    Hyb.font()
                        .listFormattedStringToWidth(warning, W - 2 * PAD - 4));
        }
        warningsY = stripEnd + 2;
        height = warningsY + (warningLines.isEmpty() ? 0 : warningLines.size() * 9 + 4) + 4;
    }

    /** A chip's width: its icon, label and value. */
    public static int chipW(final CardChips.Chip c) {
        return chipW(c.label(), c.value(), c.icon() != null);
    }

    public static int chipW(final String label, final String value, final boolean icon) {
        return CHIP_PAD + (icon ? 14 + 3 : 0) + Hyb.width(label) + 4 + Hyb.width(value) + CHIP_PAD;
    }

    /** Card-local {x, y, w, h} of a chip in the strip. */
    public int[] chipRect(final int index) {
        final int[] at = chipAt[index];
        return new int[] { at[0], at[1], at[2], CHIP };
    }

    /** The chip under a card-local point, or -1. */
    public int chipAt(final float x, final float y) {
        for (int i = 0; i < chipAt.length; i++) {
            final int[] at = chipAt[i];
            if (x >= at[0] && x < at[0] + at[2] && y >= at[1] && y < at[1] + CHIP) return i;
        }
        return -1;
    }

    /** The picture's box in the middle column, card-local {x, y, side}. */
    public int[] picture() {
        return new int[] { PICTURE_X + (PICTURE_W - PICTURE) / 2, RAILS_Y, PICTURE };
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

    /** Top of a port's row, card-local. */
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

    /** Where a wire meets the card: the card edge, level with the middle of the port's row. */
    public int anchorY(final int section, final boolean output, final int index) {
        return rowY(section, output, index) + ROW / 2;
    }

    public int anchorY(final boolean output, final int index) {
        return anchorY(0, output, index);
    }

    public static int railX(final boolean output) {
        return output ? OUT_RAIL_X : IN_RAIL_X;
    }

    /** Where a port's icon is: at the card's edge, on the left for an input and the right for an output. */
    public static int iconX(final boolean output) {
        return output ? OUT_RAIL_X + RAIL_W - ICON_X - ICON : IN_RAIL_X + ICON_X;
    }

    /** Where a port's words start (an input) or end (an output, right aligned). */
    public static int textEdge(final boolean output) {
        return output ? OUT_RAIL_X + RAIL_W - TEXT_X : IN_RAIL_X + TEXT_X;
    }

    public static int anchorX(final boolean output) {
        return output ? W : 0;
    }
}

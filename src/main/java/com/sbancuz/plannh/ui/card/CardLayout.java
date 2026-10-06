package com.sbancuz.plannh.ui.card;

/**
 * Geometry of a recipe card in GUI pixels, shared by drawing, the child widgets and wire anchors. Factory Flow's card
 * proportions, fitted to the game's 16 px icons and 9 px font line.
 */
public final class CardLayout {

    public static final int W = 320;
    public static final int PAD = 4;
    public static final int HEAD = 20;
    public static final int ROW = 20;
    public static final int ROW_STEP = 22;
    public static final int RAIL_W = 112;
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

    public CardLayout(final CardModel model) {
        final int rows = Math.max(1, Math.max(model.inputs.size(), model.outputs.size()));
        railsH = Math.max(rows * ROW_STEP - 2, PICTURE_MIN);
        settingRows = model.usesHeat ? 1 : 0;
        settingsY = RAILS_Y + railsH + 6;
        footY = settingsY + settingRows * (SETTING_ROW + 2) + (settingRows > 0 ? 2 : 0);
        height = footY + FOOT + PAD;
    }

    /** Top of a port row; both rails start at the top, as in Factory Flow. */
    public static int portRowY(final int index) {
        return RAILS_Y + index * ROW_STEP;
    }

    public static int railX(final boolean output) {
        return output ? OUT_RAIL_X : IN_RAIL_X;
    }

    /** Where a wire meets the card: the card edge, level with the port row. */
    public static int anchorX(final boolean output) {
        return output ? W : 0;
    }

    public static int anchorY(final int index) {
        return portRowY(index) + ROW / 2;
    }
}

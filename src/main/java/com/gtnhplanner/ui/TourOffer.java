package com.gtnhplanner.ui;

import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.widget.Widget;
import com.gtnhplanner.ui.theme.Hyb;

/**
 * The first time the planner opens, the tour is offered in a note over the dimmed board, in the tour's own look: take
 * it, or not now. Either answer puts it away for good; the tour stays under the "?" key. Until then the board waits
 * under it.
 */
final class TourOffer extends Widget<TourOffer> implements Interactable {

    private static final String[] LINES = { "New to GTNH Planner?", "A quick tour shows you around",
        "in about three minutes." };
    private static final String TAKE = "Take the tour", SKIP = "Not now";
    /** The tour's note: its fill and gold. */
    private static final int FILL = 0xFF222327, GOLD = 0xFFFFD257, PAD = 9, LINE = 11, KEY_H = 15;

    private static boolean wanted() {
        return !PlannerSettings.tourOffered() && !com.gtnhplanner.ui.tutorial.Tutorial.active();
    }

    @Override
    public void onUpdate() {
        super.onUpdate();
        final boolean on = wanted();
        if (isEnabled() != on) setEnabled(on);
    }

    @Override
    public boolean canHover() {
        return true;
    }

    // region Geometry: the note centred in the widget, the board under it

    private int noteW() {
        int w = Hyb.width(TAKE) + Hyb.width(SKIP) + 28 + 6;
        for (final String l : LINES) w = Math.max(w, Hyb.width(l));
        return w + 2 * PAD;
    }

    private int noteH() {
        return PAD + LINES.length * LINE + 6 + KEY_H + PAD;
    }

    private int noteX() {
        return (getArea().width - noteW()) / 2;
    }

    private int noteY() {
        return (getArea().height - noteH()) / 2;
    }

    /** {x, y, w, h} of a key, local. */
    private int[] key(final boolean take) {
        final int skipW = Hyb.width(SKIP) + 14, takeW = Hyb.width(TAKE) + 14;
        final int y = noteY() + noteH() - PAD - KEY_H, right = noteX() + noteW() - PAD;
        return take ? new int[] { right - skipW - 6 - takeW, y, takeW, KEY_H }
            : new int[] { right - skipW, y, skipW, KEY_H };
    }

    // endregion

    @Override
    public void draw(final ModularGuiContext context, final WidgetThemeEntry<?> widgetTheme) {
        final int mx = getContext().getAbsMouseX() - getArea().x, my = getContext().getAbsMouseY() - getArea().y;
        Hyb.rect(0, 0, getArea().width, getArea().height, 0x8C000000);
        final int x = noteX(), y = noteY(), w = noteW(), h = noteH();
        Hyb.rect(x + 3, y + 4, w, h, 0x66000000);
        Hyb.rect(x, y, w, h, GOLD);
        Hyb.rect(x + 1, y + 1, w - 2, h - 2, FILL);
        for (int i = 0; i < LINES.length; i++)
            Hyb.text(LINES[i], x + PAD, y + PAD + i * LINE, 1f, i == 0 ? GOLD : Hyb.INK, false);
        drawKey(TAKE, key(true), mx, my, true);
        drawKey(SKIP, key(false), mx, my, false);
    }

    private static void drawKey(final String label, final int[] r, final int mx, final int my, final boolean main) {
        final boolean hot = in(mx, my, r);
        Hyb.rect(r[0], r[1], r[2], r[3], main ? GOLD : Hyb.KEY_EDGE);
        Hyb.rect(
            r[0] + 1,
            r[1] + 1,
            r[2] - 2,
            r[3] - 2,
            main ? hot ? 0xFF5C4F25 : 0xFF4A4127 : hot ? 0xFF4E5058 : 0xFF34363C);
        Hyb.text(label, r[0] + 7, r[1] + 4, main ? GOLD : Hyb.INK);
    }

    private static boolean in(final int mx, final int my, final int[] r) {
        return mx >= r[0] && my >= r[1] && mx < r[0] + r[2] && my < r[1] + r[3];
    }

    /** The take and skip keys, screen rects, for the dev harness. */
    int[] keyRect(final boolean take) {
        final int[] r = key(take);
        return new int[] { getArea().x + r[0], getArea().y + r[1], r[2], r[3] };
    }

    @Override
    public Result onMousePressed(final int mouseButton) {
        if (mouseButton != 0) return Result.SUCCESS;
        final int mx = getContext().getAbsMouseX() - getArea().x, my = getContext().getAbsMouseY() - getArea().y;
        if (in(mx, my, key(true))) {
            Hyb.click();
            PlannerSettings.setTourOffered(true);
            com.gtnhplanner.ui.tutorial.Tutorial.start();
        } else if (in(mx, my, key(false))) {
            Hyb.click();
            PlannerSettings.setTourOffered(true);
        }
        // The board waits under it: a press anywhere else is taken, not passed on.
        return Result.SUCCESS;
    }
}

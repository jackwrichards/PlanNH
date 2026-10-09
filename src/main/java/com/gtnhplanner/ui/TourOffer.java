package com.gtnhplanner.ui;

import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.widget.Widget;
import com.gtnhplanner.ui.theme.Hyb;

/**
 * The first time the planner opens, a small offer at the bottom of the board: take the tour, or not. Either answer
 * puts it away for good; the tour stays under the "?" key.
 */
final class TourOffer extends Widget<TourOffer> implements Interactable {

    static final int W = 286, H = 24;
    private static final String TEXT = "New here? The tour shows the planner in 8 minutes.";
    private static final String TAKE = "Take the tour", SKIP = "Not now";

    TourOffer() {
        size(W, H);
    }

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

    private int takeX() {
        return W - 6 - Hyb.width(SKIP) - 14 - 4 - Hyb.width(TAKE) - 14;
    }

    private int skipX() {
        return W - 6 - Hyb.width(SKIP) - 14;
    }

    @Override
    public void draw(final ModularGuiContext context, final WidgetThemeEntry<?> widgetTheme) {
        final int mx = getContext().getAbsMouseX() - getArea().x, my = getContext().getAbsMouseY() - getArea().y;
        Hyb.rect(3, 4, W, H, 0x66000000);
        Hyb.rect(-1, -1, W + 2, H + 2, 0xFF0B0C0E);
        Hyb.rect(0, 0, W, H, 0xF52A2C31);
        Hyb.rect(0, 0, 3, H, Hyb.GOLD);
        Hyb.text(Hyb.fit(TEXT, takeX() - 14), 9, 8, Hyb.INK);
        key(TAKE, takeX(), mx, my, true);
        key(SKIP, skipX(), mx, my, false);
    }

    private static void key(final String label, final int x, final int mx, final int my, final boolean main) {
        final int w = Hyb.width(label) + 14, y = 5, h = 14;
        final boolean hot = isIn(mx, my, x, y, w, h);
        Hyb.rect(x, y, w, h, main ? Hyb.GOLD : Hyb.KEY_EDGE);
        Hyb.rect(x + 1, y + 1, w - 2, h - 2, main ? hot ? 0xFF5C4F25 : 0xFF4A4127 : hot ? 0xFF4E5058 : 0xFF34363C);
        Hyb.text(label, x + 7, y + 3, main ? Hyb.GOLD : Hyb.INK);
    }

    private static boolean isIn(final int mx, final int my, final int x, final int y, final int w, final int h) {
        return mx >= x && my >= y && mx < x + w && my < y + h;
    }

    @Override
    public Result onMousePressed(final int mouseButton) {
        if (mouseButton != 0) return Result.SUCCESS;
        final int mx = getContext().getAbsMouseX() - getArea().x, my = getContext().getAbsMouseY() - getArea().y;
        if (isIn(mx, my, takeX(), 5, Hyb.width(TAKE) + 14, 14)) {
            Hyb.click();
            PlannerSettings.setTourOffered(true);
            com.gtnhplanner.ui.tutorial.Tutorial.start();
        } else if (isIn(mx, my, skipX(), 5, Hyb.width(SKIP) + 14, 14)) {
            Hyb.click();
            PlannerSettings.setTourOffered(true);
        }
        return Result.SUCCESS;
    }
}

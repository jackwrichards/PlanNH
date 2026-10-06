package com.sbancuz.plannh.ui;

import java.util.List;

import com.cleanroommc.modularui.api.UpOrDown;
import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.drawable.Stencil;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.widget.Widget;
import com.sbancuz.plannh.ui.theme.Fmt;
import com.sbancuz.plannh.ui.theme.Hyb;

/**
 * The totals rail along the board's right edge, folded until the top bar's key opens it: what the plan takes in, what
 * it gives out, its power, and the machines to build. Scrolls with the wheel.
 */
final class TotalsRail extends Widget<TotalsRail> implements Interactable {

    static final int W = 190;
    private static final int LINE = 18;
    private static final int HEADER = 16;

    private final BoardSession session;
    private boolean open;
    private int scroll;

    TotalsRail(final BoardSession session) {
        this.session = session;
        setEnabled(false);
    }

    boolean isOpen() {
        return open;
    }

    void toggle() {
        open = !open;
        scroll = 0;
        setEnabled(open);
    }

    @Override
    public boolean canHover() {
        return open;
    }

    private int contentHeight(final BoardSession.Totals t) {
        return 4 + 3 * HEADER
            + (t.inputs()
                .size()
                + t.outputs()
                    .size()
                + t.machines()
                    .size())
                * LINE
            + HEADER
            + 30;
    }

    @Override
    public void draw(final ModularGuiContext context, final WidgetThemeEntry<?> widgetTheme) {
        if (!open) return;
        final int w = getArea().width, h = getArea().height;
        final float z = context.getCurrentDrawingZ();
        Stencil.applyAtZero(getArea(), context);
        Hyb.rect(0, 0, w, h, 0xF0202226);
        Hyb.rect(0, 0, 1, h, Hyb.RING);
        final BoardSession.Totals t = session.totals();
        final Fmt.RateUnit unit = session.rateUnit();
        int y = 4 - scroll;
        y = section("COMING IN", t.inputs(), y, w, z, unit, Hyb.SOURCE_INK, false);
        y = section("GOING OUT", t.outputs(), y, w, z, unit, Hyb.PRODUCT_INK, false);
        Hyb.text(session.peakPower() ? "POWER (PEAK)" : "POWER (AVERAGE)", 6, y + 4, Hyb.MUTED);
        y += HEADER;
        Hyb.text(Fmt.power(t.euPerTick()) + " EU/t", 6, y + 2, Hyb.INK);
        y += LINE + 4;
        section("MACHINES TO BUILD", t.machines(), y, w, z, unit, Hyb.INK, true);
        Stencil.remove();
    }

    private int section(final String title, final List<BoardSession.TotalLine> lines, int y, final int w, final float z,
        final Fmt.RateUnit unit, final int color, final boolean counts) {
        Hyb.text(title, 6, y + 4, Hyb.MUTED);
        y += HEADER;
        if (lines.isEmpty()) {
            Hyb.text("nothing", 6, y + 4, 0xFF6A6C74);
            return y + LINE;
        }
        for (final BoardSession.TotalLine line : lines) {
            if (line.fluid() != null) Hyb.fluid(line.fluid(), 4, y + 1, 16, z);
            else Hyb.item(line.item(), 4, y + 1, 16, z);
            final String amount = counts ? "x" + Fmt.compact(line.amount())
                : Fmt.rate(line.amount(), unit, line.fluid() != null);
            final int aw = Hyb.width(amount);
            Hyb.text(Hyb.fit(line.label(), w - 24 - aw - 8), 23, y + 5, Hyb.INK);
            Hyb.textRight(amount, w - 5, y + 5, color);
            y += LINE;
        }
        return y + 4;
    }

    @Override
    public boolean onMouseScroll(final UpOrDown direction, final int amount) {
        if (!open) return false;
        final int max = Math.max(0, contentHeight(session.totals()) - getArea().height);
        scroll = Math.max(0, Math.min(max, scroll + (direction == UpOrDown.UP ? -LINE : LINE)));
        return true;
    }

    @Override
    public Result onMousePressed(final int mouseButton) {
        return open ? Result.SUCCESS : Result.IGNORE;
    }
}

package com.gtnhplanner.ui.card;

import com.gtnhplanner.ui.drawer.DrawerPaint;
import com.gtnhplanner.ui.theme.Fmt;
import com.gtnhplanner.ui.theme.Hyb;
import com.gtnhplanner.ui.world.PlanSnapshot;

/**
 * A plan's drawer as the board draws it zoomed in, for the minimap: its kind's shape, its name, the resource and its
 * rate, with nothing to press. Cards are {@link CleanCardView}.
 */
public final class PlanCardView {

    private PlanCardView() {}

    /**
     * A drawer as the board draws it zoomed in, without its keys or rule: its kind's shape, its name in the header, the
     * resource and what the plan moves through it. {@code lit} rings it in the highlight.
     */
    public static void drawer(final PlanSnapshot.Box b, final Fmt.RateUnit unit, final boolean lit) {
        final com.gtnhplanner.data.flowchart.Drawer.Kind kind = b.kind();
        final int w = DrawerPaint.W, h = DrawerPaint.H, side = DrawerPaint.side(kind);
        Hyb.beginBatch();
        if (lit) DrawerPaint.shape(kind, 0, 0, w, h, 2, 0xC0000000 | Hyb.LIT & 0xFFFFFF, 1);
        DrawerPaint.frame(kind, DrawerPaint.edge(kind));
        Hyb.endBatch();
        DrawerPaint.name(kind, b.label(), 5 + side, w - 5 - side);
        DrawerPaint.icon(kind, b.item(), b.fluid(), b.power(), 0);
        final double shown = b.power() ? b.rate() / 20 : b.rate() * unit.perSecond;
        final String suffix = b.power() ? " EU/t" : (b.fluid() != null ? " L" : "") + unit.suffix;
        DrawerPaint.rate(
            kind,
            DrawerPaint.sign(kind, b.rate()) + Fmt.compact(shown),
            suffix,
            DrawerPaint.rateInk(kind, b.rate()),
            false,
            null);
    }
}

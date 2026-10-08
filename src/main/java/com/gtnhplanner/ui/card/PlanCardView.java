package com.gtnhplanner.ui.card;

import org.lwjgl.opengl.GL11;

import com.gtnhplanner.ui.theme.Fmt;
import com.gtnhplanner.ui.theme.Hyb;
import com.gtnhplanner.ui.world.PlanSnapshot;

/**
 * A plan's drawer as the board draws it zoomed in, for the minimap: the frame in its kind's colour, its name, the
 * resource and its rate, with nothing to press. Cards are {@link CleanCardView}.
 */
public final class PlanCardView {

    private PlanCardView() {}

    /**
     * A drawer as the board draws it zoomed in, without its keys or rule: the frame in its kind's colour, its name in
     * the title bar, the resource and what the plan moves through it. {@code lit} rings it in the highlight.
     */
    public static void drawer(final PlanSnapshot.Box b, final Fmt.RateUnit unit, final boolean lit) {
        final int w = com.gtnhplanner.ui.drawer.DrawerCard.W, h = com.gtnhplanner.ui.drawer.DrawerCard.H;
        final float r = b.source() ? 10 : 3;
        final int tint = b.tint();
        if (lit) Hyb.roundRect(-2, -2, w + 4, h + 4, r + 2, 0xC0000000 | Hyb.LIT & 0xFFFFFF);
        Hyb.roundRect(0, 0, w, h, r, Hyb.mix(tint, 0x262B34, 0.55f));
        Hyb.roundRect(2, 2, w - 4, h - 4, Math.max(0, r - 2), Hyb.mix(tint, 0x101318, 0.24f));
        Hyb.roundRect(2, 2, w - 4, 17, Math.max(0, r - 2), Hyb.mix(tint, 0x0B0D10, 0.30f), true, false);
        Hyb.rect(2, 19, w - 4, 1, 0x80000000);
        Hyb.text(Hyb.fit(b.label(), w - 12), 6, 6, 0xFFFFFFFF);
        if (b.power()) RecipeCard.euIcon(7, 23, 24);
        else Hyb.icon(b.item(), b.fluid(), 7, 23, 24, 0);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        final double shown = b.power() ? b.rate() / 20 : b.rate() * unit.perSecond;
        final String number = (b.rate() <= 0 ? "" : b.source() ? "-" : "+") + Fmt.compact(shown);
        final String suffix = b.power() ? " EU/t" : (b.fluid() != null ? " L" : "") + unit.suffix;
        final int colour = b.rate() <= 0 ? 0xFFA8AFBB : b.source() ? Hyb.SOURCE_INK : Hyb.PRODUCT_INK;
        final int textX = 37, room = w - 6 - textX;
        if (Hyb.width(number) * Hyb.FIGURE + Hyb.width(suffix) + 2 <= room) {
            Hyb.text(number, textX, 25, Hyb.FIGURE, colour);
            Hyb.text(suffix, textX + Hyb.width(number) * Hyb.FIGURE + 2, 28.5f, Hyb.MUTED);
        } else Hyb.text(Hyb.fit(number + suffix, room), textX, 27, colour);
    }
}

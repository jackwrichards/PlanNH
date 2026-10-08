package com.gtnhplanner.ui.card;

import java.util.List;

import org.lwjgl.opengl.GL11;

import com.gtnhplanner.ui.theme.Fmt;
import com.gtnhplanner.ui.theme.Hyb;
import com.gtnhplanner.ui.world.PlanSnapshot;

/**
 * A plan card as the board draws it, for the plan overlaid on the world: the same frame, name bar and tier chips, port
 * tiles with the plan's rates, machine picture, and POWER and MACHINES tiles, with nothing to press. Far away it is the
 * board's zoomed-out card: the machine big on a tile of its colour, with the count. In card units, at the origin.
 */
public final class PlanCardView {

    private PlanCardView() {}

    private static final int W = CardLayout.W, PAD = CardLayout.PAD, RIGHT = W - PAD;
    private static final int CHIP_W = 48, SINGLE_TIER_W = 42;
    private static final int TILE_W = (W - 2 * PAD - 4) / 2;

    private static int rows(final PlanSnapshot.Card c) {
        return Math.max(
            c.inputs()
                .size(),
            c.outputs()
                .size());
    }

    private static int railsH(final PlanSnapshot.Card c) {
        return Math.max(CardLayout.PICTURE_MIN, rows(c) * CardLayout.ROW);
    }

    private static int footY(final PlanSnapshot.Card c) {
        return CardLayout.RAILS_Y + railsH(c) + 13;
    }

    public static int height(final PlanSnapshot.Card c) {
        return footY(c) + CardLayout.FOOT + PAD;
    }

    public static int width() {
        return W;
    }

    /** The card; {@code ringed} for the one looked at. */
    public static void draw(final PlanSnapshot.Card c, final Fmt.RateUnit unit, final boolean ringed) {
        final int h = height(c);
        Hyb.dropShadow(0, 0, W, h);
        if (ringed) Hyb.ring(-2, -2, W + 4, h + 4, 2, 0xC0000000 | Hyb.LIT & 0xFFFFFF);
        Hyb.cardFrame(0, 0, W, h);
        Hyb.rect(PAD, footY(c) - 7, W - 2 * PAD, 1, 0xFF2A2C31);
        head(c);
        picture(c);
        rail(c.inputs(), CardLayout.IN_RAIL_X, unit);
        rail(c.outputs(), CardLayout.OUT_RAIL_X, unit);
        footer(c);
    }

    /** The name bar, then the amps and tier chips. */
    private static void head(final PlanSnapshot.Card c) {
        final int y = CardLayout.HEAD_Y, hh = CardLayout.HEAD;
        int chips = RIGHT;
        if (!c.tier()
            .isEmpty()) {
            final Hyb.Tier tier = Hyb.tier(c.tier());
            final int tw = c.amps() > 0 ? CHIP_W : SINGLE_TIER_W;
            chips -= tw;
            RecipeCard.chip(chips, y, tw, hh, tier, tier.name(), tier.underline(), false);
            if (c.amps() > 0) {
                chips -= CHIP_W;
                RecipeCard.chip(chips, y, CHIP_W, hh, tier, c.amps() + "A", false, false);
            }
            chips -= 4;
        }
        final int bw = chips - PAD;
        Hyb.bevel(PAD + 1, y + 1, bw - 2, hh - 2, Hyb.NAMEBAR, Hyb.KEY_HI, 0xFF1A1C20, Hyb.SHADOW, 1);
        final String name = Hyb.fit(c.name(), bw - 12);
        Hyb.text(name, RecipeCard.crisp(PAD + (bw - Hyb.width(name)) / 2f), y + 6.5f, 0xFFFFFFFF);
    }

    /** The machine in the card's sunken window. */
    private static void picture(final PlanSnapshot.Card c) {
        final int x = CardLayout.PICTURE_X, y = CardLayout.RAILS_Y, w = CardLayout.PICTURE_W, h = railsH(c);
        Hyb.rect(x, y, w, h, Hyb.TILE_EDGE);
        Hyb.rect(x + 1, y + 1, w - 2, h - 2, Hyb.PICTURE);
        Hyb.rect(x + 1, y + 1, w - 2, 1, 0x4D000000);
        Hyb.rect(x + 1, y + 1, 1, h - 2, 0x4D000000);
        art(c, x + 4, y + 4, w - 8, h - 8, 48, true);
    }

    /** The machine's structure picture fitted in a box, or its item. */
    private static void art(final PlanSnapshot.Card c, final float x, final float y, final float w, final float h,
        final float itemSize, final boolean shadow) {
        final StructureArt.Art art = c.art();
        if (art != null) {
            final float scale = Math.min(w / art.width(), h / art.height());
            final float pw = art.width() * scale, ph = art.height() * scale;
            final float px = x + (w - pw) / 2f, py = y + (h - ph) / 2f;
            if (shadow) {
                final float side = Math.min(pw, ph), pad = StructureArt.SHADOW_PAD * scale;
                Hyb.texture(
                    art.shadow(),
                    px + side * 0.045f - pad,
                    py + side * 0.06f - pad,
                    pw + 2 * pad,
                    ph + 2 * pad,
                    0x99000000);
            }
            Hyb.texture(art.location(), px, py, pw, ph);
        } else if (c.machine() != null) {
            final float side = Math.min(itemSize, Math.min(w, h));
            final float ix = x + (w - side) / 2f, iy = y + (h - side) / 2f;
            if (shadow) Hyb.iconShadow(c.machine(), null, ix, iy, side);
            Hyb.item(c.machine(), ix, iy, side, 0);
        }
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
    }

    /** Port tiles as the card's: the icon, the plan's rate in large type, the name small under it. */
    private static void rail(final List<PlanSnapshot.Flow> ports, final int x, final Fmt.RateUnit unit) {
        for (int i = 0; i < ports.size(); i++) {
            final PlanSnapshot.Flow p = ports.get(i);
            final int y = CardLayout.RAILS_Y + i * CardLayout.ROW;
            Hyb.tile(x, y, CardLayout.RAIL_W, CardLayout.ROW);
            if (p.power()) RecipeCard.euIcon(x + CardLayout.ICON_X, y + CardLayout.ICON_Y, CardLayout.ICON);
            else Hyb.icon(p.item(), p.fluid(), x + CardLayout.ICON_X, y + CardLayout.ICON_Y, CardLayout.ICON, 0);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            CardLayout.portText(
                CardLayout.nameLines(p.name()),
                p.perSecond(),
                p.power(),
                p.fluid() != null,
                unit,
                x + CardLayout.TEXT_X,
                y,
                CardLayout.ROW);
        }
    }

    /** The card's POWER (or what a generator makes) and MACHINES tiles. */
    private static void footer(final PlanSnapshot.Card c) {
        final int y = footY(c);
        int x = PAD;
        Hyb.tile(x, y, TILE_W, CardLayout.FOOT);
        final boolean makes = c.madeEuPerTick() > 0;
        Hyb.text(makes ? "MAKES" : "POWER", x + 4, y + 3, Hyb.MUTED);
        final String eu = Fmt.power(makes ? c.madeEuPerTick() : c.euPerTick());
        Hyb.text(eu, x + 4, y + 14, Hyb.FIGURE, makes ? 0xFFFCD34D : Hyb.INK);
        Hyb.text("EU/t", x + 7 + Hyb.width(eu) * Hyb.FIGURE, y + 17.5f, Hyb.MUTED);
        x += TILE_W + 4;
        Hyb.tile(x, y, TILE_W, CardLayout.FOOT);
        Hyb.text("MACHINES", x + 4, y + 3, Hyb.MUTED);
        Hyb.text(
            "×" + Fmt.machines(c.machines()),
            x + 4,
            y + 14,
            Hyb.FIGURE,
            c.pinned() ? Hyb.GOLD : c.machines() <= 0 ? Hyb.MUTED : Hyb.INK);
    }

    /**
     * The zoomed-out card, as the board draws one past its glance zoom: the machine big on a tile of its colour, and
     * how many in a dark pill in the corner. {@code zoom} is the drawing scale, for a rim and pill that stay sharp.
     */
    public static void glance(final PlanSnapshot.Card c, final float zoom, final boolean ringed) {
        final int h = height(c);
        final int tint = c.tint() < 0 ? 0x8A93A6 : c.tint();
        final float rim = Math.max(2, 1 / zoom);
        Hyb.dropShadow(0, 0, W, h);
        if (ringed) Hyb.ring(-2 * rim, -2 * rim, W + 4 * rim, h + 4 * rim, rim, 0xC0000000 | Hyb.LIT & 0xFFFFFF);
        Hyb.rect(0, 0, W, h, Hyb.mix(tint, 0x262B34, 0.55f));
        Hyb.rect(rim, rim, W - 2 * rim, h - 2 * rim, Hyb.mix(tint, 0x07090C, 0.26f));
        final float side = Math.min(W, h) - 2 * rim - 12;
        art(c, (W - side) / 2f, (h - side) / 2f, side, side, side, true);
        final String count = "×" + Fmt.machines(c.machines());
        float cs = 1 / zoom;
        if (cs > 4) cs /= 2;
        while (cs > 1 && Hyb.width(count) * cs > W * 0.8f) cs /= 2;
        final float pad = cs, tw = Hyb.width(count) * cs, th = 8 * cs;
        final float px = W - rim - 4 - tw - 2 * pad, py = h - rim - 4 - th - 2 * pad;
        Hyb.rect(px, py, tw + 2 * pad, th + 2 * pad, 0xC0101114);
        Hyb.text(count, px + pad, py + pad, cs, c.pinned() ? Hyb.GOLD : Hyb.INK);
    }

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

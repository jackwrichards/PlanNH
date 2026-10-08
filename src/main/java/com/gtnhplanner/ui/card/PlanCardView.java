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
        if (ringed) Hyb.ring(-3, -3, W + 6, h + 6, 3, Hyb.SELECTION);
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

    /** Port tiles as the card's: the icon, the name on one or two lines, the plan's rate under it. */
    private static void rail(final List<PlanSnapshot.Flow> ports, final int x, final Fmt.RateUnit unit) {
        for (int i = 0; i < ports.size(); i++) {
            final PlanSnapshot.Flow p = ports.get(i);
            final int y = CardLayout.RAILS_Y + i * CardLayout.ROW;
            Hyb.tile(x, y, CardLayout.RAIL_W, CardLayout.ROW);
            if (p.power()) RecipeCard.euIcon(x + CardLayout.ICON_X, y + CardLayout.ICON_Y, CardLayout.ICON);
            else Hyb.icon(p.item(), p.fluid(), x + CardLayout.ICON_X, y + CardLayout.ICON_Y, CardLayout.ICON, 0);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            final List<String> name = CardLayout.nameLines(p.name());
            final float textX = x + CardLayout.TEXT_X;
            final float top = RecipeCard.crisp(y + (CardLayout.ROW - ((name.size() + 1) * 9 - 1)) / 2f);
            for (int line = 0; line < name.size(); line++) Hyb.text(name.get(line), textX, top + line * 9, Hyb.INK);
            final String rate = p.power() ? Fmt.power(p.perSecond() / 20) + " EU/t"
                : Fmt.rate(p.perSecond(), unit, p.fluid() != null);
            Hyb.text(Hyb.fit(rate, CardLayout.TEXT_W), textX, top + name.size() * 9, Hyb.MUTED);
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
        if (ringed) Hyb.ring(-3 * rim, -3 * rim, W + 6 * rim, h + 6 * rim, 2 * rim, Hyb.SELECTION);
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
}

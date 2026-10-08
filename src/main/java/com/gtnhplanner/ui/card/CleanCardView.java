package com.gtnhplanner.ui.card;

import java.util.List;

import org.lwjgl.opengl.GL11;

import com.gtnhplanner.ui.theme.Fmt;
import com.gtnhplanner.ui.theme.Hyb;
import com.gtnhplanner.ui.world.PlanSnapshot;

/**
 * A trial of a cleaner card, drawn from a {@link PlanSnapshot} card to compare with today's ({@link PlanCardView}):
 * one surface and no boxes inside it. The name large on the left of the header with the amps and tier chips on the
 * right; inputs against the left edge and outputs against the right, where their wires meet the card, each its icon,
 * how much in large type and its name small under it, power being one more of them with a bolt; the machine in the
 * middle with how many in a badge on its corner. In card units, at the origin.
 */
public final class CleanCardView {

    private CleanCardView() {}

    private static final int W = CardLayout.W;
    /** The header, a port row, the side margin, and the icon. */
    private static final int HEAD = 32, ROW = 32, EDGE = 8, ICON = 24;
    /** The header's chips, as the board's, as far from the top as from the right edge. */
    private static final int CHIP_H = 20, CHIP_W = 48, SINGLE_TIER_W = 42, CHIP_MARGIN = (HEAD - CHIP_H) / 2;
    /** The middle column: the machine, its count on its corner. */
    private static final int MID_X = 112, MID_W = W - 2 * MID_X, PICTURE = 72;
    /** Where a port's words start, beside its icon; and how wide they may be. */
    private static final int TEXT_GAP = 6, TEXT_W = MID_X - EDGE - ICON - TEXT_GAP - 6;

    private static final int SURFACE = 0xFF23252A, EDGE_LINE = 0xFF34363C, HAIR = 0xFF2C2E34;

    /**
     * A side's ports, power being one more: what the machine draws after its inputs, what it makes after its outputs.
     */
    private static List<PlanSnapshot.Flow> side(final PlanSnapshot.Card c, final boolean output) {
        final List<PlanSnapshot.Flow> ports = new java.util.ArrayList<>(output ? c.outputs() : c.inputs());
        final double eu = output ? c.madeEuPerTick() : c.euPerTick();
        if (eu <= 0) return ports;
        for (final PlanSnapshot.Flow p : ports) if (p.power()) return ports;
        ports.add(new PlanSnapshot.Flow("Power", null, null, true, eu * 20, "power:eu"));
        return ports;
    }

    /** The body under the header: as tall as the longer side, or the machine. */
    private static int body(final PlanSnapshot.Card c) {
        return Math.max(PICTURE, Math.max(side(c, false).size(), side(c, true).size()) * ROW);
    }

    public static int height(final PlanSnapshot.Card c) {
        return HEAD + 6 + body(c) + 6;
    }

    public static int width() {
        return W;
    }

    /** The card; {@code ringed} for the one looked at. */
    public static void draw(final PlanSnapshot.Card c, final Fmt.RateUnit unit, final boolean ringed) {
        final int h = height(c);
        Hyb.dropShadow(0, 0, W, h);
        if (ringed) Hyb.ring(-2, -2, W + 4, h + 4, 2, 0xC0000000 | Hyb.LIT & 0xFFFFFF);
        Hyb.rect(0, 0, W, h, EDGE_LINE);
        Hyb.rect(1, 1, W - 2, h - 2, SURFACE);
        head(c);
        final int top = HEAD + 6;
        middle(c, top);
        ports(side(c, false), false, top, unit);
        ports(side(c, true), true, top, unit);
    }

    /** The name large, left; the amps and tier chips, as the board's a little heavier, right; a hairline under them. */
    private static void head(final PlanSnapshot.Card c) {
        final int y = CHIP_MARGIN;
        int right = W - CHIP_MARGIN;
        if (!c.tier()
            .isEmpty()) {
            final Hyb.Tier tier = Hyb.tier(c.tier());
            final int tw = c.amps() > 0 ? CHIP_W : SINGLE_TIER_W;
            right -= tw;
            RecipeCard.chip(right, y, tw, CHIP_H, tier, tier.name(), tier.underline(), false, true);
            if (c.amps() > 0) {
                right -= CHIP_W + 2;
                RecipeCard.chip(right, y, CHIP_W, CHIP_H, tier, c.amps() + "A", false, false, true);
            }
            right -= 8;
        }
        final float big = Hyb.FIGURE;
        final String name = Hyb.fit(c.name(), (int) ((right - EDGE) / big));
        Hyb.text(name, EDGE, y + (CHIP_H - 8 * big) / 2f, big, Hyb.INK);
        Hyb.rect(1, HEAD, W - 2, 1, HAIR);
    }

    /** The machine, and how many in white on its bottom right corner. */
    private static void middle(final PlanSnapshot.Card c, final int top) {
        final int x = MID_X + (MID_W - PICTURE) / 2;
        art(c, x, top, PICTURE, PICTURE);
        final String count = "×" + Fmt.machines(c.machines());
        final float s = Hyb.FIGURE, tw = Hyb.width(count) * s, th = 8 * s;
        Hyb.text(count, x + PICTURE - tw - 2, top + PICTURE - th - 2, s, c.machines() <= 0 ? Hyb.MUTED : Hyb.INK);
    }

    /** The machine's structure picture fitted in a box, or its item. */
    private static void art(final PlanSnapshot.Card c, final float x, final float y, final float w, final float h) {
        final StructureArt.Art art = c.art();
        if (art != null) {
            final float scale = Math.min(w / art.width(), h / art.height());
            final float pw = art.width() * scale, ph = art.height() * scale;
            final float px = x + (w - pw) / 2f, py = y + (h - ph) / 2f;
            final float side = Math.min(pw, ph), pad = StructureArt.SHADOW_PAD * scale;
            Hyb.texture(
                art.shadow(),
                px + side * 0.045f - pad,
                py + side * 0.06f - pad,
                pw + 2 * pad,
                ph + 2 * pad,
                0x7A000000);
            Hyb.texture(art.location(), px, py, pw, ph);
        } else if (c.machine() != null) {
            final float side = Math.min(48, Math.min(w, h));
            final float ix = x + (w - side) / 2f, iy = y + (h - side) / 2f;
            Hyb.iconShadow(c.machine(), null, ix, iy, side);
            Hyb.item(c.machine(), ix, iy, side, 0);
        }
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
    }

    /**
     * A side's ports: inputs against the left edge, outputs mirrored against the right, each its icon at the edge, how
     * much in large type beside it and its name small under that; hairlines between them.
     */
    private static void ports(final List<PlanSnapshot.Flow> ports, final boolean output, final int top,
        final Fmt.RateUnit unit) {
        for (int i = 0; i < ports.size(); i++) {
            final PlanSnapshot.Flow p = ports.get(i);
            final int y = top + i * ROW;
            if (i > 0) Hyb.rect(output ? MID_X + MID_W + 4 : EDGE, y - 1, MID_X - EDGE - 4, 1, HAIR);
            final int ix = output ? W - EDGE - ICON : EDGE, iy = y + (ROW - ICON) / 2 - 1;
            if (p.power()) RecipeCard.euIcon(ix, iy, ICON);
            else Hyb.icon(p.item(), p.fluid(), ix, iy, ICON, 0);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            words(p, output, output ? ix - TEXT_GAP : ix + ICON + TEXT_GAP, y, unit);
        }
    }

    /** How much in large type with its unit small beside it, the name small under it; right-aligned for an output. */
    private static void words(final PlanSnapshot.Flow p, final boolean output, final float edge, final int y,
        final Fmt.RateUnit unit) {
        final String number = p.power() ? Fmt.power(p.perSecond() / 20) : Fmt.compact(p.perSecond() * unit.perSecond);
        final String suffix = p.power() ? " EU/t" : (p.fluid() != null ? " L" : "") + unit.suffix;
        final int colour = p.perSecond() <= 0 ? 0xFFA8AFBB : Hyb.INK;
        final float rateW = Hyb.width(number) * Hyb.FIGURE + 2 + Hyb.width(suffix);
        final boolean big = rateW <= TEXT_W;
        final float top = y + (ROW - (big ? 12 : 8) - 2 - 8) / 2f - 1;
        final float x0 = output ? edge - (big ? rateW : Math.min(TEXT_W, Hyb.width(number + suffix))) : edge;
        if (big) {
            Hyb.text(number, x0, top, Hyb.FIGURE, colour);
            Hyb.text(suffix, x0 + Hyb.width(number) * Hyb.FIGURE + 2, top + 3.5f, Hyb.MUTED);
        } else Hyb.text(Hyb.fit(number + suffix, TEXT_W), x0, top, colour);
        // The name on one line: smaller when long, then cut.
        final String name = p.name() == null ? "" : p.name();
        final float size = Hyb.width(name) <= TEXT_W ? 1 : 0.75f;
        final String shown = Hyb.width(name) * size <= TEXT_W ? name : Hyb.fit(name, (int) (TEXT_W / size));
        final float nw = Hyb.width(shown) * size, ny = top + (big ? 12 : 8) + 2;
        Hyb.text(shown, output ? edge - nw : edge, ny, size, Hyb.MUTED);
    }
}

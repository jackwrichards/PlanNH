package com.gtnhplanner.ui.card;

import java.util.ArrayList;
import java.util.List;

import org.lwjgl.opengl.GL11;

import com.gtnhplanner.ui.PlannerSettings;
import com.gtnhplanner.ui.theme.Fmt;
import com.gtnhplanner.ui.theme.Hyb;
import com.gtnhplanner.ui.world.PlanSnapshot;

/**
 * A plan card drawn from a {@link PlanSnapshot} card, for the cards over the world and on the minimap: the board's
 * clean card ({@link CardLayout}, {@link CardPaint}) with nothing to press. The name large on the left of the header
 * with the amps and tier chips on the right; inputs against the left edge and outputs against the right; power one more
 * row after the inputs, the circuit too when set so (else on the picture's corner); the machine in the middle with how
 * many on its corner; the settings strip along the bottom. In card units, at the origin.
 */
public final class CleanCardView {

    private CleanCardView() {}

    private static final int W = CardLayout.W, ROW = CardLayout.ROW;
    private static final int CHIP_W = 48, SINGLE_TIER_W = 42;

    /** A row of the input side: a flow, the circuit, or the power drawn. */
    private record Row(PlanSnapshot.Flow flow, boolean circuit) {}

    private static List<Row> inputs(final PlanSnapshot.Card c) {
        final List<Row> rows = new ArrayList<>();
        for (final PlanSnapshot.Flow f : c.inputs()) rows.add(new Row(f, false));
        if (c.circuit() != null && PlannerSettings.circuitAsInput()) rows.add(new Row(null, true));
        boolean power = false;
        for (final PlanSnapshot.Flow f : c.inputs()) power |= f.power();
        if (!power && c.euPerTick() > 0)
            rows.add(new Row(new PlanSnapshot.Flow("Power", null, null, true, c.euPerTick() * 20, "power:eu"), false));
        return rows;
    }

    private static int body(final PlanSnapshot.Card c) {
        return Math.max(
            CardLayout.PICTURE_MIN,
            Math.max(
                inputs(c).size(),
                c.outputs()
                    .size())
                * ROW);
    }

    /** The settings strip's chips, each {x, y, w}. */
    private static List<int[]> strip(final PlanSnapshot.Card c, final int top) {
        final List<int[]> at = new ArrayList<>();
        int x = CardLayout.PAD, y = top + 4;
        for (final PlanSnapshot.Setting s : c.settings()) {
            final int w = CardLayout.chipW(s.label(), s.value(), s.icon() != null);
            if (x > CardLayout.PAD && x + w > W - CardLayout.PAD) {
                x = CardLayout.PAD;
                y += CardLayout.CHIP_ROW;
            }
            at.add(new int[] { x, y, w });
            x += w + 4;
        }
        return at;
    }

    public static int height(final PlanSnapshot.Card c) {
        final int stripY = CardLayout.RAILS_Y + body(c) + 6;
        final List<int[]> chips = strip(c, stripY);
        final int end = chips.isEmpty() ? CardLayout.RAILS_Y + body(c) + 2
            : chips.get(chips.size() - 1)[1] + CardLayout.CHIP + 4;
        return end + 6;
    }

    public static int width() {
        return W;
    }

    /** The card; {@code ringed} for the one looked at. */
    public static void draw(final PlanSnapshot.Card c, final Fmt.RateUnit unit, final boolean ringed) {
        final int h = height(c);
        Hyb.dropShadow(0, 0, W, h);
        if (ringed) Hyb.ring(-2, -2, W + 4, h + 4, 2, 0xC0000000 | Hyb.LIT & 0xFFFFFF);
        CardPaint.surface(W, h);
        head(c);
        CardPaint.hair(CardLayout.HEADER);
        final int top = CardLayout.RAILS_Y;
        final int[] picture = { CardLayout.PICTURE_X + (CardLayout.PICTURE_W - CardLayout.PICTURE) / 2, top,
            CardLayout.PICTURE };
        art(c, picture);
        if (c.circuit() != null && !PlannerSettings.circuitAsInput()) CardPaint.circuitBadge(c.circuit(), picture, 0);
        CardPaint.count("×" + Fmt.machines(c.machines()), c.machines() <= 0, c.pinned(), false, picture);
        final List<Row> ins = inputs(c);
        for (int i = 0; i < ins.size(); i++) {
            final int y = top + i * ROW;
            if (i > 0) CardPaint.rowSeparator(false, y);
            final Row r = ins.get(i);
            if (r.circuit()) CardPaint.circuitRow(c.circuit(), y, 0);
            else flow(r.flow(), false, y, unit);
        }
        for (int i = 0; i < c.outputs()
            .size(); i++) {
            final int y = top + i * ROW;
            if (i > 0) CardPaint.rowSeparator(true, y);
            flow(
                c.outputs()
                    .get(i),
                true,
                y,
                unit);
        }
        final int stripY = top + body(c) + 6;
        final List<int[]> chips = strip(c, stripY);
        if (!chips.isEmpty()) CardPaint.hair(stripY);
        for (int i = 0; i < chips.size(); i++) {
            final PlanSnapshot.Setting s = c.settings()
                .get(i);
            final int[] at = chips.get(i);
            CardPaint.chip(at[0], at[1], at[2], s.label(), s.value(), s.icon(), s.warn(), s.reading(), false, 0);
        }
    }

    private static void flow(final PlanSnapshot.Flow f, final boolean output, final int y, final Fmt.RateUnit unit) {
        CardPaint.icon(f.item(), f.fluid(), f.power(), output, y, 0);
        CardPaint.flowWords(f.perSecond(), f.power(), f.fluid() != null, f.name(), unit, output, y);
    }

    /** The name large, left; the amps and tier chips, as the board's, right. */
    private static void head(final PlanSnapshot.Card c) {
        final int y = CardLayout.HEAD_Y;
        int right = W - CardLayout.HEAD_Y;
        if (!c.tier()
            .isEmpty()) {
            final Hyb.Tier tier = Hyb.tier(c.tier());
            final int tw = c.amps() > 0 ? CHIP_W : SINGLE_TIER_W;
            right -= tw;
            RecipeCard.chip(right, y, tw, CardLayout.HEAD, tier, tier.name(), tier.underline(), false, true);
            if (c.amps() > 0) {
                right -= CHIP_W + 2;
                RecipeCard.chip(right, y, CHIP_W, CardLayout.HEAD, tier, c.amps() + "A", false, false, true);
            }
            right -= 8;
        }
        CardPaint.name(c.name(), 8, right, Hyb.INK);
    }

    /** The machine's structure picture fitted in its box, or its item. */
    private static void art(final PlanSnapshot.Card c, final int[] box) {
        final float x = box[0], y = box[1], side = box[2];
        final StructureArt.Art art = c.art();
        if (art != null) {
            final float scale = Math.min(side / art.width(), side / art.height());
            final float pw = art.width() * scale, ph = art.height() * scale;
            final float px = x + (side - pw) / 2f, py = y + (side - ph) / 2f;
            final float s = Math.min(pw, ph), pad = StructureArt.SHADOW_PAD * scale;
            Hyb.texture(
                art.shadow(),
                px + s * 0.045f - pad,
                py + s * 0.06f - pad,
                pw + 2 * pad,
                ph + 2 * pad,
                0x7A000000);
            Hyb.texture(art.location(), px, py, pw, ph);
        } else if (c.machine() != null) {
            final float s = Math.min(CardLayout.ITEM, side);
            final float ix = x + (side - s) / 2f, iy = y + Math.min((side - s) / 2f, 4);
            Hyb.iconShadow(c.machine(), null, ix, iy, s);
            Hyb.item(c.machine(), ix, iy, s, 0);
        }
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
    }
}

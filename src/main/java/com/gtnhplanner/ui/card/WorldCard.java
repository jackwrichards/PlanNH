package com.gtnhplanner.ui.card;

import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import org.lwjgl.opengl.GL11;

import com.gtnhplanner.ui.theme.Fmt;
import com.gtnhplanner.ui.theme.Hyb;

/**
 * A machine in the world for the AR lens, laid out as the recipe card with nothing to press: the name bar with the
 * amps and tier chips, what goes in down the left and comes out down the right as the card's port tiles, the machine
 * in the middle with its recipe's progress under it, and the card's POWER tile (what it draws now, of what it can take)
 * beside a status tile. In card units ({@link CardLayout} geometry), drawn at the origin.
 */
public final class WorldCard {

    private WorldCard() {}

    /** A port tile: the rate now, or idle when the machine is not running its recipe. */
    public record Port(String name, @Nullable ItemStack item, @Nullable FluidStack fluid, double perSecond,
        boolean live) {}

    /**
     * What a world card shows.
     *
     * @param tier         the voltage it takes, e.g. "MV", or empty
     * @param amps         the amps it takes, 0 when unknown or a single block
     * @param state        its status word ("Running", "Idle", "Stopped", "Off") and colour
     * @param progress     0 to 1 through the recipe, or -1 when not running or unknown
     * @param planMachines the linked card's machine count, or NaN when not linked
     */
    public record Data(String machineName, @Nullable ItemStack machine, String tier, long amps, String state,
        int stateInk, String detail, float progress, int ticksLeft, long euPerTick, long maxEuPerTick,
        List<Port> inputs, List<Port> outputs, double planMachines, boolean planPinned) {

        boolean linked() {
            return !Double.isNaN(planMachines);
        }
    }

    public static final int W = CardLayout.W;
    private static final int PAD = CardLayout.PAD, RIGHT = W - PAD;
    private static final int HEAD_Y = CardLayout.HEAD_Y, HEAD = CardLayout.HEAD;
    private static final int CHIP_W = 48, SINGLE_TIER_W = 42;
    private static final int ROW = CardLayout.ROW, RAIL_W = CardLayout.RAIL_W;
    private static final int TILE_W = (W - 2 * PAD - 4) / 2;

    private static int rows(final Data d) {
        return Math.max(d.inputs.size(), d.outputs.size());
    }

    private static int railsH(final Data d) {
        return Math.max(CardLayout.PICTURE_MIN, rows(d) * ROW);
    }

    private static int footY(final Data d) {
        return CardLayout.RAILS_Y + railsH(d) + 13;
    }

    public static int height(final Data d) {
        return footY(d) + CardLayout.FOOT + PAD;
    }

    public static void draw(final Data d, final boolean focused) {
        final int h = height(d);
        Hyb.dropShadow(0, 0, W, h);
        if (focused) Hyb.ring(0, 0, W, h, 2, Hyb.SELECTION);
        Hyb.cardFrame(0, 0, W, h);
        Hyb.rect(PAD, footY(d) - 7, W - 2 * PAD, 1, 0xFF2A2C31);
        head(d);
        final boolean rails = rows(d) > 0;
        picture(d, rails);
        if (rails) {
            rail(d.inputs, CardLayout.IN_RAIL_X);
            rail(d.outputs, CardLayout.OUT_RAIL_X);
        }
        footer(d);
    }

    /** The name bar, then the amps and tier chips as the card's. */
    private static void head(final Data d) {
        final int y = HEAD_Y;
        int chipsX = RIGHT;
        if (!d.tier.isEmpty()) {
            final Hyb.Tier tier = Hyb.tier(d.tier);
            final int tw = d.amps > 0 ? CHIP_W : SINGLE_TIER_W;
            chipsX -= tw;
            RecipeCard.chip(chipsX, y, tw, HEAD, tier, tier.name(), tier.underline(), false);
            if (d.amps > 0) {
                chipsX -= CHIP_W;
                RecipeCard.chip(chipsX, y, CHIP_W, HEAD, tier, d.amps + "A", false, false);
            }
            chipsX -= 4;
        }
        final int bw = chipsX - PAD;
        Hyb.bevel(PAD + 1, y + 1, bw - 2, HEAD - 2, Hyb.NAMEBAR, Hyb.KEY_HI, 0xFF1A1C20, Hyb.SHADOW, 1);
        final String name = Hyb.fit(d.machineName, bw - 12);
        Hyb.text(name, RecipeCard.crisp(PAD + (bw - Hyb.width(name)) / 2f), y + 6.5f, 0xFFFFFFFF);
    }

    /** The machine in the card's sunken window, its recipe's progress along the bottom, and why it is stopped. */
    private static void picture(final Data d, final boolean rails) {
        final int x = rails ? CardLayout.PICTURE_X : PAD, w = rails ? CardLayout.PICTURE_W : W - 2 * PAD;
        final int y = CardLayout.RAILS_Y, h = railsH(d);
        Hyb.rect(x, y, w, h, Hyb.TILE_EDGE);
        Hyb.rect(x + 1, y + 1, w - 2, h - 2, Hyb.PICTURE);
        Hyb.rect(x + 1, y + 1, w - 2, 1, 0x4D000000);
        Hyb.rect(x + 1, y + 1, 1, h - 2, 0x4D000000);
        Hyb.rect(x + 1, y + h - 2, w - 2, 1, 0x0AFFFFFF);
        Hyb.rect(x + w - 2, y + 1, 1, h - 2, 0x0AFFFFFF);
        RecipeCard.drawMachineArt(d.machineName, d.machine, x + 4, y + 4, w - 8, h - 8, 48, 0, true);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        if (!d.detail.isEmpty()) {
            final String why = Hyb.fit(d.detail, w - 8);
            Hyb.rect(x + 1, y + h - 14, w - 2, 12, 0xD0101114);
            Hyb.text(why, RecipeCard.crisp(x + (w - Hyb.width(why)) / 2f), y + h - 12, d.stateInk);
        }
        if (d.progress >= 0) {
            Hyb.rect(x + 1, y + h - 3, w - 2, 2, 0xFF1A1C20);
            Hyb.rect(x + 1, y + h - 3, (w - 2) * d.progress, 2, d.stateInk);
        }
    }

    /** The card's port tiles: the icon, the name on one or two lines, the rate under it. */
    private static void rail(final List<Port> ports, final int x) {
        for (int i = 0; i < ports.size(); i++) {
            final Port p = ports.get(i);
            final int y = CardLayout.RAILS_Y + i * ROW;
            Hyb.tile(x, y, RAIL_W, ROW);
            Hyb.icon(p.item, p.fluid, x + CardLayout.ICON_X, y + CardLayout.ICON_Y, CardLayout.ICON, 0);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            final List<String> name = CardLayout.nameLines(p.name);
            final float textX = x + CardLayout.TEXT_X;
            final float top = RecipeCard.crisp(y + (ROW - ((name.size() + 1) * 9 - 1)) / 2f);
            for (int line = 0; line < name.size(); line++) Hyb.text(name.get(line), textX, top + line * 9, Hyb.INK);
            final String rate = p.live ? Fmt.rate(p.perSecond, Fmt.RateUnit.SECOND, p.fluid != null) : "idle";
            Hyb.text(Hyb.fit(rate, CardLayout.TEXT_W), textX, top + name.size() * 9, Hyb.MUTED);
        }
    }

    /** The card's POWER tile (drawing now, of what it can take), and the status beside it. */
    private static void footer(final Data d) {
        final int y = footY(d);
        int x = PAD;
        Hyb.tile(x, y, TILE_W, CardLayout.FOOT);
        Hyb.text("POWER", x + 4, y + 3, Hyb.MUTED);
        if (d.maxEuPerTick > 0) Hyb.textRight("MAX " + Fmt.power(d.maxEuPerTick), x + TILE_W - 4, y + 3, Hyb.MUTED);
        final String eu = d.progress >= 0 ? Fmt.power(d.euPerTick) : "0";
        Hyb.text(eu, x + 4, y + 14, Hyb.FIGURE, Hyb.INK);
        Hyb.text("EU/t", x + 7 + Hyb.width(eu) * Hyb.FIGURE, y + 17.5f, Hyb.MUTED);
        x += TILE_W + 4;
        Hyb.tile(x, y, TILE_W, CardLayout.FOOT);
        Hyb.text("STATUS", x + 4, y + 3, Hyb.MUTED);
        if (d.linked()) Hyb.textRight(
            "PLAN ×" + Fmt.machines(d.planMachines),
            x + TILE_W - 4,
            y + 3,
            d.planPinned ? Hyb.GOLD : Hyb.MUTED);
        if (d.progress >= 0) {
            final String pct = Math.round(d.progress * 100) + "%";
            Hyb.text(pct, x + 4, y + 14, Hyb.FIGURE, d.stateInk);
            if (d.ticksLeft > 0)
                Hyb.text(seconds(d.ticksLeft), x + 7 + Hyb.width(pct) * Hyb.FIGURE, y + 17.5f, Hyb.MUTED);
        } else Hyb.text(d.state, x + 4, y + 14, Hyb.FIGURE, d.stateInk);
    }

    private static String seconds(final int ticks) {
        final int s = (ticks + 19) / 20;
        return s < 60 ? s + " s left" : s / 60 + " min " + s % 60 + " s left";
    }

    /**
     * A far machine as the board shows a card zoomed out: a tile in its machine's colour with the machine big in it,
     * a light for its state, its progress along the bottom, and a gold edge when it is linked. {@code size} GUI pixels.
     */
    public static void glance(final String machineName, @Nullable final ItemStack machine, final int stateInk,
        final float progress, final boolean linked, final float size) {
        final StructureArt.Art art = StructureArt.forMachine(machineName);
        int tint = art != null ? art.tint()
            : machine != null ? com.gtnhplanner.client.IngredientColors.itemColor(machine) : -1;
        if (tint < 0) tint = 0x8A93A6;
        Hyb.dropShadow(0, 0, size, size);
        Hyb.rect(0, 0, size, size, linked ? 0xFFB08A2E : Hyb.mix(tint, 0x262B34, 0.55f));
        Hyb.rect(1, 1, size - 2, size - 2, Hyb.mix(tint, 0x07090C, 0.26f));
        RecipeCard.drawMachineArt(machineName, machine, 3, 3, size - 6, size - 6, size - 6, 0, false);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        Hyb.rect(size - 7, 2, 5, 5, 0xFF000000);
        Hyb.rect(size - 6, 3, 3, 3, stateInk);
        if (progress >= 0) {
            Hyb.rect(1, size - 3, size - 2, 2, 0xFF1A1C20);
            Hyb.rect(1, size - 3, (size - 2) * progress, 2, stateInk);
        }
    }
}

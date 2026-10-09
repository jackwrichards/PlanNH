package com.gtnhplanner.ui.card;

import javax.annotation.Nullable;

import net.minecraft.item.ItemStack;

import org.lwjgl.opengl.GL11;

import com.gtnhplanner.ui.theme.Fmt;
import com.gtnhplanner.ui.theme.Hyb;

/**
 * The clean card's pieces, drawn the same by the board's cards ({@link RecipeCard}) and the cards over the world and on
 * the minimap ({@link CleanCardView}), in card units at the origin, with {@link CardLayout}'s geometry.
 */
public final class CardPaint {

    /** The card's one surface, its edge, and the hairlines that part the header and the strip. */
    public static final int SURFACE = 0xFF23252A, EDGE = 0xFF34363C, HAIR = 0xFF2C2E34;
    /** The machine's name: as large as fits, down to plain. */
    private static final float[] NAME_SIZES = { 1.5f, 1.25f, 1f };
    /** How small a port's name may go before it is cut, and how wide the fade is where it is cut. */
    private static final float NAME_SMALL = 0.75f;
    private static final int NAME_FADE = 10;

    private CardPaint() {}

    /** The surface with its edge, {@code w} by {@code h}. */
    public static void surface(final int w, final int h) {
        Hyb.rect(0, 0, w, h, EDGE);
        Hyb.rect(1, 1, w - 2, h - 2, SURFACE);
    }

    /** A hairline across the card. */
    public static void hair(final int y) {
        Hyb.rect(1, y, CardLayout.W - 2, 1, HAIR);
    }

    /**
     * The machine's name from {@code x} to {@code right}, level with the header's chips: large, smaller when long, then
     * cut. Returns where it ends.
     */
    public static float name(final String name, final float x, final float right, final int colour) {
        final float room = right - x;
        for (final float size : NAME_SIZES) {
            if (Hyb.width(name) * size <= room || size == NAME_SIZES[NAME_SIZES.length - 1]) {
                final String shown = Hyb.width(name) * size <= room ? name : Hyb.fit(name, (int) (room / size));
                // Level with the chips' labels: the game's capitals are seven of its eight rows.
                Hyb.text(
                    shown,
                    x,
                    RecipeCard.crisp(CardLayout.HEAD_Y + (CardLayout.HEAD - 7 * size) / 2f),
                    size,
                    colour);
                return x + Hyb.width(shown) * size;
            }
        }
        return x;
    }

    /** A thin line between two rows on one side. */
    public static void rowSeparator(final boolean output, final int y) {
        Hyb.rect(CardLayout.railX(output) + 4, y - 1, CardLayout.RAIL_W - 8, 1, HAIR);
    }

    /**
     * A port's words: how much in large type with its unit small beside it, and the name small under it on one line
     * (smaller when long, then cut and faded into the card), from the edge {@link CardLayout#textEdge} (right aligned
     * for an output), in a row at {@code y}. The number drops to the name's size when it would not fit.
     */
    public static void words(final String number, final String suffix, final String name, final int colour,
        final boolean output, final int y) {
        final int edge = CardLayout.textEdge(output), room = CardLayout.TEXT_W;
        final float rateW = Hyb.width(number) * Hyb.FIGURE + 2 + Hyb.width(suffix);
        final boolean big = rateW <= room;
        final float rateH = big ? 8 * Hyb.FIGURE : 8;
        final String label = name == null ? "" : name;
        final float size = Hyb.width(label) <= room ? 1 : NAME_SMALL;
        final boolean cut = Hyb.width(label) * size > room;
        final String shown = cut ? Hyb.font()
            .trimStringToWidth(label, (int) (room / size)) : label;
        final float top = RecipeCard.crisp(y + (CardLayout.ROW - (rateH + 2 + 8 * size)) / 2f);
        if (big) {
            final float x = output ? edge - rateW : edge;
            Hyb.text(number, x, top, Hyb.FIGURE, colour);
            Hyb.text(suffix, x + Hyb.width(number) * Hyb.FIGURE + 2, top + 3.5f, Hyb.MUTED);
        } else {
            final String both = Hyb.fit(number + suffix, room);
            Hyb.text(both, output ? edge - Hyb.width(both) : edge, top, colour);
        }
        final float nameW = Hyb.width(shown) * size, nameY = top + rateH + 2;
        final float nx = output ? edge - nameW : edge;
        Hyb.text(shown, nx, nameY, size, Hyb.MUTED);
        if (!cut) return;
        // The cut end fades into the card.
        for (int k = 0; k < NAME_FADE; k++) {
            final int a = (int) (255f * (k + 1) / NAME_FADE);
            final float px = nx + nameW - NAME_FADE + k;
            Hyb.rect(px, nameY - 1, 1, 8 * size + 2, a << 24 | SURFACE & 0xFFFFFF);
        }
    }

    /** A flow's words: its rate in the plan's unit (EU/t for power) and its name. */
    public static void flowWords(final double perSecond, final boolean power, final boolean fluid, final String name,
        final Fmt.RateUnit unit, final boolean output, final int y) {
        final String number = power ? Fmt.power(perSecond / 20) : Fmt.compact(perSecond * unit.perSecond);
        final String suffix = power ? " EU/t" : (fluid ? " L" : "") + unit.suffix;
        words(number, suffix, name, perSecond <= 0 ? 0xFFA8AFBB : Hyb.INK, output, y);
    }

    /** A port's icon at the card's edge (a bolt for power). */
    public static void icon(@Nullable final ItemStack item, @Nullable final net.minecraftforge.fluids.FluidStack fluid,
        final boolean power, final boolean output, final int y, final float z) {
        final int x = CardLayout.iconX(output), iy = y + CardLayout.ICON_Y;
        if (power) RecipeCard.euIcon(x, iy, CardLayout.ICON);
        else Hyb.icon(item, fluid, x, iy, CardLayout.ICON, z);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
    }

    /** The circuit's row among the inputs: its icon, its number large, "Circuit" small. */
    public static void circuitRow(final ItemStack circuit, final int y, final float z) {
        com.gtnhplanner.ui.gt.CircuitIcons
            .draw(circuit, CardLayout.iconX(false), y + CardLayout.ICON_Y, CardLayout.ICON, z);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        words(String.valueOf(circuit.getItemDamage()), "", "Circuit", Hyb.INK, false, y);
    }

    /** The circuit on the picture's bottom left corner, large. */
    public static void circuitBadge(final ItemStack circuit, final int[] picture, final float z) {
        com.gtnhplanner.ui.gt.CircuitIcons.draw(circuit, picture[0], picture[1] + picture[2] - 24, 24, z);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
    }

    /** Under a pinned count, centred, in grey. */
    public static final String PINNED = "(pinned)";
    /** Under a count the plan waits for (nothing set anywhere): what to do, pulsing gold with the drawers' Set rate. */
    public static final String SET_COUNT = "(set count)";

    /**
     * Card-local {x, y, w, h} of the machine count on the picture's bottom right corner and the line kept under it (for
     * "(pinned)" or "(set count)"), whatever it says: the count never moves.
     */
    public static int[] countRect(final String count, final int[] picture) {
        final int cw = Math.round(Hyb.width(count) * Hyb.FIGURE);
        final int w = Math.max(cw, Hyb.width(SET_COUNT)) + 4, h = 23;
        return new int[] { picture[0] + picture[2] - w, picture[1] + picture[2] - h, w, h };
    }

    /**
     * How many machines, in white on the picture's bottom right corner, and under it, centred, {@code under}
     * ("(pinned)"
     * in grey, "(set count)" pulsing gold, or nothing) in {@code underColor}; a dotted line under the count when the
     * mouse
     * is on it. The count sits in the same place whatever is under it.
     */
    public static void count(final String count, final boolean none, @Nullable final String under, final int underColor,
        final boolean hover, final int[] picture) {
        final float s = Hyb.FIGURE, tw = Hyb.width(count) * s;
        final float block = Math.max(tw, Hyb.width(SET_COUNT));
        final float right = picture[0] + picture[2] - 2;
        final float cx = right - block / 2f;
        final float y = picture[1] + picture[2] - 8 * s - 2 - 9;
        final float x = cx - tw / 2f;
        Hyb.text(count, x, y, s, none ? Hyb.MUTED : Hyb.INK);
        if (under != null && !under.isEmpty()) Hyb.text(under, cx - Hyb.width(under) / 2f, y + 8 * s + 1, underColor);
        if (hover) for (int dx = 0; dx < tw; dx += 3) Hyb.rect(x + dx, y + 8 * s, 1, 1, Hyb.MUTED);
    }

    /** Gold on the beat the drawers' Set rate pulses on, grey off it: the next thing to do, while nothing is set. */
    public static int prompt() {
        return (System.currentTimeMillis() / 500) % 2 == 0 ? Hyb.GOLD : 0xFF6F737C;
    }

    /**
     * A chip in the settings strip, {@code w} wide: its icon, its label quiet and its value plain; red edged when it
     * stops the recipe. A reading has no edge, only its words.
     */
    public static void chip(final int x, final int y, final int w, final String label, final String value,
        @Nullable final ItemStack icon, final boolean warn, final boolean reading, final boolean hover, final float z) {
        if (!reading) {
            Hyb.rect(x, y, w, CardLayout.CHIP, warn ? 0xFF6B2A2A : hover ? 0xFF4A4D55 : EDGE);
            Hyb.rect(x + 1, y + 1, w - 2, CardLayout.CHIP - 2, hover ? 0xFF33363C : 0xFF2A2C31);
        }
        int tx = x + CardLayout.CHIP_PAD;
        if (icon != null) {
            Hyb.item(icon, tx, y + 2, 14, z);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            tx += 14 + 3;
        }
        Hyb.text(label, tx, y + 5, Hyb.MUTED);
        Hyb.text(value, tx + Hyb.width(label) + 4, y + 5, warn ? Hyb.RED_INK : Hyb.INK);
    }
}

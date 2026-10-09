package com.gtnhplanner.ui.drawer;

import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;

import com.gtnhplanner.data.flowchart.Drawer;
import com.gtnhplanner.ui.card.CardPaint;
import com.gtnhplanner.ui.card.RecipeCard;
import com.gtnhplanner.ui.theme.Hyb;

/**
 * How a drawer looks, on the board and on the minimap: the clean card's surface and 1 px edge, tinted by its kind, in
 * the website's shapes (a source is a rounded tank, a product a crate, a byproduct a shield with its base cut off,
 * trash
 * a bin narrowing to its base); a slim header with its name; and its body, the resource beside what the plan moves
 * through it, as a card's port row has it.
 */
public final class DrawerPaint {

    public static final int W = 116, H = 54;
    /** The header's hairline. */
    public static final int HEADER = 17;
    /** The header's keys: square, this far in from the top and the sides. */
    public static final int KEY = 12, KEY_IN = 3;
    /** The resource, a card port's size, centred on the body. */
    public static final int ICON = 24, ICON_X = 5, ICON_Y = 23;
    /** Where the words start, right of the resource, and the rule's row under the rate. */
    public static final int TEXT_X = 34, ROW_Y = 36, ROW_H = 14, RULE_W = 20;

    // The shapes, in board pixels: the tank's corners, the crate's, the shield's cut and how far the bin narrows.
    private static final float TANK = 9, CRATE = 3, SHIELD = 10, BIN = 6;

    /** A rate at nothing, and what trash takes (always grey, as on the website). */
    public static final int IDLE_INK = 0xFFA8AFBB, TRASH_INK = 0xFFB9C0CD;

    private DrawerPaint() {}

    // region Colours

    /** The kind's colour, as the website's: sources red, products and byproducts green, trash steel. */
    public static int tint(final Drawer.Kind kind) {
        return switch (kind) {
            case SOURCE -> Hyb.SOURCE_INK;
            case PRODUCT, BYPRODUCT -> Hyb.PRODUCT_INK;
            case TRASH -> 0xFF8A93A6;
        };
    }

    /** The 1 px outline, the kind's colour over the card's edge. */
    public static int edge(final Drawer.Kind kind) {
        return Hyb.mix(tint(kind), CardPaint.EDGE, 0.55f);
    }

    /** The surface: the card's, a breath of the kind's colour in it. */
    public static int fill(final Drawer.Kind kind) {
        return Hyb.mix(tint(kind), CardPaint.SURFACE, 0.1f);
    }

    /** The header's hairline. */
    public static int hair(final Drawer.Kind kind) {
        return Hyb.mix(tint(kind), CardPaint.HAIR, 0.3f);
    }

    /** What the plan moves through a drawer, in its colour: red out of a source, green into the rest, trash grey. */
    public static int rateInk(final Drawer.Kind kind, final double rate) {
        if (rate <= 0) return IDLE_INK;
        return switch (kind) {
            case SOURCE -> Hyb.SOURCE_INK;
            case PRODUCT, BYPRODUCT -> Hyb.PRODUCT_INK;
            case TRASH -> TRASH_INK;
        };
    }

    /** A rate's sign: taken from a source, put into the rest. */
    public static String sign(final Drawer.Kind kind, final double rate) {
        return rate <= 0 ? "" : kind == Drawer.Kind.SOURCE ? "-" : "+";
    }

    // endregion

    // region The shape

    /**
     * The kind's silhouette over the box {@code x, y, w, h} grown by {@code grow} all round (negative: shrunk), in one
     * colour, its shape's sizes times {@code scale} (1 on the board). Drawn as rows of rectangles in whole-pixel steps,
     * as round corners are everywhere on the board, each row once, so a see-through colour stays even.
     */
    public static void shape(final Drawer.Kind kind, final float x, final float y, final float w, final float h,
        final float grow, final int colour, final float scale) {
        final float bx = x - grow, by = y - grow, bw = w + 2 * grow, bh = h + 2 * grow;
        final int rows = Math.max(0, Math.round(bh));
        if (rows == 0 || bw <= 0) return;
        final boolean own = !Hyb.batching();
        if (own) Hyb.beginBatch();
        // A solid shield or bin as a polygon, so its slanted sides stay smooth zoomed in (triangles draw both ways
        // round, so a see-through one would come out twice as dark: those go by rows).
        if ((colour >>> 24) == 0xFF && (kind == Drawer.Kind.BYPRODUCT || kind == Drawer.Kind.TRASH)) {
            final float[] p;
            if (kind == Drawer.Kind.BYPRODUCT) {
                final float cut = SHIELD * scale + grow * (2 - (float) Math.sqrt(2));
                p = new float[] { bx, by, bx + bw, by, bx + bw, by + bh - cut, bx + bw - cut, by + bh, bx + cut,
                    by + bh, bx, by + bh - cut };
            } else {
                final float lean = BIN * scale * bh / Math.max(1, bh - 2 * grow);
                p = new float[] { bx, by, bx + bw, by, bx + bw - lean, by + bh, bx + lean, by + bh };
            }
            for (int i = 2; i + 3 < p.length; i += 2)
                Hyb.triangle(p[0], p[1], p[i], p[i + 1], p[i + 2], p[i + 3], colour);
            if (own) Hyb.endBatch();
            return;
        }
        int from = 0, inset = in(kind, 0, bh, grow, scale);
        for (int j = 1; j <= rows; j++) {
            final int next = j < rows ? in(kind, j, bh, grow, scale) : -1;
            if (next == inset) continue;
            final float top = by + from, bottom = j < rows ? by + j : by + bh;
            Hyb.rect(bx + inset, top, bw - 2 * inset, bottom - top, colour);
            from = j;
            inset = next;
        }
        if (own) Hyb.endBatch();
    }

    /** How far in from each side the silhouette is on row {@code j} of a box {@code bh} tall. */
    private static int in(final Drawer.Kind kind, final int j, final float bh, final float grow, final float scale) {
        final float yc = j + 0.5f;
        return switch (kind) {
            case SOURCE -> round(TANK * scale + grow, yc, bh);
            case PRODUCT -> round(CRATE * scale + grow, yc, bh);
            case BYPRODUCT -> {
                // Square shoulders; the base's corners cut at 45 degrees.
                final float cut = SHIELD * scale + grow * (2 - (float) Math.sqrt(2)), fromBottom = bh - yc;
                yield fromBottom >= cut ? 0 : Math.round(cut - fromBottom);
            }
            // The sides lean in toward the base.
            case TRASH -> Math.round(BIN * scale * (bh / Math.max(1, bh - 2 * grow)) * yc / bh);
        };
    }

    /** A round corner of radius {@code r}: how far in it is at height {@code yc} of a box {@code bh} tall. */
    private static int round(final float r, final float yc, final float bh) {
        if (r <= 0) return 0;
        final float edge = Math.min(yc, bh - yc);
        if (edge >= r) return 0;
        final float dy = r - edge;
        return Math.round(r - (float) Math.sqrt(Math.max(0, r * r - dy * dy)));
    }

    /** How far in from each side the drawer's own outline is at height {@code y} of the board's drawer. */
    public static int insetAt(final Drawer.Kind kind, final float y) {
        final int j = Math.max(0, Math.min(H - 1, (int) y));
        return in(kind, j, H, 0, 1);
    }

    /** How far the content keeps in from the sides: trash's sides lean in. */
    public static int side(final Drawer.Kind kind) {
        return kind == Drawer.Kind.TRASH ? 2 : 0;
    }

    /** The outline and the surface, the outline in {@code edge}. */
    public static void frame(final Drawer.Kind kind, final int edge) {
        final boolean own = !Hyb.batching();
        if (own) Hyb.beginBatch();
        shape(kind, 0, 0, W, H, 0, edge, 1);
        shape(kind, 0, 0, W, H, -1, fill(kind), 1);
        final int in = insetAt(kind, HEADER) + 1;
        Hyb.rect(in, HEADER, W - 2 * in, 1, hair(kind));
        if (own) Hyb.endBatch();
    }

    /** The shadow the drawer casts, as the cards' (lifted: nearer and darker). */
    public static void shadow(final Drawer.Kind kind, final boolean lifted) {
        final boolean own = !Hyb.batching();
        if (own) Hyb.beginBatch();
        if (lifted) {
            shape(kind, 5, 7, W, H, 0, 0x50000000, 1);
            shape(kind, 3, 4, W, H, 0, 0x40000000, 1);
        } else for (int i = 3; i >= 0; i--) shape(kind, 6 - 2 * i, 8 - 2 * i, W, H, 2 * i, 0x18000000, 1);
        if (own) Hyb.endBatch();
    }

    // endregion

    // region Words

    /**
     * The name, centred in the header between {@code left} and {@code right}: at the game font's size, smaller when
     * long, then cut with its end faded into the drawer.
     */
    public static void name(final Drawer.Kind kind, final String label, final int left, final int right) {
        final int room = right - left;
        final int full = Hyb.width(label);
        if (full <= room) {
            Hyb.text(label, (left + right - full) / 2f, (HEADER - 8) / 2f + 1, Hyb.INK);
            return;
        }
        final float small = 0.75f;
        if (full * small <= room) {
            Hyb.text(label, (left + right - full * small) / 2f, (HEADER - 6) / 2f + 1, small, Hyb.INK);
            return;
        }
        final String cut = Hyb.font()
            .trimStringToWidth(label, (int) (room / small));
        final float y = (HEADER - 6) / 2f + 1, w = Hyb.width(cut) * small;
        Hyb.text(cut, left, y, small, Hyb.INK);
        final int fade = 8, under = fill(kind) & 0xFFFFFF;
        for (int k = 0; k < fade; k++) Hyb.rect(left + w - fade + k, y - 1, 1, 8, (255 * (k + 1) / fade) << 24 | under);
    }

    /** The resource at its place in the body: its item or fluid, or the bolt for power. */
    public static void icon(final Drawer.Kind kind, @Nullable final ItemStack item, @Nullable final FluidStack fluid,
        final boolean power, final float z) {
        final int x = ICON_X + side(kind);
        if (power) RecipeCard.euIcon(x, ICON_Y, ICON);
        else Hyb.icon(item, fluid, x, ICON_Y, ICON, z);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
    }

    /**
     * What the plan moves through the drawer, right of the resource: large with its unit small beside it, at the name's
     * size when it would not fit. With a rule ({@code ruled}) it heads the column, the rule's row under it; without,
     * it is centred on the resource with {@code under} small beneath it.
     */
    public static void rate(final Drawer.Kind kind, final String number, final String suffix, final int colour,
        final boolean ruled, @Nullable final String under) {
        final int x = TEXT_X + side(kind), room = W - 5 - side(kind) - x;
        final float big = Hyb.width(number) * Hyb.FIGURE + 2 + Hyb.width(suffix);
        final float rateH = big <= room ? 8 * Hyb.FIGURE : 8;
        final float top = ruled ? ROW_Y - 3 - rateH
            : RecipeCard.crisp(ICON_Y + ICON / 2f - (rateH + (under == null ? 0 : 10)) / 2f);
        if (big <= room) {
            Hyb.text(number, x, top, Hyb.FIGURE, colour);
            Hyb.text(suffix, x + Hyb.width(number) * Hyb.FIGURE + 2, top + 3.5f, Hyb.MUTED);
        } else Hyb.text(Hyb.fit(number + suffix, room), x, top, colour);
        if (under != null) Hyb.text(under, x, top + rateH + 2, Hyb.MUTED);
    }

    // endregion
}

package com.gtnhplanner.ui.theme;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import com.cleanroommc.modularui.drawable.GuiDraw;

/**
 * The hybrid look: Factory Flow's card palette and bevels, NEI's slots, GregTech's tier colours, the game font.
 * Every coordinate is in GUI pixels of the current (possibly zoomed) widget space.
 */
public final class Hyb {

    /**
     * The type scale: labels and body text at 1x, figures (power, machine count, drawer rates) at 1.5x. At GUI scale 2
     * both land on whole screen pixels, so the game font stays crisp; 2x reads as shouting next to 1x.
     */
    public static final float FIGURE = 1.5f;

    // Factory Flow card ramp (globals.css tokens, resolved).
    public static final int INK = 0xFFE8E9EE;
    public static final int MUTED = 0xFF9A9CA4;
    public static final int FRAME = 0xFF3C3E45;
    public static final int RING = 0xFF52545C;
    public static final int HIGHLIGHT = 0xFF5A5C65;
    public static final int SHADOW = 0xFF1D1F23;
    public static final int TILE = 0xFF36383F;
    public static final int TILE_EDGE = 0xFF25272C;
    public static final int TILE_HI = 0xFF4E5058;
    public static final int KEY = 0xFF26282D;
    public static final int KEY_HOVER = 0xFF2D2F35;
    public static final int KEY_EDGE = 0xFF111317;
    public static final int KEY_HI = 0xFF4A4C54;
    public static final int KEY_LO = 0xFF17191D;
    public static final int NAMEBAR = 0xFF2D2F35;
    public static final int WELL = 0xFF4A4C54;
    public static final int PICTURE = 0xFF26282C;
    public static final int CANVAS = 0xFF141414;
    public static final int CANVAS_DOT = 0xFF26282D;
    public static final int GOLD = 0xFFFFD257;
    public static final int SELECTION = 0xFF22D3EE;
    /**
     * What the mouse or the crosshair is on (a resource, a card, a wire, a placed spot), the same everywhere: on the
     * board, on the minimap and over the world. Drawn faint: a thin line and a soft halo.
     */
    public static final int LIT = 0xFFFFD257;
    public static final int MENU = 0xFF3C3E45;
    public static final int MENU_HOVER = 0xFF4E5058;
    public static final int RED_INK = 0xFFE05252;
    public static final int AMBER_INK = 0xFFE0A63A;
    public static final int SOURCE_INK = 0xFFFFA2A2;
    public static final int PRODUCT_INK = 0xFF5EE9B5;

    // NEI slots.
    public static final int SLOT_ITEM = 0xFF8B8B8B;
    public static final int SLOT_ITEM_TL = 0xFF373737;
    public static final int SLOT_ITEM_BR = 0xFFFFFFFF;
    public static final int SLOT_FLUID = 0xFF2B2B2B;
    public static final int SLOT_FLUID_TL = 0xFF151515;
    public static final int SLOT_FLUID_BR = 0xFF5A5A5A;

    /** GregTech tier: background, border, text; tiers from UV up are told apart by an underline, as in game. */
    public record Tier(String name, int bg, int border, int text, boolean underline) {}

    private static final Tier[] TIERS = { new Tier("ULV", 0xFFFF5555, 0xFF8C2F2F, 0xFFFFFFFF, false),
        new Tier("LV", 0xFF00AA00, 0xFF005E00, 0xFFFFFFFF, false),
        new Tier("MV", 0xFFFFAA00, 0xFF8C5E00, 0xFF111111, false),
        new Tier("HV", 0xFFFFFF55, 0xFF8C8C2F, 0xFF111111, false),
        new Tier("EV", 0xFF555555, 0xFF2F2F2F, 0xFFFFFFFF, false),
        new Tier("IV", 0xFF5555FF, 0xFF2F2F8C, 0xFFFFFFFF, false),
        new Tier("LuV", 0xFFFF55FF, 0xFF8C2F8C, 0xFFFFFFFF, false),
        new Tier("ZPM", 0xFF55FFFF, 0xFF2F8C8C, 0xFF111111, false),
        new Tier("UV", 0xFF00AA00, 0xFF005E00, 0xFFFFFFFF, true),
        new Tier("UHV", 0xFFAA0000, 0xFF5E0000, 0xFFFFFFFF, true),
        new Tier("UEV", 0xFFAA00AA, 0xFF5E005E, 0xFFFFFFFF, true),
        new Tier("UIV", 0xFF0000AA, 0xFF00005E, 0xFFFFFFFF, true),
        new Tier("UMV", 0xFFFF5555, 0xFF8C2F2F, 0xFFFFFFFF, true),
        new Tier("UXV", 0xFFAA0000, 0xFF5E0000, 0xFFFFFFFF, true),
        new Tier("MAX", 0xFFFFFFFF, 0xFF8C8C8C, 0xFF111111, true) };
    private static final Tier NO_TIER = new Tier("OFF", 0xFF4A4C54, 0xFF1D1F23, 0xFFE8E9EE, false);

    private Hyb() {}

    /** Minecraft's button click, for every control that acts on a click (ModularUI's own buttons already make it). */
    public static void click() {
        com.cleanroommc.modularui.api.widget.Interactable.playButtonClickSound();
    }

    public static Tier tier(final String name) {
        for (final Tier t : TIERS) if (t.name.equalsIgnoreCase(name)) return t;
        return NO_TIER;
    }

    public static FontRenderer font() {
        return Minecraft.getMinecraft().fontRenderer;
    }

    public static int width(final String s) {
        return font().getStringWidth(s);
    }

    public static void rect(final float x, final float y, final float w, final float h, final int argb) {
        if (batching) {
            vertex(x, y, argb);
            vertex(x, y + h, argb);
            vertex(x + w, y + h, argb);
            vertex(x, y, argb);
            vertex(x + w, y + h, argb);
            vertex(x + w, y, argb);
            return;
        }
        GuiDraw.drawRect(x, y, w, h, argb);
    }

    // region Batching: many flat shapes in one draw call

    private static boolean batching;
    private static float[] batchXY = new float[6144];
    private static int[] batchColor = new int[3072];
    private static int batchCount;

    /**
     * From here to {@link #endBatch()}, {@link #rect} and {@link #triangle} collect their triangles instead of drawing
     * each one, and {@link #endBatch()} draws them all at once, in order. For layers of thousands of flat shapes (the
     * wires), where a draw call apiece is most of the frame.
     */
    public static void beginBatch() {
        batching = true;
        batchCount = 0;
    }

    public static void endBatch() {
        batching = false;
        final int n = batchCount;
        if (n == 0) return;
        com.cleanroommc.modularui.utils.Platform.setupDrawColor();
        com.cleanroommc.modularui.utils.Platform.startDrawing(
            com.cleanroommc.modularui.utils.Platform.DrawMode.TRIANGLES,
            com.cleanroommc.modularui.utils.Platform.VertexFormat.POS_COLOR,
            buf -> {
                for (int i = 0; i < n; i++) {
                    final int c = batchColor[i];
                    buf.pos(batchXY[2 * i], batchXY[2 * i + 1], 0)
                        .color(c >> 16 & 0xFF, c >> 8 & 0xFF, c & 0xFF, c >>> 24)
                        .endVertex();
                }
            });
    }

    private static void vertex(final float x, final float y, final int argb) {
        if (batchCount == batchColor.length) {
            batchColor = java.util.Arrays.copyOf(batchColor, batchCount * 2);
            batchXY = java.util.Arrays.copyOf(batchXY, batchCount * 4);
        }
        batchXY[2 * batchCount] = x;
        batchXY[2 * batchCount + 1] = y;
        batchColor[batchCount++] = argb;
    }

    // endregion

    /** Raised bevel: fill, light band top-left, dark band bottom-right, optional 1px outer edge. */
    public static void bevel(final float x, final float y, final float w, final float h, final int fill,
        final int light, final int dark, final int edge, final int band) {
        // Its pieces in one draw call (or in the batch already open).
        final boolean own = !batching;
        if (own) beginBatch();
        if (edge != 0) {
            rect(x - 1, y - 1, w + 2, h + 2, edge);
        }
        rect(x, y, w, h, fill);
        rect(x, y, w, band, light);
        rect(x, y, band, h, light);
        rect(x, y + h - band, w, band, dark);
        rect(x + w - band, y, band, h, dark);
        if (own) endBatch();
    }

    /**
     * A filled rectangle with round corners of radius {@code r}, in whole-pixel steps like the game's own art: rows of
     * rectangles at the top and bottom, one block between. {@code top}/{@code bottom} pick which corners are round.
     */
    public static void roundRect(final float x, final float y, final float w, final float h, final float r,
        final int argb, final boolean top, final boolean bottom) {
        // Its pieces in one draw call (or in the batch already open).
        final boolean own = !batching;
        if (own) beginBatch();
        final int rows = r <= 0 ? 0 : (int) Math.min(Math.ceil(r), Math.floor(h / 2));
        for (int i = 0; i < rows; i++) {
            final float dy = r - i - 0.5f;
            final float in = Math.round(r - (float) Math.sqrt(Math.max(0, r * r - dy * dy)));
            rect(x + (top ? in : 0), y + i, w - (top ? 2 * in : 0), 1, argb);
            rect(x + (bottom ? in : 0), y + h - 1 - i, w - (bottom ? 2 * in : 0), 1, argb);
        }
        rect(x, y + rows, w, h - 2 * rows, argb);
        if (own) endBatch();
    }

    public static void roundRect(final float x, final float y, final float w, final float h, final float r,
        final int argb) {
        roundRect(x, y, w, h, r, argb, true, true);
    }

    /** Sunken well: dark top-left, light bottom-right. */
    public static void well(final float x, final float y, final float w, final float h, final int fill, final int dark,
        final int light) {
        // Its pieces in one draw call (or in the batch already open).
        final boolean own = !batching;
        if (own) beginBatch();
        rect(x, y, w, h, fill);
        rect(x, y, w, 1, dark);
        rect(x, y, 1, h, dark);
        rect(x, y + h - 1, w, 1, light);
        rect(x + w - 1, y, 1, h, light);
        if (own) endBatch();
    }

    /** Factory Flow's card window: #3c3e45 fill, #52545c ring, 2 px highlight and shadow bands inside it. */
    public static void cardFrame(final float x, final float y, final float w, final float h) {
        // Its pieces in one draw call (or in the batch already open).
        final boolean own = !batching;
        if (own) beginBatch();
        rect(x, y, w, h, RING);
        rect(x + 1, y + 1, w - 2, h - 2, FRAME);
        rect(x + 1, y + 1, w - 2, 1, HIGHLIGHT);
        rect(x + 1, y + 1, 1, h - 2, HIGHLIGHT);
        rect(x + 1, y + h - 2, w - 2, 1, SHADOW);
        rect(x + w - 2, y + 1, 1, h - 2, SHADOW);
        if (own) endBatch();
    }

    /** A Factory Flow stat or setting tile: #36383f with a 1 px light and dark inner edge. */
    public static void tile(final float x, final float y, final float w, final float h) {
        // Its pieces in one draw call (or in the batch already open).
        final boolean own = !batching;
        if (own) beginBatch();
        rect(x, y, w, h, TILE_EDGE);
        rect(x + 1, y + 1, w - 2, h - 2, TILE);
        rect(x + 1, y + 1, w - 2, 1, TILE_HI);
        rect(x + 1, y + 1, 1, h - 2, TILE_HI);
        if (own) endBatch();
    }

    /** A whole texture (a bundled PNG) drawn into a rectangle, untinted. */
    public static void texture(final net.minecraft.util.ResourceLocation loc, final float x, final float y,
        final float w, final float h) {
        net.minecraft.client.Minecraft.getMinecraft()
            .getTextureManager()
            .bindTexture(loc);
        org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_TEXTURE_2D);
        org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_BLEND);
        net.minecraft.client.renderer.OpenGlHelper.glBlendFunc(770, 771, 1, 0);
        org.lwjgl.opengl.GL11.glColor4f(1, 1, 1, 1);
        final net.minecraft.client.renderer.Tessellator t = net.minecraft.client.renderer.Tessellator.instance;
        t.startDrawingQuads();
        t.addVertexWithUV(x, y + h, 0, 0, 1);
        t.addVertexWithUV(x + w, y + h, 0, 1, 1);
        t.addVertexWithUV(x + w, y, 0, 1, 0);
        t.addVertexWithUV(x, y, 0, 0, 0);
        t.draw();
    }

    /** A picture drawn in one colour by its shape alone, e.g. black at 45% for the shadow it casts. */
    public static void texture(final net.minecraft.util.ResourceLocation loc, final float x, final float y,
        final float w, final float h, final int argb) {
        net.minecraft.client.Minecraft.getMinecraft()
            .getTextureManager()
            .bindTexture(loc);
        org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_TEXTURE_2D);
        org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_BLEND);
        net.minecraft.client.renderer.OpenGlHelper.glBlendFunc(770, 771, 1, 0);
        // Drawn once, the colour multiplied in: black keeps the texture's alpha and loses its colours.
        org.lwjgl.opengl.GL11.glColor4f(
            (argb >> 16 & 0xFF) / 255f,
            (argb >> 8 & 0xFF) / 255f,
            (argb & 0xFF) / 255f,
            (argb >>> 24) / 255f);
        org.lwjgl.opengl.GL11.glTexEnvi(
            org.lwjgl.opengl.GL11.GL_TEXTURE_ENV,
            org.lwjgl.opengl.GL11.GL_TEXTURE_ENV_MODE,
            org.lwjgl.opengl.GL11.GL_MODULATE);
        final net.minecraft.client.renderer.Tessellator t = net.minecraft.client.renderer.Tessellator.instance;
        t.startDrawingQuads();
        t.addVertexWithUV(x, y + h, 0, 0, 1);
        t.addVertexWithUV(x + w, y + h, 0, 1, 1);
        t.addVertexWithUV(x + w, y, 0, 1, 0);
        t.addVertexWithUV(x, y, 0, 0, 0);
        t.draw();
        org.lwjgl.opengl.GL11.glColor4f(1, 1, 1, 1);
    }

    /** {@code a} mixed into {@code b}, {@code share} of a (0 to 1); opaque. */
    public static int mix(final int a, final int b, final float share) {
        final float s = Math.max(0, Math.min(1, share));
        final int r = Math.round((a >> 16 & 0xFF) * s + (b >> 16 & 0xFF) * (1 - s));
        final int g = Math.round((a >> 8 & 0xFF) * s + (b >> 8 & 0xFF) * (1 - s));
        final int bl = Math.round((a & 0xFF) * s + (b & 0xFF) * (1 - s));
        return 0xFF000000 | r << 16 | g << 8 | bl;
    }

    /**
     * The shadow everything on the board casts, as Factory Flow's (6 right, 8 down, 7 soft, 45% at its heart): four
     * stacked translucent rectangles, each a little larger, so the edge fades out.
     */
    public static void dropShadow(final float x, final float y, final float w, final float h) {
        // Its pieces in one draw call (or in the batch already open).
        final boolean own = !batching;
        if (own) beginBatch();
        for (int i = 3; i >= 0; i--) {
            final float grow = i * 2;
            rect(x + 6 - grow, y + 8 - grow, w + 2 * grow, h + 2 * grow, 0x1E000000);
        }
        if (own) endBatch();
    }

    /** A filled triangle in the current (possibly zoomed) space. */
    public static void triangle(final float x1, final float y1, final float x2, final float y2, final float x3,
        final float y3, final int argb) {
        // Through ModularUI's own drawing path, so it lands at the same depth and GL state as its rectangles.
        if (batching) {
            vertex(x1, y1, argb);
            vertex(x2, y2, argb);
            vertex(x3, y3, argb);
            vertex(x1, y1, argb);
            vertex(x3, y3, argb);
            vertex(x2, y2, argb);
            return;
        }
        final int a = argb >>> 24, r = argb >> 16 & 0xFF, g = argb >> 8 & 0xFF, b = argb & 0xFF;
        com.cleanroommc.modularui.utils.Platform.setupDrawColor();
        com.cleanroommc.modularui.utils.Platform.startDrawing(
            com.cleanroommc.modularui.utils.Platform.DrawMode.TRIANGLES,
            com.cleanroommc.modularui.utils.Platform.VertexFormat.POS_COLOR,
            buf -> {
                buf.pos(x1, y1, 0)
                    .color(r, g, b, a)
                    .endVertex();
                buf.pos(x2, y2, 0)
                    .color(r, g, b, a)
                    .endVertex();
                buf.pos(x3, y3, 0)
                    .color(r, g, b, a)
                    .endVertex();
                // And the other winding, so face culling cannot drop it whichever way it points.
                buf.pos(x1, y1, 0)
                    .color(r, g, b, a)
                    .endVertex();
                buf.pos(x3, y3, 0)
                    .color(r, g, b, a)
                    .endVertex();
                buf.pos(x2, y2, 0)
                    .color(r, g, b, a)
                    .endVertex();
            });
    }

    /** A ring of {@code t} px just outside a box, for selection and warnings. */
    public static void ring(final float x, final float y, final float w, final float h, final float t,
        final int color) {
        // Its pieces in one draw call (or in the batch already open).
        final boolean own = !batching;
        if (own) beginBatch();
        rect(x - t, y - t, w + 2 * t, t, color);
        rect(x - t, y + h, w + 2 * t, t, color);
        rect(x - t, y, t, h, color);
        rect(x + w, y, t, h, color);
        if (own) endBatch();
    }

    /** A dashed rectangle outline, 2 px on and 2 px off. */
    public static void dashed(final float x, final float y, final float w, final float h, final int color) {
        dashed(x, y, w, h, color, 2, 2);
    }

    /** A dashed rectangle outline with dashes {@code on} long and gaps {@code off} long. */
    public static void dashed(final float x, final float y, final float w, final float h, final int color, final int on,
        final int off) {
        // Its pieces in one draw call (or in the batch already open).
        final boolean own = !batching;
        if (own) beginBatch();
        for (float d = 0; d < w; d += on + off) {
            rect(x + d, y, Math.min(on, w - d), 1, color);
            rect(x + d, y + h - 1, Math.min(on, w - d), 1, color);
        }
        for (float d = 0; d < h; d += on + off) {
            rect(x, y + d, 1, Math.min(on, h - d), color);
            rect(x + w - 1, y + d, 1, Math.min(on, h - d), color);
        }
        if (own) endBatch();
    }

    /** NEI's 18x18 slot, grey for items and dark for fluids. */
    public static void slot(final float x, final float y, final boolean fluid) {
        if (fluid) well(x, y, 18, 18, SLOT_FLUID, SLOT_FLUID_TL, SLOT_FLUID_BR);
        else well(x, y, 18, 18, SLOT_ITEM, SLOT_ITEM_TL, SLOT_ITEM_BR);
    }

    /**
     * An item or a fluid as Factory Flow shows one: bare, no slot, with a soft shadow under it (0 2px 3px at half
     * strength there). A fluid is a slightly smaller square, as its sprite fills its box where an item's art does not.
     */
    public static void icon(final ItemStack item, final FluidStack fluid, final float x, final float y,
        final float size, final float z) {
        if (fluid != null) {
            final float inset = size / 12f, side = size - 2 * inset;
            iconShadow(null, fluid, x + inset, y + inset, side);
            fluid(fluid, x + inset, y + inset, side, z);
        } else if (item != null) {
            iconShadow(item, null, x, y, size);
            item(item, x, y, size, z);
        }
    }

    /** The shadow alone, under the rectangle the icon fills: its silhouette, dropped and softened by spreading. */
    public static void iconShadow(final ItemStack item, final FluidStack fluid, final float x, final float y,
        final float size) {
        // Three spreads, the widest faintest: about half black at the heart, fading over two pixels.
        final float dy = size / 8f;
        final float[] grow = { 2, 1, 0 };
        final int[] shade = { 0x1A000000, 0x2E000000, 0x4D000000 };
        for (int i = 0; i < 3; i++) {
            final float g = grow[i] * size / 24f;
            if (fluid != null) rect(x - g, y + dy - g, size + 2 * g, size + 2 * g, shade[i]);
            else if (item != null) IconShadows.draw(item, x - g, y + dy - g, size + 2 * g, shade[i]);
        }
    }

    public static void item(final ItemStack stack, final float x, final float y, final float size, final float z) {
        if (stack != null) GuiDraw.drawItem(stack, (int) x, (int) y, size, size, (int) z);
    }

    public static void fluid(final FluidStack stack, final float x, final float y, final float size, final float z) {
        if (stack != null) GuiDraw.drawFluidTexture(stack, x, y, size, size, z);
    }

    public static void text(final String s, final float x, final float y, final int color) {
        GuiDraw.drawText(s, x, y, 1f, color, true);
    }

    public static void text(final String s, final float x, final float y, final float scale, final int color) {
        GuiDraw.drawText(s, x, y, scale, color, true);
    }

    public static void textRight(final String s, final float right, final float y, final int color) {
        GuiDraw.drawText(s, right - width(s), y, 1f, color, true);
    }

    public static void textCentered(final String s, final float cx, final float y, final int color) {
        GuiDraw.drawText(s, cx - width(s) / 2f, y, 1f, color, true);
    }

    /** Cuts a string to fit {@code maxWidth}, ending in "..." when it had to. The game font has one size. */
    public static String fit(final String s, final int maxWidth) {
        if (width(s) <= maxWidth) return s;
        final String dots = "...";
        final int room = maxWidth - width(dots);
        if (room <= 0) return "";
        return font().trimStringToWidth(s, room) + dots;
    }
}

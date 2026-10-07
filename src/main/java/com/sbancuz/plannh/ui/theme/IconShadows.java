package com.sbancuz.plannh.ui.theme;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import com.sbancuz.plannh.PlanNH;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Item icons' silhouettes, for the shadow Factory Flow puts under every icon. An item draws itself with its own GL
 * state, so it cannot simply be drawn again in black; instead each icon is rendered once, at twice its size, into a
 * cell of an offscreen atlas, and its shadow is that cell drawn in black. An icon seen for the first time is queued
 * and rendered after the frame, so its shadow appears one frame late.
 */
public final class IconShadows {

    private static final int ATLAS = 1024;
    private static final int CELL = 40;
    private static final int ICON = 32;
    private static final int MARGIN = (CELL - ICON) / 2;
    private static final int PER_ROW = ATLAS / CELL;
    private static final int CAPACITY = PER_ROW * PER_ROW;
    /** Icons rendered after one frame at most, so a board full of new items costs a few frames, not one long one. */
    private static final int PER_FRAME = 48;

    private static final Map<Key, Integer> CELLS = new HashMap<>();
    private static final Map<Key, ItemStack> QUEUED = new LinkedHashMap<>();
    private static Framebuffer atlas;
    private static RenderItem renderer;
    private static boolean failed;
    private static boolean tickerRegistered;

    private IconShadows() {}

    /** What makes two stacks look alike: the item, its damage and its tag (by hash: a clash costs one shadow). */
    private record Key(Item item, int damage, int tag) {

        static Key of(final ItemStack stack) {
            return new Key(
                stack.getItem(),
                stack.getItemDamage(),
                stack.getTagCompound() == null ? 0
                    : stack.getTagCompound()
                        .hashCode());
        }
    }

    /**
     * Draws the icon's silhouette in {@code argb} over the rectangle the icon itself would fill. Draws nothing until
     * the icon has been rendered into the atlas (the frame after it is first asked for).
     */
    public static void draw(final ItemStack stack, final float x, final float y, final float size, final int argb) {
        if (failed || stack == null || stack.getItem() == null) return;
        final Key key = Key.of(stack);
        final Integer cell = CELLS.get(key);
        if (cell == null) {
            if (!QUEUED.containsKey(key)) {
                final ItemStack one = stack.copy();
                one.stackSize = 1;
                QUEUED.put(key, one);
                registerTicker();
            }
            return;
        }
        final int cx = cell % PER_ROW * CELL + MARGIN, cy = cell / PER_ROW * CELL + MARGIN;
        final float u0 = cx / (float) ATLAS, u1 = (cx + ICON) / (float) ATLAS;
        // The atlas is a framebuffer: its rows run bottom up.
        final float v0 = 1 - cy / (float) ATLAS, v1 = 1 - (cy + ICON) / (float) ATLAS;
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_TEXTURE_BIT | GL11.GL_CURRENT_BIT);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_ALPHA_TEST);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glEnable(GL11.GL_BLEND);
        OpenGlHelper.glBlendFunc(770, 771, 1, 0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, atlas.framebufferTexture);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_MODE, GL11.GL_MODULATE);
        GL11.glColor4f(
            (argb >> 16 & 0xFF) / 255f,
            (argb >> 8 & 0xFF) / 255f,
            (argb & 0xFF) / 255f,
            (argb >>> 24) / 255f);
        final Tessellator t = Tessellator.instance;
        t.startDrawingQuads();
        t.addVertexWithUV(x, y + size, 0, u0, v1);
        t.addVertexWithUV(x + size, y + size, 0, u1, v1);
        t.addVertexWithUV(x + size, y, 0, u1, v0);
        t.addVertexWithUV(x, y, 0, u0, v0);
        t.draw();
        GL11.glPopAttrib();
    }

    /** The atlas as an image, for the dev harness: what the shadows are drawn from. Null before anything is in it. */
    public static java.awt.image.BufferedImage atlasImage() {
        if (atlas == null) return null;
        final java.nio.IntBuffer pixels = org.lwjgl.BufferUtils.createIntBuffer(ATLAS * ATLAS);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, atlas.framebufferTexture);
        GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, pixels);
        final java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(
            ATLAS,
            ATLAS,
            java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < ATLAS; y++)
            for (int x = 0; x < ATLAS; x++) image.setRGB(x, ATLAS - 1 - y, pixels.get(y * ATLAS + x));
        return image;
    }

    public static int cells() {
        return CELLS.size();
    }

    private static void registerTicker() {
        if (tickerRegistered) return;
        tickerRegistered = true;
        FMLCommonHandler.instance()
            .bus()
            .register(new Ticker());
    }

    /** Renders the icons queued while the frame was drawn, once the frame is done. */
    public static final class Ticker {

        @SubscribeEvent
        public void onRenderTick(final TickEvent.RenderTickEvent event) {
            if (event.phase != TickEvent.Phase.END || QUEUED.isEmpty() || failed) return;
            try {
                renderQueued();
            } catch (final Throwable t) {
                failed = true;
                QUEUED.clear();
                PlanNH.LOG.warn("Icon shadows disabled: rendering the icons offscreen failed", t);
                Minecraft.getMinecraft()
                    .getFramebuffer()
                    .bindFramebuffer(true);
            }
        }
    }

    private static void renderQueued() {
        final Minecraft mc = Minecraft.getMinecraft();
        if (!OpenGlHelper.isFramebufferEnabled()) {
            failed = true;
            QUEUED.clear();
            return;
        }
        // The game leaves alpha writes off at times; the silhouettes are nothing but alpha.
        GL11.glColorMask(true, true, true, true);
        GL11.glDepthMask(true);
        if (atlas == null) {
            atlas = new Framebuffer(ATLAS, ATLAS, true);
            atlas.setFramebufferColor(0, 0, 0, 0);
            atlas.setFramebufferFilter(GL11.GL_LINEAR);
            atlas.framebufferClear();
        }
        if (CELLS.size() + Math.min(PER_FRAME, QUEUED.size()) > CAPACITY) {
            // Full: start again. The icons on screen come back over the next frames.
            CELLS.clear();
            atlas.framebufferClear();
        }
        if (renderer == null) renderer = new RenderItem();
        atlas.bindFramebuffer(true);
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glOrtho(0, ATLAS, ATLAS, 0, 1000, 3000);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glTranslatef(0, 0, -2000);
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        try {
            RenderHelper.enableGUIStandardItemLighting();
            GL11.glEnable(GL12.GL_RESCALE_NORMAL);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glColorMask(true, true, true, true);
            GL11.glDepthMask(true);
            // Alpha piles up as coverage, so a translucent pixel over an opaque one stays opaque.
            GL11.glEnable(GL11.GL_BLEND);
            OpenGlHelper.glBlendFunc(770, 771, 1, 771);
            GL11.glColor4f(1, 1, 1, 1);
            int done = 0;
            for (final Iterator<Map.Entry<Key, ItemStack>> it = QUEUED.entrySet()
                .iterator(); it.hasNext() && done < PER_FRAME; done++) {
                final Map.Entry<Key, ItemStack> next = it.next();
                it.remove();
                final int cell = CELLS.size();
                GL11.glPushMatrix();
                GL11.glTranslatef(cell % PER_ROW * CELL + MARGIN, cell / PER_ROW * CELL + MARGIN, 0);
                GL11.glScalef(ICON / 16f, ICON / 16f, 1);
                try {
                    renderer.renderItemAndEffectIntoGUI(mc.fontRenderer, mc.getTextureManager(), next.getValue(), 0, 0);
                } catch (final RuntimeException e) {
                    // A renderer that cannot draw here: this icon goes without a shadow.
                }
                GL11.glPopMatrix();
                CELLS.put(next.getKey(), cell);
            }
            RenderHelper.disableStandardItemLighting();
        } finally {
            GL11.glPopAttrib();
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPopMatrix();
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPopMatrix();
            mc.getFramebuffer()
                .bindFramebuffer(true);
        }
    }
}

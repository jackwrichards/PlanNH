package com.gtnhplanner.ui.world;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.RenderManager;

import org.lwjgl.opengl.GL11;

/**
 * Drawing marks in the world, between {@link #begin()} and {@link #end()} in a world-last render: block outlines that
 * show through walls (faint where hidden, bright where seen), beams, lines, and labels that face the camera.
 * Positions are world coordinates.
 */
final class WorldMarks {

    private WorldMarks() {}

    private static double cx, cy, cz;

    /** Sets the state the marks draw in: untextured, blended, unlit, relative to the camera. */
    static void begin() {
        cx = RenderManager.renderPosX;
        cy = RenderManager.renderPosY;
        cz = RenderManager.renderPosZ;
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_LINE_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_COLOR_BUFFER_BIT);
        GL11.glPushMatrix();
        GL11.glTranslated(-cx, -cy, -cz);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_FOG);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDisable(GL11.GL_ALPHA_TEST);
        GL11.glDepthMask(false);
        GL11.glEnable(GL11.GL_LINE_SMOOTH);
    }

    static void end() {
        GL11.glPopMatrix();
        GL11.glPopAttrib();
        GL11.glDepthMask(true);
        GL11.glColor4f(1, 1, 1, 1);
    }

    /** The camera's distance to a block's centre. */
    static double distance(final int x, final int y, final int z) {
        final double dx = x + 0.5 - cx, dy = y + 0.5 - cy, dz = z + 0.5 - cz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    static double cameraX() {
        return RenderManager.renderPosX;
    }

    static double cameraY() {
        return RenderManager.renderPosY;
    }

    static double cameraZ() {
        return RenderManager.renderPosZ;
    }

    /**
     * A block's outline with a faint fill: faint through whatever hides it, full where it is in view. {@code grow}
     * pushes it out past the block's faces.
     */
    static void outline(final int x, final int y, final int z, final int rgb, final float grow, final float width) {
        outline(x, y, z, rgb, grow, width, 1f);
    }

    /** {@link #outline} at a fraction of its strength. */
    static void outline(final int x, final int y, final int z, final int rgb, final float grow, final float width,
        final float strength) {
        final double x0 = x - grow, y0 = y - grow, z0 = z - grow, x1 = x + 1 + grow, y1 = y + 1 + grow,
            z1 = z + 1 + grow;
        GL11.glLineWidth(width);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        box(x0, y0, z0, x1, y1, z1, rgb, 0.06f * strength);
        edges(x0, y0, z0, x1, y1, z1, rgb, 0.35f * strength);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthFunc(GL11.GL_LEQUAL);
        edges(x0, y0, z0, x1, y1, z1, rgb, strength);
    }

    /** A soft column of light from the top of a block up into the sky, seen through everything. */
    static void beam(final int x, final int y, final int z, final int rgb) {
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        final double r = 0.12, mx = x + 0.5, mz = z + 0.5;
        final Tessellator t = Tessellator.instance;
        t.startDrawingQuads();
        final int top = 256;
        final double[][] corner = { { -r, -r }, { r, -r }, { r, r }, { -r, r } };
        for (int side = 0; side < 4; side++) {
            final double[] a = corner[side], b = corner[(side + 1) % 4];
            t.setColorRGBA_I(rgb, 90);
            t.addVertex(mx + a[0], y + 1, mz + a[1]);
            t.addVertex(mx + b[0], y + 1, mz + b[1]);
            t.setColorRGBA_I(rgb, 0);
            t.addVertex(mx + b[0], top, mz + b[1]);
            t.addVertex(mx + a[0], top, mz + a[1]);
        }
        t.draw();
        GL11.glEnable(GL11.GL_DEPTH_TEST);
    }

    /** A straight line between two points, faint through walls and full where seen. */
    static void line(final double x0, final double y0, final double z0, final double x1, final double y1,
        final double z1, final int rgb, final float width) {
        GL11.glLineWidth(width);
        final Tessellator t = Tessellator.instance;
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        t.startDrawing(GL11.GL_LINES);
        t.setColorRGBA_I(rgb, 120);
        t.addVertex(x0, y0, z0);
        t.addVertex(x1, y1, z1);
        t.draw();
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        t.startDrawing(GL11.GL_LINES);
        t.setColorRGBA_I(rgb, 230);
        t.addVertex(x0, y0, z0);
        t.addVertex(x1, y1, z1);
        t.draw();
    }

    private static final net.minecraft.client.renderer.RenderBlocks GHOST = new net.minecraft.client.renderer.RenderBlocks();

    /**
     * A ghost of a machine on a block: the machine's own block model, see-through and drawn over the world (front faces
     * only), so it shows whatever is on the block now, a stand-in or the ground. Not drawn for an item that is not a
     * block.
     */
    static void ghost(final net.minecraft.item.ItemStack machine, final int x, final int y, final int z) {
        final net.minecraft.block.Block block = net.minecraft.block.Block.getBlockFromItem(machine.getItem());
        if (block == null || block == net.minecraft.init.Blocks.air) return;
        Minecraft.getMinecraft()
            .getTextureManager()
            .bindTexture(net.minecraft.client.renderer.texture.TextureMap.locationBlocksTexture);
        GL11.glPushMatrix();
        GL11.glTranslated(x + 0.5, y + 0.5, z + 0.5);
        GL11.glScalef(0.96f, 0.96f, 0.96f);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDepthMask(false);
        GL11.glEnable(GL11.GL_BLEND);
        // See-through however the model sets its colours: blend by a constant alpha.
        org.lwjgl.opengl.GL14.glBlendColor(1, 1, 1, 0.55f);
        GL11.glBlendFunc(0x8003, 0x8004); // GL_CONSTANT_ALPHA, GL_ONE_MINUS_CONSTANT_ALPHA
        GL11.glColor4f(1, 1, 1, 1);
        net.minecraft.client.renderer.RenderHelper.enableStandardItemLighting();
        try {
            GHOST.renderBlockAsItem(block, machine.getItemDamage(), 1f);
        } catch (final RuntimeException ignored) {}
        net.minecraft.client.renderer.RenderHelper.disableStandardItemLighting();
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glPopMatrix();
    }

    /**
     * Turns the drawing space into a flat panel facing the camera at a world point, a GUI pixel being {@code scale}
     * blocks, x to the right and y down as on screen; {@link #endPanel()} undoes it.
     */
    static void beginPanel(final double x, final double y, final double z, final float scale) {
        final RenderManager rm = RenderManager.instance;
        GL11.glPushMatrix();
        GL11.glTranslated(x, y, z);
        GL11.glRotatef(-rm.playerViewY, 0, 1, 0);
        GL11.glRotatef(
            Minecraft.getMinecraft().gameSettings.thirdPersonView == 2 ? -rm.playerViewX : rm.playerViewX,
            1,
            0,
            0);
        GL11.glScalef(-scale, -scale, scale);
    }

    static void endPanel() {
        GL11.glPopMatrix();
    }

    private static void box(final double x0, final double y0, final double z0, final double x1, final double y1,
        final double z1, final int rgb, final float alpha) {
        final Tessellator t = Tessellator.instance;
        t.startDrawingQuads();
        t.setColorRGBA_I(rgb, (int) (alpha * 255));
        t.addVertex(x0, y0, z0);
        t.addVertex(x1, y0, z0);
        t.addVertex(x1, y0, z1);
        t.addVertex(x0, y0, z1);
        t.addVertex(x0, y1, z0);
        t.addVertex(x0, y1, z1);
        t.addVertex(x1, y1, z1);
        t.addVertex(x1, y1, z0);
        t.addVertex(x0, y0, z0);
        t.addVertex(x0, y1, z0);
        t.addVertex(x1, y1, z0);
        t.addVertex(x1, y0, z0);
        t.addVertex(x0, y0, z1);
        t.addVertex(x1, y0, z1);
        t.addVertex(x1, y1, z1);
        t.addVertex(x0, y1, z1);
        t.addVertex(x0, y0, z0);
        t.addVertex(x0, y0, z1);
        t.addVertex(x0, y1, z1);
        t.addVertex(x0, y1, z0);
        t.addVertex(x1, y0, z0);
        t.addVertex(x1, y1, z0);
        t.addVertex(x1, y1, z1);
        t.addVertex(x1, y0, z1);
        t.draw();
    }

    private static void edges(final double x0, final double y0, final double z0, final double x1, final double y1,
        final double z1, final int rgb, final float alpha) {
        final Tessellator t = Tessellator.instance;
        t.startDrawing(GL11.GL_LINES);
        t.setColorRGBA_I(rgb, (int) (alpha * 255));
        final double[][] c = { { x0, y0, z0 }, { x1, y0, z0 }, { x1, y0, z1 }, { x0, y0, z1 }, { x0, y1, z0 },
            { x1, y1, z0 }, { x1, y1, z1 }, { x0, y1, z1 } };
        final int[][] e = { { 0, 1 }, { 1, 2 }, { 2, 3 }, { 3, 0 }, { 4, 5 }, { 5, 6 }, { 6, 7 }, { 7, 4 }, { 0, 4 },
            { 1, 5 }, { 2, 6 }, { 3, 7 } };
        for (final int[] k : e) {
            t.addVertex(c[k[0]][0], c[k[0]][1], c[k[0]][2]);
            t.addVertex(c[k[1]][0], c[k[1]][1], c[k[1]][2]);
        }
        t.draw();
    }
}

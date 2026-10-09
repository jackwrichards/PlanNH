package com.gtnhplanner.ui.gt;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderBlocks;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.init.Blocks;
import net.minecraft.world.World;

import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;

import blockrenderer6343.client.world.TrackedDummyWorld;

/**
 * Builds and draws a multiblock's ghost: the structure placed in BlockRenderer6343's fake world as the card's picture
 * is ({@link MultiblockRenderer#place}), kept, and its blocks drawn against that world each frame (so casings join and
 * machines show their faces, and only the outside faces are drawn), skipping any block already built in the real world.
 * Loads BlockRenderer6343, StructureLib and GregTech classes: only {@link StructureGhosts} calls it, after checking
 * they
 * are there.
 */
final class StructureGhostBuilder {

    /** A structure as built: its world, its renderer, and each block as {dx, dy, dz} from the controller. */
    record Built(TrackedDummyWorld world, RenderBlocks render, List<int[]> blocks) {}

    private StructureGhostBuilder() {}

    /** The ghost of one controller's structure, or null when it has none to show or is too big. Render thread only. */
    static StructureGhosts.Ghost build(final int meta) {
        final TrackedDummyWorld world = new TrackedDummyWorld();
        world.updateEntitiesForNEI();
        MultiblockRenderer.place(world, meta);
        final int count = world.blockMap.size();
        if (count <= 1 || count > MultiblockRenderer.MAX_BLOCKS) return null;
        final Vector3f min = world.getMinPos(), max = world.getMaxPos();
        final List<int[]> blocks = new ArrayList<>();
        int minX = 0, minY = 0, minZ = 0, maxX = 0, maxY = 0, maxZ = 0;
        for (int x = (int) Math.floor(min.x); x <= (int) Math.ceil(max.x); x++)
            for (int y = (int) Math.floor(min.y); y <= (int) Math.ceil(max.y); y++)
                for (int z = (int) Math.floor(min.z); z <= (int) Math.ceil(max.z); z++) {
                    final Block block = world.getBlock(x, y, z);
                    if (block == null || block == Blocks.air) continue;
                    final int dx = x - MultiblockRenderer.PLACE_X, dy = y - MultiblockRenderer.PLACE_Y,
                        dz = z - MultiblockRenderer.PLACE_Z;
                    blocks.add(new int[] { dx, dy, dz });
                    minX = Math.min(minX, dx);
                    minY = Math.min(minY, dy);
                    minZ = Math.min(minZ, dz);
                    maxX = Math.max(maxX, dx);
                    maxY = Math.max(maxY, dy);
                    maxZ = Math.max(maxZ, dz);
                }
        return new StructureGhosts.Ghost(
            new Built(world, new RenderBlocks(world), blocks),
            blocks.size(),
            minX,
            minY,
            minZ,
            maxX,
            maxY,
            maxZ);
    }

    /**
     * Draws a ghost with its controller on the block at (x, y, z), its front turned to {@code facing}, leaving out
     * every
     * block already built in the world. With {@code depthOnly}, only its depth (the first pass, see
     * {@link StructureGhosts#depth}); otherwise its colour where that depth is its own: the outside alone, see-through
     * by a constant alpha, behind real blocks. Transparent texels are left out of both: GregTech's overlays are mostly
     * transparent, and the constant alpha would otherwise paint their hidden colour.
     */
    static void draw(final StructureGhosts.Ghost ghost, final int x, final int y, final int z, final int facing,
        final boolean depthOnly) {
        final Built built = (Built) ghost.built();
        final World real = Minecraft.getMinecraft().theWorld;
        Minecraft.getMinecraft()
            .getTextureManager()
            .bindTexture(TextureMap.locationBlocksTexture);
        GL11.glPushMatrix();
        GL11.glTranslated(x + 0.5, y, z + 0.5);
        GL11.glRotatef(-90f * facing, 0, 1, 0);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_CULL_FACE);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthFunc(GL11.GL_LEQUAL);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glEnable(GL11.GL_ALPHA_TEST);
        GL11.glAlphaFunc(GL11.GL_GREATER, 0.1f);
        if (depthOnly) {
            GL11.glColorMask(false, false, false, false);
            GL11.glDepthMask(true);
        } else {
            GL11.glDepthMask(false);
            GL11.glEnable(GL11.GL_BLEND);
            org.lwjgl.opengl.GL14.glBlendColor(1, 1, 1, 0.5f);
            GL11.glBlendFunc(0x8003, 0x8004); // GL_CONSTANT_ALPHA, GL_ONE_MINUS_CONSTANT_ALPHA
        }
        GL11.glColor4f(1, 1, 1, 1);
        final Tessellator t = Tessellator.instance;
        final TrackedDummyWorld world = built.world();
        try {
            for (int pass = 0; pass < 2; pass++) {
                t.startDrawingQuads();
                t.setTranslation(
                    -MultiblockRenderer.PLACE_X - 0.5,
                    -MultiblockRenderer.PLACE_Y,
                    -MultiblockRenderer.PLACE_Z - 0.5);
                for (final int[] b : built.blocks()) {
                    final int wx = MultiblockRenderer.PLACE_X + b[0], wy = MultiblockRenderer.PLACE_Y + b[1],
                        wz = MultiblockRenderer.PLACE_Z + b[2];
                    final Block block = world.getBlock(wx, wy, wz);
                    if (!block.canRenderInPass(pass)) continue;
                    if (real != null && builtThere(real, block, world.getBlockMetadata(wx, wy, wz), b, x, y, z, facing))
                        continue;
                    try {
                        built.render()
                            .renderBlockByRenderType(block, wx, wy, wz);
                    } catch (final RuntimeException ignored) {
                        // A block that will not draw outside a real world is left out of the ghost.
                    }
                }
                t.draw();
            }
        } finally {
            t.setTranslation(0, 0, 0);
            GL11.glColorMask(true, true, true, true);
            GL11.glDepthMask(false);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glPopMatrix();
        }
    }

    /** Whether the real world has this block of the structure in its place already (its meta too, but a machine's). */
    private static boolean builtThere(final World real, final Block block, final int meta, final int[] b, final int x,
        final int y, final int z, final int facing) {
        final int[] at = StructureGhosts.turn(b[0], b[2], facing);
        final int rx = x + at[0], ry = y + b[1], rz = z + at[1];
        if (real.getBlock(rx, ry, rz) != block) return false;
        return block.hasTileEntity(meta) || real.getBlockMetadata(rx, ry, rz) == meta;
    }
}

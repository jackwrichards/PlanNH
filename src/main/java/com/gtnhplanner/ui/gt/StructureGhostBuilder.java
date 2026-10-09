package com.gtnhplanner.ui.gt;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

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
import gregtech.api.GregTechAPI;
import gregtech.api.enums.HeatingCoilLevel;

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

    /**
     * A structure that comes in sizes: its channel, how many steps it has (the Hologram Projector's 1 the smallest),
     * and how a card's recipe picks one, by its fluid outputs or item inputs (none: the smallest), with how many the
     * smallest holds.
     */
    private record Sizing(String channel, int steps, boolean byFluids, boolean byItems, int smallestHolds) {

        /** The step a card's recipe needs: one more for each output or input past what the smallest holds. */
        int forNeeds(final StructureGhosts.Needs needs) {
            final int count = byFluids ? needs.fluidOutputs() : byItems ? needs.itemInputs() : 0;
            return Math.max(1, Math.min(steps, count - smallestHolds + 1));
        }
    }

    /**
     * The structures that come in sizes, by their GregTech class, and how (read from each one's {@code construct}): a
     * distillation tower is its base and a layer for each fluid output, 3 to 12 high; an assembly line a slice for each
     * item input, 5 to 16 long; the mega tower's layers hold its outputs by a rule of their own, so it starts at its
     * smallest.
     */
    private static final Map<String, Sizing> SIZINGS = Map.of(
        "MTEDistillationTower",
        new Sizing("height", 10, true, false, 2),
        "MTEAdvDistillationTower",
        new Sizing("height", 10, true, false, 2),
        "MTEMegaDistillationTower",
        new Sizing("height", 5, false, false, 0),
        "MTEMegaDistillTowerLegacy",
        new Sizing("height", 5, false, false, 0),
        "MTEAssemblyLine",
        new Sizing("length", 12, false, true, 5),
        "MTEAdvAssLine",
        new Sizing("length", 12, false, true, 5));

    private StructureGhostBuilder() {}

    @javax.annotation.Nullable
    private static Sizing sizing(final int meta) {
        final Object machine = GregTechAPI.METATILEENTITIES[meta];
        return machine == null ? null
            : SIZINGS.get(
                machine.getClass()
                    .getSimpleName());
    }

    /**
     * The structure channels a controller is built with for a card's needs at a size (0: as its recipe needs), in
     * order: the coil's tier (1 cupronickel, as GregTech counts them) and the size's step, where it has them.
     */
    static Map<String, Integer> channels(final int meta, final StructureGhosts.Needs needs, final int size) {
        final Map<String, Integer> out = new TreeMap<>();
        if (needs.coilHeat() > 0) {
            for (final HeatingCoilLevel level : HeatingCoilLevel.values()) {
                if (level != HeatingCoilLevel.None && level.getHeat() == needs.coilHeat()) {
                    out.put("coil", level.ordinal() - 1);
                }
            }
        }
        final Sizing sizing = sizing(meta);
        if (sizing != null) {
            out.put(sizing.channel(), size > 0 ? Math.min(size, sizing.steps()) : sizing.forNeeds(needs));
        }
        return out;
    }

    /**
     * The ghost of one controller's structure as a card needs it at a size (0: as its recipe needs), or null when it
     * has none to show or is too big. Render thread only.
     */
    static StructureGhosts.Ghost build(final int meta, final StructureGhosts.Needs needs, final int size) {
        final Map<String, Integer> channels = channels(meta, needs, size);
        final TrackedDummyWorld world = new TrackedDummyWorld();
        world.updateEntitiesForNEI();
        MultiblockRenderer.place(world, meta, channels);
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
        final Sizing sizing = sizing(meta);
        return new StructureGhosts.Ghost(
            new Built(world, new RenderBlocks(world), blocks),
            blocks.size(),
            minX,
            minY,
            minZ,
            maxX,
            maxY,
            maxZ,
            sizing == null ? 0 : channels.get(sizing.channel()),
            sizing == null ? 0 : sizing.steps());
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

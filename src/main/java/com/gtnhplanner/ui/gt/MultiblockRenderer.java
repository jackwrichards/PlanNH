package com.gtnhplanner.ui.gt;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;
import org.lwjgl.util.glu.GLU;

import com.gtnewhorizon.structurelib.StructureLibAPI;
import com.gtnewhorizon.structurelib.alignment.constructable.ChannelDataAccessor;
import com.gtnewhorizon.structurelib.alignment.constructable.IConstructable;
import com.gtnewhorizon.structurelib.alignment.constructable.ISurvivalConstructable;
import com.gtnewhorizon.structurelib.structure.ISurvivalBuildEnvironment;

import blockrenderer6343.api.utils.CreativeItemSource;
import blockrenderer6343.client.renderer.WorldSceneRenderer;
import blockrenderer6343.client.utils.BRUtil;
import blockrenderer6343.client.world.ClientFakePlayer;
import blockrenderer6343.client.world.TrackedDummyWorld;
import gregtech.api.GregTechAPI;
import gregtech.api.interfaces.INEIPreviewModifier;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.interfaces.tileentity.ITurnable;
import gregtech.api.threads.RunnableMachineUpdate;

/**
 * Builds a GregTech multiblock in BlockRenderer6343's fake world and renders it to pixels. The placement follows
 * BlockRenderer6343's own NEI preview ({@code GTGuiMultiblockHandler.placeMultiblock}, reimplemented here against the
 * public APIs, not copied): controller placed at (0, 64, 0) facing south, then the structure auto-built from its
 * definition with a creative item source at the base tier.
 *
 * <p>
 * Loads BlockRenderer6343, StructureLib and GregTech classes: only {@link MultiblockPictures} calls it, after checking
 * that all three are present.
 */
final class MultiblockRenderer {

    /**
     * Largest structure side, in blocks, that still reads at well size: about 3 px a block in a 76 px picture. Covers
     * the everyday machines (fusion rings are 15, the base-tier assembly line and distillation tower fit); the giant
     * late-game structures become specks and keep their icon instead.
     */
    static final int MAX_DIMENSION = 24;
    /** Upper bound on blocks to draw, as a guard against odd structures within {@link #MAX_DIMENSION}. */
    static final int MAX_BLOCKS = 6000;

    static final int PLACE_X = 0, PLACE_Y = 64, PLACE_Z = 0;
    /** As BlockRenderer6343: survival auto-build rounds until a round places nothing. */
    private static final int MAX_PLACE_ROUNDS = 2000;
    /** Camera around the structure: from the controller's front-right, looking slightly down. */
    private static final double YAW = Math.toRadians(50), PITCH = Math.toRadians(25);
    /** Vertical field of view: narrower than BlockRenderer6343's 60 degrees, for less distortion at this size. */
    private static final float FOV = 30f;
    /** Share of the picture's half-width left empty around the structure. */
    private static final double MARGIN = 0.04;

    private MultiblockRenderer() {}

    /** The GT machine meta when the stack is a constructable multiblock controller, else -1. */
    static int controllerMeta(final ItemStack stack) {
        if (stack.getItem() == null || Block.getBlockFromItem(stack.getItem()) != GregTechAPI.sBlockMachines) return -1;
        final int meta = stack.getItemDamage();
        if (meta < 0 || meta >= GregTechAPI.METATILEENTITIES.length) return -1;
        return GregTechAPI.METATILEENTITIES[meta] instanceof IConstructable ? meta : -1;
    }

    static List<Map<String, Object>> controllers() {
        final List<Map<String, Object>> out = new ArrayList<>();
        for (int meta = 0; meta < GregTechAPI.METATILEENTITIES.length; meta++) {
            if (!(GregTechAPI.METATILEENTITIES[meta] instanceof IConstructable)) continue;
            final Map<String, Object> m = new LinkedHashMap<>();
            m.put("meta", meta);
            m.put("name", name(meta));
            out.add(m);
        }
        return out;
    }

    private static String name(final int meta) {
        try {
            return new ItemStack(GregTechAPI.sBlockMachines, 1, meta).getDisplayName();
        } catch (final RuntimeException e) {
            return "";
        }
    }

    /** Builds and renders one controller's structure. Must run on the render thread. */
    static MultiblockPictures.Build render(final int meta, final int size, final int supersample,
        final int background) {
        if (meta < 0 || meta >= GregTechAPI.METATILEENTITIES.length
            || !(GregTechAPI.METATILEENTITIES[meta] instanceof IConstructable)) {
            return new MultiblockPictures.Build(meta, "", "not a multiblock", 0, 0, 0, 0, 0, 0, null);
        }
        final String name = name(meta);
        final long start = System.nanoTime();
        final TrackedDummyWorld world = new TrackedDummyWorld();
        world.updateEntitiesForNEI();
        place(world, meta);
        final long built = System.nanoTime();

        final int blocks = world.blockMap.size();
        final Vector3f extent = new Vector3f(world.getSize());
        final Vector3f min = new Vector3f(world.getMinPos());
        final int sx = Math.round(extent.x), sy = Math.round(extent.y), sz = Math.round(extent.z);
        final long buildMillis = (built - start) / 1_000_000L;
        if (blocks <= 1) {
            return new MultiblockPictures.Build(meta, name, "empty", sx, sy, sz, blocks, buildMillis, 0, null);
        }
        if (Math.max(sx, Math.max(sy, sz)) > MAX_DIMENSION || blocks > MAX_BLOCKS) {
            return new MultiblockPictures.Build(meta, name, "too big", sx, sy, sz, blocks, buildMillis, 0, null);
        }
        final int[] argb = draw(world, min, extent, size, supersample, background);
        final long renderMillis = (System.nanoTime() - built) / 1_000_000L;
        return new MultiblockPictures.Build(meta, name, "ok", sx, sy, sz, blocks, buildMillis, renderMillis, argb);
    }

    /** Places the controller and auto-builds its structure, the way BlockRenderer6343's GT preview does. */
    static void place(final TrackedDummyWorld world, final int meta) {
        place(world, meta, Map.of());
    }

    /**
     * As {@link #place(TrackedDummyWorld, int)}, with structure channels set on the trigger as the Hologram Projector
     * sets them (a coil's tier, a tower's height), each read by the structure's own definition.
     */
    static void place(final TrackedDummyWorld world, final int meta, final Map<String, Integer> channels) {
        // BlockRenderer6343's shared fake player: its construction already ran every mod's entity hooks once.
        final ClientFakePlayer player = BRUtil.FAKE_PLAYER;
        final World playerWorld = player.worldObj;
        final boolean machineUpdates = RunnableMachineUpdate.isCurrentThreadEnabled();
        if (machineUpdates) RunnableMachineUpdate.setCurrentThreadEnabled(false);
        player.setWorld(world);
        world.unloadEntities(Collections.singletonList(player));
        try {
            final ItemStack controller = new ItemStack(GregTechAPI.sBlockMachines, 1, meta);
            controller.getItem()
                .onItemUse(controller, player, world, PLACE_X, PLACE_Y, PLACE_Z, 0, PLACE_X, PLACE_Y, PLACE_Z);
            final TileEntity tile = world.getTileEntity(PLACE_X, PLACE_Y, PLACE_Z);
            if (tile instanceof final ITurnable turnable) turnable.setFrontFacing(ForgeDirection.SOUTH);
            if (!(tile instanceof final IGregTechTileEntity gtTile)) {
                throw new IllegalStateException("the controller did not place");
            }
            final IMetaTileEntity machine = gtTile.getMetaTileEntity();
            final ItemStack trigger = new ItemStack(StructureLibAPI.getDefaultHologramItem());
            trigger.stackSize = 1;
            for (final Map.Entry<String, Integer> c : channels.entrySet())
                ChannelDataAccessor.setChannelData(trigger, c.getKey(), c.getValue());
            if (machine instanceof final INEIPreviewModifier preview) preview.onPreviewConstruct(trigger);
            if (machine instanceof final ISurvivalConstructable survival) {
                final ISurvivalBuildEnvironment env = ISurvivalBuildEnvironment
                    .create(CreativeItemSource.instance, player);
                int rounds = 0;
                do {
                    survival.survivalConstruct(trigger, Integer.MAX_VALUE, env);
                    rounds++;
                } while (world.hasChanged() && rounds < MAX_PLACE_ROUNDS);
            } else if (machine instanceof final IConstructable constructable) {
                constructable.construct(trigger, false);
            } else {
                throw new IllegalStateException("the placed controller is not constructable");
            }
            if (machine instanceof final INEIPreviewModifier preview) preview.onPreviewStructureComplete(trigger);
        } finally {
            player.setWorld(playerWorld);
            if (machineUpdates) RunnableMachineUpdate.setCurrentThreadEnabled(true);
        }
    }

    /**
     * Renders the world into a private framebuffer at {@code size * supersample} and box-filters it down to
     * {@code size}: opaque ARGB, top row first. The caller's framebuffers, viewport and GL state are restored.
     */
    private static int[] draw(final TrackedDummyWorld world, final Vector3f min, final Vector3f extent, final int size,
        final int supersample, final int background) {
        final int px = size * supersample;
        final Vector3f center = new Vector3f(min).add(extent.x / 2f, extent.y / 2f, extent.z / 2f);
        final Camera camera = Camera.fit(extent, YAW, PITCH, Math.tan(Math.toRadians(FOV / 2f)) * (1 - MARGIN));

        final int previousDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        final int previousRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        final int previousRenderbuffer = GL11.glGetInteger(GL30.GL_RENDERBUFFER_BINDING);
        final IntBuffer viewport = BufferUtils.createIntBuffer(16);
        GL11.glGetInteger(GL11.GL_VIEWPORT, viewport);
        int fbo = 0, color = 0, depth = 0;
        boolean pushed = false;
        try {
            color = GL30.glGenRenderbuffers();
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, color);
            GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL11.GL_RGBA8, px, px);
            depth = GL30.glGenRenderbuffers();
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, depth);
            GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL14.GL_DEPTH_COMPONENT24, px, px);

            // Raw GL framebuffer, verified: under Angelica, Minecraft's Framebuffer wrapper can silently not bind.
            fbo = GL30.glGenFramebuffers();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            if (GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING) != fbo) {
                throw new IllegalStateException("the framebuffer bind did not take");
            }
            GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL30.GL_RENDERBUFFER, color);
            GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL30.GL_RENDERBUFFER, depth);
            final int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
            if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException("the framebuffer is incomplete: 0x" + Integer.toHexString(status));
            }

            GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
            pushed = true;
            GL11.glColor4f(1f, 1f, 1f, 1f);
            final Scene scene = new Scene(world, background, camera);
            scene.setRenderAllBlocks();
            scene.setCameraLookAt(center, camera.distance, YAW, PITCH);
            scene.render(0, 0, px, px, -1, -1);

            // Whole RGBA rows, so the pack alignment (the screenshot code leaves it at 1) does not matter.
            final ByteBuffer rgba = BufferUtils.createByteBuffer(px * px * 4);
            GL11.glReadPixels(0, 0, px, px, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, rgba);
            return downsample(rgba, px, supersample);
        } catch (final RuntimeException | Error e) {
            // A throw mid-draw can leave a tessellator batch open, which would break the game's next draw; flush it
            // into this throwaway framebuffer ("Not tesselating!" just means there was none).
            try {
                Tessellator.instance.draw();
            } catch (final RuntimeException ignored) {}
            throw e;
        } finally {
            if (pushed) GL11.glPopAttrib();
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousDraw);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousRead);
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, previousRenderbuffer);
            GL11.glViewport(viewport.get(0), viewport.get(1), viewport.get(2), viewport.get(3));
            if (fbo != 0) GL30.glDeleteFramebuffers(fbo);
            if (color != 0) GL30.glDeleteRenderbuffers(color);
            if (depth != 0) GL30.glDeleteRenderbuffers(depth);
        }
    }

    /** GL rows run bottom-up; the result is top row first, each pixel the mean of a supersample square, opaque. */
    private static int[] downsample(final ByteBuffer rgba, final int px, final int supersample) {
        final int size = px / supersample;
        final int samples = supersample * supersample;
        final int[] argb = new int[size * size];
        for (int oy = 0; oy < size; oy++) {
            for (int ox = 0; ox < size; ox++) {
                int r = 0, g = 0, b = 0;
                for (int dy = 0; dy < supersample; dy++) {
                    final int row = px - 1 - (oy * supersample + dy);
                    for (int dx = 0; dx < supersample; dx++) {
                        final int i = (row * px + ox * supersample + dx) * 4;
                        r += rgba.get(i) & 0xFF;
                        g += rgba.get(i + 1) & 0xFF;
                        b += rgba.get(i + 2) & 0xFF;
                    }
                }
                argb[oy * size + ox] = 0xFF000000 | (r / samples) << 16 | (g / samples) << 8 | b / samples;
            }
        }
        return argb;
    }

    /**
     * How far back the camera stands so the structure's bounding box just fits a square view, and the depth range
     * that box spans. Directions follow {@link WorldSceneRenderer#setCameraLookAt(Vector3f, double, double, double)}.
     */
    private record Camera(double distance, float near, float far) {

        static Camera fit(final Vector3f extent, final double yaw, final double pitch, final double halfTan) {
            // Toward the eye from the centre, as setCameraLookAt builds it; forward is the opposite.
            double bx = Math.cos(yaw), by = Math.tan(pitch), bz = Math.sin(yaw);
            final double bl = Math.sqrt(bx * bx + by * by + bz * bz);
            bx /= bl;
            by /= bl;
            bz /= bl;
            final double fx = -bx, fy = -by, fz = -bz;
            // right = forward x worldUp(0, 1, 0); up = right x forward.
            final double rl = Math.sqrt(fz * fz + fx * fx);
            final double rx = -fz / rl, rz = fx / rl;
            final double ux = -rz * fy, uy = rz * fx - rx * fz, uz = rx * fy;

            double distance = 0, nearest = Double.MAX_VALUE, farthest = -Double.MAX_VALUE;
            for (int corner = 0; corner < 8; corner++) {
                final double dx = ((corner & 1) == 0 ? -0.5 : 0.5) * extent.x;
                final double dy = ((corner & 2) == 0 ? -0.5 : 0.5) * extent.y;
                final double dz = ((corner & 4) == 0 ? -0.5 : 0.5) * extent.z;
                final double x = dx * rx + dz * rz;
                final double y = dx * ux + dy * uy + dz * uz;
                final double z = dx * fx + dy * fy + dz * fz;
                // In view when |x| and |y| <= halfTan * depth, where depth = distance + z.
                distance = Math.max(distance, Math.max(Math.abs(x), Math.abs(y)) / halfTan - z);
                distance = Math.max(distance, 1 - z);
                nearest = Math.min(nearest, z);
                farthest = Math.max(farthest, z);
            }
            final float near = (float) Math.max(0.05, distance + nearest - 0.5);
            return new Camera(distance, near, (float) (distance + farthest + 0.5));
        }
    }

    /**
     * BlockRenderer6343's scene renderer pointed at a private framebuffer: clears to the well colour with no scissor or
     * stencil (ModularUI may leave either on), narrows the projection, and always balances its GL stack pushes.
     */
    private static final class Scene extends WorldSceneRenderer {

        private final int background;
        private final Camera camera;

        Scene(final TrackedDummyWorld world, final int background, final Camera camera) {
            super(world);
            this.background = background;
            this.camera = camera;
        }

        @Override
        public void render(final int x, final int y, final int width, final int height, final int mouseX,
            final int mouseY) {
            rect.set(x, y, width, height);
            setupCamera();
            try {
                drawWorld();
            } finally {
                resetCamera();
            }
        }

        @Override
        public void setupCamera() {
            super.setupCamera();
            // Replaces the projection super just pushed and set; resetCamera pops it as usual.
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glLoadIdentity();
            GLU.gluPerspective(FOV, 1f, camera.near, camera.far);
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
        }

        @Override
        protected void clearView(final int x, final int y, final int width, final int height) {
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL11.glDisable(GL11.GL_FOG);
            // glClear honours the write masks, and GUI drawing can leave the depth mask off.
            GL11.glColorMask(true, true, true, true);
            GL11.glDepthMask(true);
            GL11.glDepthFunc(GL11.GL_LEQUAL);
            setGlClearColorFromInt(background, 0xFF);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        }
    }
}

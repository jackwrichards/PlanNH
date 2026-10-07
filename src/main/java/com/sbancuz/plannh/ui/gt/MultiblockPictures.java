package com.sbancuz.plannh.ui.gt;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GLContext;

import com.cleanroommc.modularui.drawable.GuiDraw;
import com.sbancuz.plannh.Compat;
import com.sbancuz.plannh.PlanNH;
import com.sbancuz.plannh.ui.theme.Hyb;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Pictures of whole GregTech multiblocks for the recipe card's picture well: the structure is built from its
 * definition in BlockRenderer6343's fake world (as its NEI preview does), rendered once into an offscreen framebuffer
 * and kept as a texture for the session.
 *
 * <p>
 * {@link #get} never builds anything itself: it queues the controller and returns null (the card shows the item icon)
 * until the picture is ready. The queue is drained at the end of each rendered frame, one structure per frame. A
 * controller that cannot be pictured (not constructable, too big to read at well size, or building/rendering threw)
 * is remembered and keeps its icon for the rest of the session.
 *
 * <p>
 * Only active with GregTech, BlockRenderer6343 and OpenGL 3.0 framebuffers; {@code -Dplannh.structurePictures=false}
 * turns it off. This class is safe to load without either mod: everything that touches them lives in
 * {@link MultiblockRenderer}, which is only reached once {@link #available()} said yes.
 */
public final class MultiblockPictures {

    /**
     * Texture side in pixels. The well shows about 76 GUI px, so 256 stays sharp up to board zoom 1.5 at GUI scale 2
     * and is mipmapped for smaller.
     */
    public static final int SIZE = 256;
    /** Rendered at {@code SIZE * SUPERSAMPLE} and box-filtered down: cheap anti-aliasing for a one-off render. */
    static final int SUPERSAMPLE = 2;

    private static final boolean ENABLED = !"false".equalsIgnoreCase(System.getProperty("plannh.structurePictures"));

    private static final Map<Integer, Picture> PICTURES = new HashMap<>();
    /** Controllers that keep their icon, with the reason (see {@link Build#status}). */
    private static final Map<Integer, String> NONE = new HashMap<>();
    private static final Set<Integer> QUEUED = new LinkedHashSet<>();

    private static Boolean available;
    private static boolean tickerRegistered;

    private MultiblockPictures() {}

    /**
     * The structure picture for a GregTech multiblock controller, or null: not a constructable GT multiblock, the
     * feature is unavailable, the picture is not built yet (it is queued by this call), or it cannot be made.
     */
    public static Picture get(final ItemStack controller) {
        if (controller == null || !available()) return null;
        final int meta = controllerMeta(controller);
        if (meta < 0) return null;
        final Picture picture = PICTURES.get(meta);
        if (picture != null || NONE.containsKey(meta)) return picture;
        if (QUEUED.add(meta)) registerTicker();
        return null;
    }

    /** GregTech, BlockRenderer6343 and GL 3.0 framebuffers are present. Call on the client thread. */
    public static boolean available() {
        if (available == null) {
            available = ENABLED && Compat.GREGTECH.isLoaded
                && Loader.isModLoaded("blockrenderer6343")
                && GLContext.getCapabilities().OpenGL30;
        }
        return available;
    }

    /**
     * Builds (or rebuilds) one controller's picture now, replacing any cached one, and returns the result with its
     * pixels. For the dev harness; call on the render thread with a world loaded.
     */
    public static Build rebuild(final int meta) {
        QUEUED.remove(meta);
        NONE.remove(meta);
        final Build build = build(meta);
        install(build);
        return build;
    }

    /** Every constructable GT multiblock controller as {@code {meta, name}}, for the dev harness. */
    public static List<Map<String, Object>> controllers() {
        if (!available()) return new ArrayList<>();
        try {
            return MultiblockRenderer.controllers();
        } catch (final LinkageError e) {
            disable(e);
            return new ArrayList<>();
        }
    }

    private static int controllerMeta(final ItemStack stack) {
        try {
            return MultiblockRenderer.controllerMeta(stack);
        } catch (final LinkageError e) {
            disable(e);
            return -1;
        }
    }

    private static Build build(final int meta) {
        try {
            return MultiblockRenderer.render(meta, SIZE, SUPERSAMPLE, Hyb.PICTURE);
        } catch (final LinkageError e) {
            // BlockRenderer6343, StructureLib or GregTech is not the version this was built against.
            disable(e);
            return Build.failed(meta, e);
        } catch (final Throwable t) {
            PlanNH.LOG.warn("No structure picture for GregTech machine {}: building it failed", meta, t);
            return Build.failed(meta, t);
        }
    }

    private static void install(final Build build) {
        final Picture old = PICTURES.remove(build.meta());
        if (old != null) old.delete();
        if (build.argb() == null) {
            NONE.put(build.meta(), build.status());
            PlanNH.LOG.debug("No structure picture for GregTech machine {}: {}", build.meta(), build.status());
            return;
        }
        try {
            PICTURES.put(build.meta(), Picture.upload(build.meta(), build.argb()));
        } catch (final Throwable t) {
            PlanNH.LOG.warn("No structure picture for GregTech machine {}: texture upload failed", build.meta(), t);
            NONE.put(build.meta(), "failed: " + t);
        }
    }

    private static void disable(final Throwable cause) {
        if (Boolean.FALSE.equals(available)) return;
        available = false;
        QUEUED.clear();
        PlanNH.LOG.warn("Multiblock structure pictures disabled: incompatible BlockRenderer6343/StructureLib", cause);
    }

    private static void registerTicker() {
        if (tickerRegistered) return;
        tickerRegistered = true;
        FMLCommonHandler.instance()
            .bus()
            .register(new Ticker());
    }

    /** Builds one queued picture after each frame, so the GUI's own drawing is never interrupted. */
    public static final class Ticker {

        @SubscribeEvent
        public void onRenderTick(final TickEvent.RenderTickEvent event) {
            if (event.phase != TickEvent.Phase.END || QUEUED.isEmpty() || !available()) return;
            // Tile entity renderers light themselves from the client world; wait for one.
            if (Minecraft.getMinecraft().theWorld == null) return;
            final Iterator<Integer> next = QUEUED.iterator();
            final int meta = next.next();
            next.remove();
            install(build(meta));
        }
    }

    /**
     * One build's outcome. {@code status} is "ok", "not a multiblock", "empty", "too big" or "failed: ...";
     * {@code argb} ({@code SIZE * SIZE}, top row first, opaque) is null unless ok.
     */
    public record Build(int meta, String name, String status, int sizeX, int sizeY, int sizeZ, int blocks,
        long buildMillis, long renderMillis, int[] argb) {

        static Build failed(final int meta, final Throwable t) {
            return new Build(meta, "", "failed: " + t, 0, 0, 0, 0, 0, 0, null);
        }
    }

    /** A ready structure picture: an opaque square texture on the well's own background. */
    public static final class Picture {

        private final ResourceLocation location;
        private final PictureTexture texture;

        private Picture(final ResourceLocation location, final PictureTexture texture) {
            this.location = location;
            this.texture = texture;
        }

        public ResourceLocation location() {
            return location;
        }

        /** Draws the picture as the largest square that fits the rectangle, centred. */
        public void draw(final float x, final float y, final float w, final float h) {
            final float side = Math.min(w, h);
            final float px = x + (w - side) / 2f, py = y + (h - side) / 2f;
            GL11.glColor4f(1f, 1f, 1f, 1f);
            GuiDraw.drawTexture(location, px, py, px + side, py + side, 0f, 0f, 1f, 1f, true);
        }

        private static Picture upload(final int meta, final int[] argb) {
            final PictureTexture texture = new PictureTexture();
            texture.upload(argb, SIZE);
            final ResourceLocation location = new ResourceLocation(PlanNH.MODID, "structure_pictures/" + meta);
            // Already uploaded: registering only makes the location bindable (and survives resource reloads).
            Minecraft.getMinecraft()
                .getTextureManager()
                .loadTexture(location, texture);
            return new Picture(location, texture);
        }

        private void delete() {
            texture.deleteGlTexture();
        }
    }

    /** A texture uploaded once from pixels, with mipmaps for the zoomed-out board. */
    private static final class PictureTexture extends AbstractTexture {

        @Override
        public void loadTexture(final IResourceManager resources) {}

        void upload(final int[] argb, final int size) {
            final int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            try {
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, getGlTextureId());
                final IntBuffer pixels = BufferUtils.createIntBuffer(argb.length);
                pixels.put(argb)
                    .flip();
                // ARGB ints as Minecraft uploads them.
                GL11.glTexImage2D(
                    GL11.GL_TEXTURE_2D,
                    0,
                    GL11.GL_RGBA8,
                    size,
                    size,
                    0,
                    GL12.GL_BGRA,
                    GL12.GL_UNSIGNED_INT_8_8_8_8_REV,
                    pixels);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
                GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
            } finally {
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous);
            }
        }
    }
}

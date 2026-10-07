package com.sbancuz.plannh.ui.card;

import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ResourceLocation;

import org.jetbrains.annotations.Nullable;

import com.sbancuz.plannh.PlanNH;

/**
 * Pictures of whole multiblock structures for the card's picture well, as Factory Flow shows them: the owner's renders
 * of processing multiblocks and the Power Planner workbook's renders of power plants, both from Factory Flow,
 * bundled as {@code assets/plannh/textures/structures/<id>.png} (320 px). A machine finds its picture by its display
 * name made into an id ("Electric Blast Furnace" -> electric-blast-furnace), or through {@link #ALIASES} where the
 * game's name and Factory Flow's id differ. Machines without one keep their item icon.
 */
public final class StructureArt {

    /**
     * Display-name ids (as the game names the controller) that Factory Flow files under another id. Checked against
     * the GTNH 2.9 dev client's machine list (harness `/gtmachines`).
     */
    private static final Map<String, String> ALIASES = Map.ofEntries(
        // Processing
        Map.entry("industrial-electrolyzer", "multiblock-electrolyzer"),
        Map.entry("exxonmobil-chemical-plant", "chemical-plant"),
        Map.entry("large-sifter-control-block", "large-sifter"),
        // Turbines (the XL HP and SC steam turbines share the XL steam turbine's render)
        Map.entry("large-supercritical-steam-turbine", "large-sc-steam-turbine"),
        Map.entry("xl-turbo-hp-steam-turbine", "xl-turbo-steam-turbine"),
        Map.entry("xl-turbo-sc-steam-turbine", "xl-turbo-steam-turbine"),
        Map.entry("large-plasma-turbine", "large-plasma-generator"),
        // Generators and engines
        Map.entry("solid-oxide-fuel-cell-mk-i", "solid-oxide-fuel-cell-1"),
        Map.entry("solid-oxide-fuel-cell-mk-ii", "solid-oxide-fuel-cell-2"),
        Map.entry("large-semifluid-burner", "large-semifluid-generator"),
        Map.entry("rocketdyne-f-1a-engine", "large-rocket-engine"),
        Map.entry("deep-earth-heating-pump", "dehp"),
        Map.entry("nuclear-reactor", "ic2-fluid-reactor"),
        // Reactors
        Map.entry("thorium-high-temperature-reactor", "thtr"),
        Map.entry("high-temperature-gas-cooled-reactor", "htgr"),
        Map.entry("thorium-reactor-lftr", "lftr"),
        Map.entry("liquid-fluoride-thorium-reactor", "lftr"),
        Map.entry("semi-stable-antimatter-stabilization-sequencer", "antimatter"),
        Map.entry("antimatter-generator", "antimatter"),
        // Fusion: every Mark and FusionTech tier shares one render, the compact computers another
        Map.entry("fusion-control-computer-mark-i", "fusion-reactor"),
        Map.entry("fusion-control-computer-mark-ii", "fusion-reactor"),
        Map.entry("fusion-control-computer-mark-iii", "fusion-reactor"),
        Map.entry("fusiontech-mk-iv", "fusion-reactor"),
        Map.entry("fusiontech-mk-v", "fusion-reactor"),
        Map.entry("compact-fusion-computer-mk-i-prototype", "compact-fusion-reactor"),
        Map.entry("compact-fusion-computer-mk-ii", "compact-fusion-reactor"),
        Map.entry("compact-fusion-computer-mk-iii", "compact-fusion-reactor"),
        Map.entry("compact-fusion-computer-mk-iv-prototype", "compact-fusion-reactor"),
        Map.entry("compact-fusion-computer-mk-v", "compact-fusion-reactor"));

    /**
     * A bundled picture, its size in pixels (so the card can fit it without stretching), the average colour of its
     * opaque pixels (the zoomed-out card is tinted with it), and its shadow: the picture's shape blurred, in black,
     * {@link #SHADOW_PAD} pixels larger on every side.
     */
    public record Art(ResourceLocation location, int width, int height, int tint, ResourceLocation shadow) {}

    /** The shadow's blur: three passes of a box this many pixels each way, about a Gaussian of 7 px. */
    private static final int BLUR = 7, PASSES = 3;
    /** How far the blurred shadow reaches past the picture, in its pixels. */
    public static final int SHADOW_PAD = BLUR * PASSES + 2;

    private static final Map<String, Art> FOUND = new HashMap<>();

    private StructureArt() {}

    /** The picture for a machine name, or null when none is bundled. Looks each name up once. */
    @Nullable
    public static Art forMachine(final String displayName) {
        if (displayName == null || displayName.isEmpty()) return null;
        // Misses are cached too (as null): most machines have no picture, and the lookup throws when it misses.
        if (FOUND.containsKey(displayName)) return FOUND.get(displayName);
        final Art art = find(displayName);
        FOUND.put(displayName, art);
        return art;
    }

    /** The average colour of a picture's opaque pixels (every other pixel is plenty). */
    private static int averageColor(final java.awt.image.BufferedImage image) {
        long r = 0, g = 0, b = 0, n = 0;
        for (int y = 0; y < image.getHeight(); y += 2) {
            for (int x = 0; x < image.getWidth(); x += 2) {
                final int p = image.getRGB(x, y);
                if (p >>> 24 < 128) continue;
                r += p >> 16 & 0xFF;
                g += p >> 8 & 0xFF;
                b += p & 0xFF;
                n++;
            }
        }
        return n == 0 ? 0x8A93A6 : (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
    }

    private static Art find(final String displayName) {
        String id = displayName.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("(^-|-$)", "");
        id = ALIASES.getOrDefault(id, id);
        final ResourceLocation loc = new ResourceLocation(PlanNH.MODID, "textures/structures/" + id + ".png");
        try (java.io.InputStream in = Minecraft.getMinecraft()
            .getResourceManager()
            .getResource(loc)
            .getInputStream()) {
            final java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(in);
            return image == null ? null
                : new Art(loc, image.getWidth(), image.getHeight(), averageColor(image), shadowOf(id, image));
        } catch (final java.io.FileNotFoundException e) {
            return null;
        } catch (final IOException | RuntimeException e) {
            PlanNH.LOG.warn("Could not read the structure picture {}", loc, e);
            return null;
        }
    }

    /**
     * The picture's shadow, as Factory Flow's CSS drop-shadow draws it: its alpha, padded, blurred, in black. Uploaded
     * once with smooth filtering; the card draws it offset under the picture.
     */
    private static ResourceLocation shadowOf(final String id, final java.awt.image.BufferedImage image) {
        final int pad = SHADOW_PAD, w = image.getWidth() + 2 * pad, h = image.getHeight() + 2 * pad;
        float[] a = new float[w * h], b = new float[w * h];
        for (int y = 0; y < image.getHeight(); y++)
            for (int x = 0; x < image.getWidth(); x++) a[(y + pad) * w + x + pad] = (image.getRGB(x, y) >>> 24) / 255f;
        for (int pass = 0; pass < PASSES; pass++) {
            boxBlur(a, b, w, h, 1, w);
            boxBlur(b, a, h, w, w, 1);
        }
        final java.awt.image.BufferedImage mask = new java.awt.image.BufferedImage(
            w,
            h,
            java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) mask.setRGB(x, y, Math.round(Math.min(1, a[y * w + x]) * 255) << 24);
        final ResourceLocation loc = new ResourceLocation(PlanNH.MODID, "structure_shadows/" + id);
        Minecraft.getMinecraft()
            .getTextureManager()
            .loadTexture(loc, new ShadowTexture(mask));
        return loc;
    }

    /**
     * One box-blur pass along lines of {@code len} values {@code step} apart, {@code lines} of them {@code stride}
     * apart: a running sum over 2 * BLUR + 1 values, nothing outside the image.
     */
    private static void boxBlur(final float[] src, final float[] dst, final int len, final int lines, final int step,
        final int stride) {
        final float norm = 1f / (2 * BLUR + 1);
        for (int line = 0; line < lines; line++) {
            final int base = line * stride;
            float sum = 0;
            for (int i = 0; i <= BLUR && i < len; i++) sum += src[base + i * step];
            for (int i = 0; i < len; i++) {
                dst[base + i * step] = sum * norm;
                final int in = i + BLUR + 1, out = i - BLUR;
                if (in < len) sum += src[base + in * step];
                if (out >= 0) sum -= src[base + out * step];
            }
        }
    }

    /** A texture made in memory, filtered smoothly (a shadow is all soft edges). */
    private static final class ShadowTexture extends net.minecraft.client.renderer.texture.AbstractTexture {

        private final java.awt.image.BufferedImage image;

        ShadowTexture(final java.awt.image.BufferedImage image) {
            this.image = image;
        }

        @Override
        public void loadTexture(final net.minecraft.client.resources.IResourceManager resources) {
            net.minecraft.client.renderer.texture.TextureUtil
                .uploadTextureImageAllocate(getGlTextureId(), image, true, true);
        }
    }
}

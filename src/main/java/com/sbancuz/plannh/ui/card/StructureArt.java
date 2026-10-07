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

    /** A bundled picture and its size in pixels, so the card can fit it without stretching. */
    public record Art(ResourceLocation location, int width, int height) {}

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
            return image == null ? null : new Art(loc, image.getWidth(), image.getHeight());
        } catch (final java.io.FileNotFoundException e) {
            return null;
        } catch (final IOException | RuntimeException e) {
            PlanNH.LOG.warn("Could not read the structure picture {}", loc, e);
            return null;
        }
    }
}

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
 * Pictures of whole multiblock structures for the card's picture well: the owner's renders shipped with Factory Flow,
 * bundled as {@code assets/plannh/textures/structures/<id>.png} (320 px). A machine finds its picture by its display
 * name made into an id ("Electric Blast Furnace" -> electric-blast-furnace), or through {@link #ALIASES} where the
 * game's name and Factory Flow's id differ. Machines without one keep their item icon.
 */
final class StructureArt {

    /** Display-name ids that Factory Flow files under another id. */
    private static final Map<String, String> ALIASES = Map.of(
        "industrial-electrolyzer",
        "multiblock-electrolyzer",
        "exxonmobil-chemical-plant",
        "chemical-plant",
        "large-sifter-control-block",
        "large-sifter");

    private static final Map<String, ResourceLocation> FOUND = new HashMap<>();

    private StructureArt() {}

    /** The picture for a machine name, or null when none is bundled. Looks each name up once. */
    @Nullable
    static ResourceLocation forMachine(final String displayName) {
        if (displayName == null || displayName.isEmpty()) return null;
        return FOUND.computeIfAbsent(displayName, StructureArt::find);
    }

    private static ResourceLocation find(final String displayName) {
        String id = displayName.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("(^-|-$)", "");
        id = ALIASES.getOrDefault(id, id);
        final ResourceLocation loc = new ResourceLocation(PlanNH.MODID, "textures/structures/" + id + ".png");
        try {
            Minecraft.getMinecraft()
                .getResourceManager()
                .getResource(loc);
            return loc;
        } catch (final IOException | RuntimeException e) {
            return null;
        }
    }
}

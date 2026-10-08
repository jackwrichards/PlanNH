package com.gtnhplanner.ui.card;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import net.minecraft.item.ItemStack;

import com.gtnhplanner.data.MachineConfig;
import com.gtnhplanner.data.RecipeContext;
import com.gtnhplanner.data.SettingDef;
import com.gtnhplanner.ui.gt.GtCoils;

/**
 * What a card's settings strip shows, along its bottom: on a recipe card the coil (heat recipes), parallels when more
 * than one, and every setting changed from its default (the rest stay in the settings menu); on a power card each
 * setting in use (what it burns, its rotor, its flow...) and then its readings. The board's cards and the cards over
 * the world and on the minimap show the same chips.
 */
public final class CardChips {

    public enum Kind {
        /** The coil: pick one. */
        COIL,
        /** Parallels, for reading. */
        PARALLEL,
        /** A recipe setting changed from its default: the settings menu changes it. */
        SETTING,
        /** A power card's setting, {@link Chip#tile} its tile among {@link PowerTiles#of}: pick, toggle or type. */
        POWER,
        /** A power card's reading. */
        READING
    }

    /** A chip: what it is, its label and value, its icon, whether it stops the recipe (a coil too cold). */
    public record Chip(Kind kind, String label, String value, @Nullable ItemStack icon, boolean warn, int tile) {}

    /** What a card shows of its own, so not again among its settings. */
    private static final Set<String> SHOWN = Set
        .of("voltage", "amp", "machines", "gt_multiblock", "machine_heat", "parallels");

    private CardChips() {}

    public static List<Chip> of(final CardModel m) {
        final List<Chip> out = new ArrayList<>();
        if (m.isPower()) {
            final com.gtnhplanner.power.PowerSource source = m.power.source();
            final List<PowerTiles.Tile> tiles = PowerTiles.of(m);
            for (int i = 0; i < tiles.size(); i++) {
                final PowerTiles.Tile t = tiles.get(i);
                if (t.isSetting()) {
                    // A setting another one makes moot (a custom flow while the flow is optimal) is left off.
                    if (source != null && !PowerTiles.enabled(source, t.setting(), m.node.powerSettings)) continue;
                    out.add(
                        new Chip(
                            Kind.POWER,
                            PowerTiles.caption(t.setting()),
                            PowerTiles.shown(t.setting(), m.node.powerSettings),
                            null,
                            false,
                            i));
                } else out.add(
                    new Chip(
                        Kind.READING,
                        t.stat()
                            .label(),
                        t.stat()
                            .value(),
                        null,
                        false,
                        i));
            }
            return out;
        }
        if (m.usesHeat) {
            final GtCoils.Coil coil = m.coilHeat > 0 ? GtCoils.forHeat(m.coilHeat) : null;
            out.add(
                new Chip(
                    Kind.COIL,
                    "Coil",
                    coil == null ? "pick one" : RecipeCard.shortCoilName(coil.name()),
                    coil == null ? null : coil.stack(),
                    coil == null || m.recipeHeat > coil.heat(),
                    -1));
        }
        if (m.parallels > 1) out.add(new Chip(Kind.PARALLEL, "Parallel", "×" + m.parallels, null, false, -1));
        final MachineConfig cfg = m.node.machineConfig;
        if (cfg == null || cfg.getProfile() == null) return out;
        for (final SettingDef<?> def : cfg.getProfile()
            .visibleSettings(new RecipeContext(m.node.properties), cfg.settings)) {
            final Object value = cfg.settings.get(def.key);
            if (SHOWN.contains(def.key) || value == null || value.equals(def.defaultValue)) continue;
            out.add(
                new Chip(
                    Kind.SETTING,
                    def.label,
                    value instanceof final Boolean b ? b ? "on" : "off" : String.valueOf(value),
                    null,
                    false,
                    -1));
        }
        return out;
    }
}

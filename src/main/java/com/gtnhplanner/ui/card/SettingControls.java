package com.gtnhplanner.ui.card;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import net.minecraft.item.ItemStack;

import com.gtnhplanner.data.MachineConfig;
import com.gtnhplanner.data.RecipeContext;
import com.gtnhplanner.data.SettingDef;
import com.gtnhplanner.power.PowerSetting;
import com.gtnhplanner.power.PowerSource;
import com.gtnhplanner.ui.gt.GtCoils;

/**
 * A card's settings as the settings sheet lists them and its chips show them: on a recipe card the machine's
 * settings (the coil among them on a heat recipe), grouped; on a power card its settings and then its readings. The
 * amps, tier and machine count are left out: the card has its own controls for them.
 */
public final class SettingControls {

    /** How a setting is changed: switched, stepped or typed, picked from a list, or not at all. */
    public enum Type {
        TOGGLE,
        NUMBER,
        CHOICE,
        COIL,
        READING
    }

    /**
     * A setting: its key ({@code machine_heat} for the coil, {@code power:<id>} on a power card,
     * {@code reading:<label>}
     * for a reading), its group's heading, label and value as shown, its icon (the coil), whether it stops the recipe
     * (a coil too cold), whether another setting makes it moot, and whether it shows on the card until pins are set.
     */
    public record Control(String key, String group, String label, String value, @Nullable ItemStack icon, boolean warn,
        boolean enabled, Type type, boolean pinDefault) {}

    /** The card's own controls: never listed. */
    private static final Set<String> OWN = Set.of("voltage", "amp", "machines");
    private static final Set<String> OVERCLOCKING = Set.of(
        "perfect_oc",
        "laser_oc",
        "no_overclock",
        "max_overclocks",
        "max_regular_oc",
        "max_tier_skips",
        "unlimited_skips",
        "eut_discount",
        "eut_increase_per_oc",
        "duration_decrease_per_oc");
    private static final Set<String> HEAT = Set.of("heat_oc", "recipe_heat", "heat_discount", "heat_discount_mult");
    private static final String[] GROUPS = { "MACHINE", "OVERCLOCKING", "HEAT" };

    private SettingControls() {}

    /** Every setting of the card, in the sheet's order. */
    public static List<Control> of(final CardModel m) {
        return m.isPower() ? power(m) : recipe(m);
    }

    /** The settings the card shows as chips, as the plan has them pinned. */
    public static List<Control> pinned(final com.gtnhplanner.data.flowchart.Graph plan, final CardModel m) {
        final List<Control> out = new ArrayList<>();
        for (final Control c : of(m)) if (SettingPins.pinned(plan, m, c)) out.add(c);
        return out;
    }

    /** A recipe setting's definition, by key; null when the machine has no such setting. */
    @Nullable
    public static SettingDef<?> def(final CardModel m, final String key) {
        final MachineConfig cfg = m.node.machineConfig;
        if (cfg == null || cfg.getProfile() == null) return null;
        for (final SettingDef<?> d : cfg.getProfile()
            .visibleSettings(new RecipeContext(m.node.properties), cfg.settings)) if (d.key.equals(key)) return d;
        return null;
    }

    /** Whether the card's coil stops its recipe: none picked, or too cold. */
    public static boolean coilTooCold(final CardModel m) {
        if (!m.usesHeat) return false;
        final GtCoils.Coil coil = m.coilHeat > 0 ? GtCoils.forHeat(m.coilHeat) : null;
        return coil == null || m.recipeHeat > coil.heat();
    }

    private static List<Control> recipe(final CardModel m) {
        final List<Control> out = new ArrayList<>();
        final MachineConfig cfg = m.node.machineConfig;
        if (cfg == null || cfg.getProfile() == null) return out;
        final List<SettingDef<?>> defs = cfg.getProfile()
            .visibleSettings(new RecipeContext(m.node.properties), cfg.settings);
        for (final String group : GROUPS) {
            // The coil first, at the top of the machine's settings.
            if ("MACHINE".equals(group) && m.usesHeat) {
                final GtCoils.Coil coil = m.coilHeat > 0 ? GtCoils.forHeat(m.coilHeat) : null;
                out.add(
                    new Control(
                        "machine_heat",
                        group,
                        "Coil",
                        coil == null ? "None" : RecipeCard.shortCoilName(coil.name()),
                        coil == null ? null : coil.stack(),
                        coilTooCold(m),
                        true,
                        Type.COIL,
                        true));
            }
            for (final SettingDef<?> d : defs) {
                if (OWN.contains(d.key) || "machine_heat".equals(d.key) || !group.equals(groupOf(d.key))) continue;
                final Type type = d.type == Boolean.class ? Type.TOGGLE
                    : d.type == Integer.class ? Type.NUMBER
                        : d.options != null && !d.options.isEmpty() ? Type.CHOICE : null;
                if (type == null) continue;
                final String value = switch (type) {
                    case TOGGLE -> CardDefaults.boolSetting(cfg, d.key) ? "On" : "Off";
                    case NUMBER -> Integer.toString(CardDefaults.intSetting(cfg, d.key));
                    default -> CardDefaults.stringSetting(cfg, d.key);
                };
                out.add(new Control(d.key, group, d.label, value, null, false, true, type, false));
            }
        }
        return out;
    }

    private static String groupOf(final String key) {
        return OVERCLOCKING.contains(key) ? "OVERCLOCKING" : HEAT.contains(key) ? "HEAT" : "MACHINE";
    }

    private static List<Control> power(final CardModel m) {
        final List<Control> out = new ArrayList<>();
        final PowerSource source = m.power.source();
        for (final PowerTiles.Tile t : PowerTiles.of(m)) {
            if (t.isSetting()) {
                final PowerSetting s = t.setting();
                final Type type = s instanceof PowerSetting.Toggle ? Type.TOGGLE
                    : s instanceof PowerSetting.Number ? Type.NUMBER : Type.CHOICE;
                out.add(
                    new Control(
                        "power:" + s.id(),
                        "SETTINGS",
                        PowerTiles.caption(s),
                        PowerTiles.shown(s, m.node.powerSettings),
                        null,
                        false,
                        source != null && PowerTiles.enabled(source, s, m.node.powerSettings),
                        type,
                        false));
            } else out.add(
                new Control(
                    "reading:" + t.stat()
                        .label(),
                    "READINGS",
                    t.stat()
                        .label(),
                    t.stat()
                        .value(),
                    null,
                    false,
                    true,
                    Type.READING,
                    false));
        }
        return out;
    }
}

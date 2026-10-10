package com.gtnhplanner.machines.game;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import net.minecraft.item.ItemStack;

import com.gtnhplanner.data.MachineConfig;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.importer.game.GameIds;
import com.gtnhplanner.machines.web.Heat;
import com.gtnhplanner.machines.web.NodeMath;
import com.gtnhplanner.machines.web.RecipeRules;
import com.gtnhplanner.machines.web.Web;

/**
 * A GregTech card's settings as the website offers them (machines/web): the heating coil, and each of the machine's
 * own settings (its casings, pipes, modes, counts), in the website's order and with its rungs from the recipe's
 * minimum. Stored under the keys {@link WebCards} reads. The energy hatch type is carried with a plan but not offered:
 * once a card has its tier and amps it changes no number.
 */
public final class WebSettings {

    private WebSettings() {}

    public static final String COIL = WebCards.COIL, HATCH_TYPE = WebCards.HATCH_TYPE;

    /** Whether a card setting key is one of these (stored as is, remembered for the machine's next card). */
    public static boolean isKey(final String key) {
        return key.equals(COIL) || key.equals(HATCH_TYPE) || key.startsWith(WebCards.CONTROL_PREFIX);
    }

    /** The card's heating coil: its key, name, heat, block, and the heat the recipe needs (0 when it reads none). */
    public record Coil(String key, String label, int heat, int recipeHeat, @Nullable ItemStack icon) {}

    /** The coil the card runs with, or null when its machine has none that the website models. */
    @Nullable
    public static Coil coil(final Node node, final MachineConfig cfg, final NodeMath.Result result) {
        final Web.Recipe effective = result.effectiveRecipe();
        final RecipeRules.TierControl c = RecipeRules.coilTierControl(effective, string(cfg.settings.get(COIL)));
        if (c == null) return null;
        final Integer special = RecipeRules.specialValue(effective);
        final boolean heat = Heat.isHeatOverclockMachine(effective.machineType) && special != null && special > 0;
        return new Coil(
            c.current().key,
            c.current().label,
            c.current().heat == null ? 0 : (int) Math.round(c.current().heat),
            heat ? special : 0,
            icon(c.current().resource));
    }

    /** The card's settings, as its sheet and chips offer them; empty when the website's maths do not cover it. */
    public static List<MachineModels.Setting> settings(final Node node) {
        final List<MachineModels.Setting> out = new ArrayList<>();
        final MachineConfig cfg = node.machineConfig;
        final NodeMath.Result result = WebEffect.result(node, cfg);
        if (result == null) return out;
        final Web.Recipe recipe = WebEffect.recipe(node);
        final Web.Node web = WebCards.node(node, cfg, recipe);
        final Web.Recipe effective = result.effectiveRecipe();

        final RecipeRules.TierControl coil = RecipeRules.coilTierControl(effective, web.coilTier);
        if (coil != null) out.add(setting(COIL, coil));
        for (final RecipeRules.TierControl c : RecipeRules.configTierControls(effective, web.machineConfigTiers))
            out.add(setting(WebCards.CONTROL_PREFIX + c.id(), c));
        return out;
    }

    private static MachineModels.Setting setting(final String key, final RecipeRules.TierControl c) {
        if (c.numeric() != null) return new MachineModels.Setting(
            key,
            c.label(),
            c.current().key,
            null,
            List.of(),
            true,
            c.numeric().min,
            c.numeric().max != null ? c.numeric().max : Double.MAX_VALUE,
            c.numeric().step != null ? c.numeric().step : 1,
            false);
        final List<MachineModels.Option> options = new ArrayList<>();
        for (final Web.TierOption t : c.tiers())
            options.add(new MachineModels.Option(t.key, t.label, icon(t.resource)));
        return choice(key, c.label(), c.current().key, options, false);
    }

    private static MachineModels.Setting choice(final String key, final String label, final String value,
        final List<MachineModels.Option> options, final boolean warn) {
        MachineModels.Option current = null;
        for (final MachineModels.Option o : options) if (o.key()
            .equals(value)) current = o;
        return new MachineModels.Setting(
            key,
            label,
            current == null ? value : current.label(),
            current == null ? null : current.icon(),
            options,
            false,
            0,
            0,
            warn);
    }

    @Nullable
    private static String string(@Nullable final Object value) {
        return value instanceof final String s && !s.isEmpty() ? s : null;
    }

    private static final Map<String, ItemStack> ICONS = new HashMap<>();

    /** A setting option's block or item, from its website id; null for the website's drawn-only icons. */
    @Nullable
    private static ItemStack icon(@Nullable final Web.Resource resource) {
        return resource == null || resource.id == null || resource.id.startsWith("factoryflow:") ? null
            : stack(resource.id);
    }

    @Nullable
    private static ItemStack stack(final String id) {
        if (ICONS.containsKey(id)) return ICONS.get(id);
        ItemStack stack = null;
        try {
            stack = GameIds.stackOf(id);
        } catch (final RuntimeException ignored) {
            // An id this pack lacks: no icon.
        }
        ICONS.put(id, stack);
        return stack;
    }
}

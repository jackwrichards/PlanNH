package com.gtnhplanner.machines.web;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

/**
 * What a machine and its settings do to a recipe (src/lib/solver/machine-effects.ts): the context its table entry
 * reads, its parallels (structure, capped by what the power pays for), and its duration, EU/t and output multipliers.
 * The website's crops and bees are left out (the mod has no CropsNH, and bees have their own provider); the Tree Growth
 * Simulator and Bacterial Vat are the mod's own models (machines/), never routed here.
 */
public final class MachineEffects {

    private MachineEffects() {}

    private static final Pattern TRAILING_NUMBER = Pattern.compile("(\\d+)$");

    /** buildMachineContext: the node's settings as the table's formulas read them. */
    public static MachineTable.Context context(final Web.Recipe recipe, final Web.Node node) {
        final List<RecipeRules.TierControl> controls = new ArrayList<>(
            RecipeRules.configTierControls(recipe, node.machineConfigTiers));
        final RecipeRules.TierControl coil = RecipeRules.coilTierControl(recipe, node.coilTier);
        if (coil != null) controls.add(coil);
        return new MachineTable.Context(id -> {
            final RecipeRules.TierControl c = find(controls, id);
            if (c == null) return 0;
            // The position on the FULL ladder: a per-recipe minimum must not renumber the rungs above it.
            return c.minimumIndex() + Math.max(0, indexOf(c.tiers(), c.current().key));
        }, id -> {
            final RecipeRules.TierControl c = find(controls, id);
            if (c == null) return 0;
            // Count settings key their options by the number ("8") or end in it ("slice-3").
            final String key = c.current().key;
            final double parsed = Js.number(key);
            if (Double.isFinite(parsed)) return parsed;
            final Matcher m = TRAILING_NUMBER.matcher(key);
            if (m.find()) return Double.parseDouble(m.group(1));
            return Math.max(0, indexOf(c.tiers(), key));
        },
            Power.effectiveVoltageOrdinal(recipe, node, Power.runTier(recipe, node)),
            Tiers.index(Tiers.forEuT(Math.abs(recipe.eut))),
            RecipeRules.specialValue(recipe),
            recipe.source != null && recipe.source.recipeMap != null ? recipe.source.recipeMap : recipe.machineType,
            recipe,
            node.machineConfigTiers);
    }

    @Nullable
    private static RecipeRules.TierControl find(final List<RecipeRules.TierControl> controls, final String id) {
        for (final RecipeRules.TierControl c : controls) if (c.id()
            .equals(id)) return c;
        return null;
    }

    private static int indexOf(final List<Web.TierOption> tiers, final String key) {
        for (int i = 0; i < tiers.size(); i++) if (tiers.get(i).key.equals(key)) return i;
        return -1;
    }

    /** getMachineOutputMultiplier: every setting's output multiplier, multiplied. */
    public static double outputMultiplier(final Web.Recipe recipe, final Web.Node node, final Web.Resource output,
        final String tier) {
        if (ExtremeEntityCrusher.isRecipe(recipe)) return ExtremeEntityCrusher
            .outputMultiplier(recipe, output, ExtremeEntityCrusher.settings(node.machineConfigTiers));
        double multiplier = 1;
        for (final RecipeRules.TierControl c : RecipeRules.configTierControls(recipe, node.machineConfigTiers))
            multiplier *= c.current().outputMultiplier != null ? c.current().outputMultiplier : 1;
        return multiplier;
    }

    /** getMachineParallelMultiplier: the structure's parallels, as many as the power pays for. */
    public static double parallelMultiplier(final Web.Recipe recipe, final Web.Node node) {
        return Math.min(structuralParallels(recipe, node), poweredParallelLimit(recipe, node));
    }

    /**
     * getMachineStructuralParallels: a reactor's own, the table's formula, or the dataset's settings (fixed, and GT++'s
     * "Voltage Tier * n" read off the summed hatches' tier).
     */
    public static double structuralParallels(final Web.Recipe recipe, final Web.Node node) {
        final Fusion.Stats fusion = Fusion.stats(recipe);
        if (fusion != null) return fusion.parallels();
        final int tierOrdinal = Math.max(1, Power.effectiveVoltageOrdinal(recipe, node, Power.runTier(recipe, node)));
        final MachineTable.Behaviour b = MachineTable.behaviour(recipe.machineType);
        if (b != null) return Math.max(1, Math.floor(MachineTable.resolve(b.parallels, context(recipe, node), 1)));
        double multiplier = 1;
        for (final RecipeRules.TierControl c : RecipeRules.configTierControls(recipe, node.machineConfigTiers)) {
            final double fixed = c.current().parallelMultiplier != null ? c.current().parallelMultiplier : 1;
            final Double perTier = c.current().parallelPerVoltageTier;
            final double base = c.current().parallelVoltageBase != null ? c.current().parallelVoltageBase : 0;
            final double scaled = perTier != null && Double.isFinite(perTier)
                ? Math.max(1, Math.floor(base + perTier * tierOrdinal))
                : 1;
            multiplier = multiplier * fixed * scaled;
        }
        return multiplier;
    }

    /**
     * getPoweredParallelLimit: how many parallels the pool carries. Energy and heat discounts land first
     * (ParallelHelper folds them into the recipe's draw), so a discounted recipe fits more.
     */
    private static double poweredParallelLimit(final Web.Recipe recipe, final Web.Node node) {
        final String runTier = Power.runTier(recipe, node);
        final double available = Power.poolEuT(recipe, node, runTier);
        if (!Double.isFinite(available)) return Double.POSITIVE_INFINITY;
        final double recipeEuT = Math.abs(recipe.eut) * eutMultiplier(recipe, node)
            * Heat.discount(recipe, node, runTier, Power.effectiveVoltageOrdinal(recipe, node, runTier));
        if (!(recipeEuT > 0)) return Double.POSITIVE_INFINITY;
        return Math.max(1, Math.floor(available / recipeEuT));
    }

    /** getMachineDurationMultiplier: the table's speed inverted, else the coil's and settings' multipliers. */
    public static double durationMultiplier(final Web.Recipe recipe, final Web.Node node) {
        final MachineTable.Behaviour b = MachineTable.behaviour(recipe.machineType);
        if (b != null) {
            final double speed = MachineTable.resolve(b.speed, context(recipe, node), 1);
            return speed > 0 ? 1 / speed : 1;
        }
        final RecipeRules.TierControl coil = RecipeRules.coilTierControl(recipe, node.coilTier);
        final double coilMultiplier = coil != null && coil.current().durationMultiplier != null
            ? coil.current().durationMultiplier
            : 1;
        double config = 1;
        for (final RecipeRules.TierControl c : RecipeRules.configTierControls(recipe, node.machineConfigTiers))
            config *= c.current().durationMultiplier != null ? c.current().durationMultiplier : 1;
        return coilMultiplier * config;
    }

    /** getMachineEutMultiplier: the table's power, else the coil's and settings' multipliers. */
    public static double eutMultiplier(final Web.Recipe recipe, final Web.Node node) {
        final MachineTable.Behaviour b = MachineTable.behaviour(recipe.machineType);
        if (b != null) return MachineTable.resolve(b.power, context(recipe, node), 1);
        final RecipeRules.TierControl coil = RecipeRules.coilTierControl(recipe, node.coilTier);
        final double coilMultiplier = coil != null && coil.current().eutMultiplier != null
            ? coil.current().eutMultiplier
            : 1;
        double config = 1;
        for (final RecipeRules.TierControl c : RecipeRules.configTierControls(recipe, node.machineConfigTiers))
            config *= c.current().eutMultiplier != null ? c.current().eutMultiplier : 1;
        return coilMultiplier * config;
    }
}

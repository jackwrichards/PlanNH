package com.gtnhplanner.machines.web;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * The game's own overclock ladder where the website prefers it (src/lib/solver/runtime-calculation.ts): a recipe's
 * runtime variants are GT's OverclockCalculator run at each tier, with one parallel. A machine the curated table covers
 * (and fusion) runs on the port's own maths instead, as does a multiblock whose hatches are given; every other recipe
 * takes the variant that matches its tier, coil and settings.
 */
public final class RuntimeCalculation {

    private RuntimeCalculation() {}

    /** prefersCuratedMachineMath: fusion, or a machine the table covers. */
    public static boolean prefersCuratedMath(final Web.Recipe recipe) {
        return Fusion.isRecipe(recipe) || MachineTable.behaviour(recipe.machineType) != null;
    }

    /** selectRuntimeCalculationVariant: the best matching variant, or null for the port's own maths. */
    @Nullable
    public static Web.RuntimeVariant select(final Web.Recipe recipe, final Web.Node node) {
        if (Fusion.isRecipe(recipe)) return null;
        if (node.hatchVoltageTier != null && !node.hatchVoltageTier.isEmpty()
            && recipe.machineProfile != null
            && "multiblock".equals(recipe.machineProfile.kind)) return null;
        String overclockTier = node.overclockTier;
        if (node.hatchVoltageTier != null && !node.hatchVoltageTier.isEmpty()) overclockTier = node.hatchVoltageTier;
        // The ladder runs past the family's last machine: cap at the real block, as the solver does.
        final String maximum = Tiers.maximum(recipe.maximumTier);
        if ((maximum != null || recipe.availableTiers != null && !recipe.availableTiers.isEmpty())
            && (recipe.machineProfile == null || !"multiblock".equals(recipe.machineProfile.kind))) {
            final Web.Recipe floor = recipe.copy();
            if (floor.minimumTier == null) floor.minimumTier = "ULV";
            overclockTier = Tiers.runTier(floor, overclockTier);
        }
        final Web.RuntimeCalculation calc = recipe.runtimeCalculation;
        final List<Web.RuntimeVariant> variants = calc != null && calc.variants != null ? calc.variants
            : Collections.emptyList();
        if (calc == null || !"computed".equals(calc.status) || variants.isEmpty()) return null;
        if (prefersCuratedMath(recipe)) return null;

        Web.RuntimeVariant best = null;
        int bestScore = -1;
        for (final Web.RuntimeVariant v : variants) {
            final int score = score(v, node, overclockTier);
            // A stable sort by score, descending: the first of the highest wins.
            if (score >= 0 && score > bestScore) {
                best = v;
                bestScore = score;
            }
        }
        return best;
    }

    /** getRuntimeCalculationOutputs: the variant's outputs (with the recipe's own where it names them), or null. */
    @Nullable
    public static List<Web.Resource> outputs(final Web.Recipe recipe, final Web.Node node) {
        final Web.RuntimeVariant v = select(recipe, node);
        if (v == null || v.outputs == null || v.outputs.isEmpty()) return null;
        final List<Web.Resource> out = new ArrayList<>();
        for (final Web.Resource r : v.outputs) {
            Web.Resource existing = null;
            if (recipe.outputs != null) for (final Web.Resource o : recipe.outputs)
                if (r.kind != null && r.kind.equals(o.kind) && r.id != null && r.id.equals(o.id)) {
                    existing = o;
                    break;
                }
            final Web.Resource res = new Web.Resource();
            res.kind = r.kind;
            res.id = r.id;
            res.amount = r.amount;
            res.chance = r.chance != null ? r.chance : existing != null ? existing.chance : null;
            res.displayName = existing != null ? existing.displayName : null;
            res.consumed = existing != null ? existing.consumed : null;
            res.optional = existing != null ? existing.optional : null;
            out.add(res);
        }
        return out;
    }

    /** runtimeCalculationWarning: a strict ladder with no variant for this setup. */
    @Nullable
    public static String warning(final Web.Recipe recipe, final Web.Node node) {
        if (Fusion.isRecipe(recipe)) return null;
        final Web.RuntimeCalculation calc = recipe.runtimeCalculation;
        if (calc == null || !Boolean.TRUE.equals(calc.oracleEligible) || !Boolean.TRUE.equals(calc.strict)) return null;
        if (select(recipe, node) != null) return null;
        return recipe.name + " has no matching GTNH runtime calculation for this machine configuration.";
    }

    /** resolveRuntimeTier: the variant's tier, if it names a real one. */
    public static String tier(final Web.RuntimeVariant variant, final String fallback) {
        return Tiers.isName(variant.overclockTier) ? variant.overclockTier : fallback;
    }

    /** runtimeOverclockSteps. */
    public static int overclockSteps(final String tier, final String minimumTier) {
        return Math.max(0, Tiers.index(tier) - Tiers.index(minimumTier));
    }

    private static int score(final Web.RuntimeVariant v, final Web.Node node, @Nullable final String overclockTier) {
        int score = 0;
        if (v.machineHandlerId != null) {
            if (!v.machineHandlerId.equals(node.machineHandlerId)) return -1;
            score += 16;
        }
        if (v.overclockTier != null) {
            final boolean real = Tiers.isName(overclockTier);
            if (real && !v.overclockTier.equals(overclockTier)) return -1;
            score += real ? 8 : 1;
        }
        if (v.coilTier != null) {
            if (!v.coilTier.equals(node.coilTier)) return -1;
            score += 4;
        }
        final Map<String, String> variantConfig = v.machineConfigTiers;
        final Map<String, String> selected = node.machineConfigTiers != null ? node.machineConfigTiers
            : Collections.emptyMap();
        if (variantConfig == null && !selected.isEmpty()) return -1;
        if (variantConfig != null) {
            for (final String id : selected.keySet()) if (!variantConfig.containsKey(id)) return -1;
            for (final Map.Entry<String, String> e : variantConfig.entrySet()) {
                final String key = node.machineConfigTiers != null ? node.machineConfigTiers.get(e.getKey()) : null;
                if (key != null && !key.equals(e.getValue())) return -1;
                score += e.getValue()
                    .equals(key) ? 2 : 1;
            }
        }
        return score;
    }
}

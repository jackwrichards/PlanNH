package com.gtnhplanner.machines.web;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

/**
 * One card's numbers as the website's solver takes them (src/lib/solver/throughput.ts, a node's nameplate): the
 * overclocked recipe, the machine's parallels, crafts a second per machine, each consumed input and each output a
 * second, the EU/t, and the power report that says whether it starts at all.
 */
public final class NodeMath {

    private NodeMath() {}

    /** A card's nameplate, for {@code machineCount} machines of {@code parallel} each (both 1 for one machine). */
    public record Result(Web.Recipe effectiveRecipe, Overclock.Stats overclock, double machineParallels,
        double operationsPerSecond, List<Double> inputsPerSecond, List<Double> outputMultipliers,
        List<Double> outputsPerSecond, double euT, @Nullable PowerReport.Report power, @Nullable String stall) {

        /** The game would refuse to start it: GT has no slow mode, so it makes nothing. */
        public boolean stalled() {
            return stall != null;
        }
    }

    /**
     * Whether this port computes the recipe. The Tree Growth Simulator and Bacterial Vat are the mod's own models;
     * crops and bees are the website's alone.
     */
    public static boolean covers(final Web.Recipe recipe, final Web.Node node) {
        final Web.Recipe effective = recipe.machineType != null ? RecipeRules.applyHandler(recipe, node) : recipe;
        return !RecipeRules.TGS_MACHINE_TYPE.equals(effective.machineType)
            && !"Bacterial Vat".equals(effective.machineType);
    }

    /** One machine. */
    public static Result compute(final Web.Recipe recipe, final Web.Node node) {
        return compute(recipe, node, 1, 1);
    }

    public static Result compute(final Web.Recipe recipe, final Web.Node node, final double machineCount,
        final double parallel) {
        final Web.Recipe effective = RecipeRules.applyHandler(recipe, node);
        final Overclock.Stats stats = Overclock.stats(recipe, node);
        final Web.RuntimeVariant runtime = RuntimeCalculation.select(effective, node);
        final double machineParallels = runtime != null && runtime.parallel != null ? runtime.parallel
            : MachineEffects.parallelMultiplier(effective, node);
        final List<Web.Resource> runtimeOutputs = RuntimeCalculation.outputs(effective, node);
        final PowerReport.Report power = PowerReport.has(recipe) ? PowerReport.report(recipe, node) : null;
        final String stall = power != null && power.stalled() ? PowerReport.describeStall(power) : null;
        final double rate = machineCount * parallel * machineParallels * 20 / stats.durationTicks();

        final List<Double> inputs = new ArrayList<>();
        for (final Web.Resource input : recipe.inputs) inputs.add(input.isConsumed() ? input.amount * rate : 0);
        final List<Double> multipliers = new ArrayList<>();
        final List<Double> outputs = new ArrayList<>();
        for (final Web.Resource output : runtimeOutputs != null ? runtimeOutputs : effective.outputs) {
            final double multiplier = runtimeOutputs != null ? 1
                : MachineEffects.outputMultiplier(effective, node, output, stats.tier());
            multipliers.add(multiplier);
            outputs.add(output.amount * (output.chance != null ? output.chance : 1) * multiplier * rate);
        }
        final double euT = stats.eut() * machineCount * parallel * machineParallels;
        return new Result(effective, stats, machineParallels, rate, inputs, multipliers, outputs, euT, power, stall);
    }
}

package com.sbancuz.plannh.data.provider.gregtech.probe;

import javax.annotation.Nonnull;

/**
 * Everything one probe of a machine observed: the parallel count its processing logic settled on,
 * and every overclock field of the {@code OverclockCalculator} GregTech built for the probe recipe.
 *
 * <p>
 * {@link #movesAnythingTo} is the comparison the sensitivity scan needs: did a number the chart draws
 * change?
 */
public record ProbeReading(int maxParallel, double durationModifier, double euModifier, double eutIncreasePerOC,
    double durationDecreasePerOC, int maxTierSkip, boolean heatOC, boolean heatDiscount, int machineHeat,
    int recipeHeat, long recipeEUt, int duration, boolean noOverclock, boolean laserOC) {

    /**
     * Whether the numbers describe a machine that could run. A machine that divides by a casing tier
     * reads that tier as zero before its structure is injected, and comes back with a negative duration
     * or no EU draw - not a slow machine, an unanswerable question.
     */
    public boolean isRunnable() {
        if (durationModifier <= 0 || euModifier <= 0) return false;
        if (eutIncreasePerOC < 1 || durationDecreasePerOC < 1) return false;
        // Overclocking on heat with no heat is the same shape of answer: the coil is not placed yet.
        return !heatOC || machineHeat > 0;
    }

    /** Whether the heat fields mean anything. GT leaves them at zero on a machine that ignores heat. */
    public boolean usesHeat() {
        return heatOC || heatDiscount;
    }

    /**
     * Whether a move to {@code other} changed a number the chart draws. A true becomes a settings row,
     * so a field the chart never draws stays out: {@code recipeEUt} and {@code duration} are a rewrite
     * {@link MachineProbe} pins once, {@code maxTierSkip} is a cap, {@code noOverclock} and
     * {@code laserOC} are capabilities. {@code heatCounts} is the machine's answer, so the caller reads
     * it off the reference once and passes the same value for both sides.
     */
    public boolean movesAnythingTo(@Nonnull final ProbeReading other, final boolean heatCounts) {
        return maxParallel != other.maxParallel
            || differs(durationModifier, other.durationModifier)
            || differs(euModifier, other.euModifier)
            || differs(eutIncreasePerOC, other.eutIncreasePerOC)
            || differs(durationDecreasePerOC, other.durationDecreasePerOC)
            || heatCounts && (machineHeat != other.machineHeat || recipeHeat != other.recipeHeat);
    }

    private static boolean differs(final double a, final double b) {
        return Math.abs(a - b) > MachineProbe.SAME_NUMBER * Math.max(1, Math.abs(a));
    }
}

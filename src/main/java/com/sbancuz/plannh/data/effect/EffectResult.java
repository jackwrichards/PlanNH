package com.sbancuz.plannh.data.effect;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class EffectResult {

    private int durationTicks;
    private long costPerT;
    private int throughputFactor;
    /** Why the selected machine will not run the recipe, in the machine's own words. Null when it will. */
    @Nullable
    private final String rejection;
    /** What each output is multiplied by on average, such as a machine's chance that a run succeeds. */
    private final double outputFactor;
    /** Facts about the run the numbers above cannot carry, in the machine's own words, for the node to show. */
    @Nonnull
    private final List<String> details;

    public EffectResult(int durationTicks, long costPerT, int throughputFactor) {
        this(durationTicks, costPerT, throughputFactor, null, 1, List.of());
    }

    public EffectResult(int durationTicks, long costPerT, int throughputFactor, @Nullable String rejection,
        double outputFactor, @Nonnull List<String> details) {
        this.durationTicks = durationTicks;
        this.costPerT = costPerT;
        this.throughputFactor = throughputFactor;
        this.rejection = rejection;
        this.outputFactor = outputFactor;
        this.details = List.copyOf(details);
    }

    /** These numbers, from a machine that will not run the recipe for this reason. */
    public EffectResult rejectedBecause(String reason) {
        return new EffectResult(durationTicks, costPerT, throughputFactor, reason, outputFactor, details);
    }

    /** These numbers, with the outputs scaled by {@code outputFactor} and these details for the node. */
    public EffectResult withOutputs(double outputFactor, @Nonnull List<String> details) {
        return new EffectResult(durationTicks, costPerT, throughputFactor, rejection, outputFactor, details);
    }

    /** These numbers at another duration and draw, everything else kept. */
    public EffectResult withTiming(int durationTicks, long costPerT) {
        return new EffectResult(durationTicks, costPerT, throughputFactor, rejection, outputFactor, details);
    }

    public double outputFactor() {
        return outputFactor;
    }

    @Nonnull
    public List<String> details() {
        return details;
    }

    @Nullable
    public String rejection() {
        return rejection;
    }

    public int durationTicks() {
        return durationTicks;
    }

    public long energyPerT() {
        return costPerT;
    }

    public int throughputFactor() {
        return throughputFactor;
    }

    public void durationTicks(int value) {
        this.durationTicks = value;
    }

    public void energyPerT(long value) {
        this.costPerT = value;
    }

    public void throughputFactor(int value) {
        this.throughputFactor = value;
    }
}

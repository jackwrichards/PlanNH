package com.gtnhplanner.data.effect;

import javax.annotation.Nullable;

public class EffectResult {

    private int durationTicks;
    private long costPerT;
    private int throughputFactor;
    /**
     * The true duration and EU/t where whole ticks and whole EU cannot hold them (a heat discount's EU/t, an Extreme
     * Entity Crusher's per-kill time); NaN when the whole numbers are exact.
     */
    private double exactDuration = Double.NaN, exactEnergy = Double.NaN;
    /** Per-port multipliers the machine's own maths set (an output's productivity); null when all are 1. */
    @Nullable
    private double[] inputMultipliers, outputMultipliers;
    /** Why the game would not start this build, or null when it runs. */
    @Nullable
    private String stall;
    /** The maths behind the numbers, for the card's working (machines/web's NodeMath.Result). */
    @Nullable
    private Object detail;

    public EffectResult(int durationTicks, long costPerT, int throughputFactor) {
        this.durationTicks = durationTicks;
        this.costPerT = costPerT;
        this.throughputFactor = throughputFactor;
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
        this.exactDuration = Double.NaN;
    }

    public void energyPerT(long value) {
        this.costPerT = value;
        this.exactEnergy = Double.NaN;
    }

    public void throughputFactor(int value) {
        this.throughputFactor = value;
    }

    /** Ticks a craft takes, exactly. */
    public double duration() {
        return Double.isNaN(exactDuration) ? durationTicks : exactDuration;
    }

    /** The machine's EU/t, exactly. */
    public double energy() {
        return Double.isNaN(exactEnergy) ? costPerT : exactEnergy;
    }

    /** Sets the exact duration and EU/t, the whole-number ones rounded from them. */
    public EffectResult exact(final double duration, final double energy) {
        this.durationTicks = (int) Math.max(1, Math.round(duration));
        this.costPerT = Math.round(energy);
        this.exactDuration = duration;
        this.exactEnergy = energy;
        return this;
    }

    public double inputMultiplier(final int index) {
        return inputMultipliers != null && index < inputMultipliers.length ? inputMultipliers[index] : 1;
    }

    public double outputMultiplier(final int index) {
        return outputMultipliers != null && index < outputMultipliers.length ? outputMultipliers[index] : 1;
    }

    public EffectResult multipliers(@Nullable final double[] inputs, @Nullable final double[] outputs) {
        this.inputMultipliers = inputs;
        this.outputMultipliers = outputs;
        return this;
    }

    @Nullable
    public String stall() {
        return stall;
    }

    public EffectResult stall(@Nullable final String reason) {
        this.stall = reason;
        return this;
    }

    @Nullable
    public Object detail() {
        return detail;
    }

    public EffectResult detail(@Nullable final Object value) {
        this.detail = value;
        return this;
    }
}

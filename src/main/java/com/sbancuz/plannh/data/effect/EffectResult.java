package com.sbancuz.plannh.data.effect;

import javax.annotation.Nullable;

public class EffectResult {

    private int durationTicks;
    private long costPerT;
    private int throughputFactor;
    /** Why the selected machine will not run the recipe, in the machine's own words. Null when it will. */
    @Nullable
    private final String rejection;

    public EffectResult(int durationTicks, long costPerT, int throughputFactor) {
        this(durationTicks, costPerT, throughputFactor, null);
    }

    public EffectResult(int durationTicks, long costPerT, int throughputFactor, @Nullable String rejection) {
        this.durationTicks = durationTicks;
        this.costPerT = costPerT;
        this.throughputFactor = throughputFactor;
        this.rejection = rejection;
    }

    /** These numbers, from a machine that will not run the recipe for this reason. */
    public EffectResult rejectedBecause(String reason) {
        return new EffectResult(durationTicks, costPerT, throughputFactor, reason);
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

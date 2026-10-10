package com.gtnhplanner.machines.game;

import java.util.Map;

import com.gtnhplanner.data.MachineProfile;
import com.gtnhplanner.data.RecipeContext;
import com.gtnhplanner.data.Settings;
import com.gtnhplanner.data.effect.EffectResult;
import com.gtnhplanner.data.effect.EffectStep;
import com.gtnhplanner.data.effect.steps.GTOverclockStep;
import com.gtnhplanner.machines.TreeGrowthSimulator;

/**
 * The Tree Growth Simulator's run (MTETreeFarm): 100 ticks whatever the voltage, at VP[t] EU/t, t the tier of the
 * hatches' V x A rounded up. It replaces whatever the overclock step made of NEI's page. The vat needs nothing here:
 * GT's normal overclocks are its own.
 */
public final class MachineModelStep implements EffectStep {

    @Override
    public EffectResult apply(final EffectResult current, final Map<String, Object> s, final RecipeContext ctx) {
        if (!MachineModels.isTgs(ctx.properties())) return current;
        final long voltage = GTOverclockStep
            .tierNameToVoltage(MachineProfile.getString(s, Settings.VOLTAGE.key(), "OFF"));
        final long amps = Math.max(1, MachineProfile.getInt(s, Settings.AMP.key(), 1));
        final int t = TreeGrowthSimulator.tier((voltage > 0 ? voltage : 32) * amps);
        current.durationTicks(TreeGrowthSimulator.TICKS);
        current.energyPerT(TreeGrowthSimulator.euPerTick(t));
        current.throughputFactor(1);
        return current;
    }
}

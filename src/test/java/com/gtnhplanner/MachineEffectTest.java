package com.gtnhplanner;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.data.MachineProfile;
import com.gtnhplanner.data.MachineProfileRegistry;
import com.gtnhplanner.data.RecipeContext;
import com.gtnhplanner.data.Settings;
import com.gtnhplanner.data.effect.EffectComputer;
import com.gtnhplanner.data.effect.EffectResult;
import com.gtnhplanner.data.effect.steps.GTHeat;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.balancer.BalanceMode;
import com.gtnhplanner.data.flowchart.balancer.BalanceResult;
import com.gtnhplanner.data.flowchart.balancer.Balancer;
import com.gtnhplanner.data.provider.DefaultProvider;
import com.gtnhplanner.harness.GtnhFlowLoader;
import com.gtnhplanner.harness.GtnhFlowLoader.LoadedChart;

/** What a machine's settings do to its effect: parallels multiply throughput, the count does not. */
class MachineEffectTest {

    private static final String PARALLEL_PROFILE = "test:parallel";

    @Test
    void parallelismIsParallelsOnly() {
        final EffectComputer base = (s, ctx) -> new EffectResult(100, 0, 1);
        final Map<String, Object> settings = new HashMap<>();
        settings.put(Settings.MACHINES.key(), 4);
        settings.put(Settings.PARALLELS.key(), 2);

        final EffectResult effect = base.applyParallelism()
            .compute(settings, new RecipeContext(new HashMap<>()));

        assertEquals(2, effect.throughputFactor(), "four machines of two parallels each run 2 crafts per machine");
    }

    @Test
    void theDefaultProfileRunsOneCraftPerMachine() {
        final Map<String, Object> settings = new HashMap<>();
        settings.put(Settings.MACHINES.key(), 3);
        settings.put(Settings.DURATION_TICKS.key(), 40);

        final EffectResult effect = DefaultProvider.noopEffect(settings, new RecipeContext(new HashMap<>()));

        assertEquals(1, effect.throughputFactor());
        assertEquals(40, effect.durationTicks());
    }

    @Test
    void aPinnedCountRunsAtTheCountNotItsSquare() {
        // The distillery makes 25 SLF from 25 oil in 1 s. Pinned at 3 machines of 2 parallels:
        // 3 machines x 2 crafts x 25 = 150 oil/s. Before the fix the count also went into the
        // throughput factor and the same pin ran 3 x (3 x 2) x 25 = 450 oil/s.
        registerParallelProfile();
        final LoadedChart chart = GtnhFlowLoader.load("light_fuel");
        final Node distillery = chart.machine(1);
        distillery.machineConfig.profileId = PARALLEL_PROFILE;
        distillery.machineConfig.settings.put(Settings.PARALLELS.key(), 2);
        distillery.machineConfig.setMachineCount(3);
        distillery.setMachineCountFixed(true);

        final BalanceResult result = Balancer.balance(chart.graph(), BalanceMode.AUTO);

        final var balance = result.nodeBalances()
            .get(distillery.id);
        assertEquals(3.0, balance.operations(), 1e-6, "the count is the pin");
        // Effective rates are per cycle; the distillery's cycle is one second.
        assertEquals(
            150.0,
            balance.effectiveInputs()
                .get(0),
            1e-3,
            "oil in per second");
        // The reactor's cycle is 160 ticks: per cycle times 20/160 is per second.
        assertEquals(
            150.0,
            result.nodeBalances()
                .get(chart.machine(0).id)
                .effectiveOutputs()
                .get(1) * 20.0
                / 160.0,
            1e-3,
            "light fuel follows");
        assertEquals(
            150.0,
            result.nodeBalances()
                .get(chart.machine(0).id)
                .outputPerSecond(1),
            1e-3,
            "and reads per second without the arithmetic");
        assertEquals(150.0, balance.inputPerSecond(0), 1e-3);
    }

    @Test
    void heatOverclockUsesTheRecipesCoilHeatWhenNoOverrideIsSet() {
        assertEquals(1800, GTHeat.recipeHeat(0, 1800, 4500), "the recipe's own heat, not the machine's");
        assertEquals(2700, GTHeat.recipeHeat(2700, 1800, 4500), "the player's override wins");
        assertEquals(4500, GTHeat.recipeHeat(0, 0, 4500), "no stated heat: no bonus, no penalty");
    }

    private static void registerParallelProfile() {
        GtnhFlowLoader.ensureDefaultMachineProfile();
        if (MachineProfileRegistry.get(PARALLEL_PROFILE) != null) return;
        final EffectComputer base = (s,
            ctx) -> new EffectResult(ctx.getOrDefault(GtnhFlowLoader.DURATION_TICKS, 1), 0, 1);
        MachineProfileRegistry.register(
            new MachineProfile(
                PARALLEL_PROFILE,
                "Parallel",
                List.of(Settings.MACHINES.def(), Settings.PARALLELS.def()),
                base.applyParallelism()));
    }
}

package com.sbancuz.plannh.data.flowchart.balancer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.sbancuz.plannh.harness.GtnhFlowLoader;
import com.sbancuz.plannh.harness.GtnhFlowLoader.LoadedChart;

/**
 * A model built when the solve's budget is (nearly) spent gets the minimum model time, not an
 * exception: the non-optional stages still run after the budget expires.
 */
class SpentBudgetTest {

    @Test
    void aSpentBudgetStillBuildsAndSolvesAModel() {
        final LoadedChart chart = GtnhFlowLoader.load("light_fuel");
        final SolveInput input = SolveInput.of(chart.graph(), BalanceMode.AUTO, null);
        final SolveContext ctx = new SolveContext(
            input,
            Heuristics.auto(),
            Budget.of(0),
            Map.of(),
            BalanceMode.AUTO.pins(),
            true,
            Profiler.disabled());

        final SolveResult result = assertDoesNotThrow(() -> Solver.flowMinimal(ctx, Set.of(), 0.0));

        assertFalse(result.isRejected(), "light_fuel balances gate-free: " + result.rejection());
    }
}

package com.gtnhplanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.balancer.BalanceResult;
import com.gtnhplanner.harness.GtnhFlowLoader;
import com.gtnhplanner.harness.GtnhFlowLoader.LoadedChart;

/** The two versions: every edit moves one, only solve-relevant edits move the other. */
class GraphVersionTest {

    @Test
    void touchMovesBothVersions() {
        final Graph graph = GtnhFlowLoader.load("light_fuel")
            .graph();
        final long version = graph.version();
        final long solve = graph.solveVersion();

        graph.touch();

        assertTrue(graph.version() > version);
        assertTrue(graph.solveVersion() > solve);
        assertEquals(graph.version(), graph.solveVersion());
    }

    @Test
    void aLayoutEditMovesOnlyTheVersion() {
        final Graph graph = GtnhFlowLoader.load("light_fuel")
            .graph();
        final long version = graph.version();
        final long solve = graph.solveVersion();

        graph.touchLayout();

        assertTrue(graph.version() > version, "the board redraws");
        assertEquals(solve, graph.solveVersion(), "the solver does not run again");
    }

    @Test
    void theGraphsOwnSolveIsKeptAcrossLayoutEditsAndRedoneAfterATouch() {
        final LoadedChart chart = GtnhFlowLoader.load("light_fuel");
        final Graph graph = chart.graph();
        final BalanceResult first = graph.balance();

        graph.touchLayout();
        assertSame(first, graph.balance(), "moving a card is not a reason to solve");

        chart.machine(1).machineConfig.setMachineCount(2);
        graph.touch();
        final BalanceResult second = graph.balance();
        assertNotSame(first, second, "a pin edit is");
        assertEquals(
            2.0,
            second.nodeBalances()
                .get(chart.machine(1).id)
                .operations(),
            1e-6);
    }

    @Test
    @SuppressWarnings("deprecation")
    void markDirtyIsTouch() {
        final Graph graph = new Graph("x");
        final long solve = graph.solveVersion();
        graph.markDirty();
        assertTrue(graph.solveVersion() > solve);
    }
}

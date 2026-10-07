package com.gtnhplanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.data.flowchart.Edge;
import com.gtnhplanner.data.flowchart.balancer.BalanceMode;
import com.gtnhplanner.data.flowchart.balancer.Balancer;
import com.gtnhplanner.data.flowchart.balancer.Balancer.Answer;
import com.gtnhplanner.data.flowchart.balancer.External;
import com.gtnhplanner.data.flowchart.balancer.SolutionView;
import com.gtnhplanner.harness.GtnhFlowLoader;
import com.gtnhplanner.harness.GtnhFlowLoader.LoadedChart;

/**
 * A machine wired into its own input, ported from Factory Flow's machine-count-optimizer.test.ts.
 * There a self edge switched off the optimizer's cycle guards and a lossy loop multiplied its own
 * demand until it overflowed. Here a self loop has to be an ordinary chart: the solve finishes
 * within budget, the counts are the recipe ratios, and every port conserves.
 */
class SelfLoopTest {

    private static final double EPS = 1e-4;
    /** Factory Flow's own timeout for these cases. */
    private static final long BUDGET_MILLIS = 10_000;

    @Test
    void aLossySelfLoopImportsWhatItCannotReturn() {
        // 100 glass/s is 100 one-second crafts, which eat 1000 sand/s and give 500 back. The loop
        // returns all 500 and the other 500 come in through one gate.
        final LoadedChart chart = GtnhFlowLoader
            .parse("lossy", "- {m: kiln, dur: 1, I: {sand: 10}, O: {sand: 5, glass: 1}, target: {glass: 100}}");
        final Edge loop = selfEdge(chart);

        final SolutionView s = solved(chart);

        assertEquals(100, s.machineCounts.get(chart.machine(0).id), EPS);
        assertEquals(500, s.edgeFlowsPerSecond.get(loop.id), EPS, "the loop carries everything it makes");
        assertEquals(1, s.gatedSources.size(), "one import: " + s.gatedSources);
        assertEquals(0, s.gatedSinks.size());
        final External sand = s.gatedSources.get(0);
        assertEquals(
            0,
            sand.port()
                .portIndex());
        assertEquals(500, sand.ratePerSecond(), EPS);
        assertEquals(1, s.terminalOutputs.size());
        assertEquals(
            100,
            s.terminalOutputs.get(0)
                .ratePerSecond(),
            EPS,
            "glass");
        GroundTruthTest.assertPortsConserve("lossy", chart, s);
        assertTrue(s.wallMillis < BUDGET_MILLIS, "wall " + s.wallMillis + "ms");
    }

    @Test
    void aSelfSustainingLoopVoidsWhatItDoesNotNeed() {
        // 10 crop/s is 10 one-second crafts: 10 seed in, 20 out. The loop feeds back the 10 it needs
        // and the other 10 leave through one gate.
        final LoadedChart chart = GtnhFlowLoader
            .parse("gainy", "- {m: farm, dur: 1, I: {seed: 1}, O: {seed: 2, crop: 1}, target: {crop: 10}}");
        final Edge loop = selfEdge(chart);

        final SolutionView s = solved(chart);

        assertEquals(10, s.machineCounts.get(chart.machine(0).id), EPS);
        assertEquals(10, s.edgeFlowsPerSecond.get(loop.id), EPS);
        assertEquals(0, s.gatedSources.size());
        assertEquals(1, s.gatedSinks.size(), "one void: " + s.gatedSinks);
        final External seed = s.gatedSinks.get(0);
        assertEquals(
            0,
            seed.port()
                .portIndex());
        assertEquals(10, seed.ratePerSecond(), EPS);
        GroundTruthTest.assertPortsConserve("gainy", chart, s);
        assertTrue(s.wallMillis < BUDGET_MILLIS, "wall " + s.wallMillis + "ms");
    }

    /** The chart's only edge, checked to run from its machine back into itself. */
    private static Edge selfEdge(final LoadedChart chart) {
        assertEquals(
            1,
            chart.graph()
                .getEdges()
                .size(),
            "the loader wires the shared ingredient");
        final Edge edge = chart.graph()
            .getEdges()
            .iterator()
            .next();
        assertEquals(edge.sourceNodeId, edge.targetNodeId, "a self edge");
        return edge;
    }

    private static SolutionView solved(final LoadedChart chart) {
        final Answer answer = Balancer.solveWithAlternatives(BalanceMode.AUTO, chart.graph(), null, Map.of());
        return assertInstanceOf(Answer.Solved.class, answer, () -> chart.name() + " failed: " + answer).solution();
    }
}

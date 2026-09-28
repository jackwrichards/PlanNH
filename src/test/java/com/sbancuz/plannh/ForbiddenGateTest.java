package com.sbancuz.plannh;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.sbancuz.plannh.data.flowchart.Edge;
import com.sbancuz.plannh.data.flowchart.Graph;
import com.sbancuz.plannh.data.flowchart.Node;
import com.sbancuz.plannh.data.flowchart.Serializer;
import com.sbancuz.plannh.data.flowchart.Summary;
import com.sbancuz.plannh.data.flowchart.balancer.BalanceMode;
import com.sbancuz.plannh.data.flowchart.balancer.Balancer;
import com.sbancuz.plannh.data.flowchart.balancer.Balancer.Answer;
import com.sbancuz.plannh.data.flowchart.balancer.External;
import com.sbancuz.plannh.data.flowchart.balancer.Note;
import com.sbancuz.plannh.data.flowchart.balancer.PortRef;
import com.sbancuz.plannh.data.flowchart.balancer.SolutionView;
import com.sbancuz.plannh.data.flowchart.balancer.SolverMessage;
import com.sbancuz.plannh.harness.GtnhFlowLoader;
import com.sbancuz.plannh.harness.GtnhFlowLoader.LoadedChart;

/** A gate the player forbids carries nothing, survives save/load, and is named when it blocks a balance. */
class ForbiddenGateTest {

    @Test
    void platline230_forbiddingHydrogenCostsExactlyOneMoreGate() {
        // One hydrogen source balances the chart, and every answer without it needs two gates. The
        // forbidden gate spans both hydrogen inputs, so neither may import, and the count must still
        // be proven rather than bought.
        final LoadedChart chart = GtnhFlowLoader.load("230_platline");
        final PortRef ammoniaHydrogen = new PortRef(chart.machine(27).id, 1, true);
        final PortRef sodiumSulfateHydrogen = new PortRef(chart.machine(25).id, 1, true);
        chart.graph()
            .forbidGate(ammoniaHydrogen);

        final SolutionView s = solved(solve(chart.graph()));

        assertEquals(2, s.openGates, "one gate more than with hydrogen allowed");
        for (final External source : s.gatedSources) {
            assertFalse(
                source.port()
                    .equals(ammoniaHydrogen)
                    || source.port()
                        .equals(sodiumSulfateHydrogen),
                "hydrogen imported at " + source.port());
        }
        assertFalse(
            s.notes.stream()
                .anyMatch(n -> n.containsMessage(SolverMessage.GATE_COUNT_NOT_CERTIFIED)),
            "the count is proven, not bought");
    }

    @Test
    void loopGraph_forbiddingEveryGateNamesTheForbids() {
        // The loop returns only 2/3 of what the pinned DT consumes, so with every gate forbidden no
        // balance exists, and the pin alone is not the reason.
        final LoadedChart chart = GtnhFlowLoader.load("loopGraph");
        final Graph graph = chart.graph();
        for (final Edge edge : graph.getEdges()) {
            graph.forbidGate(new PortRef(edge.sourceNodeId, edge.sourceOutputIndex, false));
            graph.forbidGate(new PortRef(edge.targetNodeId, edge.targetInputIndex, true));
        }

        final Answer answer = solve(graph);

        assertTrue(answer instanceof Answer.Failed, "no balance exists with every gate forbidden");
        final Note failure = ((Answer.Failed) answer).failure();
        assertTrue(failure.containsMessage(SolverMessage.FORBIDDEN_CONFLICT), "blamed on the forbids: " + failure);
    }

    @Test
    void forbiddenGatesSurviveEncodeDecode() {
        final LoadedChart chart = GtnhFlowLoader.load("230_platline");
        final PortRef hydrogen = new PortRef(chart.machine(27).id, 1, true);
        chart.graph()
            .forbidGate(hydrogen);

        final Graph decoded = Serializer.decode(Serializer.encode(chart.graph()));

        assertEquals(
            chart.graph()
                .getForbiddenGates(),
            decoded.getForbiddenGates());
        assertTrue(
            decoded.getForbiddenGates()
                .contains(hydrogen));
    }

    @Test
    void removingANodeDropsItsForbids() {
        final LoadedChart chart = GtnhFlowLoader.load("230_platline");
        final Node ammonia = chart.machine(27);
        chart.graph()
            .forbidGate(new PortRef(ammonia.id, 1, true));

        chart.graph()
            .removeNode(ammonia.id);

        assertTrue(
            chart.graph()
                .getForbiddenGates()
                .isEmpty());
    }

    @Test
    void aSectionOrderSavedBeforeForbiddenKeepsItsOrderAndGainsItLast() {
        final Summary summary = new Summary();
        final int[] saved = { 7, 6, 5, 4, 3, 2, 1, 0 };
        summary.setSectionOrder(saved.clone());

        final int[] order = summary.getSectionOrder();

        assertEquals(Summary.Section.VALUES.length, order.length);
        assertArrayEquals(saved, Arrays.copyOf(order, saved.length), "the player's order survives");
        assertEquals(Summary.Section.FORBIDDEN.ordinal(), order[saved.length]);
    }

    private static Answer solve(final Graph graph) {
        return Balancer.solveWithAlternatives(BalanceMode.AUTO, graph, null, Map.of());
    }

    private static SolutionView solved(final Answer answer) {
        if (answer instanceof final Answer.Solved solved) return solved.solution();
        throw new AssertionError("solve failed: " + ((Answer.Failed) answer).failure());
    }
}

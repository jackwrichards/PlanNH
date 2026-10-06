package com.sbancuz.plannh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.sbancuz.plannh.data.flowchart.Drawer;
import com.sbancuz.plannh.data.flowchart.Drawer.Kind;
import com.sbancuz.plannh.data.flowchart.Drawer.Link;
import com.sbancuz.plannh.data.flowchart.Drawer.Rule;
import com.sbancuz.plannh.data.flowchart.Graph;
import com.sbancuz.plannh.data.flowchart.Node;
import com.sbancuz.plannh.data.flowchart.balancer.BalanceMode;
import com.sbancuz.plannh.data.flowchart.balancer.BalanceResult;
import com.sbancuz.plannh.data.flowchart.balancer.Balancer;
import com.sbancuz.plannh.data.flowchart.balancer.Balancer.Answer;
import com.sbancuz.plannh.data.flowchart.balancer.DrawerReadout;
import com.sbancuz.plannh.data.flowchart.balancer.External;
import com.sbancuz.plannh.data.flowchart.balancer.Note;
import com.sbancuz.plannh.data.flowchart.balancer.SolutionView;
import com.sbancuz.plannh.data.flowchart.balancer.SolverMessage;
import com.sbancuz.plannh.harness.GtnhFlowLoader;
import com.sbancuz.plannh.harness.GtnhFlowLoader.LoadedChart;

/**
 * Drawer rules in the balancer, on corpus charts whose numbers are easy to derive by hand.
 *
 * <p>
 * light_fuel, port by port: the chemical reactor (machine 0, 8 s) takes hydrogen (in 0, 2000) and
 * sulfuric light fuel (in 1, 12000) and makes hydrogen sulfide (out 0, 1000) and light fuel (out 1,
 * 12000); the distillery (machine 1, 1 s, pinned at one machine) turns oil (in 0, 25) into sulfuric
 * light fuel (out 0, 25); the electrolyzer (machine 2, 50 s) turns water (in 0, 3000) into hydrogen
 * (out 0, 1000) and oxygen (out 1, 500). Sulfuric light fuel and hydrogen are wired; everything
 * else is unwired. Light fuel comes out 1:1 with the oil going in.
 */
class DrawerRulesTest {

    private static final double EPS = 1e-4;

    // ── Products on unwired outputs ──

    @Test
    void productAtLeastAnchorsAnUnpinnedChart() {
        final LoadedChart chart = unpinnedLightFuel();
        final Drawer fuel = product(chart, Rule.AT_LEAST, 100, 0, 1);

        final SolutionView s = solved(chart.graph());

        assertEquals(100, s.drawers.rate(fuel.getId()), EPS, "the plan makes what was asked, no more");
        assertTrue(s.drawers.met(fuel.getId()));
        assertEquals(4.0, s.machineCounts.get(chart.machine(1).id), EPS, "four distilleries: 100 oil/s");
        assertEquals(100.0 / 12000 * 8, s.machineCounts.get(chart.machine(0).id), EPS);
        assertTrue(
            s.drawers.unmet()
                .isEmpty());
    }

    @Test
    void productExactlyHoldsTheRate() {
        final LoadedChart chart = unpinnedLightFuel();
        final Drawer fuel = product(chart, Rule.EXACTLY, 60, 0, 1);

        final SolutionView s = solved(chart.graph());

        assertEquals(60, s.drawers.rate(fuel.getId()), EPS);
        assertEquals(2.4, s.machineCounts.get(chart.machine(1).id), EPS);
    }

    @Test
    void productAtMostIsKeptWhenThePinsAllowIt() {
        final LoadedChart chart = GtnhFlowLoader.load("light_fuel"); // distillery pinned: 25/s
        final Drawer fuel = product(chart, Rule.AT_MOST, 30, 0, 1);

        final SolutionView s = solved(chart.graph());

        assertEquals(25, s.drawers.rate(fuel.getId()), EPS, "an upper limit does not push the plan up");
        assertTrue(s.drawers.met(fuel.getId()));
    }

    @Test
    void productAtMostBelowAPinnedRateIsReportedUnmetAndThePlanStillSolves() {
        // One chemical reactor pinned: 20/160 crafts/s x 12000 = 1500 light fuel/s, straight out
        // of an unwired port, so no gate can take any of it away.
        final LoadedChart chart = unpinnedLightFuel();
        chart.machine(0)
            .setMachineCountFixed(true);
        final Drawer fuel = product(chart, Rule.AT_MOST, 1000, 0, 1);

        final SolutionView s = solved(chart.graph());

        assertFalse(s.drawers.met(fuel.getId()), "1500/s cannot be held at most 1000/s");
        assertEquals(1500, s.drawers.rate(fuel.getId()), 1e-3, "the pin wins; the plan keeps its numbers");
        assertEquals(1.0, s.machineCounts.get(chart.machine(0).id), EPS);
        final DrawerReadout.Shortfall shortfall = s.drawers.shortfall(fuel.getId());
        assertNotNull(shortfall);
        assertEquals(1500, shortfall.reachable(), 1e-3);
        assertEquals(1000, shortfall.target(), 0);
        assertEquals(Rule.AT_MOST, shortfall.rule());
        assertEquals(List.of(chart.machine(0).id), shortfall.limitingNodes(), "the reactor's pin holds it");
        assertTrue(hasNote(s.notes, SolverMessage.DRAWER_UNMET), "the notice names the drawer: " + s.notes);
        assertTrue(hasNote(s.notes, SolverMessage.DRAWER_LIMITED_BY), "and the limit: " + s.notes);
    }

    @Test
    void anAtMostTheChartCanMeetBySinkingSurplusIsMet() {
        // The distillery is pinned at 25 SLF/s, but its output is wired: AUTO may leave the surplus
        // at a gated sink there, so a product held at most 20/s of light fuel is met, not violated.
        final LoadedChart chart = GtnhFlowLoader.load("light_fuel");
        final Drawer fuel = product(chart, Rule.AT_MOST, 20, 0, 1);

        final SolutionView s = solved(chart.graph());

        assertTrue(s.drawers.met(fuel.getId()));
        assertTrue(s.drawers.rate(fuel.getId()) <= 20 + EPS, "rate " + s.drawers.rate(fuel.getId()));
        assertEquals(1, s.gatedSinks.size(), "the rest leaves at the distillery: " + s.gatedSinks);
    }

    // ── Sources on unwired inputs ──

    @Test
    void sourceExactlyAnchorsTheChart() {
        final LoadedChart chart = unpinnedLightFuel();
        final Drawer oil = source(chart, Rule.EXACTLY, 50, 1, 0);

        final SolutionView s = solved(chart.graph());

        assertEquals(50, s.drawers.rate(oil.getId()), EPS);
        assertEquals(2.0, s.machineCounts.get(chart.machine(1).id), EPS, "two distilleries eat 50 oil/s");
        assertEquals(50, terminalRate(s, chart.machine(0).id, 1, false), EPS, "light fuel follows the oil");
    }

    @Test
    void sourceAtMostCapsAProductAndNamesItAsTheLimit() {
        // The electrolyzer turns 3000 water into 500 oxygen, both unwired: at most 30 water/s is at
        // most 5 oxygen/s, short of the 10/s asked for.
        final LoadedChart chart = unpinnedLightFuel();
        final Drawer water = source(chart, Rule.AT_MOST, 30, 2, 0);
        final Drawer oxygen = product(chart, Rule.AT_LEAST, 10, 2, 1);

        final Answer answer = Balancer.solveWithAlternatives(BalanceMode.AUTO, chart.graph(), null, Map.of());

        final SolutionView s = assertInstanceOf(Answer.Solved.class, answer, () -> "failed: " + answer).solution();
        assertFalse(s.drawers.met(oxygen.getId()), "10 oxygen/s needs 60 water/s");
        assertTrue(s.drawers.met(water.getId()), "the cheaper miss is the product's (relative shortfall)");
        assertEquals(5, s.drawers.rate(oxygen.getId()), EPS, "the plan runs at what the water allows");
        assertEquals(30, s.drawers.rate(water.getId()), EPS);
        final DrawerReadout.Shortfall shortfall = s.drawers.shortfall(oxygen.getId());
        assertNotNull(shortfall);
        assertEquals(5, shortfall.reachable(), EPS);
        assertEquals(List.of(water.getId()), shortfall.limitingDrawers(), "the water cap is what holds it back");
        assertTrue(
            shortfall.limitingNodes()
                .isEmpty());
        assertEquals(
            Map.of(water.getId(), false, oxygen.getId(), true),
            Map.of(
                water.getId(),
                s.drawers.unmet()
                    .contains(water.getId()),
                oxygen.getId(),
                s.drawers.unmet()
                    .contains(oxygen.getId())));
    }

    @Test
    void aWiredInputCanStillBeImportedPastASourceCap() {
        // Oil is capped at 10/s, but the reactor's sulfuric light fuel input is wired, and AUTO may
        // import at a wired port through a gate: 100 light fuel/s is met by importing 90 SLF/s.
        final LoadedChart chart = unpinnedLightFuel();
        final Drawer oil = source(chart, Rule.AT_MOST, 10, 1, 0);
        final Drawer fuel = product(chart, Rule.AT_LEAST, 100, 0, 1);

        final SolutionView s = solved(chart.graph());

        assertTrue(s.drawers.met(fuel.getId()));
        assertTrue(s.drawers.met(oil.getId()));
        assertEquals(100, s.drawers.rate(fuel.getId()), EPS);
        final double imported = s.gatedSources.stream()
            .filter(
                e -> e.port()
                    .nodeId()
                    .equals(chart.machine(0).id)
                    && e.port()
                        .portIndex() == 1)
            .mapToDouble(External::ratePerSecond)
            .sum();
        assertEquals(100 - s.drawers.rate(oil.getId()), imported, EPS, "the shortfall is imported, and shown");
    }

    // ── Wired ports: the drawer binds the port's external ──

    @Test
    void productOnAWiredOutputTakesTheSurplus() {
        final LoadedChart chart = GtnhFlowLoader.load("light_fuel"); // CR wants 25/12*2 = 4.1667 H2/s
        final Drawer hydrogen = product(chart, Rule.AT_LEAST, 5, 2, 0);

        final SolutionView s = solved(chart.graph());

        assertEquals(5, s.drawers.rate(hydrogen.getId()), EPS, "the surplus leaving at the drawer");
        final double h2 = 2000 * 25.0 / 12000;
        assertEquals((h2 + 5) / 1000 * 1000 / 20, s.machineCounts.get(chart.machine(2).id), EPS);
        assertEquals(1, s.gatedSinks.size());
        final External sink = s.gatedSinks.get(0);
        assertEquals(
            chart.machine(2).id,
            sink.port()
                .nodeId());
        assertEquals(5, sink.ratePerSecond(), EPS);
    }

    @Test
    void sourceOnAWiredInputSuppliesItsShareAndTheMachineMakesTheRest() {
        final LoadedChart chart = GtnhFlowLoader.load("light_fuel");
        final Drawer hydrogen = source(chart, Rule.EXACTLY, 2, 0, 0);

        final SolutionView s = solved(chart.graph());

        final double h2 = 2000 * 25.0 / 12000;
        assertEquals(2, s.drawers.rate(hydrogen.getId()), EPS);
        assertEquals((h2 - 2) / 1000 * 1000 / 20, s.machineCounts.get(chart.machine(2).id), EPS);
        assertTrue(
            s.notes.stream()
                .noneMatch(n -> n.message() == SolverMessage.WIRING_UNLINKED),
            "an import the player asked for is not a missing edge: " + s.notes);
    }

    // ── Rule-free drawers read what arrives ──

    @Test
    void byproductAndTrashReadWhatArrivesAndAskForNothing() {
        final LoadedChart chart = GtnhFlowLoader.load("light_fuel");
        final Drawer oxygen = drawer(chart, Kind.BYPRODUCT, Rule.ANY, 0, 2, 1);
        final Drawer h2s = drawer(chart, Kind.TRASH, Rule.AT_LEAST, 1000, 0, 0); // stored rule is ignored
        final Drawer any = drawer(chart, Kind.PRODUCT, Rule.ANY, 0, 0, 1);

        final SolutionView s = solved(chart.graph());

        assertEquals(25.0 / 12, s.drawers.rate(oxygen.getId()), EPS);
        assertEquals(25.0 / 12, s.drawers.rate(h2s.getId()), EPS);
        assertEquals(25, s.drawers.rate(any.getId()), EPS);
        assertTrue(
            s.drawers.unmet()
                .isEmpty());
        assertEquals(1.0, s.machineCounts.get(chart.machine(1).id), EPS, "nothing moved the pinned chart");
    }

    // ── Anchoring and idle states ──

    @Test
    void onlyAtMostRulesLeaveAutoIdleAndSaySo() {
        final LoadedChart chart = unpinnedLightFuel();
        product(chart, Rule.AT_MOST, 100, 0, 1);

        final BalanceResult result = Balancer.balance(chart.graph(), BalanceMode.AUTO);

        assertTrue(hasNote(result.notes(), SolverMessage.NO_PIN), "still the idle answer: " + result.notes());
        assertTrue(hasNote(result.notes(), SolverMessage.DRAWER_ONLY_UPPER_BOUNDS), "and why: " + result.notes());
        assertEquals(0, result.totalOperations(), 0);
    }

    @Test
    void aDrawerAskingForFlowWithNoLinksIsUnmetButDoesNotBreakThePlan() {
        final LoadedChart chart = GtnhFlowLoader.load("light_fuel");
        final Drawer lonely = new Drawer(Kind.PRODUCT, "fluid:nothing");
        lonely.setTarget(Rule.AT_LEAST, 10);
        chart.graph()
            .addDrawer(lonely);

        final BalanceResult result = Balancer.balance(chart.graph(), BalanceMode.AUTO);

        assertInstanceOf(BalanceResult.Solved.class, result, () -> "notes: " + result.notes());
        assertTrue(
            result.unmetDrawers()
                .contains(lonely.getId()));
        assertEquals(
            0,
            result.drawerRates()
                .get(lonely.getId()),
            0);
        assertTrue(hasNote(result.notes(), SolverMessage.DRAWER_NOT_CONNECTED));
        assertEquals(
            1.0,
            result.nodeBalances()
                .get(chart.machine(1).id)
                .operations(),
            EPS);
    }

    @Test
    void outputModeCountsUnwiredLinksOnlyAndSaysSo() {
        final LoadedChart chart = GtnhFlowLoader.load("light_fuel");
        final Drawer hydrogen = product(chart, Rule.AT_LEAST, 5, 2, 0); // hydrogen is wired

        final BalanceResult result = Balancer.balance(chart.graph(), BalanceMode.OUTPUT);

        assertTrue(hasNote(result.notes(), SolverMessage.DRAWER_WIRED_IGNORED), "notes: " + result.notes());
        assertTrue(
            result.unmetDrawers()
                .contains(hydrogen.getId()));
    }

    // ── Drawers against the existing target pins ──

    @Test
    void mk1_aProductDrawerSolvesLikeTheTargetPinItReplaces() {
        final LoadedChart pinned = GtnhFlowLoader.load("mk1"); // target: 10 mk1 fuel/s on the fusion reactor
        final SolutionView byTarget = solved(pinned.graph());

        for (final Rule rule : new Rule[] { Rule.EXACTLY, Rule.AT_LEAST }) {
            final LoadedChart chart = GtnhFlowLoader.load("mk1");
            GtnhFlowLoader.clearTargetPins(chart);
            final Drawer fuel = product(chart, rule, 10, 0, 0);

            final SolutionView byDrawer = solved(chart.graph());

            assertEquals(10, byDrawer.drawers.rate(fuel.getId()), EPS, rule.name());
            for (final Node machine : chart.machines()) {
                assertEquals(
                    byTarget.machineCounts.get(machine.id),
                    byDrawer.machineCounts.get(machine.id),
                    EPS,
                    rule + ": " + machine.machineName);
            }
            assertEquals(byTarget.openGates, byDrawer.openGates, rule + ": same gates");
        }
    }

    @Test
    void palladium_aProductDrawerOnALoopedChartSolvesLikeTheTargetPin() {
        final LoadedChart pinned = GtnhFlowLoader.load("palladium"); // 2 reprecipitated dust/s
        final SolutionView byTarget = solved(pinned.graph());

        final LoadedChart chart = GtnhFlowLoader.load("palladium");
        GtnhFlowLoader.clearTargetPins(chart);
        final Drawer dust = product(chart, Rule.EXACTLY, 2, 0, 1);

        final SolutionView byDrawer = solved(chart.graph());

        assertEquals(2, byDrawer.drawers.rate(dust.getId()), EPS);
        for (final Node machine : chart.machines()) {
            assertEquals(
                byTarget.machineCounts.get(machine.id),
                byDrawer.machineCounts.get(machine.id),
                EPS,
                machine.machineName);
        }
    }

    @Test
    void palladium_aPinnedCountBelowTheTargetIsTheReportedLimit() {
        // The reprecipitating LCR (machine 0) makes one dust per 112.5 s craft: 100 of them make
        // 100 / 112.5 = 0.889 dust/s, short of the 2/s the drawer asks for.
        final LoadedChart chart = GtnhFlowLoader.load("palladium");
        GtnhFlowLoader.clearTargetPins(chart);
        chart.machine(0).machineConfig.setMachineCount(100);
        chart.machine(0)
            .setMachineCountFixed(true);
        final Drawer dust = product(chart, Rule.AT_LEAST, 2, 0, 1);

        final SolutionView s = solved(chart.graph());

        assertFalse(s.drawers.met(dust.getId()));
        assertEquals(100 / 112.5, s.drawers.rate(dust.getId()), EPS, "the plan runs at what the pin makes");
        final DrawerReadout.Shortfall shortfall = s.drawers.shortfall(dust.getId());
        assertNotNull(shortfall);
        assertEquals(100 / 112.5, shortfall.reachable(), EPS);
        assertEquals(List.of(chart.machine(0).id), shortfall.limitingNodes());
    }

    // ── helpers ──

    /** light_fuel without the distillery's number pin, so nothing but drawers sets its scale. */
    private static LoadedChart unpinnedLightFuel() {
        final LoadedChart chart = GtnhFlowLoader.load("light_fuel");
        chart.machine(1)
            .setMachineCountFixed(false);
        return chart;
    }

    private static Drawer product(final LoadedChart chart, final Rule rule, final double rate, final int machine,
        final int port) {
        return drawer(chart, Kind.PRODUCT, rule, rate, machine, port);
    }

    private static Drawer source(final LoadedChart chart, final Rule rule, final double rate, final int machine,
        final int port) {
        return drawer(chart, Kind.SOURCE, rule, rate, machine, port);
    }

    private static Drawer drawer(final LoadedChart chart, final Kind kind, final Rule rule, final double rate,
        final int machine, final int port) {
        final Graph graph = chart.graph();
        final Drawer drawer = new Drawer(kind, "test:" + kind + machine + "/" + port);
        drawer.setTarget(rule, rate);
        graph.addDrawer(drawer);
        graph.linkDrawer(drawer.getId(), new Link(chart.machine(machine).id, port));
        return drawer;
    }

    private static SolutionView solved(final Graph graph) {
        final Answer answer = Balancer.solveWithAlternatives(BalanceMode.AUTO, graph, null, Map.of());
        if (answer instanceof final Answer.Solved solved) return solved.solution();
        throw new AssertionError("solve failed: " + answer);
    }

    private static boolean hasNote(final List<Note> notes, final SolverMessage message) {
        return notes.stream()
            .anyMatch(n -> n.containsMessage(message));
    }

    private static double terminalRate(final SolutionView s, final UUID node, final int port, final boolean input) {
        for (final External e : input ? s.terminalInputs : s.terminalOutputs) {
            if (e.port()
                .nodeId()
                .equals(node)
                && e.port()
                    .portIndex() == port)
                return e.ratePerSecond();
        }
        return 0;
    }
}

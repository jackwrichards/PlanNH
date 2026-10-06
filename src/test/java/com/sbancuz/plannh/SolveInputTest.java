package com.sbancuz.plannh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.sbancuz.plannh.data.flowchart.Drawer;
import com.sbancuz.plannh.data.flowchart.Graph;
import com.sbancuz.plannh.data.flowchart.Node;
import com.sbancuz.plannh.data.flowchart.balancer.BalanceMode;
import com.sbancuz.plannh.data.flowchart.balancer.BalanceResult;
import com.sbancuz.plannh.data.flowchart.balancer.Balancer;
import com.sbancuz.plannh.data.flowchart.balancer.SolveInput;
import com.sbancuz.plannh.data.flowchart.balancer.SolveInput.DrawerIn;
import com.sbancuz.plannh.data.flowchart.balancer.SolveInput.EdgeIn;
import com.sbancuz.plannh.data.flowchart.balancer.SolveInput.Machine;
import com.sbancuz.plannh.data.flowchart.balancer.SolveInput.PortIn;
import com.sbancuz.plannh.harness.GtnhFlowLoader;
import com.sbancuz.plannh.harness.GtnhFlowLoader.LoadedChart;

/** The plain-data solve: the snapshot is complete, detached, and solvable on any thread. */
class SolveInputTest {

    private static final double EPS = 1e-6;

    @Test
    void solvingASnapshotMatchesSolvingTheGraph() {
        for (final String name : new String[] { "light_fuel", "mk1", "loopGraph", "palladium", "excess_choice" }) {
            final LoadedChart chart = GtnhFlowLoader.load(name);
            final BalanceResult viaGraph = Balancer.balance(chart.graph(), BalanceMode.AUTO);
            final BalanceResult viaSnapshot = Balancer.solve(
                SolveInput.of(
                    chart.graph(),
                    BalanceMode.AUTO,
                    chart.graph()
                        .getExcessChoice()));
            for (final Node machine : chart.machines()) {
                assertEquals(
                    viaGraph.nodeBalances()
                        .get(machine.id)
                        .operations(),
                    viaSnapshot.nodeBalances()
                        .get(machine.id)
                        .operations(),
                    EPS,
                    name + ": " + machine.machineName);
            }
            assertEquals(viaGraph.notes(), viaSnapshot.notes(), name);
        }
    }

    @Test
    void aSnapshotDoesNotSeeLaterEdits() {
        final LoadedChart chart = GtnhFlowLoader.load("mk1"); // target: 10 mk1 fuel/s
        final SolveInput before = SolveInput.of(chart.graph(), BalanceMode.AUTO, null);
        final double fusionBefore = Balancer.solve(before)
            .nodeBalances()
            .get(chart.machine(0).id)
            .operations();

        chart.machine(0).targetOutputRates.put(0, 20.0);
        chart.graph()
            .removeNode(chart.machine(1).id);

        final double fusionAgain = Balancer.solve(before)
            .nodeBalances()
            .get(chart.machine(0).id)
            .operations();
        assertEquals(fusionBefore, fusionAgain, EPS, "the snapshot still holds the old chart");
        assertEquals(
            2,
            before.machines()
                .size());
    }

    @Test
    void aSnapshotSolvesOnAnotherThread() throws Exception {
        final LoadedChart chart = GtnhFlowLoader.load("light_fuel");
        final SolveInput input = SolveInput.of(chart.graph(), BalanceMode.AUTO, null);
        final BalanceResult here = Balancer.solve(input);

        final BalanceResult there = CompletableFuture.supplyAsync(() -> Balancer.solve(input))
            .get(60, TimeUnit.SECONDS);

        assertInstanceOf(BalanceResult.Solved.class, there);
        assertEquals(here.totalOperations(), there.totalOperations(), EPS);
    }

    @Test
    void aHandBuiltSnapshotNeedsNoGraph() {
        // Two machines, no Node anywhere: a 1 s smelter turning 2 ore into 1 ingot, and a 2 s press
        // turning 3 ingots into 1 plate. 1 plate/s is 1 press craft/s, so 2 presses; that eats
        // 3 ingots/s, which is 3 smelter crafts/s, so 3 smelters.
        final UUID smelter = UUID.randomUUID();
        final UUID press = UUID.randomUUID();
        final PortIn ore = new PortIn(2, 1f, 1f, 0, "ore", false);
        final PortIn ingotOut = new PortIn(1, 1f, 1f, 1, "ingot", false);
        final PortIn ingotIn = new PortIn(3, 1f, 1f, 1, "ingot", false);
        final PortIn plate = new PortIn(1, 1f, 1f, 2, "plate", false);
        final List<Machine> machines = List.of(
            new Machine(smelter, "smelter", 20, 0, 1, List.of(ore), List.of(ingotOut), false, 1, Map.of(), Map.of()),
            new Machine(press, "press", 40, 0, 1, List.of(ingotIn), List.of(plate), false, 1, Map.of(), Map.of()));
        final List<EdgeIn> edges = List.of(new EdgeIn(UUID.randomUUID(), smelter, 0, press, 0));
        final UUID product = UUID.randomUUID();
        final List<DrawerIn> drawers = List.of(
            new DrawerIn(
                product,
                "plates",
                Drawer.Kind.PRODUCT,
                Drawer.Rule.EXACTLY,
                1.0,
                List.of(new Drawer.Link(press, 0))));
        final SolveInput input = new SolveInput(BalanceMode.AUTO, null, machines, edges, List.of(), drawers, Map.of());

        final BalanceResult result = Balancer.solve(input);

        assertInstanceOf(BalanceResult.Solved.class, result, () -> "notes: " + result.notes());
        assertEquals(
            3.0,
            result.nodeBalances()
                .get(smelter)
                .operations(),
            EPS);
        assertEquals(
            2.0,
            result.nodeBalances()
                .get(press)
                .operations(),
            EPS);
        assertEquals(
            1.0,
            result.drawerRates()
                .get(product),
            EPS);
    }

    @Test
    void portsThatConnectShareAnIngredientNumber() {
        final LoadedChart chart = GtnhFlowLoader.load("light_fuel");
        final SolveInput input = SolveInput.of(chart.graph(), BalanceMode.AUTO, null);
        final Machine reactor = input.machines()
            .get(indexOf(input, chart.machine(0).id));
        final Machine distillery = input.machines()
            .get(indexOf(input, chart.machine(1).id));
        final Machine electrolyzer = input.machines()
            .get(indexOf(input, chart.machine(2).id));

        assertEquals(
            distillery.outputs()
                .get(0)
                .resource(),
            reactor.inputs()
                .get(1)
                .resource(),
            "sulfuric light fuel");
        assertEquals(
            electrolyzer.outputs()
                .get(0)
                .resource(),
            reactor.inputs()
                .get(0)
                .resource(),
            "hydrogen");
        assertNotEquals(
            reactor.outputs()
                .get(0)
                .resource(),
            reactor.outputs()
                .get(1)
                .resource());
        assertEquals(
            "oil",
            distillery.inputs()
                .get(0)
                .name());
    }

    @Test
    void graphVersionsNeverRepeatAcrossGraphs() {
        final Graph a = new Graph("a");
        final Graph b = new Graph("b");
        assertNotEquals(a.version(), b.version(), "two fresh graphs");
        final long before = b.version();
        a.addDrawer(new Drawer());
        assertTrue(a.version() > before, "one counter for every graph");
    }

    private static int indexOf(final SolveInput input, final UUID id) {
        for (int i = 0; i < input.machines()
            .size(); i++) {
            if (input.machines()
                .get(i)
                .id()
                .equals(id)) return i;
        }
        throw new AssertionError("no machine " + id);
    }
}

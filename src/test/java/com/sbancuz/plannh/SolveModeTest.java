package com.sbancuz.plannh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import org.junit.jupiter.api.Test;

import com.sbancuz.plannh.data.flowchart.Drawer;
import com.sbancuz.plannh.data.flowchart.Drawer.Kind;
import com.sbancuz.plannh.data.flowchart.Drawer.Link;
import com.sbancuz.plannh.data.flowchart.Drawer.Rule;
import com.sbancuz.plannh.data.flowchart.Graph;
import com.sbancuz.plannh.data.flowchart.balancer.BalanceMode;
import com.sbancuz.plannh.data.flowchart.balancer.Balancer;
import com.sbancuz.plannh.data.flowchart.balancer.Balancer.Answer;
import com.sbancuz.plannh.data.flowchart.balancer.DrawerReadout;
import com.sbancuz.plannh.data.flowchart.balancer.External;
import com.sbancuz.plannh.data.flowchart.balancer.Note;
import com.sbancuz.plannh.data.flowchart.balancer.SolutionView;
import com.sbancuz.plannh.data.flowchart.balancer.SolveInput;
import com.sbancuz.plannh.data.flowchart.balancer.SolveInput.EdgeIn;
import com.sbancuz.plannh.data.flowchart.balancer.SolveInput.Machine;
import com.sbancuz.plannh.data.flowchart.balancer.SolveInput.PoolIn;
import com.sbancuz.plannh.data.flowchart.balancer.SolveInput.PortIn;
import com.sbancuz.plannh.data.flowchart.balancer.SolverMessage;
import com.sbancuz.plannh.harness.GtnhFlowLoader;
import com.sbancuz.plannh.harness.GtnhFlowLoader.LoadedChart;

/**
 * Solve-mode rules ported from Factory Flow's solver tests (src/lib/solver/solve-mode.test.ts and
 * shared-rates.test.ts). Every recipe takes one second, so a machine count is also crafts per
 * second and every expected number is a recipe ratio times the rate asked for.
 *
 * <p>
 * Factory Flow puts a drawer on every port; PlanNH leaves a port unwired instead, which imports or
 * exports freely, the way Factory Flow's rule-free source and product drawers do. Where a case
 * leans on the two apps' other differences (a wired surplus can leave through a gate here, and a
 * pin outranks a drawer's rate), the test says so and checks PlanNH's answer.
 */
class SolveModeTest {

    private static final double EPS = 1e-4;

    /** One op makes 1 heavy and 4 light: the distillation shape. */
    private static final String STILL = "- {m: still, dur: 1, I: {oil: 1}, O: {heavy: 1, light: 4}}";
    /** Two ore make one ingot: the shared-rates fixture. */
    private static final String SMELTER = "- {m: smelter, dur: 1, I: {ore: 2}, O: {ingot: 1}}";

    // ── Products and sources (solve-mode.test.ts) ──

    @Test
    void twoProductsOnOneRecipeRunForTheBiggerAsk() {
        // 2 heavy/s is 2 crafts/s, and 2 crafts make 8 light: the light target of 1 is met with
        // surplus rather than reported broken.
        final LoadedChart chart = chart("still", STILL);
        final Drawer oil = drawer(chart, Kind.SOURCE, Rule.ANY, 0, 0, 0);
        final Drawer heavy = drawer(chart, Kind.PRODUCT, Rule.AT_LEAST, 2, 0, 0);
        final Drawer light = drawer(chart, Kind.PRODUCT, Rule.AT_LEAST, 1, 0, 1);

        final SolutionView s = solved(chart);

        assertEquals(2, count(s, chart, 0), EPS);
        assertEquals(2, s.drawers.rate(heavy.getId()), EPS);
        assertEquals(8, s.drawers.rate(light.getId()), EPS);
        assertEquals(2, s.drawers.rate(oil.getId()), EPS);
        assertTrue(
            s.drawers.unmet()
                .isEmpty(),
            "both met: " + s.notes);
    }

    @Test
    void aMachineStrandedFromEveryAskReadsZero() {
        // The kit asks for 1/s, so one brewery runs. Nothing asks the lathe for anything and no wire
        // ties it to what does, so it stays at zero rather than at a size nobody set.
        final LoadedChart chart = chart(
            "stranded",
            "- {m: lathe, dur: 1, I: {ore: 1}, O: {gear: 1}}\n- {m: brewery, dur: 1, I: {water: 1}, O: {kit: 1}}");
        final Drawer gear = drawer(chart, Kind.PRODUCT, Rule.ANY, 0, 0, 0);
        final Drawer kit = drawer(chart, Kind.PRODUCT, Rule.AT_LEAST, 1, 1, 0);

        final SolutionView s = solved(chart);

        assertEquals(1, count(s, chart, 1), EPS);
        assertEquals(1, s.drawers.rate(kit.getId()), EPS);
        assertEquals(0, s.extentsPerSecond.get(chart.machine(0).id), EPS, "the stranded lathe is idle");
        assertEquals(0, s.drawers.rate(gear.getId()), EPS);
        assertTrue(s.drawers.met(kit.getId()));
    }

    @Test
    void aPinnedCountWithNoProductDrivesTheWholeLine() {
        // Three assemblers eat 3 gear/s; a lathe makes 2 per craft, so 1.5 lathes. The kit drawer
        // asks for nothing and reads what falls out.
        final LoadedChart chart = chart("pinned line", gearLine("", ", number: 3"));
        final Drawer kit = drawer(chart, Kind.PRODUCT, Rule.ANY, 0, 1, 0);

        final SolutionView s = solved(chart);

        assertEquals(1.5, count(s, chart, 0), EPS);
        assertEquals(3, count(s, chart, 1), EPS);
        assertEquals(3, s.drawers.rate(kit.getId()), EPS);
        assertEquals(0, s.openGates, "the gear is all used");
    }

    @Test
    void anExactProductLeavesAPinnedFeedersSurplusAtItsByproductDrawer() {
        // Four lathes make 8 gear/s; exactly 2 kits/s takes two assemblers eating 2 of them, and the
        // other 6 leave at the lathe, where the byproduct drawer reads them.
        final LoadedChart chart = chart("pin and product", gearLine(", number: 4", ""));
        final Drawer kit = drawer(chart, Kind.PRODUCT, Rule.EXACTLY, 2, 1, 0);
        final Drawer spare = drawer(chart, Kind.BYPRODUCT, Rule.ANY, 0, 0, 0);

        final SolutionView s = solved(chart);

        assertEquals(4, count(s, chart, 0), EPS);
        assertEquals(2, count(s, chart, 1), EPS);
        assertEquals(2, s.drawers.rate(kit.getId()), EPS);
        assertEquals(6, s.drawers.rate(spare.getId()), EPS);
    }

    @Test
    void anAtLeastProductLetsAPinnedFeederRunWithoutVoiding() {
        // Factory Flow reads "at least 2 kits" as 2 and voids the other 6 gear. PlanNH counts gates
        // before quantities: eight assemblers eat all 8 gear/s with no gate at all, and 8 kits/s is
        // at least 2.
        final LoadedChart chart = chart("pin and minimum", gearLine(", number: 4", ""));
        final Drawer kit = drawer(chart, Kind.PRODUCT, Rule.AT_LEAST, 2, 1, 0);
        final Drawer spare = drawer(chart, Kind.BYPRODUCT, Rule.ANY, 0, 0, 0);

        final SolutionView s = solved(chart);

        assertEquals(0, s.openGates);
        assertEquals(8, count(s, chart, 1), EPS);
        assertEquals(8, s.drawers.rate(kit.getId()), EPS);
        assertEquals(0, s.drawers.rate(spare.getId()), EPS);
        assertTrue(s.drawers.met(kit.getId()));
    }

    @Test
    void aByproductsDormantRateAsksForNothing() {
        // Typed while the drawer was a product and kept for the flip back: as a product it runs
        // five lathes, as a byproduct it must not run any.
        final LoadedChart asProduct = chart("dormant", "- {m: lathe, dur: 1, I: {ore: 1}, O: {gear: 1}}");
        drawer(asProduct, Kind.PRODUCT, Rule.AT_LEAST, 5, 0, 0);
        assertEquals(5, count(solved(asProduct), asProduct, 0), EPS, "the number is live on a product");

        final LoadedChart chart = chart("dormant", "- {m: lathe, dur: 1, I: {ore: 1}, O: {gear: 1}}");
        final Drawer gear = drawer(chart, Kind.PRODUCT, Rule.AT_LEAST, 5, 0, 0);
        gear.setKind(Kind.BYPRODUCT);

        final Answer answer = solve(chart.graph());

        final Answer.Failed failed = assertInstanceOf(Answer.Failed.class, answer, () -> "solved: " + answer);
        assertEquals(
            SolverMessage.NO_PIN,
            failed.failure()
                .message());
    }

    // ── Pins ──

    @Test
    void twoPinsAcrossAWireLeaveTheSurplusAtAGate() {
        // Factory Flow's pin conflict as drawn there: four lathes make 8 gear/s for one assembler
        // that eats 1/s. Adapted, not ported: a wired port in PlanNH may sink its surplus through a
        // gate, so these pins hold together and the other 7 gear/s leave at the lathe.
        final LoadedChart chart = chart("pins and a wire", gearLine(", number: 4", ", number: 1"));

        final SolutionView s = solved(chart);

        assertEquals(4, count(s, chart, 0), EPS);
        assertEquals(1, count(s, chart, 1), EPS);
        assertEquals(1, s.gatedSinks.size(), "one gate: " + s.gatedSinks);
        final External sink = s.gatedSinks.get(0);
        assertEquals(
            chart.machine(0).id,
            sink.port()
                .nodeId());
        assertEquals(7, sink.ratePerSecond(), EPS);
        assertFalse(hasNote(s.notes, SolverMessage.PIN_CONFLICT), "nothing to conflict: " + s.notes);
        GroundTruthTest.assertPortsConserve("pins and a wire", chart, s);
    }

    @Test
    void pinsThatCannotBothHoldNameThePinToDrop() {
        // A gate absorbs any mismatch on a wire (above), so two pins only collide where nothing can
        // take up the difference: a capped machine group. The assembler's target of 3 kits/s asks
        // for 3 machines, the lathe is pinned at 2, and the group has 4. Either fits, both do not.
        // Pins are freed weakest first, so the fixed count is named and the target is kept; the
        // assembler is listed first so that it is the strength, not the order, that decides.
        final UUID assembler = UUID.nameUUIDFromBytes("assembler".getBytes(StandardCharsets.UTF_8));
        final UUID lathe = UUID.nameUUIDFromBytes("lathe".getBytes(StandardCharsets.UTF_8));

        final Answer answer = Balancer.solveWithAlternatives(groupedLine(assembler, lathe, true));

        final Answer.Failed failed = assertInstanceOf(Answer.Failed.class, answer, () -> "solved: " + answer);
        final Note conflict = find(failed.failure(), SolverMessage.PIN_CONFLICT);
        assertNotNull(conflict, "the failure names the conflict: " + failed.failure());
        // Each dropped pin is a nested note: the machine, then its kind (rendered in game, not a raw key).
        final List<Object> dropped = new ArrayList<>();
        for (final Object pin : (List<?>) conflict.args()[0]) {
            final Note note = assertInstanceOf(Note.class, pin);
            assertEquals(SolverMessage.PIN_DROPPED, note.message());
            assertEquals(SolverMessage.PIN_COUNT, assertInstanceOf(Note.class, note.args()[1]).message());
            dropped.add(note.args()[0]);
        }
        assertEquals(List.of("lathe"), dropped);

        // And dropping the named pin is enough. With the lathe free the group's last machine goes to
        // it: one lathe makes 2 gear/s for an assembler eating 3, and the other 1/s comes in.
        final Answer freed = Balancer.solveWithAlternatives(groupedLine(assembler, lathe, false));

        final SolutionView s = assertInstanceOf(Answer.Solved.class, freed, () -> "failed: " + freed).solution();
        assertEquals(3, s.machineCounts.get(assembler), EPS);
        assertEquals(1, s.machineCounts.get(lathe), EPS);
        assertEquals(1, s.gatedSources.size(), "imports: " + s.gatedSources);
        assertEquals(
            1,
            s.gatedSources.get(0)
                .ratePerSecond(),
            EPS);
    }

    // ── Sources against products (shared-rates.test.ts) ──

    @Test
    void aSourceAtLeastTenWithAProductOfEightDrawsSixteen() {
        // 8 ingots/s is 8 crafts/s, which eats 16 ore/s: past the source's minimum of 10.
        final LoadedChart chart = chart("smelter", SMELTER);
        final Drawer ore = drawer(chart, Kind.SOURCE, Rule.AT_LEAST, 10, 0, 0);
        final Drawer ingot = drawer(chart, Kind.PRODUCT, Rule.AT_LEAST, 8, 0, 0);

        final SolutionView s = solved(chart);

        assertEquals(16, s.drawers.rate(ore.getId()), EPS);
        assertEquals(8, s.drawers.rate(ingot.getId()), EPS);
        assertTrue(
            s.drawers.unmet()
                .isEmpty(),
            "both met: " + s.notes);
    }

    @Test
    void anExactProductAgainstAnExactSourceKeepsTheProduct() {
        // Exactly 10 ore/s is 5 ingots, not 3, so one rule gives. A miss weighs 1/max(1, rate) per
        // unit: x crafts/s miss by |10 - 2x|/10 + |3 - x|/3, which falls until x = 3 and rises
        // after. So the ingots hold at 3 and the ore reads 6, unmet, held there by the ingot rule.
        final LoadedChart chart = chart("smelter", SMELTER);
        final Drawer ore = drawer(chart, Kind.SOURCE, Rule.EXACTLY, 10, 0, 0);
        final Drawer ingot = drawer(chart, Kind.PRODUCT, Rule.EXACTLY, 3, 0, 0);

        final SolutionView s = solved(chart);

        assertEquals(3, s.drawers.rate(ingot.getId()), EPS);
        assertTrue(s.drawers.met(ingot.getId()));
        assertEquals(6, s.drawers.rate(ore.getId()), EPS);
        assertFalse(s.drawers.met(ore.getId()));
        final DrawerReadout.Shortfall shortfall = s.drawers.shortfall(ore.getId());
        assertNotNull(shortfall);
        assertEquals(6, shortfall.reachable(), EPS);
        assertEquals(List.of(ingot.getId()), shortfall.limitingDrawers());
    }

    @Test
    void aProductAtMostZeroStopsTheSmelterAndTheSourceGoesUnmet() {
        // Any ore at all makes ingots, so the two rules cannot both hold. A miss weighs
        // 1/max(1, rate) per unit: an ingot over the limit of 0 weighs 1, an ore short of 10 weighs
        // 1/10. x crafts/s miss by x + (10 - 2x)/10 = 1 + 0.8x, least at x = 0.
        final LoadedChart chart = chart("smelter", SMELTER);
        final Drawer ore = drawer(chart, Kind.SOURCE, Rule.EXACTLY, 10, 0, 0);
        final Drawer ingot = drawer(chart, Kind.PRODUCT, Rule.AT_MOST, 0, 0, 0);

        final SolutionView s = solved(chart);

        assertEquals(0, s.drawers.rate(ingot.getId()), EPS);
        assertTrue(s.drawers.met(ingot.getId()));
        assertEquals(0, s.drawers.rate(ore.getId()), EPS);
        assertFalse(s.drawers.met(ore.getId()));
        assertEquals(0, count(s, chart, 0), EPS);
    }

    @Test
    void aSourceAtMostZeroStarvesTheProduct() {
        // The other way round: ore over the limit of 0 weighs 1 per unit, ingots short of 3 weigh
        // 1/3. x crafts/s miss by 2x + (3 - x)/3 = 1 + 5x/3, least at x = 0, and it is the ore
        // limit that holds the ingots back.
        final LoadedChart chart = chart("smelter", SMELTER);
        final Drawer ore = drawer(chart, Kind.SOURCE, Rule.AT_MOST, 0, 0, 0);
        final Drawer ingot = drawer(chart, Kind.PRODUCT, Rule.AT_LEAST, 3, 0, 0);

        final SolutionView s = solved(chart);

        assertEquals(0, s.drawers.rate(ore.getId()), EPS);
        assertTrue(s.drawers.met(ore.getId()));
        assertEquals(0, s.drawers.rate(ingot.getId()), EPS);
        assertFalse(s.drawers.met(ingot.getId()));
        final DrawerReadout.Shortfall shortfall = s.drawers.shortfall(ingot.getId());
        assertNotNull(shortfall);
        assertEquals(List.of(ore.getId()), shortfall.limitingDrawers());
    }

    @Test
    void aPinnedCountPastASourceLimitKeepsThePinAndFlagsTheSource() {
        // Factory Flow keeps the limit and flags the pin. Adapted: in PlanNH a pin is the player's
        // word and a drawer's rate a wish (DrawerRelaxation), so six smelters run, eating 12 ore/s
        // against a limit of 10, and the source says so, naming the pin. One pin conflicts with
        // nothing, so there is no pin conflict to report.
        final LoadedChart chart = chart("smelter", "- {m: smelter, dur: 1, I: {ore: 2}, O: {ingot: 1}, number: 6}");
        final Drawer ore = drawer(chart, Kind.SOURCE, Rule.AT_MOST, 10, 0, 0);

        final SolutionView s = solved(chart);

        assertEquals(6, count(s, chart, 0), EPS);
        assertEquals(12, s.drawers.rate(ore.getId()), EPS);
        assertFalse(s.drawers.met(ore.getId()));
        final DrawerReadout.Shortfall shortfall = s.drawers.shortfall(ore.getId());
        assertNotNull(shortfall);
        assertEquals(Rule.AT_MOST, shortfall.rule());
        assertEquals(10, shortfall.target(), 0);
        assertEquals(12, shortfall.reachable(), EPS);
        assertEquals(List.of(chart.machine(0).id), shortfall.limitingNodes());
        assertTrue(hasNote(s.notes, SolverMessage.DRAWER_UNMET), "notes: " + s.notes);
        assertFalse(hasNote(s.notes, SolverMessage.PIN_CONFLICT), "notes: " + s.notes);
    }

    // ── helpers ──

    private static LoadedChart chart(final String name, final String yaml) {
        return GtnhFlowLoader.parse(name, yaml);
    }

    /**
     * A lathe turning one ore into two gears, wired into an assembler turning one gear into a kit.
     * The extras go into each machine's entry, e.g. {@code ", number: 4"} for a pin.
     */
    private static String gearLine(final String latheExtra, final String assemblerExtra) {
        return "- {m: lathe, dur: 1, I: {ore: 1}, O: {gear: 2}" + latheExtra
            + "}\n- {m: assembler, dur: 1, I: {gear: 1}, O: {kit: 1}"
            + assemblerExtra
            + "}";
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

    private static Answer solve(final Graph graph) {
        return Balancer.solveWithAlternatives(BalanceMode.AUTO, graph, null, Map.of());
    }

    private static SolutionView solved(final LoadedChart chart) {
        final Answer answer = solve(chart.graph());
        if (answer instanceof final Answer.Solved solved) return solved.solution();
        throw new AssertionError(chart.name() + " failed: " + answer);
    }

    private static double count(final SolutionView s, final LoadedChart chart, final int machine) {
        return s.machineCounts.get(chart.machine(machine).id);
    }

    private static boolean hasNote(final List<Note> notes, final SolverMessage message) {
        return notes.stream()
            .anyMatch(n -> n.containsMessage(message));
    }

    /** The note speaking {@code message}, searched through nested notes; null when none does. */
    @Nullable
    private static Note find(final Note note, final SolverMessage message) {
        if (note.message() == message) return note;
        for (final Object arg : note.args()) {
            if (arg instanceof final Note nested) {
                final Note hit = find(nested, message);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    /**
     * A lathe (1 ore to 2 gear) wired into an assembler (1 gear to 1 kit, target 3 kits/s), both
     * one-second recipes in one machine group of 4; the lathe is pinned at 2 machines when asked.
     */
    private static SolveInput groupedLine(final UUID assembler, final UUID lathe, final boolean pinLathe) {
        final PortIn ore = new PortIn(1, 1f, 1f, 0, "ore", false);
        final PortIn gearOut = new PortIn(2, 1f, 1f, 1, "gear", false);
        final PortIn gearIn = new PortIn(1, 1f, 1f, 1, "gear", false);
        final PortIn kit = new PortIn(1, 1f, 1f, 2, "kit", false);
        final List<Machine> machines = List.of(
            new Machine(
                assembler,
                "assembler",
                20,
                0,
                1,
                List.of(gearIn),
                List.of(kit),
                false,
                1,
                Map.of(0, 3.0),
                Map.of()),
            new Machine(lathe, "lathe", 20, 0, 1, List.of(ore), List.of(gearOut), pinLathe, 2, Map.of(), Map.of()));
        final List<EdgeIn> edges = List
            .of(new EdgeIn(UUID.nameUUIDFromBytes("gear".getBytes(StandardCharsets.UTF_8)), lathe, 0, assembler, 0));
        final List<PoolIn> pools = List.of(new PoolIn(List.of(assembler, lathe), 4));
        return new SolveInput(BalanceMode.AUTO, null, machines, edges, pools, List.of(), Map.of());
    }
}

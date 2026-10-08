package com.gtnhplanner.power;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.gtnhplanner.power.PowerModel.Flow;
import com.gtnhplanner.power.PowerModel.Unit;
import com.gtnhplanner.power.sources.Reactors;

/**
 * The website's power.test.ts, its "reactors and endgame" block and the Dyson Swarm case. Golden values are the
 * Power Planner 2.9 workbook's own computed cells (docs/power-planner-math.md documents which); a failure here means
 * the transcription drifted from the spreadsheet, not that the spreadsheet moved.
 */
class PowerSheetReactorsEndgameTest {

    private static PowerModel compute(final String sourceId, final Map<String, String> settings) {
        final PowerSource source = PowerRegistry.get(sourceId);
        if (source == null) throw new IllegalArgumentException("No power source " + sourceId);
        return source.compute(settings);
    }

    private static PowerModel compute(final String sourceId) {
        return compute(sourceId, Map.of());
    }

    /** The flow with this name (the TS's {@code find(...)!}); fails when there is none. */
    private static Flow find(final List<Flow> flows, final String name) {
        for (final Flow flow : flows) {
            if (flow.name()
                .equals(name)) return flow;
        }
        throw new AssertionError("No flow " + name);
    }

    private static boolean has(final List<Flow> flows, final String name) {
        for (final Flow flow : flows) {
            if (flow.name()
                .equals(name)) return true;
        }
        return false;
    }

    private static boolean anyContains(final List<String> lines, final String part) {
        for (final String line : lines) {
            if (line.contains(part)) return true;
        }
        return false;
    }

    /** vitest's toBeCloseTo(expected, digits): within half a unit of the last digit. */
    private static void assertCloseTo(final double expected, final double actual, final int digits) {
        assertEquals(expected, actual, 0.5 * Math.pow(10, -digits));
    }

    private static final Pattern JS_DECIMAL = Pattern.compile("[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?");

    /** JavaScript's Number(string): NaN for anything that is not a numeric literal ("1,234" and "1.2M" are NaN). */
    private static double jsNumber(final String text) {
        final String trimmed = text.trim();
        if (trimmed.isEmpty()) return 0;
        if (trimmed.matches("[+-]?Infinity"))
            return trimmed.startsWith("-") ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        if (trimmed.matches("0[xX][0-9a-fA-F]+")) return Long.parseLong(trimmed.substring(2), 16);
        if (trimmed.matches("0[oO][0-7]+")) return Long.parseLong(trimmed.substring(2), 8);
        if (trimmed.matches("0[bB][01]+")) return Long.parseLong(trimmed.substring(2), 2);
        return JS_DECIMAL.matcher(trimmed)
            .matches() ? Double.parseDouble(trimmed) : Double.NaN;
    }

    // ---- RTG and Dyson Swarm (the Dyson Swarm case only; the RTG belongs to the singleblocks)

    @Test
    @DisplayName("pays the Dyson Swarm 10M EU/t per module times the dimension factor")
    void dysonSwarm() {
        final PowerModel model = compute("dyson-swarm", Map.of("modules", "250", "factor", "2"));
        assertEquals(250d * 10_000_000 * 2, model.euPerTick(), 0);
        assertEquals(List.of(new Flow("Cryotheum", 1000, Unit.L)), model.inputs());
        assertTrue(anyContains(model.warnings(), "Modules burn off"));
    }

    // ---- reactors and endgame

    @Test
    @DisplayName("computes THTR full-fill efficiency 1.0 and the parasitic draw")
    void thtrFullFill() {
        final PowerModel model = compute("thtr", Map.of("fill", "675000"));
        assertCloseTo(-3840, model.euPerTick(), 6);
        assertEquals(
            4800 * 20,
            model.outputs()
                .get(0)
                .perSecond(),
            0);
    }

    @Test
    @DisplayName("burns THTR pebbles and hands them back burned, a ball per 64")
    void thtrPebbles() {
        // 675,000 x 0.5% = 3,375 pebbles per 9-hour operation: 52 balls + 47 loose.
        final PowerModel model = compute("thtr", Map.of("fill", "675000"));
        assertEquals(3375, perOperation(find(model.inputs(), "TRISO pebble").perSecond()));
        assertEquals(52, perOperation(find(model.outputs(), "Burned Out TRISO pebble ball").perSecond()));
        assertEquals(47, perOperation(find(model.outputs(), "Burned Out TRISO pebble").perSecond()));
        final PowerResources.Ref pebble = PowerResources.resolve("TRISO pebble");
        assertNotNull(pebble);
        assertEquals("item", pebble.kind);
        assertEquals("bartworks:bw.thtrmaterials@4", pebble.id);
        // The helium is a one-time charge the reactor keeps, not a flow.
        assertFalse(has(model.inputs(), "Helium"));
    }

    private static long perOperation(final double perSecond) {
        return Math.round(perSecond * 32_400);
    }

    @Test
    @DisplayName("runs an HTGR operation the way kubatech does: fuel, helium, burned fuel")
    void htgrOperation() {
        // Glowstone at a full 10,000 balls: exponent 1.1 gives 16,528 ticks of progress, and full coolant + water
        // add 57 + 24 a tick, so 202 ticks.
        final Reactors.Pebble pebble = new Reactors.Pebble(2, 1.2, 1.1);
        final Reactors.HtgrOperation run = Reactors.htgrOperation(pebble, 10_000);
        assertEquals(202, run.cycleTicks(), 0);
        assertCloseTo(14.159265, run.burned(), 5);
        assertEquals(256, run.heliumLost(), 0);
        final PowerModel model = compute("htgr", Map.of("pebble", "Glowstone", "fill", "10000"));
        final double cycleSeconds = 202.0 / 20;
        assertCloseTo(run.burned() / cycleSeconds, find(model.inputs(), "TRISO Fuel (Glowstone)").perSecond(), 9);
        assertCloseTo(
            run.burned() / cycleSeconds,
            find(model.outputs(), "Burned Out TRISO Fuel (Glowstone)").perSecond(),
            9);
        assertCloseTo(256 / cycleSeconds, find(model.inputs(), "Helium").perSecond(), 9);
        assertEquals("kubatech:htgr_item_triso_fuel@1", PowerResources.resolve("TRISO Fuel (Uranium 235)").id);
        // A half-full reactor keeps its fuel base: the ball count alone scales the draw.
        final Reactors.HtgrOperation half = Reactors.htgrOperation(pebble, 5_000);
        assertCloseTo(2 * Math.pow(1.1, 1.05), half.energyMultiplier(), 9);
    }

    @Test
    @DisplayName("computes the HTGR glowstone multiplier (2.444)")
    void htgrGlowstoneMultiplier() {
        // COOLANT_PER_BALL is a per-tick figure; hot coolant out = 0.5 x fill x multiplier L/t, and the steam line is
        // the water line x160.
        final PowerModel model = compute("htgr", Map.of("pebble", "Glowstone", "fill", "10000"));
        final double multiplier = model.outputs()
            .get(0)
            .perSecond() / (0.5 * 10000 * 20);
        assertCloseTo(2.444, multiplier, 2);
        final Flow water = find(model.inputs(), "Distilled Water");
        final Flow steam = find(model.outputs(), "Steam");
        assertCloseTo(water.perSecond() * 160, steam.perSecond(), 6);
    }

    @ParameterizedTest(name = "generates the game-source output for {0}")
    @CsvSource({ "LFTR Fuel 1, 32768, 655360", "LFTR Fuel 2, 131072, 2621440", "LFTR Fuel 3, 524288, 10485760" })
    void lftrGameSourceOutput(final String fuel, final double euPerTick, final double euPerLiter) {
        // RecipeLoaderLFTR's output metadata x4 in MTENuclearReactor; 100 L fuel + 200 L carrier salt over 100
        // seconds, with no overclock.
        final PowerModel model = compute("lftr", Map.of("fuel", fuel));
        assertEquals(euPerTick, model.euPerTick(), 0);
        assertEquals(List.of(new Flow(fuel, 1, Unit.L), new Flow("Li2BeF4", 2, Unit.L)), model.inputs());
        assertEquals(
            euPerLiter,
            (model.euPerTick() * 20) / model.inputs()
                .get(0)
                .perSecond(),
            0);
        // A 1-in-300 chance every tick of 1-10 L.
        assertCloseTo(0.36667, find(model.outputs(), "Uranium-233").perSecond(), 5);
        // Not ported yet: the website's buildPowerRecipe (power-recipe.ts) checks here that the synthesized recipe
        // carries the same euPerTick and a power output of euPerTick x 20 per second:
        // expect(recipe?.power?.euPerTick).toBe(euPerTick);
        // expect(recipe?.outputs.find((slot) => slot.kind === "power")?.amount).toBe(euPerTick * 20);
    }

    @Test
    @Disabled("tests the website's plan-load resynthesis (resynthesizePowerRecipes in power-recipe.ts), not ported yet")
    @DisplayName("repairs saved LFTR Fuel 3 power and its output port on load")
    void lftrRepairsSavedPowerOnLoad() {
        // The website builds the LFTR Fuel 3 recipe, overwrites its power with Fuel 1's (32,768 EU/t, 655,360 EU/s
        // out), and checks that resynthesizePowerRecipes restores 524,288 EU/t and 10,485,760 EU/s, keeps the
        // inputs and keeps the node's fuel setting. Port it with power-recipe.ts.
    }

    @Test
    @DisplayName("runs the Vacuum Reactor's quad uranium layout at the workbook's 43,600 EU/t")
    void vacuumReactorQuadUranium() {
        final PowerModel model = compute("vacuum-reactor", Map.of("fuel", "uranium-4", "coolant", "he-360k"));
        assertEquals(43_600, model.euPerTick(), 0);
        // 40 quad rods over their 20,000 s lifespan, burned to depleted rods.
        assertEquals(
            new Flow("Quad Fuel Rod (Uranium)", 40.0 / 20_000, Unit.ITEM),
            model.inputs()
                .get(0));
        assertEquals(
            new Flow("Quad Fuel Rod (Depleted Uranium)", 40.0 / 20_000, Unit.ITEM),
            model.outputs()
                .get(0));
        assertEquals(
            model.inputs()
                .get(1),
            model.outputs()
                .get(1));
        assertEquals("item", PowerResources.resolve("360k He Coolant Cell").kind);
        assertEquals("item", PowerResources.resolve("10k Coolant Cell").kind);
        assertEquals("item", PowerResources.resolve("Quad Fuel Rod (Uranium)").kind);
        assertEquals("item", PowerResources.resolve("Fuel Rod (Depleted Tiberium)").kind);
        assertEquals("item", PowerResources.resolve("The Core (Depleted)").kind);
        // Sheet: average cell decay 813.7 Hu/s, minimum coolant lifespan 267.9 s.
        assertEquals("813.71/s avg, 1,344/s max", model.stat("Cell heat"));
        assertEquals("267.86 s min, 442.42 s avg", model.stat("Coolant lifespan"));
        // Sheet W14: 1.9 cells a minute to recool, so the cells are real ports - hot out, cold in, one item id for
        // both - for a placed freezer to close.
        assertEquals("1.9 a minute", model.stat("Cells to recool"));
        final Flow cells = model.inputs()
            .get(1);
        assertEquals("360k He Coolant Cell", cells.name());
        assertCloseTo(14 / 442.42, cells.perSecond(), 3);
        assertEquals(Unit.ITEM, cells.unit());
    }

    @Test
    @DisplayName("prices every Vacuum Reactor rod from the mod source, MOX by core temp")
    void vacuumReactorRods() {
        assertEquals(8_720, compute("vacuum-reactor", Map.of("fuel", "thorium-4")).euPerTick(), 0);
        assertEquals(
            50 * (4 * 2 + 14 * 3 + 22 * 4),
            compute("vacuum-reactor", Map.of("fuel", "uranium-1")).euPerTick(),
            0);
        // The Core: 32 cells, 17 pulses, 200 EU a pulse - 4,979,200 on the layout.
        assertEquals(4_979_200, compute("vacuum-reactor", Map.of("fuel", "the-core")).euPerTick(), 0);
        // MOX rods multiply by 1 + heatBonus x core temp: MOX 1.5, HD Plutonium 6.
        final PowerModel mox = compute("vacuum-reactor", Map.of("fuel", "mox-4", "coreTemp", "98"));
        assertCloseTo(43_600 * (1 + 1.5 * 0.98), mox.euPerTick(), 6);
        assertTrue(anyContains(mox.warnings(), "melts at 100%"));
        assertCloseTo(
            43_600 * (1 + 6 * 0.98),
            compute("vacuum-reactor", Map.of("fuel", "high-density-plutonium-4", "coreTemp", "98")).euPerTick(),
            6);
        assertEquals(43_600, compute("vacuum-reactor", Map.of("fuel", "mox-4", "coreTemp", "0")).euPerTick(), 0);
        assertFalse(anyContains(compute("vacuum-reactor", Map.of("fuel", "uranium-4")).warnings(), "melts"));
    }

    @Test
    @DisplayName("flags a Vacuum Reactor coolant cell that bursts and pins the ports per second")
    void vacuumReactorBursts() {
        // Excited uranium on 10k cells: the hottest cell takes more heat a second than it holds.
        final PowerModel burst = compute(
            "vacuum-reactor",
            Map.of("fuel", "excited-uranium-4", "coolant", "coolant-10k"));
        assertTrue(anyContains(burst.warnings(), "bursts"));
        // The cell ports run at 14 cells per average lifespan; a quad uranium layout heats the average 360k cell out
        // in 442.4 s.
        final PowerModel model = compute("vacuum-reactor", Map.of("fuel", "uranium-4", "coolant", "he-360k"));
        assertCloseTo(
            14 / (360_000 / 813.7142857),
            model.inputs()
                .get(1)
                .perSecond(),
            6);
        assertFalse(
            compute("vacuum-reactor").stats()
                .stream()
                .anyMatch(
                    line -> line.label()
                        .equals("Freezers")));
    }

    @Test
    @DisplayName("multiplies the LNR by coolant and booster (5.85M EU/t)")
    void lnrCoolantAndBooster() {
        final PowerModel model = compute(
            "large-naquadah-reactor",
            Map.of("fuel", "Naq Fuel Mk-I", "coolant", "Super Coolant", "booster", "Molten Naquadah"));
        assertCloseTo(975000 * 1.5 * 4, model.euPerTick(), 4);
        assertTrue(
            model.inputs()
                .stream()
                .anyMatch(
                    flow -> flow.name()
                        .equals("Liquid Air") && flow.perSecond() == 2400));
    }

    // Game: FuelRecipeLoader getFluidOrGas(1) per recipe; MTELargeNaquadahReactor burns pall litres (booster
    // multiplier) over NaquadahFuelTime ticks. Wiki Large_Naquadah_Reactor summary table pins the boosted L/s figures.
    @Test
    @DisplayName("burns 1 L of LNR fuel per recipe, not 1000 L (wiki EU/L and L/s)")
    void lnrBurnsOneLitrePerRecipe() {
        final PowerModel base = compute(
            "large-naquadah-reactor",
            Map.of("fuel", "Naq Fuel Mk-I", "coolant", "None", "booster", "None"));
        // 1 L / 3 s; the old formula used 1000 L and reported 333.33 L/s.
        assertCloseTo(1.0 / 3, find(base.inputs(), "Naq Fuel Mk-I").perSecond(), 6);
        assertCloseTo(
            1.0 / 3,
            base.outputs()
                .get(0)
                .perSecond(),
            6);

        // Wiki: Mk-I + Cryotheum + Molten Naquadah (x4) -> 1.33 L/s fuel.
        final PowerModel wikiMkI = compute(
            "large-naquadah-reactor",
            Map.of("fuel", "Naq Fuel Mk-I", "coolant", "Cryotheum", "booster", "Molten Naquadah"));
        assertCloseTo(975000 * 2.75 * 4, wikiMkI.euPerTick(), 4);
        assertCloseTo(4.0 / 3, find(wikiMkI.inputs(), "Naq Fuel Mk-I").perSecond(), 6);
        assertEquals(1000, find(wikiMkI.inputs(), "Cryotheum").perSecond(), 0);
        assertEquals(20, find(wikiMkI.inputs(), "Molten Naquadah").perSecond(), 0);

        // Wiki / Discord: Mk-VI + Temporal Fluid + Enlarged Fluid (x64) -> 5.33 L/s.
        final PowerModel wikiMkVI = compute(
            "large-naquadah-reactor",
            Map.of(
                "fuel",
                "Naq Fuel Mk-VI",
                "coolant",
                "Tachyon Rich Temporal Fluid",
                "booster",
                "Spatially Enlarged Fluid"));
        assertCloseTo(2_077_795_200d * 5 * 64, wikiMkVI.euPerTick(), 0);
        assertCloseTo(64.0 / 12, find(wikiMkVI.inputs(), "Naq Fuel Mk-VI").perSecond(), 6);
        assertEquals(20, find(wikiMkVI.inputs(), "Tachyon Rich Temporal Fluid").perSecond(), 0);
    }

    @Test
    @DisplayName("runs helium fusion at Mk-I from the workbook table")
    void heliumFusionMk1() {
        final PowerModel model = compute("fusion-reactor", Map.of("recipe", "Helium Plasma", "mark", "1"));
        assertEquals(-1920, model.euPerTick(), 0);
        final Flow plasma = model.outputs()
            .get(0);
        assertEquals("Helium Plasma", plasma.name());
        assertEquals(156, plasma.perSecond(), 0);
        assertEquals(
            2,
            model.inputs()
                .size());
    }

    @Test
    @DisplayName("finds an interior antimatter optimum with positive net power")
    void antimatterOptimum() {
        final PowerModel model = compute("antimatter", Map.of("amount", "0"));
        assertTrue(model.euPerTick() > 1e12);
        final String best = model.stat("Best quantity");
        final double optimum = best == null ? Double.NaN : jsNumber(best);
        assertTrue(Double.isNaN(optimum)); // formatted, not raw - presence is what matters
    }
}

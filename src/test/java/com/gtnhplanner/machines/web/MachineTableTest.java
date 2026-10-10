package com.gtnhplanner.machines.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/**
 * machine-table.test.ts: the table's own cases, and the table side of the ones that go through the solver
 * (machine-effects, recipe-rules), which the golden fixture covers end to end.
 */
class MachineTableTest {

    private static MachineTable.Context ctx(final int voltageTier, final int tier, final double value) {
        return new MachineTable.Context(id -> tier, id -> value, voltageTier, null, null, null, null, null);
    }

    /** A context whose settings sit at these positions, as the solver's buildMachineContext reads them. */
    private static MachineTable.Context ctx(final int voltageTier, final Map<String, Integer> tiers,
        @Nullable final Integer recipeSpecialValue) {
        return new MachineTable.Context(
            id -> tiers.getOrDefault(id, 0),
            id -> 0,
            voltageTier,
            null,
            recipeSpecialValue,
            null,
            null,
            null);
    }

    private static List<String> ids(final List<Web.Control> controls) {
        final List<String> out = new ArrayList<>();
        for (final Web.Control c : controls) out.add(c.id);
        return out;
    }

    private static int position(final Web.Control control, final String key) {
        for (int i = 0; i < control.tiers.size(); i++) if (control.tiers.get(i).key.equals(key)) return i;
        return -1;
    }

    // region curated machine table

    @Test
    void isKeyedByOurNamesAndByTheReferencesNames() {
        assertEquals(MachineTable.normal(), MachineTable.behaviour("Chemical Plant").overclock);
        assertEquals(MachineTable.normal(), MachineTable.behaviour("ExxonMobil Chemical Plant").overclock);
        assertSame(MachineTable.HEAT, MachineTable.behaviour("Blast Furnace").overclock);
        assertSame(MachineTable.HEAT, MachineTable.behaviour("Electric Blast Furnace").overclock);
        assertEquals(MachineTable.perfect(), MachineTable.behaviour("Large Chemical Reactor").overclock);
    }

    @Test
    void hasEveryEntryOfTheWebsitesTableInItsOrder() {
        final List<String> names = MachineTable.names();
        assertEquals(96, names.size());
        assertEquals("Blast Furnace", names.get(0));
        assertEquals("Extreme Heat Exchanger", names.get(names.size() - 1));
    }

    @Test
    void scalesVoltageDrivenParallelsOffOurUlvBasedTierOrdinal() {
        // The reference counts LV as 0, we count it as 1. Zhuhai is ((theirTier + 1) + 1) * 2, which is
        // (ourTier + 1) * 2.
        final MachineTable.Behaviour zhuhai = MachineTable.behaviour("Zhuhai - Fishing Port");
        assertEquals(4, MachineTable.resolve(zhuhai.parallels, ctx(1, 0, 0), 1));
        assertEquals(18, MachineTable.resolve(zhuhai.parallels, ctx(8, 0, 0), 1));

        // Density^2 is floor((theirTier + 1) / 2) + 1 = floor(ourTier / 2) + 1.
        final MachineTable.Behaviour density = MachineTable.behaviour("Density^2");
        final int[][] cases = { { 1, 1 }, { 2, 2 }, { 3, 2 }, { 4, 3 } };
        for (final int[] c : cases) assertEquals(c[1], MachineTable.resolve(density.parallels, ctx(c[0], 0, 0), 1));
    }

    private static boolean close(final double a, final double b) {
        return Math.abs(a - b) <= 1e-9 * Math.max(1, Math.max(Math.abs(a), Math.abs(b)));
    }

    private static JsonObject reference() throws Exception {
        try (InputStream in = MachineTableTest.class.getResourceAsStream("/machines/reference-coefficients.json")) {
            assertNotNull(in, "reference-coefficients.json on the test classpath");
            return new JsonParser().parse(new InputStreamReader(in, StandardCharsets.UTF_8))
                .getAsJsonObject();
        }
    }

    /** Machines deliberately modelled differently from the reference; the reason is on each one's table entry. */
    private static final Set<String> DIVERGES_FROM_REFERENCE = Set.of(
        // Two recipe-map modes; Java charges 15% EU in distillery mode, not the reference's 85%.
        "Dangote Distillus",
        // Java's 0.9f, ceil ticks and the custom supplier's floored parallels.
        "Neutron Activator",
        // Real hatch combinations and the independent source-voltage OC cap.
        "Hyper-Intensity Laser Engraver",
        // A fixed 20-tick cycle with no energy hatch, so nothing to overclock.
        "Extreme Heat Exchanger",
        "Utupu-Tanuri",
        "Zyngen",
        "Exothermic Hearth",
        "Endothermic Fridge",
        "Cryogenic Freezer",
        "Mega Oil Cracker",
        "Mega Alloy Blast Smelter",
        "Mega Distillation Tower",
        "Large Thermal Refinery",
        "Industrial Wire Factory",
        "Amazon Warehousing Depot",
        "Industrial Mixing Machine",
        "Multiblock Mixer",
        "Industrial Sledgehammer",
        "Industrial Precision Lathe",
        "Industrial Arc Furnace",
        "Industrial Coke Oven",
        "Coke Oven",
        "Hot Isostatic Pressurization Unit",
        "Spinmatron-2737",
        "Source Chamber",
        "Target Chamber",
        // The steam multiblocks were rewritten; the reference still models the old upgrade ladder.
        "Steam Grinder",
        "Steam Squasher",
        "Steam Separator",
        "Steam Purifier",
        "Steam Presser",
        "Steam Blender",
        "Steam Fuser",
        "Steam Hearth");

    /** {@code "inf"} as infinity, a number as itself, anything else (absent) as null: the website's undefined. */
    @Nullable
    private static Double bound(@Nullable final JsonElement e) {
        if (e == null || e.isJsonNull()) return null;
        if (e.getAsJsonPrimitive()
            .isString()) return "inf".equals(e.getAsString()) ? Double.POSITIVE_INFINITY : null;
        return e.getAsDouble();
    }

    private static boolean numbers(final JsonObject sample, final String... keys) {
        for (final String k : keys) {
            final JsonElement e = sample.get(k);
            if (e == null || !e.isJsonPrimitive() || !((JsonPrimitive) e).isNumber()) return false;
        }
        return true;
    }

    @Test
    void reproducesTheReferencesOwnNumbersForEveryMachineItClaims() throws Exception {
        // reference-coefficients.json is ShadowTheAge's machines.ts evaluated over a grid of voltage tiers and choice
        // indices. Their voltageTier counts LV as 0; ours counts ULV as 0, so a sample's tier maps to ours plus one.
        final JsonObject reference = reference();
        final List<String> problems = new ArrayList<>();
        int checked = 0;

        for (final Map.Entry<String, JsonElement> entry : reference.entrySet()) {
            final String name = entry.getKey();
            final MachineTable.Behaviour behaviour = MachineTable.behaviour(name);
            if (behaviour == null || DIVERGES_FROM_REFERENCE.contains(name)) continue;

            for (final JsonElement s : entry.getValue()
                .getAsJsonObject()
                .getAsJsonArray("samples")) {
                final JsonObject sample = s.getAsJsonObject();
                if (!numbers(sample, "speed", "power", "parallels")) continue;

                final int choice = sample.get("choiceValue")
                    .getAsInt();
                final int voltageTier = sample.get("voltageTier")
                    .getAsInt();
                final MachineTable.Context ctx = ctx(voltageTier + 1, choice, choice);
                final String at = name + " @tier" + voltageTier + " choice" + choice;

                final double speed = MachineTable.resolve(behaviour.speed, ctx, 1);
                final double power = MachineTable.resolve(behaviour.power, ctx, 1);
                final double parallels = MachineTable.resolve(behaviour.parallels, ctx, 1);
                final Object spec = MachineTable.overclockSpec(behaviour, ctx);

                final double wantSpeed = sample.get("speed")
                    .getAsDouble();
                final double wantPower = sample.get("power")
                    .getAsDouble();
                final double wantParallels = sample.get("parallels")
                    .getAsDouble();
                if (!close(speed, wantSpeed)) problems.add(at + ": speed " + speed + " vs " + wantSpeed);
                if (!close(parallels, wantParallels))
                    problems.add(at + ": parallels " + parallels + " vs " + wantParallels);
                // The reference folds the coil heat EU discount into power, while the heat path applies it where the
                // recipe's heat requirement is known, so a heat machine's power is checked by the overclock tests.
                if (spec != MachineTable.HEAT && !close(power, wantPower))
                    problems.add(at + ": power " + power + " vs " + wantPower);
                final JsonElement expected = sample.get("overclocker");
                if (spec != MachineTable.HEAT && expected != null && expected.isJsonObject()) {
                    final JsonObject want = expected.getAsJsonObject();
                    final MachineTable.Rule rule = (MachineTable.Rule) spec;
                    if ("null".equals(
                        want.get("kind")
                            .getAsString())) {
                        // Their NullOverclocker and our zero-step rule say the same thing.
                        if (rule != null && (rule.maxPerfect() != 0 || rule.maxNormal() != 0))
                            problems.add(at + ": expected no overclocks, got " + rule);
                    } else {
                        final Double maxPerfect = bound(want.get("maxPerfect"));
                        final Double maxNormal = bound(want.get("maxNormal"));
                        final JsonElement m = want.get("multiplier");
                        final Double multiplier = m != null && m.isJsonPrimitive() && ((JsonPrimitive) m).isNumber()
                            ? m.getAsDouble()
                            : null;
                        if (rule == null || maxPerfect == null
                            || rule.maxPerfect() != maxPerfect
                            || maxNormal == null
                            || rule.maxNormal() != maxNormal
                            || multiplier == null
                            || rule.multiplier() != multiplier)
                            problems.add(at + ": overclock " + rule + " vs " + want);
                    }
                }
                checked++;
            }
        }

        assertEquals(List.of(), problems.subList(0, Math.min(20, problems.size())));
        assertTrue(checked > 100, "checked " + checked);
    }

    @Test
    void onlyClaimsMachinesWithARecordedProvenance() throws Exception {
        final Set<String> referenceNames = new HashSet<>();
        for (final Map.Entry<String, JsonElement> e : reference().entrySet())
            referenceNames.add(MachineTable.normalizeMachineName(e.getKey()));
        // Entries transcribed straight from the GT5-Unofficial source rather than the reference calculator.
        final Set<String> sourceVerified = new HashSet<>();
        for (final String name : List.of(
            "Mega Blast Furnace",
            "Arc Furnace",
            "Short Circuit Heater",
            // The plain parallels-per-voltage-tier batch the reference calculator never covered.
            "Industrial Chemical Bath",
            "Industrial Bending Machine",
            "Industrial 3D Copying Machine",
            "Mass Solidifier",
            "L.A.T.E.X.",
            // The steam multiblocks, from MTESteamMacerator and its siblings.
            "Steam Grinder",
            "Steam Squasher",
            "Steam Separator",
            "Steam Purifier",
            "Steam Presser",
            "Steam Blender",
            "Steam Fuser",
            "Steam Hearth",
            // GT++'s Electric Auto Workbench: not a recipe-map machine.
            "Auto Workbench",
            // MTEPreciseAssembler's dedicated precise mode.
            "Precise Assembler",
            // kubatech's MTEExtremeEntityCrusher.
            "Extreme Entity Crusher")) sourceVerified.add(MachineTable.normalizeMachineName(name));

        // Every entry traces back to a reference definition or a direct source transcription, under its own name or
        // an alias: nothing is invented.
        final List<String> untraceable = new ArrayList<>();
        for (final String name : MachineTable.names()) {
            final MachineTable.Behaviour behaviour = MachineTable.behaviour(name);
            final List<String> candidates = new ArrayList<>();
            candidates.add(name);
            if (behaviour.aliases != null) candidates.addAll(behaviour.aliases);
            boolean traced = false;
            for (final String candidate : candidates) {
                final String normalized = MachineTable.normalizeMachineName(candidate);
                if (referenceNames.contains(normalized) || sourceVerified.contains(normalized)) traced = true;
            }
            if (!traced) untraceable.add(name);
        }

        assertEquals(List.of(), untraceable);
    }

    @Test
    void runsTheSteamMultiblocksAt62Point5PercentBasicAnd125HighPressure() {
        // Table side of the website's case: the speed is 0.625 per pressure step (duration x 1.6 / tierMachine), 8
        // fixed parallels, no overclocking; the Steam Hearth's 0.9765625 lands its 200-tick base on 204.8 and 102.4.
        final MachineTable.Behaviour grinder = MachineTable.behaviour("Steam Grinder");
        final MachineTable.Context bronze = ctx(1, 0, 0);
        final MachineTable.Context highPressure = ctx(1, 1, 0);
        assertEquals(1.6, 1 / MachineTable.resolve(grinder.speed, bronze, 1), 1e-10);
        assertEquals(0.8, 1 / MachineTable.resolve(grinder.speed, highPressure, 1), 1e-10);
        assertEquals(8, MachineTable.resolve(grinder.parallels, bronze, 1));
        assertEquals(MachineTable.none(), MachineTable.overclockSpec(grinder, ctx(1, 0, 0)));

        final MachineTable.Behaviour hearth = MachineTable.behaviour("Steam Hearth");
        assertEquals(204.8, 200 * (1 / MachineTable.resolve(hearth.speed, bronze, 1)), 1e-10);
        assertEquals(102.4, 200 * (1 / MachineTable.resolve(hearth.speed, highPressure, 1)), 1e-10);
    }

    @Test
    void givesTheNaquadahFuelRefineryFieldRestrictionCoilsNotHeatingCoils() {
        // Table side of the website's case: the real coils replace the scraped heating coil, the recipe's special
        // value is their floor, parallels count the coil's position on the FULL ladder, and each coil tier above the
        // recipe's minimum is one perfect overclock.
        final MachineTable.Behaviour nfr = MachineTable.behaviour("Naquadah Fuel Refinery");
        assertEquals(List.of("fieldRestrictionCoil"), ids(MachineTable.controls("Naquadah Fuel Refinery", null)));
        assertEquals(List.of("heatingCoil"), MachineTable.hiddenControlIds("Naquadah Fuel Refinery"));
        assertEquals(Boolean.TRUE, MachineControls.FIELD_COIL_CONTROL.minimumFromSpecialValue);

        final MachineTable.Context t2 = ctx(9, Map.of("fieldRestrictionCoil", 1), 2);
        final MachineTable.Context t4 = ctx(9, Map.of("fieldRestrictionCoil", 3), 2);
        assertEquals(8, MachineTable.resolve(nfr.parallels, t2, 1));
        assertEquals(16, MachineTable.resolve(nfr.parallels, t4, 1));
        assertEquals(MachineTable.perfect(2), MachineTable.overclockSpec(nfr, t4));
        assertEquals(MachineTable.perfect(0), MachineTable.overclockSpec(nfr, t2));
    }

    @Test
    void leavesMachinesItDoesNotCoverOnTheDatasetsOwnValues() {
        assertNull(MachineTable.behaviour("Some Machine We Have Not Verified"));
    }

    // endregion

    // region pipe casings

    /** The dataset's scraped knob: the fluid pipe ladder with two rungs no machine takes. */
    private static final List<String> SCRAPED_KEYS = List
        .of("bronze", "steel", "titanium", "tungstensteel", "ptfe", "pbi");

    private static Map<String, String> settings(final String... kv) {
        final Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    /** Table side of getMachineStructuralParallels: the parallels at the settings' normalized item pipe casing. */
    private static double itemPipeParallels(final String machine, final Map<String, String> raw) {
        final MachineTable.Behaviour b = MachineTable.behaviour(machine);
        final Map<String, String> s = b.normalizeConfig != null ? b.normalizeConfig.apply(raw) : raw;
        final String key = s.getOrDefault("itemPipeCasing", MachineControls.ITEM_PIPE_CONTROL.defaultKey);
        final int at = Math.max(0, position(MachineControls.ITEM_PIPE_CONTROL, key));
        return Math.floor(MachineTable.resolve(b.parallels, ctx(1, Map.of("itemPipeCasing", at), null), 1));
    }

    @Test
    void givesTheLatheItemPipeCasingsTinToBlackPlutonium() {
        final List<Web.Control> controls = MachineTable.controls("Industrial Precision Lathe", null);
        assertEquals(List.of("itemPipeCasing"), ids(controls));
        final Web.Control control = controls.get(0);
        final List<String> labels = new ArrayList<>();
        for (final Web.TierOption t : control.tiers) labels.add(t.label);
        assertEquals(
            List.of("Tin", "Brass", "Electrum", "Platinum", "Osmium", "Quantium", "Fluxed Electrum", "Black Plutonium"),
            labels);
        assertEquals("gregtech:gt.blockcasings11", control.tiers.get(0).resource.id);
        assertEquals("gregtech:gt.blockcasings11@7", control.tiers.get(7).resource.id);
        assertEquals("Black Plutonium Item Pipe Casing", control.tiers.get(7).resource.displayName);
        assertTrue(
            MachineTable.hiddenControlIds("Industrial Precision Lathe")
                .contains("pipeCasing"));

        // getMaxParallelRecipes: pipeTier * 8, tin = 1.
        assertEquals(8, itemPipeParallels("Industrial Precision Lathe", settings("itemPipeCasing", "tin")));
        assertEquals(
            64,
            itemPipeParallels("Industrial Precision Lathe", settings("itemPipeCasing", "black-plutonium")));
    }

    @Test
    void carriesALatheSavedOnTheOldKnobToTheItemPipeWithTheSameParallels() {
        final MachineTable.Behaviour lathe = MachineTable.behaviour("Industrial Precision Lathe");
        for (int position = 0; position < SCRAPED_KEYS.size(); position++) {
            assertEquals(
                (position + 1) * 8,
                itemPipeParallels("Industrial Precision Lathe", settings("pipeCasing", SCRAPED_KEYS.get(position))));
        }
        assertEquals(
            "quantium",
            lathe.normalizeConfig.apply(settings("pipeCasing", "pbi"))
                .get("itemPipeCasing"));

        // A pick made on the real knob wins over whatever the old one says.
        assertEquals(
            8,
            itemPipeParallels("Industrial Precision Lathe", settings("pipeCasing", "pbi", "itemPipeCasing", "tin")));
    }

    @Test
    void neverOffersPtfeOrPbiPipeCasingsWhichNoStructureAccepts() {
        for (final String machine : List.of("Chemical Plant", "Industrial Autoclave")) {
            Web.Control pipe = null;
            for (final Web.Control c : MachineTable.controls(machine, null)) if (c.id.equals("pipeCasing")) pipe = c;
            assertNotNull(pipe, machine);
            final List<String> keys = new ArrayList<>();
            for (final Web.TierOption t : pipe.tiers) keys.add(t.key);
            assertEquals(List.of("bronze", "steel", "titanium", "tungstensteel"), keys);
            assertEquals("gregtech:gt.blockcasings2@15", pipe.tiers.get(3).resource.id);
        }

        // A plan saved on either keeps the best real casing, tungstensteel.
        final MachineTable.Behaviour chemPlant = MachineTable.behaviour("Chemical Plant");
        final MachineTable.Behaviour autoclave = MachineTable.behaviour("Industrial Autoclave");
        for (final String key : List.of("ptfe", "pbi")) {
            final String kept = chemPlant.normalizeConfig.apply(settings("pipeCasing", key))
                .get("pipeCasing");
            assertEquals("tungstensteel", kept);
            assertEquals(
                "tungstensteel",
                autoclave.normalizeConfig.apply(settings("pipeCasing", key))
                    .get("pipeCasing"));
            final int at = position(MachineControls.FLUID_PIPE_CONTROL, kept);
            assertEquals(8, MachineTable.resolve(chemPlant.parallels, ctx(1, Map.of("pipeCasing", at), null), 1));
            assertEquals(
                8 / 12.0,
                MachineTable.resolve(autoclave.power, ctx(1, Map.of("pipeCasing", at), null), 1),
                1e-10);
        }
    }

    @Test
    void hidesTheScrapedPipeKnobOnMachinesThatHaveNoFluidPipes() {
        for (final String machine : List.of(
            "Dissection Apparatus",
            "Industrial Wire Factory",
            "Amazon Warehousing Depot",
            "Industrial Mixing Machine")) {
            assertEquals(List.of("itemPipeCasing"), ids(MachineTable.controls(machine, null)), machine);
            assertTrue(
                MachineTable.hiddenControlIds(machine)
                    .contains("pipeCasing"),
                machine);
        }
        assertEquals(List.of("steamPressure"), ids(MachineTable.controls("Steam Blender", null)));
        assertTrue(
            MachineTable.hiddenControlIds("Steam Blender")
                .contains("pipeCasing"));
        assertEquals(List.of("pipeCasing", "itemPipeCasing"), ids(MachineTable.controls("Industrial Autoclave", null)));
        assertEquals(List.of(), MachineTable.hiddenControlIds("Industrial Autoclave"));
    }

    // endregion
}

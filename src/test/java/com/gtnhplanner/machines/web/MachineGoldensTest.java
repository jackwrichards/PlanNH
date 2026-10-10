package com.gtnhplanner.machines.web;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import org.junit.jupiter.api.Test;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/**
 * The website's own functions run over real dataset recipes and a spread of nodes (machine-goldens.jsonl.gz, written
 * by its {@code tools/audits/export-mod-machine-data.mjs}): the port must give every number the same. Runtime-variant
 * cases are checked against the generic path the website also records, and the Tree Growth Simulator and Bacterial
 * Vat against nothing here (the mod's own models in machines/ are matched to the website on their own).
 */
class MachineGoldensTest {

    private static final String FILE = "/machine-goldens.jsonl.gz";
    /** Relative tolerance for EU/t and multipliers; ticks, steps and tiers must be exact. */
    private static final double REL = 1e-9;

    private final Gson gson = new Gson();
    private final List<String> failures = new ArrayList<>();
    private final Map<String, Integer> failuresByGroup = new LinkedHashMap<>();
    private int cases, skipped;

    @Test
    void portMatchesTheWebsite() throws Exception {
        final InputStream raw = MachineGoldensTest.class.getResourceAsStream(FILE);
        assumeTrue(raw != null, "no " + FILE);
        final Map<String, Web.Recipe> recipes = new HashMap<>();
        try (BufferedReader in = new BufferedReader(
            new InputStreamReader(new GZIPInputStream(raw), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isEmpty()) continue;
                final JsonObject c = new JsonParser().parse(line)
                    .getAsJsonObject();
                final String recipeId = c.get("recipeId")
                    .getAsString();
                if (c.has("recipe")) recipes.put(recipeId, gson.fromJson(c.get("recipe"), Web.Recipe.class));
                final String group = c.get("group")
                    .getAsString();
                final Web.Recipe recipe = recipes.get(recipeId);
                if (!NodeMath.covers(recipe, gson.fromJson(c.get("node"), Web.Node.class))) {
                    skipped++;
                    continue;
                }
                check(c, recipe, group);
            }
        }
        final StringBuilder report = new StringBuilder(
            cases + " cases, " + failures.size() + " mismatches (" + skipped + " left to the mod's own models)");
        for (final Map.Entry<String, Integer> e : failuresByGroup.entrySet()) report.append("\n  ")
            .append(e.getKey())
            .append(": ")
            .append(e.getValue());
        for (int i = 0; i < Math.min(40, failures.size()); i++) report.append("\n")
            .append(failures.get(i));
        System.out.println(report);
        assertTrue(failures.isEmpty(), report.toString());
    }

    private void check(final JsonObject c, final Web.Recipe recipe, final String group) {
        cases++;
        final Web.Node node = gson.fromJson(c.get("node"), Web.Node.class);
        final JsonObject expected = c.getAsJsonObject("expected");
        if (c.has("generic")) for (final Map.Entry<String, JsonElement> e : c.getAsJsonObject("generic")
            .entrySet()) expected.add(e.getKey(), e.getValue());
        final String where = "#" + c.get("case")
            .getAsInt()
            + " "
            + group
            + " "
            + recipe.name
            + " ["
            + c.get("variant")
                .getAsString()
            + "]";
        final List<String> diffs = new ArrayList<>();
        try {
            final Overclock.Stats s = Overclock.stats(recipe, node);
            same(diffs, expected, "tier", s.tier());
            same(diffs, expected, "minimumTier", s.minimumTier());
            same(diffs, expected, "overclockSteps", s.overclockSteps());
            same(diffs, expected, "perfectOverclockSteps", s.perfectOverclockSteps());
            near(diffs, expected, "perfectSpeedFactor", s.perfectSpeedFactor());
            near(diffs, expected, "perfectEuFactor", s.perfectEuFactor());
            near(diffs, expected, "durationTicks", s.durationTicks());
            near(diffs, expected, "eut", s.eut());

            final Web.Recipe effective = RecipeRules.applyHandler(recipe, node);
            near(diffs, expected, "parallels", MachineEffects.parallelMultiplier(effective, node));
            near(diffs, expected, "structuralParallels", MachineEffects.structuralParallels(effective, node));
            near(diffs, expected, "durationMultiplier", MachineEffects.durationMultiplier(effective, node));
            near(diffs, expected, "eutMultiplier", MachineEffects.eutMultiplier(effective, node));
            final List<Double> multipliers = new ArrayList<>(), chances = new ArrayList<>();
            for (final Web.Resource out : effective.outputs) {
                multipliers.add(MachineEffects.outputMultiplier(effective, node, out, s.tier()));
                chances.add(out.chance != null ? out.chance : 1);
            }
            nearList(diffs, expected, "outputMultipliers", multipliers);
            nearList(diffs, expected, "chances", chances);
            same(diffs, expected, "machineType", effective.machineType);
            same(diffs, expected, "multiblock", Power.isMultiblock(effective));
            final String runTier = Power.runTier(effective, node);
            same(diffs, expected, "runTier", runTier);
            near(diffs, expected, "amps", Power.amps(effective, node));
            final int ordinal = Power.effectiveVoltageOrdinal(effective, node, runTier);
            same(diffs, expected, "voltageOrdinal", ordinal);
            near(diffs, expected, "heatDiscount", Heat.discount(effective, node, runTier, ordinal));

            if (PowerReport.has(recipe)) {
                final PowerReport.Report p = PowerReport.report(recipe, node);
                same(diffs, expected, "powerState", p.state());
                same(diffs, expected, "recipeGateReason", p.recipeGateReason());
                near(diffs, expected, "poolEuT", p.poolEuT());
                near(diffs, expected, "singleDrawEuT", p.singleDrawEuT());
            } else if (expected.has("powerState")) diffs.add("powerState: website has a report, port none");

            final PowerReport.Steam steam = PowerReport.steam(recipe, node);
            if (expected.has("steam") != (steam != null)) diffs.add("steam: website " + expected.get("steam"));
            else if (steam != null) {
                final JsonObject e = expected.getAsJsonObject("steam");
                near(diffs, e, "perTick", steam.drawSteamPerTick());
                near(diffs, e, "singlePerTick", steam.singleDrawSteamPerTick());
                near(diffs, e, "parallels", steam.parallels());
                same(diffs, e, "multiblock", steam.isMultiblock());
                same(diffs, e, "highPressure", steam.highPressure());
            }

            checkControls(diffs, expected, effective, node);
        } catch (final RuntimeException | StackOverflowError e) {
            diffs.add("threw " + e);
        }
        if (!diffs.isEmpty()) {
            failures.add(where + ": " + String.join("; ", diffs));
            failuresByGroup.merge(group, 1, Integer::sum);
        }
    }

    private void checkControls(final List<String> diffs, final JsonObject expected, final Web.Recipe effective,
        final Web.Node node) {
        if (!expected.has("controls")) return;
        final List<RecipeRules.TierControl> controls = new ArrayList<>(
            RecipeRules.configTierControls(effective, node.machineConfigTiers));
        final RecipeRules.TierControl coil = RecipeRules.coilTierControl(effective, node.coilTier);
        if (coil != null) controls.add(coil);
        final JsonArray want = expected.getAsJsonArray("controls");
        if (want.size() != controls.size()) {
            diffs.add("controls: " + controls.size() + " for " + want.size());
            return;
        }
        for (int i = 0; i < want.size(); i++) {
            final JsonObject w = want.get(i)
                .getAsJsonObject();
            final RecipeRules.TierControl got = controls.get(i);
            final String id = w.get("id")
                .getAsString();
            same(diffs, w, "id", got.id());
            same(diffs, w, "label", got.label());
            same(diffs, w, "minimumKey", got.minimum().key);
            same(diffs, w, "currentKey", got.current().key);
            same(diffs, w, "minimumIndex", got.minimumIndex());
            final List<String> keys = new ArrayList<>();
            for (final Web.TierOption t : got.tiers()) keys.add(t.key);
            final List<String> wantKeys = new ArrayList<>();
            for (final JsonElement k : w.getAsJsonArray("tiers")) wantKeys.add(k.getAsString());
            if (!keys.equals(wantKeys)) diffs.add("controls[" + id + "].tiers: " + keys + " for " + wantKeys);
        }
    }

    private static void same(final List<String> diffs, final JsonObject expected, final String field,
        final Object got) {
        final JsonElement want = expected.get(field);
        final Object w = want == null || want.isJsonNull() ? null
            : want.getAsJsonPrimitive()
                .isBoolean() ? want.getAsBoolean()
                    : want.getAsJsonPrimitive()
                        .isNumber() ? (Object) want.getAsDouble() : want.getAsString();
        final Object g = got instanceof final Number n ? (Object) n.doubleValue() : got;
        if (w == null ? g != null : !w.equals(g)) diffs.add(field + ": " + g + " for " + w);
    }

    private static void near(final List<String> diffs, final JsonObject expected, final String field,
        final double got) {
        final JsonElement want = expected.get(field);
        if (want == null || want.isJsonNull()) {
            diffs.add(field + ": " + got + " for nothing");
            return;
        }
        final double w = number(want);
        if (!close(w, got)) diffs.add(field + ": " + got + " for " + w);
    }

    private static void nearList(final List<String> diffs, final JsonObject expected, final String field,
        final List<Double> got) {
        final JsonArray want = expected.getAsJsonArray(field);
        if (want == null) return;
        if (want.size() != got.size()) {
            diffs.add(field + ": " + got.size() + " for " + want.size());
            return;
        }
        for (int i = 0; i < got.size(); i++) if (!close(number(want.get(i)), got.get(i)))
            diffs.add(field + "[" + i + "]: " + got.get(i) + " for " + want.get(i));
    }

    private static double number(final JsonElement e) {
        final JsonPrimitive p = e.getAsJsonPrimitive();
        if (p.isNumber()) return p.getAsDouble();
        return switch (p.getAsString()) {
            case "Infinity" -> Double.POSITIVE_INFINITY;
            case "-Infinity" -> Double.NEGATIVE_INFINITY;
            default -> Double.NaN;
        };
    }

    private static boolean close(final double want, final double got) {
        if (Double.isNaN(want) || Double.isNaN(got)) return Double.isNaN(want) && Double.isNaN(got);
        if (Double.isInfinite(want) || Double.isInfinite(got)) return want == got;
        if (want == got) return true;
        return Math.abs(want - got) <= REL * Math.max(Math.abs(want), Math.abs(got));
    }
}

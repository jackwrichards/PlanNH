package com.gtnhplanner.machines.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * extreme-entity-crusher.test.ts, the cases on this module's own functions (transcribed there from kubatech
 * MTEExtremeEntityCrusher / MobHandlerLoader and KubaTechGTMultiBlockBase.calculateOverclock at GT5U 5.09.54.20).
 */
class ExtremeEntityCrusherTest {

    private static final String ZOMBIE = """
        {
          "mob": "Zombie",
          "maxHealth": 20,
          "baseEut": 1920,
          "spawnInterval": 55,
          "spikesDamage": 9,
          "maxLooting": 4,
          "outputs": [
            { "drops": [{ "amount": 1, "c0": 10000, "lootable": true }] },
            { "drops": [{ "amount": 1, "c0": 83 }] },
            { "drops": [{ "amount": 1, "c0": 3, "voidable": true }, { "amount": 1, "c0": 10, "voidable": true }] },
            { "refLooting": 1, "drops": [{ "amount": 1, "c0": 0, "cL": 500 }] },
            { "xp": true }
          ]
        }
        """;

    private static Web.Resource resource(final String kind, final String id, final double amount,
        @Nullable final Double chance, @Nullable final Boolean consumed) {
        final Web.Resource r = new Web.Resource();
        r.kind = kind;
        r.id = id;
        r.amount = amount;
        r.chance = chance;
        r.consumed = consumed;
        return r;
    }

    private static Web.Recipe zombie() {
        return zombie(eec -> {});
    }

    private static Web.Recipe zombie(final Consumer<JsonObject> overrides) {
        final JsonObject eec = new JsonParser().parse(ZOMBIE)
            .getAsJsonObject();
        overrides.accept(eec);
        final boolean alwaysInfernal = eec.has("alwaysInfernal") && eec.get("alwaysInfernal")
            .getAsBoolean();
        final Web.Recipe r = new Web.Recipe();
        r.id = "eec:zombie";
        r.name = "Extreme Entity Crusher: Zombie";
        r.kind = "gregtech_machine";
        r.machineType = ExtremeEntityCrusher.MACHINE_TYPE;
        r.minimumTier = "EV";
        r.durationTicks = 55;
        r.eut = alwaysInfernal ? 15360 : 1920;
        r.inputs = List.of(resource("item", "factoryflow:eec_mob:zombie", 1, null, false));
        r.outputs = List.of(
            resource("item", "minecraft:rotten_flesh", 1, null, null),
            resource("item", "minecraft:iron_ingot", 1, 0.0083, null),
            resource("item", "minecraft:iron_sword", 0.0013, null, null),
            resource("item", "ForbiddenMagic:NetherShard@5", 0.05, null, null),
            resource("fluid", "xpjuice", 120, null, null));
        r.metadata = new JsonObject();
        r.metadata.add("eec", eec);
        return r;
    }

    private static ExtremeEntityCrusher.Metadata meta(final Web.Recipe recipe) {
        final ExtremeEntityCrusher.Metadata meta = ExtremeEntityCrusher.metadata(recipe);
        assertNotNull(meta);
        return meta;
    }

    private static ExtremeEntityCrusher.Metadata withHealth(final double maxHealth) {
        final ExtremeEntityCrusher.Metadata meta = meta(zombie()).copy();
        meta.maxHealth = maxHealth;
        return meta;
    }

    private static ExtremeEntityCrusher.Settings settings(final String... keyValues) {
        final Map<String, String> map = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) map.put(keyValues[i], keyValues[i + 1]);
        return ExtremeEntityCrusher.settings(map);
    }

    private static double fround(final double value) {
        return (float) value;
    }

    private static double trunc(final double value) {
        return value < 0 ? Math.ceil(value) : Math.floor(value);
    }

    @Test
    void killsInMaxOf55AndHealthOverDamageTimes10() {
        assertEquals(55, ExtremeEntityCrusher.killTicks(meta(zombie()), 0));
        assertEquals(555, ExtremeEntityCrusher.killTicks(withHealth(500), 0));
        assertEquals(250, ExtremeEntityCrusher.killTicks(withHealth(500), 11));
        // Truncated like the game's int cast: 300 / 13.25 x 10 = 226.4.
        assertEquals(226, ExtremeEntityCrusher.killTicks(withHealth(300), 4.25));
    }

    @Test
    void overclocksPerfectlyToA20TickFloorThenMultipliesTheKill() {
        // One EV hatch reads 2 A, 4096 EU/t: under 4x the draw, no step.
        assertEquals(new ExtremeEntityCrusher.Cycle(55, 1920, 1, 0), ExtremeEntityCrusher.overclock(1920, 55, 4096));
        // One step: 55 >> 2 = 13, held at 20. Four times the power for 2.75x the speed.
        assertEquals(new ExtremeEntityCrusher.Cycle(20, 7680, 1, 1), ExtremeEntityCrusher.overclock(1920, 55, 8192));
        // A second step has no duration left to cut, so the kill yields 4x.
        assertEquals(new ExtremeEntityCrusher.Cycle(20, 30720, 4, 2), ExtremeEntityCrusher.overclock(1920, 55, 32768));
        // 100 ticks: log4ceil(100 / 20 = 5) = 2 duration steps before the floor.
        assertEquals(new ExtremeEntityCrusher.Cycle(20, 30720, 1, 2), ExtremeEntityCrusher.overclock(1920, 100, 32768));
        assertEquals(new ExtremeEntityCrusher.Cycle(25, 7680, 1, 1), ExtremeEntityCrusher.overclock(1920, 100, 8192));
    }

    @Test
    void handsTheSolverAPerKillDurationWithTheMultiplierFoldedIn() {
        final ExtremeEntityCrusher.Stats stats = ExtremeEntityCrusher
            .stats(meta(zombie()), settings(ExtremeEntityCrusher.INFERNAL, "off"), 32768);
        assertEquals(new ExtremeEntityCrusher.Stats(5, 30720, 2), stats);
    }

    @Test
    void averagesInfernalKillsInOnceTheHatchesCarry8x1920() {
        final ExtremeEntityCrusher.Settings settings = settings();
        // Below 15360 EU/t no kill can be infernal.
        assertEquals(
            new ExtremeEntityCrusher.Stats(20, 7680, 1),
            ExtremeEntityCrusher.stats(meta(zombie()), settings, 8192));
        // At 15360: an ordinary kill takes one step (20 ticks at 7680); an elite infernal runs 15360 EU/t with no
        // step for (int)(55 x (2 x 1.8f)) ticks, in FLOAT arithmetic: 197.999995 rounds to 198.0f before the cast.
        // Ultra has 5 modifiers, inferno 8.
        final double p = 1.0 / 20;
        final double[][] kinds = { { 1 - p, 20, 7680 }, { p * 0.9, 198, 15360 },
            { p * 0.1 * (6.0 / 7), trunc(fround(55 * fround(5 * fround(1.8)))), 15360 },
            { p * 0.1 * (1.0 / 7), trunc(fround(55 * fround(8 * fround(1.8)))), 15360 } };
        double ticks = 0;
        for (final double[] kind : kinds) ticks = ticks + kind[0] * kind[1];
        double energy = 0;
        for (final double[] kind : kinds) energy = energy + kind[0] * kind[1] * kind[2];
        final ExtremeEntityCrusher.Stats stats = ExtremeEntityCrusher.stats(meta(zombie()), settings, 15360);
        // toBeCloseTo(x, 9) and (x, 6): within half of 1e-9 and 1e-6.
        assertEquals(ticks, stats.durationTicks(), 0.5e-9);
        assertEquals(energy / ticks, stats.eut(), 0.5e-6);
        // The screwdriver switch turns them off.
        assertEquals(
            20,
            ExtremeEntityCrusher.stats(meta(zombie()), settings(ExtremeEntityCrusher.INFERNAL, "off"), 15360)
                .durationTicks());
        // An always-infernal mob ignores the switch: every kill is at least elite.
        final ExtremeEntityCrusher.Metadata blaze = meta(zombie(eec -> eec.addProperty("alwaysInfernal", true)));
        final ExtremeEntityCrusher.Stats always = ExtremeEntityCrusher
            .stats(blaze, settings(ExtremeEntityCrusher.INFERNAL, "off"), 15360);
        assertTrue(always.durationTicks() > 197);
    }

    @Test
    void runsTheRitualAt400TicksAndAQuarterOfThePowerWithNoOverclock() {
        final ExtremeEntityCrusher.Stats stats = ExtremeEntityCrusher.stats(
            meta(zombie()),
            settings(ExtremeEntityCrusher.MODE, "ritual", ExtremeEntityCrusher.INFERNAL, "off"),
            32768);
        assertEquals(new ExtremeEntityCrusher.Stats(400, 480, 0), stats);
    }

    private static List<Double> looting(final Web.Recipe recipe, final int level, final String... extra) {
        final String[] keyValues = new String[extra.length + 2];
        keyValues[0] = ExtremeEntityCrusher.LOOTING;
        keyValues[1] = String.valueOf(level);
        System.arraycopy(extra, 0, keyValues, 2, extra.length);
        final ExtremeEntityCrusher.Settings settings = settings(keyValues);
        final List<Double> out = new ArrayList<>();
        for (final Web.Resource output : recipe.outputs)
            out.add(ExtremeEntityCrusher.outputMultiplier(recipe, output, settings));
        return out;
    }

    @Test
    void addsLootingTheWayGenerateOutputsDoes() {
        // +5000 per level; past 10000 the chance splits into whole extra items.
        assertEquals(1.5, ExtremeEntityCrusher.expectedItems(1, 10000, true, 1));
        assertEquals(3 * 0.8333, ExtremeEntityCrusher.expectedItems(1, 10000, true, 3), 0.5e-9);
        assertEquals(1, ExtremeEntityCrusher.expectedItems(1, 5000, true, 1));
        assertEquals(0.0083, ExtremeEntityCrusher.expectedItems(1, 83, false, 4), 0.5e-9);
        final Web.Recipe recipe = zombie();
        assertEquals(List.of(1.0, 1.0, 1.0, 0.0, 1.0), looting(recipe, 0));
        // Greed shards only drop with a Looting weapon at all.
        assertEquals(List.of(1.5, 1.0, 1.0, 1.0, 1.0), looting(recipe, 1));
        // Looting is capped at 4; the ritual never swings a weapon.
        assertEquals(looting(recipe, 4).get(0), looting(recipe, 9).get(0));
        assertEquals(
            List.of(1.0, 1.0, 1.0, 0.0, 5000.0 / 120),
            looting(recipe, 3, ExtremeEntityCrusher.MODE, "ritual"));
        // The void switch throws the damaged and enchanted gear away.
        assertEquals(0.0, looting(recipe, 0, ExtremeEntityCrusher.VOID, "void").get(2));
    }

    // Not in the website's test: the module's own shapes, as its source states them.

    @Test
    void readsOnlyTheCrushersMetadataAndSettings() {
        assertTrue(ExtremeEntityCrusher.isRecipe(zombie()));
        final Web.Recipe other = zombie();
        other.machineType = "Mob Crusher";
        assertFalse(ExtremeEntityCrusher.isRecipe(other));
        assertNull(ExtremeEntityCrusher.metadata(zombie(eec -> eec.addProperty("maxHealth", 0))));
        assertNull(ExtremeEntityCrusher.metadata(zombie(eec -> eec.addProperty("maxHealth", "20"))));
        assertNull(ExtremeEntityCrusher.metadata(zombie(eec -> eec.remove("outputs"))));
        assertEquals(new ExtremeEntityCrusher.Settings(0, 0, true, false, false), ExtremeEntityCrusher.settings(null));
        assertEquals(
            new ExtremeEntityCrusher.Settings(13.25, 2, false, true, true),
            settings(
                ExtremeEntityCrusher.WEAPON_DAMAGE,
                "13.25",
                ExtremeEntityCrusher.LOOTING,
                "2.7",
                ExtremeEntityCrusher.INFERNAL,
                "off",
                ExtremeEntityCrusher.MODE,
                "ritual",
                ExtremeEntityCrusher.VOID,
                "void"));
        assertEquals(
            new ExtremeEntityCrusher.Settings(0, 0, true, false, false),
            settings(ExtremeEntityCrusher.WEAPON_DAMAGE, "-3", ExtremeEntityCrusher.LOOTING, "-1.5"));
    }

    @Test
    void controlsAreTheWebsitesKnobs() {
        final List<String> ids = new ArrayList<>();
        for (final Web.Control c : ExtremeEntityCrusher.CONTROLS) ids.add(c.id);
        assertEquals(
            List.of(
                ExtremeEntityCrusher.WEAPON_DAMAGE,
                ExtremeEntityCrusher.LOOTING,
                ExtremeEntityCrusher.INFERNAL,
                ExtremeEntityCrusher.MODE,
                ExtremeEntityCrusher.VOID),
            ids);
        final Web.Control weapon = ExtremeEntityCrusher.CONTROLS.get(0);
        assertEquals(0.25, weapon.numeric.step);
        assertEquals(10000.0, weapon.numeric.max);
        final Web.Control looting = ExtremeEntityCrusher.CONTROLS.get(1);
        assertEquals(5, looting.tiers.size());
        assertEquals("4", looting.tiers.get(4).key);
        assertEquals("factoryflow:machine_config/eecLooting_4", looting.tiers.get(4).resource.id);
        final Web.TierOption ritual = ExtremeEntityCrusher.CONTROLS.get(3).tiers.get(1);
        assertEquals("Ritual", ritual.resource.displayName);
        assertEquals(
            List.of(
                "Linked to a Well of Suffering: 400 ticks a kill, a quarter of the power, no overclock, 5000 L of XP."),
            ritual.resource.tooltip);
        assertFalse(ritual.resource.isConsumed());
    }
}

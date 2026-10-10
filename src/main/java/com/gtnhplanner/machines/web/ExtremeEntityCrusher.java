package com.gtnhplanner.machines.web;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * kubatech's Extreme Entity Crusher as the website models it (src/lib/machines/extreme-entity-crusher.ts, after
 * MTEExtremeEntityCrusher and MobHandlerLoader.MobEECRecipe at GT5U 5.09.54.20). One recipe per mob a Powered Spawner
 * can hold; the dataset's {@code metadata.eec} carries what this replays.
 * <p>
 * A kill takes max(55, (int)(health / (9 + weapon damage) * 10)) ticks at 1920 EU/t (eight times that for a mob that
 * is always infernal). The overclock is kubatech's own calculateOverclock: perfect, every tier the hatches afford, the
 * duration shifted right in whole ticks but never under 20, and each step past that floor multiplying the kill's drops
 * and XP by 4 instead. The durations handed to the solver are per kill, with that multiplier folded in. Infernal spawns
 * (on unless the screwdriver turned them off, forced for the always-infernal mobs) need 8 x 1920 EU/t of input; in
 * ritual mode a kill takes a flat 400 ticks at a quarter of the power, with no overclock and 5000 L of XP.
 * <p>
 * Transcribed one for one, JavaScript number semantics kept: do not "fix" a number here without the website.
 */
public final class ExtremeEntityCrusher {

    private ExtremeEntityCrusher() {}

    public static final String MACHINE_TYPE = "Extreme Entity Crusher";
    public static final String WEAPON_DAMAGE = "eecWeaponDamage";
    public static final String LOOTING = "eecLooting";
    public static final String INFERNAL = "eecInfernal";
    public static final String MODE = "eecMode";
    public static final String VOID = "eecVoid";

    // The machine's own constants, used when the dataset does not carry them.
    private static final double SPAWN_INTERVAL = 55;
    private static final double SPIKES_DAMAGE = 9;
    private static final double MAX_LOOTING = 4;
    private static final double MIN_TICKS = 20;
    private static final double XP_PER_KILL = 120;
    private static final double XP_PER_RITUAL = 5000;
    private static final double RITUAL_TICKS = 400;
    private static final double ELITE_RARITY = 20;
    private static final double ULTRA_RARITY = 10;
    private static final double INFERNO_RARITY = 7;
    private static final double MIN_ELITE_MODIFIERS = 2;
    private static final double MIN_ULTRA_MODIFIERS = 5;
    private static final double MIN_INFERNO_MODIFIERS = 8;
    private static final double MOB_MOD_HEALTH_FACTOR = 1.8;

    private static final Gson GSON = new Gson();

    // region Metadata

    /**
     * EecDrop: one roll of a drop. The required numbers start as NaN, so a missing one acts as the website's
     * {@code undefined} does in its comparisons.
     */
    public static class Drop {

        public double amount = Double.NaN;
        /** Chance out of 10,000 with no Looting weapon, after the mob's modifiers. */
        public double c0 = Double.NaN;
        /** The same with a Looting weapon, when a modifier reads it (greed shards). */
        @Nullable
        public Double cL;
        @Nullable
        public Boolean lootable;
        /** Damaged or randomly enchanted: what the void switch throws away. */
        @Nullable
        public Boolean voidable;
    }

    /** EecOutputMeta: what one of the recipe's outputs is made of, by the recipe's output index. */
    public static class OutputMeta {

        @Nullable
        public Boolean xp;
        /** The Looting level the output's static amount was written at. */
        @Nullable
        public Double refLooting;
        @Nullable
        public List<Drop> drops;
    }

    /** The infernal mobs' odds and modifiers, each absent one the machine's default (INFERNAL_DEFAULTS). */
    public static class Infernal {

        @Nullable
        public Double eliteRarity;
        @Nullable
        public Double ultraRarity;
        @Nullable
        public Double infernoRarity;
        @Nullable
        public Double minEliteModifiers;
        @Nullable
        public Double minUltraModifiers;
        @Nullable
        public Double minInfernoModifiers;
        @Nullable
        public Double mobModHealthFactor;
    }

    /** EecMetadata: the mob a recipe kills and what each of its outputs is made of. */
    public static class Metadata {

        public String mob;
        public double maxHealth = Double.NaN;
        public double baseEut = Double.NaN;
        @Nullable
        public Boolean alwaysInfernal;
        @Nullable
        public Boolean infernalityAllowed;
        @Nullable
        public Double spawnInterval;
        @Nullable
        public Double spikesDamage;
        @Nullable
        public Double maxLooting;
        @Nullable
        public Infernal infernal;
        public List<OutputMeta> outputs;

        /** A shallow copy, as the website's {@code {...meta}}. */
        public Metadata copy() {
            final Metadata m = new Metadata();
            m.mob = mob;
            m.maxHealth = maxHealth;
            m.baseEut = baseEut;
            m.alwaysInfernal = alwaysInfernal;
            m.infernalityAllowed = infernalityAllowed;
            m.spawnInterval = spawnInterval;
            m.spikesDamage = spikesDamage;
            m.maxLooting = maxLooting;
            m.infernal = infernal;
            m.outputs = outputs;
            return m;
        }
    }

    /**
     * getEecMetadata: the recipe's {@code metadata.eec} when it is the crusher's, an object with an outputs array and
     * a positive numeric maxHealth; else null. Read afresh on every call.
     */
    @Nullable
    public static Metadata metadata(final Web.Recipe recipe) {
        if (!MACHINE_TYPE.equals(recipe.machineType)) return null;
        final JsonElement eec = recipe.metadata == null ? null : recipe.metadata.get("eec");
        if (!(eec instanceof final JsonObject object) || !(object.get("outputs") instanceof JsonArray)) return null;
        if (!(object.get("maxHealth") instanceof final JsonPrimitive health && health.isNumber()
            && health.getAsDouble() > 0)) return null;
        return GSON.fromJson(object, Metadata.class);
    }

    /** isEecRecipe. */
    public static boolean isRecipe(final Web.Recipe recipe) {
        return metadata(recipe) != null;
    }

    // endregion

    // region Settings

    /** EecSettings: the card's settings as numbers and switches. */
    public record Settings(double weaponDamage, double looting, boolean infernal, boolean ritual,
        boolean voidDamaged) {}

    /** getEecSettings: the card's machine settings read under the website's keys; null reads as no settings. */
    public static Settings settings(@Nullable final Map<String, String> settings) {
        final double weapon = Js.number(settings == null ? null : settings.get(WEAPON_DAMAGE));
        final double looting = Js.number(settings == null ? null : settings.get(LOOTING));
        return new Settings(
            Double.isFinite(weapon) && weapon > 0 ? weapon : 0,
            Double.isFinite(looting) ? Math.max(0, trunc(looting)) : 0,
            !"off".equals(settings == null ? null : settings.get(INFERNAL)),
            "ritual".equals(settings == null ? null : settings.get(MODE)),
            "void".equals(settings == null ? null : settings.get(VOID)));
    }

    private static Web.TierOption option(final String id, final String key, final String label, final String detail) {
        final Web.Resource resource = new Web.Resource();
        resource.kind = "item";
        resource.id = "factoryflow:machine_config/" + id + "_" + key;
        resource.amount = 1;
        resource.displayName = label;
        resource.tooltip = List.of(detail);
        resource.consumed = false;
        final Web.TierOption option = new Web.TierOption();
        option.key = key;
        option.label = label;
        option.resource = resource;
        return option;
    }

    private static Web.Control control(final String id, final String label, final String minimumKey,
        final String defaultKey, @Nullable final Web.Numeric numeric, final List<Web.TierOption> tiers) {
        final Web.Control control = new Web.Control();
        control.id = id;
        control.label = label;
        control.minimumKey = minimumKey;
        control.defaultKey = defaultKey;
        control.numeric = numeric;
        control.tiers = tiers;
        return control;
    }

    private static Web.Numeric numeric(final double min, final double max, final double step) {
        final Web.Numeric numeric = new Web.Numeric();
        numeric.min = min;
        numeric.max = max;
        numeric.step = step;
        return numeric;
    }

    private static List<Web.TierOption> lootingLevels() {
        final List<Web.TierOption> levels = new ArrayList<>();
        for (final int level : new int[] { 0, 1, 2, 3, 4 }) levels.add(
            option(LOOTING, String.valueOf(level), String.valueOf(level), "The weapon's Looting level, capped at 4."));
        return Collections.unmodifiableList(levels);
    }

    /** EEC_CONTROLS: the knobs the crusher's GUI and screwdriver offer, in the machine table's control shape. */
    public static final List<Web.Control> CONTROLS = List.of(
        control(
            WEAPON_DAMAGE,
            "Weapon damage",
            "0",
            "0",
            numeric(0, 10000, 0.25),
            List.of(
                option(
                    WEAPON_DAMAGE,
                    "0",
                    "Weapon damage",
                    "The weapon's attack damage, plus 1.25 per level of Sharpness. The spikes add 9 on their own."))),
        control(LOOTING, "Looting", "0", "0", null, lootingLevels()),
        control(
            INFERNAL,
            "Infernal spawns",
            "on",
            "on",
            null,
            List.of(
                option(
                    INFERNAL,
                    "on",
                    "On",
                    "With 15360 EU/t of input, 1 kill in 20 is infernal: 8x power and a longer kill."),
                option(
                    INFERNAL,
                    "off",
                    "Off",
                    "Shift-screwdriver: no infernal spawns, except mobs that are always infernal."))),
        control(
            MODE,
            "Kill method",
            "spikes",
            "spikes",
            null,
            List.of(
                option(MODE, "spikes", "Spikes", "Diamond spikes, with the weapon and overclocks."),
                option(
                    MODE,
                    "ritual",
                    "Ritual",
                    "Linked to a Well of Suffering: 400 ticks a kill, a quarter of the power, no overclock, "
                        + "5000 L of XP."))),
        control(
            VOID,
            "Damaged gear",
            "keep",
            "keep",
            null,
            List.of(
                option(VOID, "keep", "Keep", "Damaged and enchanted drops come out with the rest."),
                option(VOID, "void", "Void", "Damaged and enchanted drops are thrown away."))));

    // endregion

    // region Overclock and kills

    /** GTUtility.log4 on a long: whole powers of four, 0 for anything under 4. */
    private static int floorLog4(final double value) {
        int steps = 0;
        for (double power = 4; power <= value; power *= 4) steps += 1;
        return steps;
    }

    /** GTUtility.log4ceil on an int: 0 for anything up to 1. */
    private static int ceilLog4(final double value) {
        int steps = 0;
        for (double power = 1; power < value; power *= 4) steps += 1;
        return steps;
    }

    /**
     * Cycle: one run of the machine, its ticks and EU/t, the kills' worth of drops it yields (past-floor overclocks
     * multiply them) and its overclock steps.
     */
    public record Cycle(double ticks, double eut, double kills, int steps) {}

    /** eecOverclock: KubaTechGTMultiBlockBase.calculateOverclock, perfect, with infinite overclocking. */
    public static Cycle overclock(final double eut, final double ticks, final double maxInputEu) {
        final int tiers = eut > 0 && Double.isFinite(maxInputEu) ? floorLog4(Math.floor(maxInputEu / eut)) : 0;
        if (tiers <= 0) return new Cycle(ticks, eut, 1, 0);
        final int durationTiers = Math.min(tiers, ceilLog4(Math.floor(ticks / MIN_TICKS)));
        return new Cycle(
            Math.max(MIN_TICKS, Math.floor(ticks / Math.pow(4, durationTiers))),
            eut * Math.pow(4, tiers),
            Math.pow(4, tiers - durationTiers),
            tiers);
    }

    /** eecKillTicks: MobEECRecipe.getProgressTimeForAttackDamage. */
    public static double killTicks(final Metadata meta, final double weaponDamage) {
        final double damage = or(meta.spikesDamage, SPIKES_DAMAGE) + weaponDamage;
        return Math.max(or(meta.spawnInterval, SPAWN_INTERVAL), trunc((meta.maxHealth / damage) * 10));
    }

    private record Kind(double probability, double mods) {}

    /** killKinds: how likely each kind of kill is: plain, then elite, ultra and inferno infernals. */
    private static List<Kind> killKinds(final Metadata meta, final Settings settings, final double maxInputEu) {
        final Infernal given = meta.infernal != null ? meta.infernal : new Infernal();
        final boolean possible = !Boolean.FALSE.equals(meta.infernalityAllowed) && meta.baseEut * 8 <= maxInputEu;
        final double chance = Boolean.TRUE.equals(meta.alwaysInfernal) ? 1
            : settings.infernal() ? 1 / or(given.eliteRarity, ELITE_RARITY) : 0;
        if (!possible || chance == 0) return List.of(new Kind(1, 0));
        final double ultra = 1 / or(given.ultraRarity, ULTRA_RARITY);
        final double inferno = 1 / or(given.infernoRarity, INFERNO_RARITY);
        final List<Kind> kinds = new ArrayList<>();
        for (final Kind kind : List.of(
            new Kind(1 - chance, 0),
            new Kind(chance * (1 - ultra), or(given.minEliteModifiers, MIN_ELITE_MODIFIERS)),
            new Kind(chance * ultra * (1 - inferno), or(given.minUltraModifiers, MIN_ULTRA_MODIFIERS)),
            new Kind(chance * ultra * inferno, or(given.minInfernoModifiers, MIN_INFERNO_MODIFIERS))))
            if (kind.probability() > 0) kinds.add(kind);
        return kinds;
    }

    /**
     * EecStats: ticks per kill's worth of drops averaged over the kinds of kill, EU/t averaged over time, and the
     * overclock steps of the likeliest kind of kill.
     */
    public record Stats(double durationTicks, double eut, int steps) {}

    /** getEecStats: the per-kill duration and the time-averaged EU/t on hatches giving {@code maxInputEu}. */
    public static Stats stats(final Metadata meta, final Settings settings, final double maxInputEu) {
        final double baseTicks = killTicks(meta, settings.ritual() ? 0 : settings.weaponDamage());
        final double factor = fround(
            meta.infernal != null && meta.infernal.mobModHealthFactor != null ? meta.infernal.mobModHealthFactor
                : MOB_MOD_HEALTH_FACTOR);
        double ticks = 0;
        double energy = 0;
        double kills = 0;
        int steps = 0;
        double likeliest = 0;
        for (final Kind kind : killKinds(meta, settings, maxInputEu)) {
            final double eut = kind.mods() > 0 ? meta.baseEut * 8 : meta.baseEut;
            // `mMaxProgresstime *= mods * factor`: int times float, float arithmetic.
            final double killTicks = kind.mods() > 0 ? trunc(fround(baseTicks * fround(kind.mods() * factor)))
                : baseTicks;
            final Cycle cycle = settings.ritual() ? new Cycle(RITUAL_TICKS, Math.floor(eut / 4), 1, 0)
                : overclock(eut, killTicks, maxInputEu);
            if (kind.probability() > likeliest) {
                likeliest = kind.probability();
                steps = cycle.steps();
            }
            ticks += kind.probability() * cycle.ticks();
            energy += kind.probability() * cycle.ticks() * cycle.eut();
            kills += kind.probability() * cycle.kills();
        }
        return new Stats(kills > 0 ? ticks / kills : baseTicks, ticks > 0 ? energy / ticks : meta.baseEut, steps);
    }

    // endregion

    // region Outputs

    /** eecExpectedItems: expected items from one roll of a drop (MobEECRecipe.generateOutputs). */
    public static double expectedItems(final double amount, final double chance, final boolean lootable,
        final double looting) {
        if (!(chance > 0) || !(amount > 0)) return 0;
        double rolled = chance;
        double items = amount;
        if (lootable && looting > 0) {
            rolled += looting * 5000;
            if (rolled > 10000) {
                final double div = Math.ceil(rolled / 10000);
                items *= div;
                rolled = trunc(rolled / div);
            }
        }
        return items * (rolled >= 10000 ? 1 : rolled / 10000);
    }

    private static double expectedAt(final List<Drop> drops, final double looting, final boolean voidDamaged) {
        double sum = 0;
        for (final Drop drop : drops) {
            if (voidDamaged && Boolean.TRUE.equals(drop.voidable)) continue;
            final double chance = looting > 0 ? (drop.cL != null ? drop.cL : drop.c0) : drop.c0;
            sum += expectedItems(drop.amount, chance, Boolean.TRUE.equals(drop.lootable), looting);
        }
        return sum;
    }

    /**
     * getEecOutputMultiplier: how far this output's rate moves from what the dataset wrote: Looting, the void switch,
     * and the ritual's XP. The per-kill overclock multiplier is already in the duration. The output is found in the
     * recipe's outputs as that object, else by kind and id.
     */
    public static double outputMultiplier(final Web.Recipe recipe, final Web.Resource output, final Settings settings) {
        final Metadata meta = metadata(recipe);
        if (meta == null) return 1;
        final List<Web.Resource> outputs = recipe.outputs != null ? recipe.outputs : List.of();
        int index = -1;
        for (int i = 0; i < outputs.size() && index < 0; i++) if (outputs.get(i) == output) index = i;
        for (int i = 0; i < outputs.size() && index < 0; i++) {
            final Web.Resource entry = outputs.get(i);
            if (Objects.equals(entry.kind, output.kind) && Objects.equals(entry.id, output.id)) index = i;
        }
        final OutputMeta entry = index >= 0 && index < meta.outputs.size() ? meta.outputs.get(index) : null;
        if (entry == null) return 1;
        if (Boolean.TRUE.equals(entry.xp)) return settings.ritual() ? XP_PER_RITUAL / XP_PER_KILL : 1;
        final List<Drop> drops = entry.drops != null ? entry.drops : List.of();
        final double reference = expectedAt(drops, entry.refLooting != null ? entry.refLooting : 0, false);
        if (!(reference > 0)) return 0;
        final double looting = settings.ritual() ? 0 : Math.min(settings.looting(), or(meta.maxLooting, MAX_LOOTING));
        return expectedAt(drops, looting, settings.voidDamaged()) / reference;
    }

    // endregion

    // region JavaScript numbers

    /** {@code value ?? fallback}. */
    private static double or(@Nullable final Double value, final double fallback) {
        return value != null ? value : fallback;
    }

    /** {@code Math.trunc}. */
    private static double trunc(final double value) {
        return value < 0 ? Math.ceil(value) : Math.floor(value);
    }

    /** {@code Math.fround}: to the nearest float. */
    private static double fround(final double value) {
        return (float) value;
    }

    // endregion
}

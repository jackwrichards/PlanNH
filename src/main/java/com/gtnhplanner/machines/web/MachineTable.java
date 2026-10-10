package com.gtnhplanner.machines.web;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.DoubleUnaryOperator;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;
import java.util.function.ToIntFunction;
import java.util.function.UnaryOperator;

import javax.annotation.Nullable;

/**
 * The website's curated machine table (src/lib/machines/machine-table.ts) in Java: what each multiblock does to a
 * recipe (speed, power, parallels, overclock rule, its own settings), keyed by the machine's name and aliases. The
 * entries live in {@link MachineTableEntries}, transcribed one for one; the golden fixture checks them against the
 * website. Pure: no game classes.
 */
public final class MachineTable {

    private MachineTable() {}

    // region Overclock rules

    /**
     * How a machine spends each step of spare voltage (OverclockRule): perfect steps first, up to {@code maxPerfect},
     * dividing duration by {@code multiplier} and raising EU/t by {@code euMultiplier} (default the same); then normal
     * steps up to {@code maxNormal}, halving duration for 4x EU/t. Infinity is {@code Double.POSITIVE_INFINITY}.
     */
    public record Rule(double maxPerfect, double maxNormal, double multiplier, @Nullable Double euMultiplier) {

        public double euFactor() {
            return euMultiplier != null ? euMultiplier : multiplier;
        }
    }

    public static final double INF = Double.POSITIVE_INFINITY;

    /** OVERCLOCK.normal(): 2x speed for 4x EU/t on every step. */
    public static Rule normal() {
        return new Rule(0, INF, 4, null);
    }

    /** OVERCLOCK.perfect(maxPerfect = Infinity, multiplier = 4). */
    public static Rule perfect() {
        return perfect(INF, 4);
    }

    public static Rule perfect(final double maxPerfect) {
        return perfect(maxPerfect, 4);
    }

    public static Rule perfect(final double maxPerfect, final double multiplier) {
        return new Rule(maxPerfect, 0, multiplier, null);
    }

    /** OVERCLOCK.perfectThenNormal(maxPerfect = Infinity). */
    public static Rule perfectThenNormal(final double maxPerfect) {
        return new Rule(maxPerfect, INF, 4, null);
    }

    /** OVERCLOCK.custom(speedFactor): duration divided by the factor, EU/t times 4, on every step. */
    public static Rule custom(final double speedFactor) {
        return new Rule(INF, 0, speedFactor, 4.0);
    }

    /** OVERCLOCK.none(): extra voltage buys nothing. */
    public static Rule none() {
        return new Rule(0, 0, 4, null);
    }

    /** HEAT_OVERCLOCK: coil heat over the recipe's requirement buys perfect steps, then normal (overclock.ts). */
    public static final Object HEAT = "heat";

    // endregion

    // region What an entry's formulas read

    /**
     * MachineContext: a setting's position on its full ladder ({@code tier}), the count behind a count setting
     * ({@code value}), the voltage-tier ordinal of the summed hatches, the recipe's own voltage tier and special value,
     * its recipe map, and the raw recipe and settings.
     */
    public static final class Context {

        public final ToIntFunction<String> tier;
        public final ToDoubleFunction<String> value;
        public final int voltageTier;
        @Nullable
        public final Integer recipeVoltageTier;
        @Nullable
        public final Integer recipeSpecialValue;
        @Nullable
        public final String recipeMap;
        @Nullable
        public final Web.Recipe recipe;
        @Nullable
        public final Map<String, String> settings;

        public Context(final ToIntFunction<String> tier, final ToDoubleFunction<String> value, final int voltageTier,
            @Nullable final Integer recipeVoltageTier, @Nullable final Integer recipeSpecialValue,
            @Nullable final String recipeMap, @Nullable final Web.Recipe recipe,
            @Nullable final Map<String, String> settings) {
            this.tier = tier;
            this.value = value;
            this.voltageTier = voltageTier;
            this.recipeVoltageTier = recipeVoltageTier;
            this.recipeSpecialValue = recipeSpecialValue;
            this.recipeMap = recipeMap;
            this.recipe = recipe;
            this.settings = settings;
        }

        public int tier(final String controlId) {
            return tier.applyAsInt(controlId);
        }

        public double value(final String controlId) {
            return value.applyAsDouble(controlId);
        }
    }

    /** A number or a formula of the context (Coefficient). */
    @FunctionalInterface
    public interface Coefficient {

        double apply(Context c);

        static Coefficient of(final double value) {
            return c -> value;
        }
    }

    /** How a heat machine reads its coils (MachineBehaviour.heat). */
    public record Heat(@Nullable Boolean voltageBonus, @Nullable Double coilHeatMultiplier,
        @Nullable Boolean discount) {}

    // endregion

    // region An entry

    /** MachineBehaviour: every field the website's entries may set; null where an entry leaves it out. */
    public static final class Behaviour {

        /** A machine-specific whole or sub-tick rounding, in place of {@code Overclock.quantiseDurationToTicks}. */
        @Nullable
        public DoubleUnaryOperator quantiseDuration;
        @Nullable
        public Integer cycleTicks;
        @Nullable
        public Coefficient speed;
        @Nullable
        public Coefficient power;
        @Nullable
        public Coefficient parallels;
        /** A {@link Rule}, {@link #HEAT}, or a {@code Function<Context, Object>} giving one of those. */
        public Object overclock;
        /** kind: "single". */
        public boolean single;
        @Nullable
        public Double amperage;
        public boolean fullPowerPool;
        public boolean unlimitedTierSkip;
        @Nullable
        public Heat heat;
        @Nullable
        public List<Web.Control> controls;
        @Nullable
        public Function<Web.Recipe, List<Web.Control>> controlsFor;
        @Nullable
        public UnaryOperator<Map<String, String>> normalizeConfig;
        @Nullable
        public Function<Context, String> recipeGate;
        @Nullable
        public ToIntFunction<Map<String, String>> inputVoltageTierLimit;
        public boolean recipeTierFromBase;
        @Nullable
        public List<String> hidesControls;
        @Nullable
        public List<String> aliases;
        @Nullable
        public String note;

        // Builder-style setters, so entries read like the website's object literals.

        public Behaviour overclock(final Object spec) {
            overclock = spec;
            return this;
        }

        public Behaviour overclock(final Function<Context, Object> spec) {
            overclock = spec;
            return this;
        }

        public Behaviour speed(final double v) {
            speed = Coefficient.of(v);
            return this;
        }

        public Behaviour speed(final Coefficient f) {
            speed = f;
            return this;
        }

        public Behaviour power(final double v) {
            power = Coefficient.of(v);
            return this;
        }

        public Behaviour power(final Coefficient f) {
            power = f;
            return this;
        }

        public Behaviour parallels(final double v) {
            parallels = Coefficient.of(v);
            return this;
        }

        public Behaviour parallels(final Coefficient f) {
            parallels = f;
            return this;
        }

        public Behaviour single() {
            single = true;
            return this;
        }

        public Behaviour amperage(final double amps) {
            amperage = amps;
            return this;
        }

        public Behaviour fullPowerPool() {
            fullPowerPool = true;
            return this;
        }

        public Behaviour unlimitedTierSkip() {
            unlimitedTierSkip = true;
            return this;
        }

        public Behaviour heat(@Nullable final Boolean voltageBonus, @Nullable final Double coilHeatMultiplier,
            @Nullable final Boolean discount) {
            heat = new Heat(voltageBonus, coilHeatMultiplier, discount);
            return this;
        }

        public Behaviour controls(final Web.Control... list) {
            controls = List.of(list);
            return this;
        }

        /** A list another module keeps (the crusher's EEC_CONTROLS), shared as the website shares it. */
        public Behaviour controls(final List<Web.Control> list) {
            controls = list;
            return this;
        }

        public Behaviour controlsFor(final Function<Web.Recipe, List<Web.Control>> f) {
            controlsFor = f;
            return this;
        }

        public Behaviour normalizeConfig(final UnaryOperator<Map<String, String>> f) {
            normalizeConfig = f;
            return this;
        }

        public Behaviour recipeGate(final Function<Context, String> f) {
            recipeGate = f;
            return this;
        }

        public Behaviour inputVoltageTierLimit(final ToIntFunction<Map<String, String>> f) {
            inputVoltageTierLimit = f;
            return this;
        }

        public Behaviour recipeTierFromBase() {
            recipeTierFromBase = true;
            return this;
        }

        public Behaviour quantiseDuration(final DoubleUnaryOperator f) {
            quantiseDuration = f;
            return this;
        }

        public Behaviour cycleTicks(final int ticks) {
            cycleTicks = ticks;
            return this;
        }

        public Behaviour hidesControls(final String... ids) {
            hidesControls = List.of(ids);
            return this;
        }

        public Behaviour aliases(final String... names) {
            aliases = List.of(names);
            return this;
        }

        public Behaviour note(final String text) {
            note = text;
            return this;
        }
    }

    /** A new entry, as an object literal begins. */
    public static Behaviour machine() {
        return new Behaviour();
    }

    // endregion

    // region The table and its lookups

    private static final Map<String, Behaviour> MACHINES = new LinkedHashMap<>();
    private static final Map<String, Behaviour> BY_NAME = new HashMap<>();

    static {
        MachineTableEntries.register(MACHINES);
        for (final Map.Entry<String, Behaviour> e : MACHINES.entrySet()) {
            BY_NAME.put(normalizeMachineName(e.getKey()), e.getValue());
            if (e.getValue().aliases != null)
                for (final String alias : e.getValue().aliases) BY_NAME.put(normalizeMachineName(alias), e.getValue());
        }
    }

    /** getMachineBehaviour: the entry for a machine's name or alias; null when the table does not cover it. */
    @Nullable
    public static Behaviour behaviour(@Nullable final String machineType) {
        return machineType == null ? null : BY_NAME.get(normalizeMachineName(machineType));
    }

    /** machineTableSeedsFromBase: the table states this machine's speed or power itself. */
    public static boolean seedsFromBase(@Nullable final String machineType) {
        final Behaviour b = behaviour(machineType);
        return b != null && (b.speed != null || b.power != null);
    }

    /** getMachineTableControls: the settings this machine adds (per recipe where it says so). */
    public static List<Web.Control> controls(@Nullable final String machineType, @Nullable final Web.Recipe recipe) {
        final Behaviour b = behaviour(machineType);
        if (b != null && b.controlsFor != null && recipe != null) return b.controlsFor.apply(recipe);
        return b != null && b.controls != null ? b.controls : Collections.emptyList();
    }

    /** getMachineHiddenControlIds: dataset settings this machine does not really have. */
    public static List<String> hiddenControlIds(@Nullable final String machineType) {
        final Behaviour b = behaviour(machineType);
        return b != null && b.hidesControls != null ? b.hidesControls : Collections.emptyList();
    }

    /** resolveCoefficient. */
    public static double resolve(@Nullable final Coefficient coefficient, final Context ctx, final double fallback) {
        return coefficient == null ? fallback : coefficient.apply(ctx);
    }

    /** resolveOverclockSpec: a {@link Rule} or {@link #HEAT}; null without an entry. */
    @Nullable
    @SuppressWarnings("unchecked")
    public static Object overclockSpec(@Nullable final Behaviour behaviour, final Context ctx) {
        if (behaviour == null) return null;
        return behaviour.overclock instanceof final Function<?, ?> f ? ((Function<Context, Object>) f).apply(ctx)
            : behaviour.overclock;
    }

    /** Every machine name the table answers to. */
    public static List<String> names() {
        return new ArrayList<>(MACHINES.keySet());
    }

    /** normalizeMachineName: lower case, every run of anything but a-z, 0-9 and ^ one space, trimmed. */
    public static String normalizeMachineName(final String name) {
        return name.trim()
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9^]+", " ")
            .replaceAll("\\s+", " ")
            .trim();
    }

    // endregion
}

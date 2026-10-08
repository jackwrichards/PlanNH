package com.gtnhplanner.power.sources;

import static com.gtnhplanner.power.sources.Helpers.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import com.gtnhplanner.power.PowerData;
import com.gtnhplanner.power.PowerGroup;
import com.gtnhplanner.power.PowerModel;
import com.gtnhplanner.power.PowerModel.Flow;
import com.gtnhplanner.power.PowerSetting;
import com.gtnhplanner.power.PowerSetting.Option;
import com.gtnhplanner.power.PowerSource;

/**
 * Endgame power (the website's sources/endgame.ts): the Large Naquadah Reactor, the fusion reactors as plasma
 * factories, the Eye of Harmony (simplified expected-value model), the Antimatter loop (closed-form optimum search
 * instead of the workbook's 3,235-row sweep) and the Dyson Swarm.
 */
public final class Endgame {

    public static List<PowerSource> sources() {
        return List.of(lnr(), buildFusion(false), buildFusion(true), eoh(), antimatter(), dysonSwarm());
    }

    private static final String NONE = "None";

    // ---- Large Naquadah Reactor

    /**
     * The LNR returns its fuel depleted, litre for litre (FuelRecipeLoader pairs every fuel with a depleted output;
     * the machine copies the same amount to both sides).
     */
    private static final Map<String, String> LNR_DEPLETED = Map.of(
        "Thorium Fuel (Excited)",
        "Thorium Based Liquid Fuel (Depleted)",
        "Uranium Fuel (Excited)",
        "Uranium Based Liquid Fuel (Depleted)",
        "Plutonium Fuel (Excited)",
        "Plutonium Based Liquid Fuel (Depleted)",
        "Naq Fuel Mk-I",
        "Naquadah Based Liquid Fuel MkI (Depleted)",
        "Naq Fuel Mk-II",
        "Naquadah Based Liquid Fuel MkII (Depleted)",
        "Naq Fuel Mk-III",
        "Naquadah Based Liquid Fuel MkIII (Depleted)",
        "Naq Fuel Mk-IV",
        "Naquadah Based Liquid Fuel MkIV (Depleted)",
        "Naq Fuel Mk-V",
        "Naquadah Based Liquid Fuel MkV (Depleted)",
        "Naq Fuel Mk-VI",
        "Naquadah Based Liquid Fuel MkVI (Depleted)");

    private static PowerSource lnr() {
        final PowerData data = PowerData.get();
        final List<Option> fuels = new ArrayList<>();
        for (final PowerData.LnrFuel entry : data.lnrFuels) fuels.add(new Option(entry.name, entry.name));
        final List<Option> coolants = new ArrayList<>();
        coolants.add(new Option(NONE, "None"));
        for (final PowerData.LnrCoolant entry : data.lnrCoolants) {
            if (!entry.name.equals(NONE))
                coolants.add(new Option(entry.name, entry.name + " (x" + js(entry.efficiency) + ")"));
        }
        final List<Option> boosters = new ArrayList<>();
        boosters.add(new Option(NONE, "None"));
        for (final PowerData.LnrBooster entry : data.lnrBoosters) {
            if (!entry.name.equals(NONE))
                boosters.add(new Option(entry.name, entry.name + " (x" + js(entry.multiplier) + ")"));
        }
        return new PowerSource(
            "large-naquadah-reactor",
            "Large Naquadah Reactor",
            PowerGroup.ENDGAME,
            // goodgenerator assembles the controller on the assembly line at RECIPE_UV.
            "UV",
            "Naquadah fuel times coolant and booster.",
            List.of(
                new PowerSetting.Select("fuel", "Fuel", fuels, "Naq Fuel Mk-I"),
                new PowerSetting.Select("coolant", "Coolant", coolants, NONE),
                new PowerSetting.Select("booster", "Booster", boosters, NONE)),
            read -> {
                final String fuelName = read.select("fuel");
                final PowerData.LnrFuel found = find(data.lnrFuels, entry -> entry.name.equals(fuelName));
                final PowerData.LnrFuel fuel = found != null ? found : data.lnrFuels.get(0);
                final String coolantName = read.select("coolant");
                final PowerData.LnrCoolant coolant = find(data.lnrCoolants, entry -> entry.name.equals(coolantName));
                final String boosterName = read.select("booster");
                final PowerData.LnrBooster booster = find(data.lnrBoosters, entry -> entry.name.equals(boosterName));
                final double coolantEfficiency = coolant != null ? coolant.efficiency : 1;
                final double boostMultiplier = booster != null ? booster.multiplier : 1;
                final double euPerTick = fuel.euPerTick * coolantEfficiency * boostMultiplier;
                // FuelRecipeLoader registers every LNR fuel as getFluidOrGas(1) (= 1 L); MTELargeNaquadahReactor
                // consumes pall litres per recipe (pall = booster multiplier, or 1), lasting secondsPerCell seconds.
                // So L/s = boost / seconds: the workbook's "cell" is that 1 L recipe, not 1000 L.
                final double fuelPerSecond = boostMultiplier / fuel.secondsPerCell;
                // MTELargeNaquadahReactor.onRunningTick draws liquid air, coolant and booster whenever the recipe's
                // progress is a multiple of 20 ticks, so a recipe of d ticks draws ceil(d/20) times: Mk-II (70
                // ticks) draws 4 times in 3.5 s.
                final double recipeTicks = Math.round(fuel.secondsPerCell * 20);
                final double drawsPerSecond = Math.ceil(recipeTicks / 20) / (recipeTicks / 20);

                final List<Flow> inputs = new ArrayList<>();
                inputs.add(liters(fuel.name, fuelPerSecond));
                inputs.add(liters("Liquid Air", 2400 * drawsPerSecond));
                if (coolant != null && coolant.litersPerSecond > 0) {
                    inputs.add(liters(coolant.name, coolant.litersPerSecond * drawsPerSecond));
                }
                if (booster != null && booster.litersPerSecond > 0) {
                    inputs.add(liters(booster.name, booster.litersPerSecond * drawsPerSecond));
                }
                final String depleted = LNR_DEPLETED.get(fuel.name);
                return new PowerModel(
                    euPerTick,
                    inputs,
                    truthy(depleted) ? List.of(liters(depleted, fuelPerSecond)) : List.of(),
                    List.of(stat("Fuel", formatAmount(fuelPerSecond) + " L/s")));
            });
    }

    // ---- Fusion

    private static PowerSource buildFusion(final boolean compact) {
        final List<PowerData.FusionRecipe> recipes = PowerData.get().fusionRecipes;
        final List<String> marks = compact ? List.of("MK-I", "MK-II", "MK-III", "MK-IV", "MK-V")
            : List.of("Mk-I", "Mk-II", "Mk-III", "Mk-IV", "Mk-V");
        final List<Option> plasmas = new ArrayList<>();
        for (final PowerData.FusionRecipe entry : recipes) {
            if (entry.euPerLiter > 0) plasmas.add(new Option(entry.name, entry.name));
        }
        final List<Option> markOptions = new ArrayList<>();
        for (int index = 0; index < marks.size(); index++) {
            markOptions.add(new Option(String.valueOf(index + 1), marks.get(index)));
        }
        return new PowerSource(
            compact ? "compact-fusion-reactor" : "fusion-reactor",
            compact ? "Compact Fusion Reactor" : "Fusion Reactor",
            PowerGroup.ENDGAME,
            "LuV",
            compact ? "The fusion recipes at 64x the scale." : "Makes plasma; charges its own drain.",
            List.of(
                new PowerSetting.Select("recipe", "Plasma", plasmas, "Helium Plasma"),
                new PowerSetting.Select("mark", "Mark", markOptions, "1")),
            read -> {
                final String recipeName = read.select("recipe");
                final PowerData.FusionRecipe found = find(recipes, entry -> entry.name.equals(recipeName));
                final PowerData.FusionRecipe recipe = found != null ? found : recipes.get(0);
                final double markIndex = toNumber(read.select("mark")) - 1;
                final double output = at(compact ? recipe.compactOutputLpsByMark : recipe.outputLpsByMark, markIndex);
                final double drain = at(compact ? recipe.compactDrainEutByMark : recipe.drainEutByMark, markIndex);
                final boolean belowMark = markIndex + 1 < recipe.minMark || output <= 0;

                final List<Flow> inputs = new ArrayList<>();
                if (!belowMark) {
                    if (truthy(recipe.input1) && truthy(recipe.ratio1)) {
                        inputs.add(liters(recipe.input1, recipe.ratio1 * output));
                    }
                    if (truthy(recipe.input2) && truthy(recipe.ratio2)) {
                        inputs.add(liters(recipe.input2, recipe.ratio2 * output));
                    }
                }
                return new PowerModel(
                    belowMark ? 0 : -drain,
                    inputs,
                    belowMark ? List.of() : List.of(liters(recipe.name, output)),
                    List.of(
                        stat("Plasma value", formatAmount(recipe.euPerLiter) + " EU/L"),
                        stat("Startup", formatAmount(recipe.startupEu) + " EU")),
                    belowMark ? List.of(recipe.name + " needs at least mark " + js(recipe.minMark) + ".") : List.of());
            });
    }

    /** {@code ladder[index] ?? 0}: an index off the ladder, or a gap in it, reads as 0. */
    private static double at(@Nullable final Double[] ladder, final double index) {
        if (ladder == null || index != Math.rint(index) || index < 0 || index >= ladder.length) return 0;
        final Double value = ladder[(int) index];
        return value != null ? value : 0;
    }

    // ---- Eye of Harmony

    /**
     * Eye of Harmony, simplified: expected EU over a cycle at base upgrade tiers, success chance shown as a stat.
     * Almost always a net EU cost (the EOH is a materials machine); the card puts that bill in the power summary.
     */
    private static PowerSource eoh() {
        final List<PowerData.EohStar> stars = PowerData.get().eohStars;
        final List<Option> options = new ArrayList<>();
        for (final PowerData.EohStar entry : stars) options.add(new Option(entry.name, entry.name));
        return new PowerSource(
            "eye-of-harmony",
            "Eye of Harmony",
            PowerGroup.ENDGAME,
            "UMV",
            "A materials machine with a power bill.",
            List.of(new PowerSetting.Select("star", "Target block", options, stars.isEmpty() ? "" : stars.get(0).name)),
            read -> {
                final String starName = read.select("star");
                final PowerData.EohStar found = find(stars, entry -> entry.name.equals(starName));
                final PowerData.EohStar star = found != null ? found : stars.get(0);
                // Base upgrade tiers: EU-output efficiency 0.6, no overclocks.
                final double netPerCycle = star.euOutput * 0.6 - star.euInput;
                // EyeOfHarmonyRecipeStorage.timeCalculator returns seconds (18,000 x 1.4^tier); the recipe runs that
                // many seconds x 20 ticks.
                final double euPerTick = netPerCycle / (star.durationSeconds * 20);
                // Each craft needs BILLION x (rocket tier + 1) L of hydrogen and of helium, and MTEEyeOfHarmony
                // consumes all it holds when the craft starts. The Deep Dark is the sheet's T10 but runs at rocket
                // tier 9.
                final double gasPerCraft = 1e9 * (Math.min(9, star.tier) + 1);
                final double gasPerSecond = gasPerCraft / star.durationSeconds;
                return new PowerModel(
                    euPerTick,
                    List.of(liters("Hydrogen", gasPerSecond), liters("Helium", gasPerSecond)),
                    List.of(),
                    List.of(
                        stat("Cycle", formatAmount(star.durationSeconds / 3600) + "h"),
                        stat("Success", js(Math.round(star.baseSuccess * 100)) + "%"),
                        stat("EU in", formatAmount(star.euInput)),
                        stat("EU out", formatAmount(star.euOutput * 0.6)),
                        stat("Star matter", formatAmount(star.starMatter))),
                    List.of("Simplified model: base upgrade tiers, success assumed."));
            });
    }

    // ---- Antimatter

    /*
     * Antimatter: gain scales ~AM^0.55, costs ~AM^1.45, so there is one best antimatter quantity - found here by
     * golden-section search, which is what the workbook's 3,235-row sweep approximates. Catalyst constants are the
     * workbook's defaults (Tengam / Spacetime / Shirabon / Depleted Mk-V).
     */
    private record AmConstants(double magnetic, double gravity, double containment, double activation) {}

    private static final AmConstants AM_K = new AmConstants(0.1, 0.05, 0.05, 0.05);
    /**
     * The most EU one burn can bank. AntimatterGenerator clamps a wireless burn to its exotic dynamo hatches'
     * storage, V x 24 x amps each (MTEHatchDynamoTunnel.maxEUStore); this is 64 UIV hatches at 1,048,576 A. The hatch
     * tier is the player's build, not a game constant. A double, as in JavaScript (2^63 - 1 rounds to 2^63).
     */
    private static final double AM_BURN_EU = Math.min(Math.pow(2, 63) - 1, 3.3554432e7 * 64 * 1048576 * 24);
    /** AntimatterGenerator's exponent for Molten Superconductor Base UMV, the only wireless catalyst. */
    private static final double AM_PER_BURN_EXPONENT = 1.03;
    private static final String AM_CATALYST = "Molten Superconductor Base UMV";

    /** AntimatterForge's expected antimatter gain a 20-tick cycle, one cycle a second. */
    private static double antimatterGainPerSecond(final double amPerSsass) {
        return Math.pow(amPerSsass, 0.5 + AM_K.containment()) * (0.2 + AM_K.activation());
    }

    private static double antimatterNetEuT(final double amPerSsass) {
        final double gainPerSecond = antimatterGainPerSecond(amPerSsass);
        final double amountPerBurn = Math.pow(AM_BURN_EU / 1e12, 1 / AM_PER_BURN_EXPONENT);
        final double secondsPerBurn = amountPerBurn / gainPerSecond;
        final double passiveCost = -(1e7 + Math.pow(amPerSsass * 1000, 1.5 - AM_K.magnetic()));
        final double activeCost = -Math.pow(amPerSsass * 10_000, 1.5 - AM_K.gravity()) / 20;
        return AM_BURN_EU / (secondsPerBurn * 20) + passiveCost + activeCost;
    }

    private static double antimatterOptimum() {
        double low = 100;
        double high = 50_000_000;
        for (int i = 0; i < 80; i++) {
            final double m1 = low + (high - low) * 0.382;
            final double m2 = low + (high - low) * 0.618;
            if (antimatterNetEuT(m1) < antimatterNetEuT(m2)) {
                low = m1;
            } else {
                high = m2;
            }
        }
        return Math.round((low + high) / 2);
    }

    /** The optimum, searched once on first use (the website's cachedOptimum). */
    private static final class CachedOptimum {

        static final double VALUE = antimatterOptimum();
    }

    private static PowerSource antimatter() {
        return new PowerSource(
            "antimatter",
            "Antimatter Forge",
            PowerGroup.ENDGAME,
            // The controller's assembly line recipe runs at RECIPE_UMV.
            "UMV",
            "Grows and burns antimatter.",
            List.of(
                new PowerSetting.Number("amount", "Antimatter held", 0, 50_000_000, 1000, 0, "L (0 = optimal)", null)),
            read -> {
                final double requested = read.number("amount");
                final double optimum = CachedOptimum.VALUE;
                final double amount = requested > 0 ? requested : optimum;
                final double euPerTick = antimatterNetEuT(amount);
                // At steady state every litre grown is burned. AntimatterForge depletes one litre of Protomatter per
                // litre of antimatter it adds, and AntimatterGenerator runs at full efficiency with catalyst equal to
                // the antimatter it annihilates.
                final double gainPerSecond = antimatterGainPerSecond(amount);
                return new PowerModel(
                    euPerTick,
                    List.of(
                        liters("Molten Tengam", Math.pow(amount, 0.5)),
                        liters("Molten SpaceTime", Math.pow(amount, 0.5)),
                        liters("Molten Shirabon", Math.pow(amount, 2.0 / 7)),
                        liters("Naquadah Based Liquid Fuel MkV (Depleted)", Math.pow(amount, 1.0 / 3)),
                        liters("Protomatter", gainPerSecond),
                        liters(AM_CATALYST, gainPerSecond)),
                    List.of(),
                    List.of(
                        stat("Antimatter held", formatAmount(amount)),
                        stat("Best quantity", formatAmount(optimum))),
                    requested > 0 && Math.abs(requested - optimum) / optimum > 0.5
                        ? List.of("Far from the best quantity; net power falls off steeply.")
                        : List.of());
            });
    }

    // ---- Dyson Swarm

    /**
     * Dyson Swarm Ground Unit (gtnhintergalactic TileEntityDysonSwarm): each deployed module makes euPerModule (pack
     * config: 10,000,000 EU/t) times the dimension's power factor (Overworld 1.0), up to 10,000 modules. The swarm
     * drinks the configured coolant, 3,600,000 L of Gelid Cryotheum per hour, and burns off modules each 72,000-tick
     * cycle at a rate set by module count and supplied computation - that upkeep is not modeled.
     */
    private static PowerSource dysonSwarm() {
        return new PowerSource(
            "dyson-swarm",
            "Dyson Swarm",
            PowerGroup.ENDGAME,
            "UIV",
            "Orbital modules beaming power down.",
            List.of(
                new PowerSetting.Number("modules", "Deployed modules", 1, 10_000, 1, 100),
                new PowerSetting.Number("factor", "Dimension power factor", 0.01, 3.37, 0.01, 1)),
            read -> {
                final double modules = read.number("modules");
                final double factor = read.number("factor");
                return new PowerModel(
                    modules * 10_000_000 * factor,
                    List.of(liters("Cryotheum", 1000)),
                    List.of(),
                    List.of(
                        stat("Per module", formatAmount(10_000_000 * factor) + " EU/t"),
                        stat("Overworld factor", "1")),
                    List.of(
                        "Modules burn off over time. The rate depends on module count and computation, and is not modeled."));
            });
    }

    // ---- JavaScript semantics

    /** The first entry that passes, or null (Array.prototype.find). */
    @Nullable
    private static <T> T find(final List<T> list, final Predicate<T> test) {
        for (final T entry : list) {
            if (test.test(entry)) return entry;
        }
        return null;
    }

    /** JavaScript truthiness of an optional string: present and not empty. */
    private static boolean truthy(@Nullable final String value) {
        return value != null && !value.isEmpty();
    }

    /** JavaScript truthiness of an optional number: present, not zero, not NaN. */
    private static boolean truthy(@Nullable final Double value) {
        return value != null && value != 0 && !Double.isNaN(value);
    }

    /** Number(string) for the plain decimal keys stored here; NaN when it does not parse. */
    private static double toNumber(final String text) {
        final String trimmed = text.trim();
        if (trimmed.isEmpty()) return 0;
        try {
            return Double.parseDouble(trimmed);
        } catch (final NumberFormatException e) {
            return Double.NaN;
        }
    }

    /** JavaScript's String(number) for the plain values interpolated here: whole numbers print without ".0". */
    private static String js(final double value) {
        if (value == Math.rint(value) && Math.abs(value) < 1e21) return new BigDecimal(value).toPlainString();
        return Double.toString(value);
    }

    private Endgame() {}
}

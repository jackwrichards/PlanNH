package com.gtnhplanner.power.sources;

import static com.gtnhplanner.power.sources.Helpers.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import com.gtnhplanner.power.PowerData;
import com.gtnhplanner.power.PowerData.Fuel;
import com.gtnhplanner.power.PowerData.HeatExchanger;
import com.gtnhplanner.power.PowerData.HeatExchangerFluidRule;
import com.gtnhplanner.power.PowerGroup;
import com.gtnhplanner.power.PowerModel;
import com.gtnhplanner.power.PowerModel.Flow;
import com.gtnhplanner.power.PowerSetting;
import com.gtnhplanner.power.PowerSetting.Condition;
import com.gtnhplanner.power.PowerSetting.Option;
import com.gtnhplanner.power.PowerSource;

/**
 * Port of the website's sources/steam-makers.ts. The steam producers: the four Large Boilers and the heat exchanger
 * family. Boiler rule from the workbook: burning a liquid AND a solid at once gives 100% steam and halves each fuel's
 * burn rate; either alone gives 80%. Exchangers convert hot fluids to steam by threshold: below it the Under ratio
 * and lower grade, at/above it the Over ratio and higher grade.
 */
public final class SteamMakers {

    private record BoilerSpec(String id, String name, String unlock, double steamPerTick, double singleFuelSteamPerTick,
        String steamGrade, List<Fuel> liquidTable, List<Fuel> solidTable) {}

    private static List<BoilerSpec> boilerSpecs() {
        final Map<String, List<Fuel>> fuels = PowerData.get().boilerFuels;
        return List.of(
            new BoilerSpec(
                "large-bronze-boiler",
                "Large Bronze Boiler",
                "MV",
                1200,
                960,
                "Steam",
                fuels.get("bronzeLiquid"),
                fuels.get("bronzeSolid")),
            new BoilerSpec(
                "large-steel-boiler",
                "Large Steel Boiler",
                "HV",
                3000,
                2400,
                "Steam",
                fuels.get("steelLiquid"),
                fuels.get("steelSolid")),
            new BoilerSpec(
                "large-titanium-boiler",
                "Large Titanium Boiler",
                "EV",
                4000,
                3200,
                "SH Steam",
                fuels.get("titaniumLiquid"),
                fuels.get("titaniumSolid")),
            new BoilerSpec(
                "large-tungstensteel-boiler",
                "Large Tungstensteel Boiler",
                "IV",
                16000,
                12800,
                "SH Steam",
                fuels.get("tungstensteelLiquid"),
                fuels.get("tungstensteelSolid")));
    }

    private static final String NO_FUEL = "None";

    private static final List<Option> WATER_KINDS = List
        .of(new Option("Water", "Water"), new Option("Distilled Water", "Distilled Water"));

    private static List<Option> fuelChoices(final List<Fuel> table) {
        final List<Option> options = new ArrayList<>();
        options.add(new Option(NO_FUEL, "None"));
        for (final Fuel entry : table) options.add(new Option(entry.name, entry.name));
        return options;
    }

    /** JavaScript truthiness of an optional number: present, not 0 and not NaN. */
    private static boolean truthy(@Nullable final Double value) {
        return value != null && value != 0 && !Double.isNaN(value);
    }

    private static PowerSource buildBoiler(final BoilerSpec spec) {
        final List<PowerSetting> settings = List.of(
            new PowerSetting.Select(
                "liquidFuel",
                "Liquid fuel",
                fuelChoices(spec.liquidTable()),
                spec.liquidTable()
                    .isEmpty() ? NO_FUEL
                        : spec.liquidTable()
                            .get(0).name),
            new PowerSetting.Select("solidFuel", "Solid fuel", fuelChoices(spec.solidTable()), NO_FUEL),
            // MTELargeBoilerBase.consumeWater tries plain water first and falls
            // back to distilled; either works, same amount, no difference.
            new PowerSetting.Select("waterKind", "Water supply", WATER_KINDS, "Water"));
        return new PowerSource(
            spec.id(),
            spec.name(),
            PowerGroup.STEAM,
            spec.unlock(),
            formatAmount(spec.steamPerTick()) + " L/t of "
                + ("SH Steam".equals(spec.steamGrade()) ? "SH steam" : "steam")
                + " on dual fuel.",
            settings,
            read -> {
                final String liquidName = read.select("liquidFuel");
                final String solidName = read.select("solidFuel");
                final Fuel liquid = NO_FUEL.equals(liquidName) ? null
                    : PowerData.findFuel(spec.liquidTable(), liquidName);
                final Fuel solid = NO_FUEL.equals(solidName) ? null : PowerData.findFuel(spec.solidTable(), solidName);
                final boolean dual = liquid != null && solid != null;
                final double steamPerTick = liquid == null && solid == null ? 0
                    : dual ? spec.steamPerTick() : spec.singleFuelSteamPerTick();

                final List<Flow> inputs = new ArrayList<>();
                if (liquid != null && truthy(liquid.burnTime)) {
                    // 1000 L lasts burnTime seconds; sharing the firebox halves the rate.
                    inputs.add(liters(liquid.name, 1000 / (liquid.burnTime * (dual ? 2 : 1))));
                }
                if (solid != null && truthy(solid.burnTime)) {
                    inputs.add(items(solid.name, 1 / (solid.burnTime * (dual ? 2 : 1))));
                }
                if (steamPerTick > 0) {
                    inputs.add(liters(read.select("waterKind"), (steamPerTick / 160) * 20));
                }

                return new PowerModel(
                    0,
                    inputs,
                    steamPerTick > 0 ? List.of(liters(spec.steamGrade(), steamPerTick * 20)) : List.of(),
                    List.of(
                        stat("Steam", formatAmount(steamPerTick) + " L/t"),
                        stat("Firebox", dual ? "Dual fuel: 100%" : "Single fuel: 80%")),
                    liquid == null && solid == null ? List.of("Pick a fuel to make steam.") : List.of());
            });
    }

    /**
     * The singleblock boilers (MTEBoiler subclasses). Steam flows at the full per-second rate once hot; at steady
     * state fuel is burned only to cancel cooldown, and a solid fuel item is worth its furnace burn time / 10 in
     * boiler energy. MTEBoiler.calculateCooldown drops one degree every cooldownInterval + 1 ticks (the timer must
     * pass the interval), and each degree costs energyConsumption to win back. Water is 1 L per 160 L of steam
     * (GTValues.STEAM_PER_WATER).
     *
     * @param energyPerSecond energyConsumption x 20 / (cooldownInterval + 1), from the machine's class.
     */
    private record SmallBoilerSpec(String id, String name, String unlock, double steamPerSecond, double energyPerSecond,
        boolean automatable) {}

    private static final List<SmallBoilerSpec> SMALL_BOILER_SPECS = List.of(
        // MTEBoilerBronze: 120 L/s, consumption 1, cooldown interval 45.
        new SmallBoilerSpec("small-coal-boiler", "Small Coal Boiler", "ULV", 120, 20.0 / 46, false),
        // MTEBoilerSteel, in-game name "Large Coal Boiler": 300 L/s, consumption 2, interval 40.
        new SmallBoilerSpec("large-coal-boiler", "Large Coal Boiler", "LV", 300, 40.0 / 41, false));

    private static final Pattern ASH_FUELS = Pattern
        .compile("coal|lignite|charcoal|coke|diamond", Pattern.CASE_INSENSITIVE);

    /**
     * MTEBoilerBronze.getCombustionPotential only takes fuels that leave ash: coal, lignite, charcoal, coke and
     * diamond, or anything burning 2000 ticks or more. Sulfur dust (1600) is refused.
     */
    private static boolean smallBoilerTakes(final Fuel entry) {
        return (entry.euPerItem != null ? entry.euPerItem : 0) >= 2000 || ASH_FUELS.matcher(entry.name)
            .find();
    }

    private static List<Option> nameOptions(final List<Fuel> table) {
        final List<Option> options = new ArrayList<>(table.size());
        for (final Fuel entry : table) options.add(new Option(entry.name, entry.name));
        return options;
    }

    private static PowerSource buildSmallBoiler(final SmallBoilerSpec spec) {
        final List<Fuel> solidTable = new ArrayList<>();
        for (final Fuel entry : PowerData.get().boilerFuels.get("bronzeSolid")) {
            if (smallBoilerTakes(entry)) solidTable.add(entry);
        }
        return new PowerSource(
            spec.id(),
            spec.name(),
            PowerGroup.STEAM,
            spec.unlock(),
            formatAmount(spec.steamPerSecond()) + " L/s of steam from any furnace fuel.",
            List.of(
                new PowerSetting.Select("solidFuel", "Fuel", nameOptions(solidTable), "Coal"),
                new PowerSetting.Select("waterKind", "Water supply", WATER_KINDS, "Water")),
            read -> {
                final Fuel fuel = PowerData.findFuel(solidTable, read.select("solidFuel"));
                // euPerItem in the solid table IS the furnace burn time in ticks.
                final double energyPerItem = (fuel.euPerItem != null ? fuel.euPerItem : 0) / 10;
                final double secondsPerItem = energyPerItem / spec.energyPerSecond();
                final List<String> warnings = new ArrayList<>();
                if (!spec.automatable()) {
                    warnings.add("GTNH turns small boiler automation off. Fuel goes in by hand.");
                }
                return new PowerModel(
                    0,
                    List.of(
                        items(fuel.name, secondsPerItem > 0 ? 1 / secondsPerItem : 0),
                        liters(read.select("waterKind"), spec.steamPerSecond() / 160)),
                    List.of(liters("Steam", spec.steamPerSecond())),
                    List.of(
                        stat("Steam", formatAmount(spec.steamPerSecond() / 20) + " L/t"),
                        stat("One item burns", formatAmount(secondsPerItem) + "s")),
                    warnings);
            });
    }

    /**
     * GT++ Advanced Boilers (MTEAdvancedBoilerBase): 750 L/s per tier, automatable, and no water explosion. A fuel
     * item goes in only once the boiler has cooled to 101C, and gives burnTime / 10 energy (2 per degree) plus
     * burnTime / 500 bonus degrees. Cooldown takes one degree every 41 ticks, so an item lasts (floor(bt/10)/2 +
     * floor(bt/500)) x 41/20 seconds on every tier.
     */
    private static double advancedBoilerSecondsPerItem(final double burnTime) {
        return (Math.floor(burnTime / 10) / 2 + Math.floor(burnTime / 500)) * (41.0 / 20);
    }

    private record AdvancedBoilerTier(String key, String label, double tier) {}

    private static final List<AdvancedBoilerTier> ADVANCED_BOILER_TIERS = List.of(
        new AdvancedBoilerTier("LV", "Advanced Boiler [LV] (750 L/s)", 1),
        new AdvancedBoilerTier("MV", "Advanced Boiler [MV] (1,500 L/s)", 2),
        new AdvancedBoilerTier("HV", "Advanced Boiler [HV] (2,250 L/s)", 3));

    private static PowerSource advancedBoiler() {
        final List<Fuel> bronzeSolid = PowerData.get().boilerFuels.get("bronzeSolid");
        final List<Option> tiers = new ArrayList<>();
        for (final AdvancedBoilerTier row : ADVANCED_BOILER_TIERS) tiers.add(new Option(row.key(), row.label()));
        return new PowerSource(
            "advanced-boiler",
            "Advanced Boiler",
            PowerGroup.STEAM,
            "LV",
            "GT++ boilers: up to 2,250 L/s, safe and automatable.",
            List.of(
                new PowerSetting.Select("tier", "Boiler", tiers, "LV"),
                new PowerSetting.Select("solidFuel", "Fuel", nameOptions(bronzeSolid), "Coal"),
                new PowerSetting.Select("waterKind", "Water supply", WATER_KINDS, "Water")),
            read -> {
                AdvancedBoilerTier entry = ADVANCED_BOILER_TIERS.get(0);
                for (final AdvancedBoilerTier row : ADVANCED_BOILER_TIERS) {
                    if (row.key()
                        .equals(read.select("tier"))) {
                        entry = row;
                        break;
                    }
                }
                final double steamPerSecond = 750 * entry.tier();
                final Fuel fuel = PowerData.findFuel(bronzeSolid, read.select("solidFuel"));
                final double secondsPerItem = advancedBoilerSecondsPerItem(fuel.euPerItem != null ? fuel.euPerItem : 0);
                return new PowerModel(
                    0,
                    List.of(
                        items(fuel.name, secondsPerItem > 0 ? 1 / secondsPerItem : 0),
                        liters(read.select("waterKind"), steamPerSecond / 160)),
                    List.of(liters("Steam", steamPerSecond)),
                    List.of(
                        stat("Steam", formatAmount(steamPerSecond / 20) + " L/t"),
                        stat("One item burns", formatAmount(secondsPerItem) + "s")));
            });
    }

    /**
     * MTEBoilerLava: 600 L/s, 1 boiler energy per L of lava, 3 energy per degree of cooldown.
     * MTEBoiler.calculateCooldown drops a degree every 20 + 1 ticks, so 60/21 L/s of lava at steady state. Every
     * 1000 L burned leaves one obsidian, and the boiler stops taking lava while that slot is full. Pulls lava from a
     * tank above it.
     */
    private static final double LAVA_PER_SECOND = 60.0 / 21;

    private static PowerSource lavaBoiler() {
        return new PowerSource(
            "lava-boiler",
            "Reinforced Lava Boiler",
            PowerGroup.STEAM,
            "LV",
            "600 L/s of steam on " + formatAmount(LAVA_PER_SECOND) + " L/s of lava.",
            List.of(new PowerSetting.Select("waterKind", "Water supply", WATER_KINDS, "Water")),
            read -> new PowerModel(
                0,
                List.of(liters("Lava", LAVA_PER_SECOND), liters(read.select("waterKind"), 600.0 / 160)),
                List.of(liters("Steam", 600), items("Obsidian", LAVA_PER_SECOND / 1000)),
                List.of(
                    stat("Steam", "30 L/t"),
                    stat("Lava", formatAmount(LAVA_PER_SECOND) + " L/s"),
                    stat("Obsidian", formatAmount((LAVA_PER_SECOND / 1000) * 3600) + " per hour"))));
    }

    /**
     * MTEBoilerSolar / MTEBoilerSolarSteel, values from MachineStats.cfg (at defaults): fuel-free steam. On regular
     * water the boiler counts steaming ticks; past calcificationTicks the output falls linearly and reaches the
     * minimum at getMaxRuntimeTicks, (max - min) x calcification / max + calcification. Distilled water never
     * calcifies.
     */
    private record SolarBoilerModel(String key, String label, double max, double min) {}

    private static final List<SolarBoilerModel> SOLAR_BOILER_MODELS = List.of(
        new SolarBoilerModel("bronze", "Simple Solar Boiler", 120, 40),
        new SolarBoilerModel("steel", "Advanced Solar Boiler", 360, 120));
    private static final double SOLAR_CALCIFICATION_TICKS = 1_080_000;
    private static final double TICKS_PER_HOUR = 72_000;

    private static PowerSource solarBoiler() {
        final List<Option> models = new ArrayList<>();
        for (final SolarBoilerModel row : SOLAR_BOILER_MODELS) models.add(new Option(row.key(), row.label()));
        return new PowerSource(
            "solar-boiler",
            "Solar Boiler",
            PowerGroup.STEAM,
            "ULV",
            "Free steam from the sun. Calcifies on regular water.",
            List.of(
                new PowerSetting.Select("model", "Boiler", models, "bronze"),
                new PowerSetting.Select("waterKind", "Water supply", WATER_KINDS, "Distilled Water"),
                new PowerSetting.Toggle("calcified", "Fully calcified", false, new Condition("waterKind", "Water"))),
            read -> {
                SolarBoilerModel model = SOLAR_BOILER_MODELS.get(0);
                for (final SolarBoilerModel row : SOLAR_BOILER_MODELS) {
                    if (row.key()
                        .equals(read.select("model"))) {
                        model = row;
                        break;
                    }
                }
                final boolean onWater = "Water".equals(read.select("waterKind"));
                final double steamPerSecond = onWater && read.on("calcified") ? model.min() : model.max();
                final List<String> warnings = new ArrayList<>();
                if (onWater) {
                    final double fallTicks = ((model.max() - model.min()) * SOLAR_CALCIFICATION_TICKS) / model.max();
                    final double minTicks = fallTicks + SOLAR_CALCIFICATION_TICKS;
                    warnings.add(
                        "Regular water calcifies this boiler: full " + jsString(model.max())
                            + " L/s for "
                            + formatAmount(SOLAR_CALCIFICATION_TICKS / TICKS_PER_HOUR)
                            + " hours of run time, then down to "
                            + jsString(model.min())
                            + " L/s by "
                            + formatAmount(minTicks / TICKS_PER_HOUR)
                            + " hours. Distilled water does not.");
                }
                return new PowerModel(
                    0,
                    List.of(liters(read.select("waterKind"), steamPerSecond / 160)),
                    List.of(liters("Steam", steamPerSecond)),
                    List.of(stat("Steam", formatAmount(steamPerSecond / 20) + " L/t"), stat("Needs", "Open sky")),
                    warnings);
            });
    }

    /**
     * JavaScript's {@code String(number)} for a raw number in a template string, between 1e-7 and 1e21 (where it
     * prints no exponent). Only small whole numbers are interpolated raw here.
     */
    private static String jsString(final double value) {
        if (value == Math.rint(value) && Math.abs(value) < 0x1p53) return Long.toString((long) value);
        return new BigDecimal(Double.toString(value)).stripTrailingZeros()
            .toPlainString();
    }

    /**
     * Each programmed circuit above 1 in the controller (1 to 25) lowers the threshold by the fluid's throttle and
     * costs 1.5% steam (MTEHeatExchanger, MTEAdvHeatExchanger, MTEExtremeHeatExchanger). The LHE and the XL take at
     * most twice their lowered threshold a second; the EHE takes its recipe's fixed amount and never lets the
     * threshold drop below 1. MTEThermalBoiler reads no circuit.
     */
    private static final Map<String, String> EXCHANGER_UNLOCK = Map.of(
        "Thermal Boiler",
        "IV",
        "Large Heat Exchanger",
        "EV",
        "Whakawhiti Wera XL",
        "LuV",
        "Extreme Heat Exchanger",
        "IV");

    /** RecipesGregTech.thermalBoilerRecipes: lava makes plain Steam, the rest superheated. */
    private static final Set<String> THERMAL_BOILER_PLAIN_STEAM = Set.of("Lava", "Pahoehoe Lava");

    /** Names as the resource map keys them (the workbook's own spellings). */
    private static final Map<String, String> COLD_RETURN = Map
        .of("Lava", "Pahoehoe Lava", "Hot Coolant", "Coolant", "Hot Solar Salt", "Cold Solar Salt");

    private static PowerSource buildExchanger(final HeatExchanger entry) {
        final boolean isThermalBoiler = "Thermal Boiler".equals(entry.name);
        final boolean isExtreme = "Extreme Heat Exchanger".equals(entry.name);
        final boolean capAtMax = isThermalBoiler || isExtreme;
        final List<String> fluidNames = new ArrayList<>(entry.fluids.keySet());
        final String id = entry.name.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-");
        final HeatExchangerFluidRule defaults = entry.fluids.get(fluidNames.get(0));

        final List<Option> fluidOptions = new ArrayList<>();
        for (final String name : fluidNames) fluidOptions.add(new Option(name, name));
        final List<PowerSetting> settings = new ArrayList<>();
        settings.add(new PowerSetting.Select("fluid", "Hot fluid", fluidOptions, fluidNames.get(0)));
        settings.add(
            new PowerSetting.Number(
                "intake",
                "Hot fluid rate",
                1,
                10_000_000,
                1,
                Math.max(1, defaults != null ? defaults.threshold : 1),
                "L/s",
                null));
        if (!isThermalBoiler) {
            // The id stays "tier" so saved plans keep their circuit.
            settings.add(new PowerSetting.Number("tier", "Circuit", 1, 25, 1, 1));
        }
        if (isThermalBoiler) {
            // MTEThermalBoiler.useWater takes plain water first, distilled second;
            // the true exchangers demand distilled and explode without it.
            settings.add(
                new PowerSetting.Select(
                    "waterKind",
                    "Water supply",
                    List.of(new Option("Water", "Water"), new Option("Distilled Water", "Distilled Water")),
                    "Water"));
        }

        return new PowerSource(
            id,
            entry.name,
            PowerGroup.STEAM,
            EXCHANGER_UNLOCK.getOrDefault(entry.name, "EV"),
            isExtreme ? "Hot fluids to supercritical steam."
                : entry.name.startsWith("Whakawhiti") ? "32 heat exchangers in one block."
                    : "Hot fluids to steam; cold comes back.",
            settings,
            read -> {
                final String fluidName = read.select("fluid");
                final HeatExchangerFluidRule found = entry.fluids.get(fluidName);
                final HeatExchangerFluidRule rule = found != null ? found : defaults;
                final double tier = isThermalBoiler ? 1 : read.number("tier");
                final double intake = read.number("intake");
                final double lowered = rule.threshold + (tier - 1) * rule.throttle;
                final double threshold = isExtreme ? Math.max(1, lowered) : lowered;
                final double cap = capAtMax ? rule.max : threshold * 2;
                final double used = Math.min(intake, cap);
                final boolean overThreshold = used >= threshold;
                final double ratio = overThreshold ? rule.overRatio : rule.underRatio;
                final double efficiency = isThermalBoiler ? 1 : 1 - 0.015 * (tier - 1);
                final double steamPerSecond = used * ratio * efficiency;
                final String grade = isThermalBoiler
                    ? THERMAL_BOILER_PLAIN_STEAM.contains(fluidName) ? "Steam" : "SH Steam"
                    : isExtreme ? overThreshold ? "SC Steam" : "SH Steam" : overThreshold ? "SH Steam" : "Steam";

                final List<Flow> outputs = new ArrayList<>();
                outputs.add(liters(grade, steamPerSecond));
                final String coldReturn = COLD_RETURN.get(fluidName);
                if (coldReturn != null) {
                    outputs.add(liters(coldReturn, used));
                }
                final String waterName = isThermalBoiler ? read.select("waterKind") : "Distilled Water";
                return new PowerModel(
                    0,
                    List.of(liters(fluidName, used), liters(waterName, steamPerSecond / 160)),
                    outputs,
                    List.of(
                        stat("Steam", formatAmount(steamPerSecond / 20) + " L/t " + grade),
                        stat("Threshold", formatAmount(threshold) + " L/s")),
                    intake > cap ? List.of("Intake is capped at " + formatAmount(cap) + " L/s for this fluid.")
                        : List.of());
            });
    }

    /** Every steam maker, in the website's order (steamMakerSources). */
    public static List<PowerSource> sources() {
        final List<PowerSource> all = new ArrayList<>();
        for (final SmallBoilerSpec spec : SMALL_BOILER_SPECS) all.add(buildSmallBoiler(spec));
        all.add(solarBoiler());
        all.add(lavaBoiler());
        all.add(advancedBoiler());
        for (final BoilerSpec spec : boilerSpecs()) all.add(buildBoiler(spec));
        for (final HeatExchanger entry : PowerData.get().heatExchangers) all.add(buildExchanger(entry));
        return List.copyOf(all);
    }

    private SteamMakers() {}
}

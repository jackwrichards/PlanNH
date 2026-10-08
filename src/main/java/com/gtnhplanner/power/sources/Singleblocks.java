package com.gtnhplanner.power.sources;

import static com.gtnhplanner.power.sources.Helpers.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleUnaryOperator;

import javax.annotation.Nullable;

import com.gtnhplanner.power.PowerData;
import com.gtnhplanner.power.PowerData.Fuel;
import com.gtnhplanner.power.PowerGroup;
import com.gtnhplanner.power.PowerModel;
import com.gtnhplanner.power.PowerModel.Flow;
import com.gtnhplanner.power.PowerModel.Stat;
import com.gtnhplanner.power.PowerResources;
import com.gtnhplanner.power.PowerSetting;
import com.gtnhplanner.power.PowerSource;
import com.gtnhplanner.power.SettingsReader;

/**
 * Singleblock generators (the website's sources/singleblocks.ts; MTEBasicGenerator and its subclasses): 1 amp of
 * their tier while running, with a per-family per-tier efficiency ladder. Every 1 A packet drains V plus GT's output
 * loss (BaseMetaTileEntity), so the burn rate prices V + loss.
 *
 * <p>
 * MTEBasicGenerator.onPostTick burns every 10 ticks: fluid in whole liters at floor(fuel value x efficiency% / 100)
 * EU each, at most the tank's capacity per burn; items one per burn at fuel value x 10 x efficiency% EU. A fuel too
 * thin for its tank, or an item too small for its tier, derates the output instead of running at V.
 */
public final class Singleblocks {

    /** What a family burns: a fuel table, steam, or the naquadah reactor's rods by mark. */
    private enum FuelKind {
        TABLE,
        STEAM,
        NAQUADAH
    }

    private record Byproduct(String name, double count) {}

    /** One singleblock family (SingleblockSpec); the optional fields are set fluently. */
    private static final class Spec {

        final String id;
        final String name;
        final String family;
        final String unlock;
        final String blurb;
        FuelKind kind = FuelKind.TABLE;
        List<Fuel> fuels = List.of();
        /** Solid fuels burn whole items; the sheet prices them per hour. */
        boolean solid;
        /** The fuel players actually run, so a fresh card starts sane. */
        @Nullable
        String defaultFuel;
        /** Liters the machine's tank holds at a GT tier (the class's getCapacity()). */
        @Nullable
        DoubleUnaryOperator capacity;
        /** The table's EU per L over the machine's own fuel map value. */
        @Nullable
        Double fuelValueDivisor;
        /** Items getEmptyContainer hands back per item burned. */
        Map<String, Byproduct> byproducts = Map.of();
        /**
         * MTEMagicalEnergyAbsorber overrides onPostTick without the base burn, so none of the burn rules below
         * describe it; the card keeps the sheet's model.
         */
        boolean sheetModel;

        Spec(final String id, final String name, final String family, final String unlock, final String blurb) {
            this.id = id;
            this.name = name;
            this.family = family;
            this.unlock = unlock;
            this.blurb = blurb;
        }

        Spec fuels(final List<Fuel> table) {
            this.kind = FuelKind.TABLE;
            this.fuels = table;
            return this;
        }

        Spec fuels(final FuelKind special) {
            this.kind = special;
            return this;
        }

        Spec solid() {
            this.solid = true;
            return this;
        }

        Spec defaultFuel(final String fuel) {
            this.defaultFuel = fuel;
            return this;
        }

        Spec capacity(final DoubleUnaryOperator litersAtGtTier) {
            this.capacity = litersAtGtTier;
            return this;
        }

        Spec fuelValueDivisor(final double divisor) {
            this.fuelValueDivisor = divisor;
            return this;
        }

        Spec byproducts(final Map<String, Byproduct> table) {
            this.byproducts = table;
            return this;
        }

        Spec sheetModel() {
            this.sheetModel = true;
            return this;
        }
    }

    /** MTEBasicGenerator.getCapacity(). */
    private static final double BASE_TANK_LITERS = 16_000;
    /** One burn every 10 ticks (MTEBasicGenerator.onPostTick). */
    private static final double BURNS_PER_SECOND = 2;

    /**
     * The magic fuel map's recipe outputs, which the converter hands back per item (FuelLoader's Blood Magic slates,
     * FuelRecipes' enchanted golden apple, GTItemIterator's liveroots).
     */
    private static final Map<String, Byproduct> MAGIC_BYPRODUCTS = Map.of(
        "Reinforced Slate",
        new Byproduct("Blank Slate", 1),
        "Imbued Slate",
        new Byproduct("Reinforced Slate", 1),
        "Demonic Slate",
        new Byproduct("Imbued Slate", 1),
        "Ethereal Slate",
        new Byproduct("Demonic Slate", 1),
        "Ench. Golden Apple",
        new Byproduct("Apple", 1),
        "Liveroots",
        new Byproduct("Stick", 4));

    private static List<Spec> specs() {
        final PowerData data = PowerData.get();
        return List.of(
            new Spec(
                "steam-turbine",
                "Steam Turbine",
                "steamTurbine",
                "LV",
                "Turns 7, 8 or 9 L of steam into 3 EU (LV, MV, HV).").fuels(FuelKind.STEAM)
                    // MTESteamTurbine.getCapacity()
                    .capacity(gtTier -> 24_000 * gtTier),
            new Spec("gas-turbine", "Gas Turbine", "gasTurbine", "LV", "Burns benzene and the other gas fuels.")
                .fuels(data.gasFuels)
                .defaultFuel("Benzene"),
            new Spec("combustion-generator", "Combustion Generator", "combustion", "LV", "Burns the diesel-line fuels.")
                .fuels(data.combustionFuels)
                .defaultFuel("Diesel"),
            new Spec(
                "semifluid-generator",
                "Semifluid Generator",
                "semifluid",
                "LV",
                "Burns heavy oils and semifluids.").fuels(data.semifluidFuels)
                    .defaultFuel("Creosote Oil"),
            new Spec("acid-generator", "Acid Generator", "chem", "LV", "Burns the acid-line fluids.")
                .fuels(data.chemFuels)
                .defaultFuel("Sulfuric Acid"),
            new Spec("geothermal-engine", "Geothermal Engine", "frost", "EV", "Burns lava, cryotheum and pyrotheum.")
                .fuels(data.frostFuels)
                .defaultFuel("Lava")
                // MTEGeothermalGenerator.getCapacity()
                .capacity(gtTier -> 5_000 * gtTier),
            new Spec("rocket-fuel-generator", "Rocket Fuel Generator", "rocket", "EV", "Burns mixed rocket fuels.")
                .fuels(data.rocketFuels)
                // MTERocketFuelGeneratorBase.getCapacity()
                .capacity(gtTier -> 32_000)
                // The table carries the Large Rocket Engine's figures, which triple the fuel map value
                // (MTELargeRocketEngine); MTERocketFuelGenerator burns the map value itself
                // (RecipeLoaderRocketFuels: 512 for RP-1).
                .fuelValueDivisor(3),
            // Mark I's crafting recipe takes a LuV hull (MTERecipeLoader).
            new Spec("plasma-generator", "Plasma Generator", "plasma", "LuV", "Burns plasma from fusion.")
                .fuels(data.plasmas)
                .defaultFuel("Helium Plasma"),
            new Spec("naquadah-reactor", "Naquadah Reactor", "naquadah", "EV", "Depletes naquadah and tiberium rods.")
                .fuels(FuelKind.NAQUADAH)
                .solid(),
            new Spec(
                "magic-energy-converter",
                "Magic Energy Converter",
                "magicConverter",
                "LV",
                "Consumes magical items for power.").fuels(data.magicSolids)
                    .solid()
                    .defaultFuel("Quicksilver")
                    .byproducts(MAGIC_BYPRODUCTS),
            new Spec(
                "magic-energy-absorber",
                "Magic Energy Absorber",
                "magicAbsorber",
                "LV",
                "Consumes magical items for power.").fuels(data.magicSolids)
                    .solid()
                    .sheetModel());
    }

    /** One Naquadah Reactor mark's rods: its naquadah part, what that hands back, and its tiberium form. */
    private record NaquadahMark(String naquadah, String spent, String tiberium) {}

    /**
     * Each Naquadah Reactor mark reads its own fuel map (MTENaquadahReactor.getRecipeMap): the mark's naquadah or
     * naquadria part (FuelLoader, which hands back the plain naquadah part) and one tiberium form (bartworks
     * AdditionalRecipes, nothing handed back). In mark order.
     */
    private static final Map<String, NaquadahMark> NAQUADAH_MARKS = naquadahMarks();

    private static Map<String, NaquadahMark> naquadahMarks() {
        final Map<String, NaquadahMark> marks = new LinkedHashMap<>();
        marks.put("EV", new NaquadahMark("Enriched Naquadah Bolt (EV)", "Naquadah Bolt", "Tiberium Bolt (EV)"));
        marks.put("IV", new NaquadahMark("Enriched Naquadah Rod (IV)", "Naquadah Rod", "Tiberium Rod (IV)"));
        marks.put(
            "LuV",
            new NaquadahMark("Long Enriched Naquadah Rod (LuV)", "Long Naquadah Rod", "Long Tiberium Rod (LuV)"));
        marks.put("ZPM", new NaquadahMark("Naquadria Bolt (ZPM)", "Naquadah Bolt", "Tiberium Rod (ZPM)"));
        marks.put("UV", new NaquadahMark("Naquadria Rod (UV)", "Naquadah Rod", "Long Tiberium Rod (UV)"));
        return marks;
    }

    private static final List<PowerSetting.Option> NAQUADAH_FUEL_OPTIONS = List
        .of(new PowerSetting.Option("naquadah", "Naquadah/Naquadria"), new PowerSetting.Option("tiberium", "Tiberium"));

    /** Stored plans name the rod itself; each rod reads as its form. */
    private static final Map<String, String> NAQUADAH_LEGACY_KEYS = naquadahLegacyKeys();

    private static Map<String, String> naquadahLegacyKeys() {
        final Map<String, String> keys = new LinkedHashMap<>();
        for (final NaquadahMark mark : NAQUADAH_MARKS.values()) {
            keys.put(mark.naquadah(), "naquadah");
            keys.put(mark.tiberium(), "tiberium");
        }
        return keys;
    }

    /** log2, exact on powers of two as JavaScript's Math.log2 is. */
    private static double log2(final double value) {
        if (value > 0 && Double.isFinite(value) && value == Math.scalb(1.0, Math.getExponent(value))) {
            return Math.getExponent(value);
        }
        return Math.log(value) / Math.log(2);
    }

    /** GT tier index (LV = 1) of a tier voltage. */
    private static double gtTierOf(final double voltage) {
        return Math.round(log2(voltage / 8) / 2);
    }

    /** GTUtility.getTier then BaseMetaTileEntity's loss: 2^(tier - 1) EU per packet, at least 1. */
    private static double packetLoss(final double voltage) {
        final double tier = voltage <= 8 ? 0 : Math.floor((Math.ceil(log2(voltage)) - 2) / 2);
        return Math.pow(2, Math.max(0, tier - 1));
    }

    private static String eu(final double value) {
        return formatAmount(Math.round(value));
    }

    /** A raw number in a JavaScript template string: integers without ".0". */
    private static String js(final double value) {
        return value == Math.rint(value) && Math.abs(value) < 1e15 ? Long.toString((long) value)
            : Double.toString(value);
    }

    private static double or(@Nullable final Double value, final double fallback) {
        return value != null ? value : fallback;
    }

    /**
     * A fluid burn: per 10-tick burn the machine needs 10 packets of EU, but can only drain what its tank holds; past
     * that it runs below V.
     */
    private static PowerModel fluidModel(final String fuelName, final double voltage, final double ampLoss,
        final double euPerLiter, final double tankLiters, final List<Stat> stats) {
        if (!(euPerLiter > 0)) {
            return new PowerModel(
                0,
                List.of(),
                List.of(),
                stats,
                List.of("Makes 0 EU per L at this tier, so the generator will not burn it."));
        }
        final double packet = voltage + ampLoss;
        final double litersPerBurn = (packet * 10) / euPerLiter;
        if (litersPerBurn <= tankLiters) {
            return new PowerModel(
                voltage,
                List.of(liters(fuelName, litersPerBurn * BURNS_PER_SECOND)),
                List.of(),
                stats);
        }
        final double euPerTick = ((tankLiters * euPerLiter) / 10) * (voltage / packet);
        return new PowerModel(
            euPerTick,
            List.of(liters(fuelName, tankLiters * BURNS_PER_SECOND)),
            List.of(),
            stats,
            List.of(
                "Burns at most " + formatAmount(tankLiters)
                    + " L per 10 ticks (the tank), so it runs at "
                    + eu(euPerTick)
                    + " EU/t, not "
                    + eu(voltage)
                    + "."));
    }

    /** An item burn: one item per 10-tick burn, so thin items derate the output. */
    private static PowerModel itemModel(final String fuelName, final double voltage, final double ampLoss,
        final double euPerItem, final Stat efficiencyStat, @Nullable final Byproduct byproduct) {
        final double packet = voltage + ampLoss;
        final double needed = euPerItem > 0 ? (packet * 20) / euPerItem : 0;
        final boolean capped = needed > BURNS_PER_SECOND;
        final double perSecond = capped ? BURNS_PER_SECOND : needed;
        final double euPerTick = capped ? ((BURNS_PER_SECOND * euPerItem) / 20) * (voltage / packet) : voltage;
        final List<Flow> outputs = byproduct != null && PowerResources.resolve(byproduct.name()) != null
            && perSecond > 0 ? List.of(items(byproduct.name(), perSecond * byproduct.count())) : List.of();
        return new PowerModel(
            euPerTick,
            List.of(items(fuelName, perSecond)),
            outputs,
            List.of(
                efficiencyStat,
                stat("Fuel per hour", formatAmount(perSecond * 3600)),
                stat("EU per item", formatAmount(euPerItem))),
            capped ? List.of(
                "Burns at most 1 item per 10 ticks, so it runs at " + eu(euPerTick) + " EU/t, not " + eu(voltage) + ".")
                : List.of());
    }

    private static PowerSource buildSingleblock(final Spec spec) {
        final FamilyTiers tiers = familyTierOptions(spec.family);
        final List<PowerSetting> settings = new ArrayList<>();
        settings.add(
            new PowerSetting.Select(
                "tier",
                "Tier",
                tiers.options(),
                tiers.options()
                    .isEmpty() ? "LV"
                        : tiers.options()
                            .get(0)
                            .key()));
        if (spec.kind == FuelKind.NAQUADAH) {
            // The mark (tier) picks the rod; this knob picks its naquadah or tiberium form.
            settings.add(
                new PowerSetting.Select("fuel", "Fuel", NAQUADAH_FUEL_OPTIONS, "naquadah", null, NAQUADAH_LEGACY_KEYS));
        } else if (spec.kind != FuelKind.STEAM) {
            settings.add(
                new PowerSetting.Select(
                    "fuel",
                    "Fuel",
                    PowerData.fuelOptions(spec.fuels),
                    spec.defaultFuel != null ? spec.defaultFuel : spec.fuels.isEmpty() ? "" : spec.fuels.get(0).name));
        }

        return new PowerSource(
            spec.id,
            spec.name,
            PowerGroup.BURNERS,
            spec.unlock,
            spec.blurb,
            settings,
            read -> compute(spec, tiers, read));
    }

    private static PowerModel compute(final Spec spec, final FamilyTiers tiers, final SettingsReader read) {
        final String tier = read.select("tier");
        final TierPower power = tierPower(tier);
        final double voltage = power.voltage();
        final double ampLoss = power.ampLoss();
        final double gtTier = gtTierOf(voltage);
        final double tankLiters = spec.capacity != null ? spec.capacity.applyAsDouble(gtTier) : BASE_TANK_LITERS;

        if (spec.kind == FuelKind.STEAM) {
            // MTESteamTurbine: 3 EU from every (6 + tier) L of steam.
            final double litersPerOperation = 6 + gtTier;
            final double euPerLiter = 3 / litersPerOperation;
            return fluidModel(
                "Steam",
                voltage,
                ampLoss,
                euPerLiter,
                tankLiters,
                List.of(
                    stat("Efficiency", percent(6 / litersPerOperation)),
                    stat("EU per L", formatAmount(euPerLiter))));
        }

        // getEfficiency() is a whole percent.
        final double efficiencyPercent = Math.round(tiers.efficiencyFor(tier) * 100);
        final Stat efficiencyStat = stat("Efficiency", percent(efficiencyPercent / 100));

        if (spec.kind == FuelKind.NAQUADAH) {
            final NaquadahMark mark = NAQUADAH_MARKS.getOrDefault(tier, NAQUADAH_MARKS.get("EV"));
            final boolean tiberium = "tiberium".equals(read.select("fuel"));
            final Fuel rod = PowerData
                .findFuel(PowerData.get().naquadahRods, tiberium ? mark.tiberium() : mark.naquadah());
            final double euPerItem = (or(rod.euPerItem, 0) * efficiencyPercent) / 100;
            return itemModel(
                rod.name,
                voltage,
                ampLoss,
                euPerItem,
                efficiencyStat,
                tiberium ? null : new Byproduct(mark.spent(), 1));
        }

        final Fuel fuel = PowerData.findFuel(spec.fuels, read.select("fuel"));
        // The geothermal engine mixes forms: the lavas are fluids, the theum dusts items - decided per fuel, not per
        // machine.
        final boolean solidFuel = spec.solid || (fuel.euPerItem != null && fuel.euPerLiter == null);

        if (spec.sheetModel) {
            final double euPerItem = or(fuel.euPerItem, 0);
            final double efficiency = tiers.efficiencyFor(tier);
            // The workbook prices solids per hour: (V+loss)/EU/eff x 20 x 3600.
            final double perHour = euPerItem > 0 ? ((voltage + ampLoss) / (euPerItem * efficiency)) * 20 * 3600 : 0;
            return new PowerModel(
                voltage,
                List.of(items(fuel.name, perHour / 3600)),
                List.of(),
                List.of(
                    stat("Efficiency", percent(efficiency)),
                    stat("Fuel per hour", formatAmount(perHour)),
                    stat("EU per item", formatAmount(euPerItem * efficiency))));
        }

        if (solidFuel) {
            // Item tables carry fuel value x 1000; the burn is fuel value x 10 x efficiency%.
            final double euPerItem = (or(fuel.euPerItem, 0) * efficiencyPercent) / 100;
            return itemModel(fuel.name, voltage, ampLoss, euPerItem, efficiencyStat, spec.byproducts.get(fuel.name));
        }

        final double fuelValue = or(fuel.euPerLiter, 0) / or(spec.fuelValueDivisor, 1);
        final double euPerLiter = Math.floor((fuelValue * efficiencyPercent) / 100);
        return fluidModel(
            fuel.name,
            voltage,
            ampLoss,
            euPerLiter,
            tankLiters,
            List.of(efficiencyStat, stat("EU per L", formatAmount(euPerLiter))));
    }

    /** One RTG pellet, with its run worked out once. */
    private record Pellet(String key, String name, String isotope, double euPerTick, double days, double euPerPellet,
        double seconds, String label, String runs) {}

    private static Pellet pellet(final String key, final String name, final String isotope, final double euPerTick,
        final double days) {
        final double euPerPellet = Math.min(20.0 * 86_400 * days * euPerTick, Integer.MAX_VALUE);
        final double seconds = euPerPellet / (euPerTick + packetLoss(euPerTick)) / 20;
        final double realDays = seconds / 86_400;
        return new Pellet(
            key,
            name,
            isotope,
            euPerTick,
            days,
            euPerPellet,
            seconds,
            isotope + " (" + js(euPerTick) + " EU/t, " + formatAmount(realDays) + " days)",
            formatAmount(realDays) + " real " + (realDays == 1 ? "day" : "days"));
    }

    /**
     * GT++ RTG (MTERTGenerator): a pellet holds its recipe's days of EU at the recipe voltage (20 x 86400 x days
     * ticks), capped at Integer.MAX_VALUE EU, and each tick's 1 A packet also pays GT's output loss. Voltages are
     * TierEU.RECIPE values; days are MathUtils.roundToClosestInt of the recipe's figure (87.7 gives 87, 2.6 gives 2).
     */
    private static final List<Pellet> RTG_PELLETS = List.of(
        pellet("am241", "Am Pellet", "Am-241", 15, 216),
        pellet("sr90", "Sr Pellet", "Sr-90", 30, 29),
        pellet("pu238", "Pu Pellet", "Pu-238", 60, 87),
        pellet("po210", "Po Pellet", "Po-210", 480, 1),
        pellet("ic2", "Pellets of RTG Fuel", "Pellets of RTG Fuel", 7, 2));

    private static PowerSource rtg() {
        final List<PowerSetting.Option> options = new ArrayList<>();
        for (final Pellet pellet : RTG_PELLETS) options.add(new PowerSetting.Option(pellet.key(), pellet.label()));
        return new PowerSource(
            "rtg",
            "Radioisotope Thermoelectric Generator",
            PowerGroup.BURNERS,
            // Its assembler recipe runs at IV (GT++ RecipesMachines).
            "IV",
            "Pellets decay into steady EU for real days.",
            List.of(new PowerSetting.Select("pellet", "Pellet", options, "pu238")),
            read -> {
                final String key = read.select("pellet");
                Pellet pellet = RTG_PELLETS.get(2);
                for (final Pellet row : RTG_PELLETS) {
                    if (row.key()
                        .equals(key)) {
                        pellet = row;
                        break;
                    }
                }
                return new PowerModel(
                    pellet.euPerTick(),
                    List.of(items(pellet.name(), 1 / pellet.seconds())),
                    List.of(),
                    List.of(
                        stat("One pellet runs", pellet.runs()),
                        stat("EU per pellet", formatAmount(pellet.euPerPellet())),
                        stat("Pollution", "None")));
            });
    }

    private static List<PowerSource> sources;

    public static synchronized List<PowerSource> sources() {
        if (sources == null) {
            final List<PowerSource> all = new ArrayList<>();
            for (final Spec spec : specs()) all.add(buildSingleblock(spec));
            all.add(rtg());
            sources = List.copyOf(all);
        }
        return sources;
    }

    private Singleblocks() {}
}

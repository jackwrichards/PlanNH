package com.gtnhplanner.power;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;

/**
 * The tables extracted from the community "GTNH Power Planner 2.9" workbook (the website's
 * {@code src/lib/power/data/power-planner-data.json}, copied verbatim to
 * {@code assets/gtnhplanner/power/}). Never hand-edit that file: it is regenerated on the website from the workbook
 * with Java corrections, and copied here.
 *
 * <p>
 * Plain Gson classes (Gson 2.2.4 cannot fill records). Every number is a double, as in JavaScript; an optional
 * number is a nullable {@link Double}, and a sparse ladder ({@code Array<number | null>}) a {@code Double[]} with
 * nulls.
 */
public final class PowerData {

    public static final String RESOURCE = "/assets/gtnhplanner/power/power-planner-data.json";

    private static PowerData data;

    /** The tables, read once. */
    public static synchronized PowerData get() {
        if (data == null) data = read(RESOURCE, PowerData.class);
        return data;
    }

    static <T> T read(final String resource, final Class<T> type) {
        try (InputStream in = PowerData.class.getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("Missing " + resource);
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return new Gson().fromJson(reader, type);
            }
        } catch (final IOException e) {
            throw new IllegalStateException("Cannot read " + resource, e);
        }
    }

    public static class Fuel {

        public String name;
        public Double euPerLiter;
        public Double euPerItem;
        /** Large-boiler seconds (boilerFuels); for boilers {@code euPerItem} is burn ticks. */
        public Double burnTime;
        public Double eheMaxLps;
        public Double promoterCoefficient;
    }

    public static class LneBase {

        public String name;
        public double multiplier;
        public double litersPerSecond;
        public double boostTicks;
    }

    public static class LneStructureTier {

        public String name;
        public double residueCapacity;
        public double baseDecay;
    }

    public static class LneRobotArm {

        public String name;
        public double tier;
    }

    public static class LnrFuel {

        public String name;
        public double euPerTick;
        public double secondsPerCell;
        public double euPerThousandLiters;
    }

    public static class LnrCoolant {

        public String name;
        public double efficiency;
        public double litersPerSecond;
    }

    public static class LnrBooster {

        public String name;
        public double multiplier;
        public double litersPerSecond;
    }

    public static class LftrFuel {

        public String name;
        public double euPerLiter;
        public String powerLabel;
        public double uSalt;
        public double tSalt;
        public double tbSalt;
        public double uf6;
        public double uranium233PerSecond;
    }

    public static class LftbRecipe {

        public String name;
        public String fuelName;
        public double eut;
        public double durationSeconds;
        public String baseTier;
        public double outputLiters;
    }

    public static class HtgrPebble {

        public String name;
        public double base;
        public double mult;
        public double exp;
    }

    public static class HeatExchangerFluidRule {

        public double threshold;
        public double max;
        public double throttle;
        public double underRatio;
        public double overRatio;
    }

    public static class HeatExchanger {

        public String name;
        /** By hot fluid name, in the workbook's order. */
        public Map<String, HeatExchangerFluidRule> fluids;
    }

    public static class FusionRecipe {

        public String name;
        public double minMark;
        public double euPerLiter;
        public double startupEu;
        public String input1;
        public Double ratio1;
        public String input2;
        public Double ratio2;
        public Double eheMaxLps;
        public String decayOutput;
        public Double[] outputLpsByMark;
        public Double[] drainEutByMark;
        public Double[] compactOutputLpsByMark;
        public Double[] compactDrainEutByMark;
    }

    public static class RotorClass {

        public Double[] efficiencyTight;
        public Double[] efficiencyLoose;
        public Double[] optimalTight;
        public Double[] optimalLoose;
        /** Plasma only. */
        public Double[] euAtOptimalTight;
    }

    public static class Rotor {

        public String name;
        public String unlock;
        public double durability;
        public double overflowTier;
        public RotorClass steam;
        public RotorClass gas;
        public RotorClass plasma;
    }

    public static class RotorSize {

        public String name;
        public double durabilityMult;
        public double efficiencyDelta;
        public double damage;
    }

    public static class SingleblockTier {

        public String tier;
        public double voltage;
        public double ampLoss;
    }

    public static class EohStar {

        public String name;
        public double tier;
        public double durationSeconds;
        public double baseSuccess;
        public double efficiency;
        public double euInput;
        public double euOutput;
        public double starMatter;
    }

    public String source;
    public String extractedAt;
    public List<Fuel> steamGrades;
    public List<Fuel> gasFuels;
    public List<Fuel> gasFuelsXl;
    public List<Fuel> plasmas;
    public List<Fuel> combustionFuels;
    public List<Fuel> eceFuels;
    public List<Fuel> semifluidFuels;
    public List<Fuel> chemFuels;
    public List<Fuel> frostFuels;
    public List<LneBase> lneBases;
    public List<LneStructureTier> lneStructureTiers;
    public List<LneRobotArm> lneRobotArms;
    public List<Fuel> ucfeFuels;
    public List<Fuel> magicSolids;
    public List<Fuel> naquadahRods;
    public List<Fuel> rocketFuels;
    public List<LnrFuel> lnrFuels;
    public List<LnrCoolant> lnrCoolants;
    public List<LnrBooster> lnrBoosters;
    public List<LftrFuel> lftrFuels;
    public List<LftbRecipe> lftbRecipes;
    public List<HtgrPebble> htgrPebbles;
    /** bronzeLiquid, bronzeSolid, steelLiquid, ... tungstensteelSolid. */
    public Map<String, List<Fuel>> boilerFuels;
    public List<HeatExchanger> heatExchangers;
    public List<FusionRecipe> fusionRecipes;
    public List<Rotor> rotors;
    public List<RotorSize> rotorSizes;
    public List<SingleblockTier> singleblockTiers;
    /** Per family, one efficiency per {@link #singleblockTiers} row, null where the family has no machine. */
    public Map<String, Double[]> singleblockEfficiency;
    public List<EohStar> eohStars;

    // ---- planner-data.ts helpers

    /** The entry with this name, else the table's first (findFuel). */
    public static Fuel findFuel(final List<Fuel> table, final String name) {
        for (final Fuel entry : table) {
            if (entry.name.equals(name)) return entry;
        }
        return table.get(0);
    }

    /** One option per entry, keyed and labelled by name (fuelOptions). */
    public static List<PowerSetting.Option> fuelOptions(final List<Fuel> table) {
        final List<PowerSetting.Option> options = new ArrayList<>(table.size());
        for (final Fuel entry : table) options.add(new PowerSetting.Option(entry.name, entry.name));
        return options;
    }

    public static final List<String> ROTOR_SIZE_NAMES = List.of("Small", "Normal", "Large", "Huge");

    /** The rotor with this name, else the first (findRotor). */
    public Rotor findRotor(final String name) {
        for (final Rotor rotor : rotors) {
            if (rotor.name.equals(name)) return rotor;
        }
        return rotors.get(0);
    }

    /** Durability of a rotor at a size index (rotorDurability). */
    public double rotorDurability(final Rotor rotor, final int sizeIndex) {
        final double mult = sizeIndex >= 0 && sizeIndex < rotorSizes.size() ? rotorSizes.get(sizeIndex).durabilityMult
            : 1;
        return rotor.durability * mult;
    }
}

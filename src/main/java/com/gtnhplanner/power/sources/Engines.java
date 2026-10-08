package com.gtnhplanner.power.sources;

import static com.gtnhplanner.power.sources.Helpers.*;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.gtnhplanner.power.PowerData;
import com.gtnhplanner.power.PowerData.Fuel;
import com.gtnhplanner.power.PowerGroup;
import com.gtnhplanner.power.PowerModel;
import com.gtnhplanner.power.PowerModel.Flow;
import com.gtnhplanner.power.PowerModel.Stat;
import com.gtnhplanner.power.PowerSetting;
import com.gtnhplanner.power.PowerSource;
import com.gtnhplanner.power.SettingsReader;

/**
 * The engine multiblocks (the website's sources/engines.ts): Large Combustion Engine, Extreme Combustion Engine,
 * Large Semifluid Generator, Large Rocket Engine and the Universal Chemical Fuel Engine. Boost mechanics follow
 * MTELargeCombustionEngine (fuel x2, output x3, oxygen 40 L/s) and MTEUniversalChemicalFuelEngine (eff = 1.5
 * e^(-C/ratio)).
 */
public final class Engines {

    /**
     * mRuntime counts 0..1001 before it wraps (MTEMultiBlockBase.doRandomMaintenanceDamage), so a
     * {@code mRuntime % 72 == 0} gate fires 14 times per 1002 ticks, not once per 72.
     */
    private static final double GATE_72_PER_SECOND = (14.0 / 1002) * 20;

    /** One combustion-style engine (EngineSpec); the optional fields are set fluently. */
    private static final class EngineSpec {

        final String id;
        final String name;
        final String unlock;
        final String blurb;
        List<Fuel> fuels = List.of();
        double baseOutput;
        double boostedOutput;
        String booster = "";
        double boosterPerSecond;
        /** LCE only: fuels above this EU/L refuse to run without the boost. */
        @Nullable
        Double unboostedFuelCap;
        /**
         * LCE and ECE: a boosted fuel worth more than half the boosted output burns one extra litre at random,
         * weighted by the leftover fraction.
         */
        boolean weightedExtraLitre;
        @Nullable
        String defaultFuel;

        EngineSpec(final String id, final String name, final String unlock, final String blurb) {
            this.id = id;
            this.name = name;
            this.unlock = unlock;
            this.blurb = blurb;
        }

        EngineSpec fuels(final List<Fuel> table) {
            this.fuels = table;
            return this;
        }

        EngineSpec output(final double base, final double boosted) {
            this.baseOutput = base;
            this.boostedOutput = boosted;
            return this;
        }

        EngineSpec booster(final String fluid, final double perSecond) {
            this.booster = fluid;
            this.boosterPerSecond = perSecond;
            return this;
        }

        EngineSpec unboostedFuelCap(final double cap) {
            this.unboostedFuelCap = cap;
            return this;
        }

        EngineSpec weightedExtraLitre() {
            this.weightedExtraLitre = true;
            return this;
        }

        EngineSpec defaultFuel(final String fuel) {
            this.defaultFuel = fuel;
            return this;
        }
    }

    private static List<EngineSpec> engineSpecs() {
        final PowerData data = PowerData.get();
        return List.of(
            new EngineSpec(
                "large-combustion-engine",
                "Large Combustion Engine",
                "EV",
                "Diesel fuels; oxygen boost triples it.").fuels(data.combustionFuels)
                    .output(2048, 6144)
                    .booster("Oxygen", 40)
                    .unboostedFuelCap(2048)
                    .weightedExtraLitre()
                    .defaultFuel("Diesel"),
            new EngineSpec(
                "extreme-combustion-engine",
                "Extreme Combustion Engine",
                "IV",
                "Jet fuels and HOG; liquid oxygen boost.").fuels(data.eceFuels)
                    .output(10900, 32700)
                    // Lubricant: the tooltip claims 8000 L/hr, but getAdditiveFactor() is 1, the same gate as the LCE
                    // (MTEExtremeCombustionEngine).
                    .booster("Liquid Oxygen", 40)
                    .unboostedFuelCap(10900)
                    .weightedExtraLitre(),
            new EngineSpec("large-semifluid-generator", "Large Semifluid Burner", "EV", "The engine for heavy oils.")
                .fuels(data.semifluidFuels)
                // Boosted it meters fuel against 4096 and runs at 150% efficiency (MTELargeSemifluidGenerator).
                .output(2048, 6144)
                .booster("Oxygen", 80)
                .defaultFuel("Creosote Oil"));
    }

    /** A raw number in a JavaScript template string: integers without ".0". */
    private static String js(final double value) {
        return value == Math.rint(value) && Math.abs(value) < 1e15 ? Long.toString((long) value)
            : Double.toString(value);
    }

    private static double or(@Nullable final Double value, final double fallback) {
        return value != null ? value : fallback;
    }

    /** JavaScript's Math.trunc. */
    private static double trunc(final double value) {
        return value < 0 ? Math.ceil(value) : Math.floor(value);
    }

    private static PowerSource buildEngine(final EngineSpec spec) {
        return new PowerSource(
            spec.id,
            spec.name,
            PowerGroup.ENGINES,
            spec.unlock,
            spec.blurb,
            List.of(
                new PowerSetting.Select(
                    "fuel",
                    "Fuel",
                    PowerData.fuelOptions(spec.fuels),
                    spec.defaultFuel != null ? spec.defaultFuel : spec.fuels.isEmpty() ? "" : spec.fuels.get(0).name),
                new PowerSetting.Toggle("boost", "Oxygen boost", false)),
            read -> computeEngine(spec, read));
    }

    private static PowerModel computeEngine(final EngineSpec spec, final SettingsReader read) {
        final Fuel fuel = PowerData.findFuel(spec.fuels, read.select("fuel"));
        final boolean boost = read.on("boost");
        final double euPerLiter = or(fuel.euPerLiter, 0);
        final boolean blocked = spec.unboostedFuelCap != null && !boost && euPerLiter > spec.unboostedFuelCap;
        final double output = blocked ? 0 : boost ? spec.boostedOutput : spec.baseOutput;
        // Whole litres per tick: floor(nominal / fuel value), against twice the nominal when boosted
        // (MTELargeCombustionEngine, MTEExtremeCombustionEngine, MTELargeSemifluidGenerator). A fuel that does not
        // divide it burns less.
        double fuelPerTick = 0;
        if (euPerLiter > 0 && output > 0) {
            fuelPerTick = Math.floor(((boost ? 2 : 1) * spec.baseOutput) / euPerLiter);
            final double boostedFuelValue = Math.floor(euPerLiter * 1.5);
            if (boost && spec.weightedExtraLitre && boostedFuelValue * 2 > spec.boostedOutput) {
                final double ratio = spec.boostedOutput / boostedFuelValue;
                fuelPerTick += ratio - trunc(ratio);
            }
        }
        final double effectiveEu = fuelPerTick > 0 ? output / fuelPerTick : 0;

        // 1 L of lubricant per gate, 2 L while boosted, in every engine class.
        final double lubricantPerSecond = GATE_72_PER_SECOND * (boost ? 2 : 1);
        final List<Flow> inputs = new ArrayList<>();
        inputs.add(liters(fuel.name, fuelPerTick * 20));
        inputs.add(liters("Lubricant", lubricantPerSecond));
        if (boost) {
            inputs.add(liters(spec.booster, spec.boosterPerSecond));
        }
        return new PowerModel(
            output,
            inputs,
            List.of(),
            List.of(stat("EU per L", formatAmount(effectiveEu))),
            blocked ? List.of(fuel.name + " is over " + js(spec.unboostedFuelCap) + " EU/L and needs the oxygen boost.")
                : List.of());
    }

    /**
     * Large Rocket Engine (GT++ MTELargeRocketEngine). One burn feeds 21 ticks (freeFuelTicks = 20) but
     * setEUProduction spreads its energy over 20, so a steady R L/s burns 1.05 R per burn: P = fuel EU/L x 1.05 R /
     * 20. Output falls off by cube roots past 30,000 and 80,000; liquid hydrogen boost meters that falloff on a third
     * of the fuel and triples the result, 3 f(P/3). Air is euProduction/100 per tick; CO2 is consumed as the lubricant
     * (1 L per gate, 3 L boosted); the engine outputs no fluid.
     */
    private static double rocketFalloff(final double energy) {
        if (energy <= 30000) {
            return energy;
        }
        return energy * Math.cbrt(30000 / energy) * (energy >= 80000 ? Math.cbrt(80000 / energy) : 1);
    }

    private static PowerSource largeRocketEngine() {
        final List<Fuel> rocketFuels = PowerData.get().rocketFuels;
        return new PowerSource(
            "large-rocket-engine",
            "Large Rocket Engine",
            PowerGroup.ENGINES,
            "IV",
            "Rocket fuel; falls off past its knees.",
            List.of(
                new PowerSetting.Select(
                    "fuel",
                    "Fuel",
                    PowerData.fuelOptions(rocketFuels),
                    rocketFuels.isEmpty() ? "" : rocketFuels.get(0).name),
                new PowerSetting.Number("throttle", "Fuel rate", 1, 4000, 1, 500, "L/s", null),
                new PowerSetting.Toggle("boost", "Liquid hydrogen boost", false)),
            read -> {
                final Fuel fuel = PowerData.findFuel(rocketFuels, read.select("fuel"));
                final double throttle = read.number("throttle");
                final boolean boost = read.on("boost");
                // The table carries the tag's fuel value x3, as consumeFuel applies it.
                final double euPerLiter = or(fuel.euPerLiter, 0);
                final double perLiterPerSecond = (euPerLiter * 1.05) / 20;
                final double power = throttle * perLiterPerSecond;
                final double kneeFactor = boost ? 3 : 1;
                final double knee1 = (30000 * kneeFactor) / perLiterPerSecond;
                final double knee2 = (80000 * kneeFactor) / perLiterPerSecond;
                // euProduction is what the game meters air and hydrogen against; getMaxEfficiency returns it, so the
                // output is 16384 x euProduction / 10000.
                final double euProduction = boost ? 3 * rocketFalloff(power / 3) : rocketFalloff(power);
                final double euPerTick = Math.max(0, 1.6384 * euProduction);

                final List<Flow> inputs = new ArrayList<>();
                inputs.add(liters(fuel.name, throttle));
                // aAirToConsume = euProduction / 100 per tick.
                inputs.add(liters("Air", (euProduction / 100) * 20));
                // consumeCO2 at the mRuntime % 72 gate.
                inputs.add(liters("Carbon Dioxide", kneeFactor * GATE_72_PER_SECOND));
                if (boost) {
                    // consumeLOH: 3 x euProduction / 1000 L once per 21-tick burn.
                    inputs.add(liters("Liquid Hydrogen", ((3 * euProduction) / 1000) * (20.0 / 21)));
                }
                return new PowerModel(
                    euPerTick,
                    inputs,
                    List.of(),
                    List.of(
                        stat("EU per L", formatAmount(throttle > 0 ? euPerTick / (throttle / 20) : 0)),
                        stat("Power knees", formatAmount(knee1) + " / " + formatAmount(knee2) + " L/s")));
            });
    }

    /**
     * Universal Chemical Fuel Engine (Good Generator): burns almost any fuel with Combustion Promoter; efficiency 1.5
     * x e^(-C / promoterRatio), C from the fuel table.
     */
    private static PowerSource universalChemicalFuelEngine() {
        final List<Fuel> ucfeFuels = PowerData.get().ucfeFuels;
        return new PowerSource(
            "universal-chemical-fuel-engine",
            "Universal Chemical Fuel Engine",
            PowerGroup.ENGINES,
            "LuV",
            "Any fuel plus combustion promoter.",
            List.of(
                new PowerSetting.Select("fuel", "Fuel", PowerData.fuelOptions(ucfeFuels), "RP-1 (red)"),
                new PowerSetting.Number("flow", "Fuel rate", 1, 100000, 1, 500, "L/s", null),
                new PowerSetting.Number("promoterRatio", "Promoter per fuel", 0.01, 2, 0.01, 0.2)),
            read -> {
                final Fuel fuel = PowerData.findFuel(ucfeFuels, read.select("fuel"));
                final double flow = read.number("flow");
                final double ratio = read.number("promoterRatio");
                final double coefficient = or(fuel.promoterCoefficient, 0.04);
                final double efficiency = 1.5 * Math.exp(-coefficient / ratio);
                final double euPerTick = (flow * or(fuel.euPerLiter, 0) * efficiency) / 20;
                return new PowerModel(
                    euPerTick,
                    List.of(liters(fuel.name, flow), liters("Combustion Promoter", flow * ratio)),
                    List.of(),
                    List.of(stat("Efficiency", percent(efficiency))));
            });
    }

    /**
     * Large Neutralization Engine (GT++): acids to EU at rate x density, a hydroxide base multiplying the power at its
     * own drink rate, robot arms boosting toxic-residue decay at the cost of a loss chance. Residue is a rare
     * accumulation, not a steady flow, so it stays in the stats.
     */
    private static PowerSource largeNeutralizationEngine() {
        final PowerData data = PowerData.get();
        final List<PowerSetting.Option> structures = new ArrayList<>();
        for (final PowerData.LneStructureTier entry : data.lneStructureTiers) {
            structures.add(new PowerSetting.Option(entry.name, entry.name));
        }
        final List<PowerSetting.Option> bases = new ArrayList<>();
        bases.add(new PowerSetting.Option("None", "None"));
        for (final PowerData.LneBase entry : data.lneBases) {
            bases.add(new PowerSetting.Option(entry.name, entry.name + " (x" + js(entry.multiplier) + ")"));
        }
        final List<PowerSetting.Option> armTiers = new ArrayList<>();
        for (final PowerData.LneRobotArm entry : data.lneRobotArms) {
            armTiers.add(new PowerSetting.Option(entry.name, entry.name.replaceFirst("^Amount \\((.+)\\)$", "$1")));
        }
        return new PowerSource(
            "large-neutralization-engine",
            "Large Neutralization Engine",
            PowerGroup.ENGINES,
            "EV",
            "Neutralizes acids for power.",
            List.of(
                new PowerSetting.Select(
                    "structure",
                    "Structure",
                    structures,
                    data.lneStructureTiers.isEmpty() ? "T1" : data.lneStructureTiers.get(0).name),
                new PowerSetting.Select("fuel", "Acid", PowerData.fuelOptions(data.chemFuels), "Molten Redstone"),
                // Per TICK, like the game's own fluid-use dial (maxFluidUse) and the workbook's rate cell:
                // mEUt = fuel value x litres per tick.
                new PowerSetting.Number("rate", "Acid rate", 1, 100_000, 1, 50, "L/t", null),
                new PowerSetting.Select("base", "Base", bases, "None"),
                new PowerSetting.Number("arms", "Robot arms", 0, 16, 1, 0),
                new PowerSetting.Select("armTier", "Arm tier", armTiers, "Amount (HV)")),
            read -> computeNeutralization(data, read));
    }

    private static PowerModel computeNeutralization(final PowerData data, final SettingsReader read) {
        final String structureName = read.select("structure");
        PowerData.LneStructureTier structure = data.lneStructureTiers.get(0);
        for (final PowerData.LneStructureTier entry : data.lneStructureTiers) {
            if (entry.name.equals(structureName)) {
                structure = entry;
                break;
            }
        }
        final Fuel fuel = PowerData.findFuel(data.chemFuels, read.select("fuel"));
        final double rate = read.number("rate");
        final String baseName = read.select("base");
        PowerData.LneBase base = null;
        for (final PowerData.LneBase entry : data.lneBases) {
            if (entry.name.equals(baseName)) {
                base = entry;
                break;
            }
        }
        final double arms = Math.min(16, read.number("arms"));
        final String armTierName = read.select("armTier");
        double armTier = 2;
        for (final PowerData.LneRobotArm entry : data.lneRobotArms) {
            if (entry.name.equals(armTierName)) {
                armTier = entry.tier;
                break;
            }
        }
        final double density = or(fuel.euPerLiter, 0);
        final double multiplier = base != null ? base.multiplier : 1;
        final double euPerTick = rate * density * multiplier;

        // MTELargeNeutralizationEngine, tier = ROBOT_ARMS index (LV 0): decay boost sqrt(arms) x 1.2^tier (1.4 past
        // IV); one arm lost when a roll of 45 x (tier + 2) each minute lands under the arm count. Residue is the
        // workbook's floor/ceil of the ^12.5.
        final double decayBoost = arms == 0 ? 1
            : Math.sqrt(arms) * (armTier <= 4 ? Math.pow(1.2, armTier) : Math.pow(1.4, armTier));
        final double lossChance = arms / (45 * (armTier + 2));
        final double residueCore = (0.05 * Math.pow(density, 0.8) * rate) / (structure.baseDecay * decayBoost);
        final double residueMedian = Math.floor(Math.pow(residueCore, 12.5));
        final double residueMax = Math.ceil(Math.pow(residueCore * 1.3, 12.5));
        // The random walk targets 0.7-1.3 uniformly, so residue arrives at an average of exactly 0.05 x density^0.8 x
        // rate per tick. Decay scales with the stored amount (^0.08), so its ceiling is at a full tank: baseDecay x
        // armBoost x capacity^0.08. A positive net there means no equilibrium fits inside the tank and the engine
        // eventually explodes.
        final double residuePerTick = 0.05 * Math.pow(density, 0.8) * rate;
        final double decayAtFull = structure.baseDecay * decayBoost * Math.pow(structure.residueCapacity, 0.08);
        final double netAtFull = residuePerTick - decayAtFull;

        final List<Flow> inputs = new ArrayList<>();
        inputs.add(liters(fuel.name, rate * 20));
        if (base != null) {
            // useBooster: one hydroxide dust lasts boostTicks, then one tick to reload, so a dust per boostTicks + 1.
            inputs.add(items(base.name + " Dust", 20 / (base.boostTicks + 1)));
        }
        final List<Stat> stats = new ArrayList<>();
        stats.add(stat("EU per L", formatAmount(density * multiplier)));
        stats
            .add(stat("Toxic residue", formatAmount(residueMedian) + " median / " + formatAmount(residueMax) + " max"));
        stats.add(stat("Avg residue", formatAmount(residuePerTick) + "/t"));
        stats.add(stat("At full tank", (netAtFull > 0 ? "+" : "") + formatAmount(netAtFull) + "/t"));
        stats.add(stat("Residue capacity", formatAmount(structure.residueCapacity)));
        if (arms > 0) {
            stats.add(stat("Decay boost", "x" + formatAmount(decayBoost)));
            stats.add(stat("Avg lifespan", formatAmount(Math.floor(1 / lossChance)) + " min"));
        }
        return new PowerModel(
            euPerTick,
            inputs,
            List.of(),
            stats,
            netAtFull > 0
                ? List.of("Residue builds faster than it decays even at a full tank. The engine will explode.")
                : List.of());
    }

    private static List<PowerSource> sources;

    public static synchronized List<PowerSource> sources() {
        if (sources == null) {
            final List<PowerSource> all = new ArrayList<>();
            for (final EngineSpec spec : engineSpecs()) all.add(buildEngine(spec));
            all.add(largeRocketEngine());
            all.add(largeNeutralizationEngine());
            all.add(universalChemicalFuelEngine());
            sources = List.copyOf(all);
        }
        return sources;
    }

    private Engines() {}
}

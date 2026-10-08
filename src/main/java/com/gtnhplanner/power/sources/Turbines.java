package com.gtnhplanner.power.sources;

import static com.gtnhplanner.power.sources.Helpers.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleUnaryOperator;

import javax.annotation.Nullable;

import com.gtnhplanner.power.PowerData;
import com.gtnhplanner.power.PowerData.FusionRecipe;
import com.gtnhplanner.power.PowerData.Rotor;
import com.gtnhplanner.power.PowerData.RotorClass;
import com.gtnhplanner.power.PowerGroup;
import com.gtnhplanner.power.PowerModel;
import com.gtnhplanner.power.PowerModel.Flow;
import com.gtnhplanner.power.PowerResources;
import com.gtnhplanner.power.PowerSetting;
import com.gtnhplanner.power.PowerSetting.Condition;
import com.gtnhplanner.power.PowerSetting.Option;
import com.gtnhplanner.power.PowerSource;

/**
 * Port of the website's sources/turbines.ts. Large and XL Turbo turbines, each following its fluidIntoPower in GT5U
 * (MTELargeTurbine* and MTEXLTurbine*): the fuel EU of the flow, scaled by
 *
 * <pre>
 *   1 - |flow - opt| / (opt x penalty)
 * </pre>
 *
 * (penalty 1 below optimal, per-class above it), then by the rotor efficiency, truncating after each step in float
 * like the Java. Per-class caps, weak-rotor branches and lifespans are in the website's docs/power-planner-math.md.
 * Steam and gas flows are litres per TICK (the game's own tooltip unit); plasma flows are litres per second.
 *
 * <p>
 * The website computes in JavaScript doubles and rounds to float with {@code Math.fround} at chosen steps; this port
 * keeps every value a double and rounds at the same steps ({@link #f32}), so intermediate sums and products see the
 * same precision as on the website.
 */
public final class Turbines {

    private enum TurbineClass {
        STEAM,
        GAS,
        PLASMA
    }

    /**
     * @param steamGrade        Steam machines: the grade burned, or "select" for the XL's grade knob; null otherwise.
     * @param steamGradeOptions For "select": which grades this XL machine accepts (plain + dense).
     */
    private record TurbineSpec(String id, String name, String unlock, String blurb, TurbineClass turbineClass,
        boolean xl, @Nullable String steamGrade, @Nullable List<String> steamGradeOptions) {

        TurbineSpec(final String id, final String name, final String unlock, final String blurb,
            final TurbineClass turbineClass, final boolean xl) {
            this(id, name, unlock, blurb, turbineClass, xl, null, null);
        }
    }

    private static final Map<String, String> STEAM_EXHAUST = Map.of(
        "SC Steam",
        "SH Steam",
        "SH Steam",
        "Steam",
        "Dense SC Steam",
        "Dense SH Steam",
        "Dense SH Steam",
        "Dense Steam");

    /**
     * The de-powered fluid a plasma turbine returns, 1 L per 1 L of plasma. MTELargeTurbinePlasma strips the
     * "plasma." fluid-name prefix and takes the plain fluid if the registry has one, else the molten form - so every
     * plasma exhausts, not only the fusion-made ones. The fusion table's decay column wins where it exists (same rule,
     * already spelled out); the rest mirror the registry fallback against our own resource map.
     */
    @Nullable
    private static String plasmaExhaust(final String plasmaName) {
        FusionRecipe recipe = null;
        for (final FusionRecipe entry : PowerData.get().fusionRecipes) {
            if (entry.name.equals(plasmaName)) {
                recipe = entry;
                break;
            }
        }
        if (recipe != null && recipe.decayOutput != null && !recipe.decayOutput.isEmpty()) {
            return !"None".equals(recipe.decayOutput) ? recipe.decayOutput : null;
        }
        final String base = plasmaName.endsWith(" Plasma")
            ? plasmaName.substring(0, plasmaName.length() - " Plasma".length())
            : plasmaName;
        if (base.equals(plasmaName)) {
            return null;
        }
        if (PowerResources.resolve(base) != null) {
            return base;
        }
        final String molten = "Molten " + base;
        if (PowerResources.resolve(molten) != null) {
            return molten;
        }
        // Neither form is a registered fluid: the game consumes the plasma and
        // outputs nothing (the null-check around addOutputPartial).
        return null;
    }

    private static RotorClass classData(final Rotor rotor, final TurbineClass turbineClass) {
        return switch (turbineClass) {
            case STEAM -> rotor.steam;
            case GAS -> rotor.gas;
            case PLASMA -> rotor.plasma;
        };
    }

    private static double pickLadder(@Nullable final Double[] values, final int sizeIndex) {
        if (values == null || sizeIndex < 0 || sizeIndex >= values.length) return 0;
        final Double value = values[sizeIndex];
        return value != null ? value : 0;
    }

    /** The turbines compute in Java float; every step below rounds like it ({@code Math.fround}). */
    private static double f32(final double value) {
        return (float) value;
    }

    /** {@code Math.trunc}: toward zero, keeping infinities and NaN. */
    private static double trunc(final double value) {
        return value < 0 ? Math.ceil(value) : Math.floor(value);
    }

    /** JavaScript's {@code Math.round}: halves toward positive infinity, on doubles. */
    private static double jsRound(final double value) {
        final double floor = Math.floor(value);
        return value - floor >= 0.5 ? floor + 1 : floor;
    }

    /** getBaseDamage of ToolTurbineSmall, Normal, Large and Huge. */
    private static final double[] ROTOR_BASE_DAMAGE = { 0, 2.5, 5, 7.5 };

    /**
     * TurbineStatCalculator.getBaseEfficiency in float, 0.5 + (0.5 + base damage + tool quality) x 0.1, rebuilt from
     * the ladder's decimal value so products and the SC's int cast see the game's exact float.
     */
    private static double javaBaseEfficiency(final double tightEfficiency, final int sizeIndex) {
        final double baseDamage = sizeIndex >= 0 && sizeIndex < ROTOR_BASE_DAMAGE.length ? ROTOR_BASE_DAMAGE[sizeIndex]
            : 0;
        final double quality = jsRound((tightEfficiency - 0.5) / 0.1 - 0.5 - baseDamage);
        return f32(0.5 + f32(f32(0.5 + f32(baseDamage + quality)) * f32(0.1)));
    }

    /** GTUtility/MathUtils.safeInt clamp outputs and flows at the top voltage. */
    private static final double INT_CAP = 2_147_483_640;

    /** An integer amount times a float factor, truncated: {@code tEU *= efficiency} in the Java. */
    private static double scale(final double amount, final double factor) {
        return Math.min(INT_CAP, trunc(f32(f32(amount) * f32(factor))));
    }

    /**
     * getOverflowEfficiency: the linear loss off optimal. Below optimal every class loses 1:1; above it the loss
     * divides by the class's overflow divisor (1 for SC steam and every XL, so both sides cost the same).
     */
    private static double flowEfficiency(final double flow, final double optimal, final double overDivisor) {
        if (flow > optimal) {
            return f32(1 - f32((flow - optimal) / f32(f32(optimal) * overDivisor)));
        }
        return f32(1 - Math.abs(f32(f32(f32(flow) - f32(optimal)) / f32(optimal))));
    }

    /**
     * Rotor wear per damage roll (getDamageToComponent), averaged over its dice. Steam and HP: a loose rotor skips 1
     * roll in 4. SC (MTELargeTurbineSCSteam): 2 tight, a coin flip of 0 or 1 loose. MTEXLTurbineGas skips 1 in 4 in
     * either fitting. Gas and plasma otherwise take 1.
     */
    private static double damagePerRoll(final TurbineSpec spec, final String fuelName, final boolean tight) {
        if (spec.turbineClass() == TurbineClass.GAS) {
            return spec.xl() ? 0.75 : 1;
        }
        if (spec.turbineClass() == TurbineClass.PLASMA) {
            return 1;
        }
        if (!spec.xl() && "SC Steam".equals(fuelName)) {
            return tight ? 2 : 0.5;
        }
        return tight ? 1 : 0.75;
    }

    /**
     * Seconds until one rotor breaks. A damage roll comes every 1002 ticks (mRuntime++ > 1000) and lands half the
     * time, for min(EU/5, EU^0.6) truncated (MTEMultiBlockBase.doRandomMaintenanceDamage; the exponent is the float
     * damageFactorHigh, so exact powers like 243^0.6 land on 27). The XL rolls each of its rotors on a fifth of its
     * output (MTEXLTurbineBase.damageTurbine).
     *
     * <p>
     * StrictMath.pow is fdlibm's pow, as JavaScript engines compute {@code Math.pow}.
     */
    private static double rotorLifespanSeconds(final boolean xl, final double euPerTick, final double durability,
        final double perRoll) {
        final double eu = xl ? Math.floor(euPerTick / 5) : euPerTick;
        final double linear = xl ? eu / 5 : Math.floor(eu / 5);
        final double damage = Math.floor(Math.min(linear, StrictMath.pow(eu, f32(0.6)))) * perRoll;
        return damage > 0 ? ((durability / damage) * 2 * 1002) / 20 : Double.POSITIVE_INFINITY;
    }

    /**
     * One turbine's operating point, flows in the card's own unit.
     *
     * @param steamPerTick XL dense steam: the steam-equivalent litres per tick actually used; null otherwise.
     */
    private record TurbineRun(double optimal, double flow, double maxFlow, double euPerTick,
        @Nullable Double steamPerTick, double efficiency, @Nullable String warning) {

        TurbineRun(final double optimal, final double flow, final double maxFlow, final double euPerTick,
            final double efficiency) {
            this(optimal, flow, maxFlow, euPerTick, null, efficiency, null);
        }
    }

    private static TurbineRun idle(final double optimal, final double efficiency, final String warning) {
        return new TurbineRun(optimal, 0, 0, 0, null, efficiency, warning);
    }

    /** {@code wanted ?? optimal}. */
    private static double orOptimal(@Nullable final Double wanted, final double optimal) {
        return wanted != null ? wanted : optimal;
    }

    /**
     * MTELargeTurbineSteam / HPSteam / SCSteam. {@code realOpt} is the rotor's steam flow (tight or loose) in L/t.
     * Steam pays 0.5 EU/L, SH and SC 1 EU/L.
     */
    private static TurbineRun largeSteamRun(final String grade, final double realOpt, final double overflowTier,
        final double eff, final double baseEfficiency, @Nullable final Double wanted) {
        final double opt = f32(realOpt);
        if ("SC Steam".equals(grade)) {
            // useLegacyEfficiencyScaling: the base efficiency is cast to int, and 0
            // stops the machine, so rotors under 100% never run here.
            if (trunc(f32(baseEfficiency)) <= 0) {
                return idle(
                    Math.floor(opt),
                    eff,
                    "Rotor base efficiency is under 100%, so the SC turbine will not run it.");
            }
            final double maxFlow = Math.min(INT_CAP, Math.floor(opt * 1.25));
            final double optimal = Math.min(Math.floor(opt), maxFlow);
            final double flow = Math.min(orOptimal(wanted, optimal), maxFlow);
            final double euPerTick = flow == opt ? scale(flow, eff)
                : Math.max(1, scale(f32(f32(flow) * flowEfficiency(flow, opt, 1)), eff));
            return new TurbineRun(optimal, flow, maxFlow, euPerTick, eff);
        }
        final boolean sh = "SH Steam".equals(grade);
        final double optimal = Math.floor(opt);
        final double maxFlow = Math.min(INT_CAP, Math.floor(opt * (f32(0.5 * overflowTier) + (sh ? 1.5 : 1))));
        final double flow = Math.min(orOptimal(wanted, optimal), maxFlow);
        final DoubleUnaryOperator euFrom = steam -> sh ? scale(steam, eff)
            : trunc(f32(f32(f32(steam) * f32(eff)) * 0.5));
        final double euPerTick = flow == optimal ? euFrom.applyAsDouble(flow)
            : Math
                .max(1, euFrom.applyAsDouble(scale(flow, flowEfficiency(flow, optimal, overflowTier + (sh ? 2 : 1)))));
        return new TurbineRun(optimal, flow, maxFlow, euPerTick, eff);
    }

    /**
     * MTEXLTurbineSteam / HPSteam / SCSteam: sixteen rotors' flow, a 1.25x cap and the same loss either side of
     * optimal, measured on the unrounded optimum. Dense steam is 1000 steam-equivalent litres per litre: the turbine
     * takes whole dense litres (up to ceil(cap / 1000)) but uses only up to the cap.
     */
    private static TurbineRun xlSteamRun(final String grade, final double rotorFlow, final double eff,
        @Nullable final Double wanted) {
        final boolean dense = grade.startsWith("Dense");
        final boolean plain = "Steam".equals(grade) || "Dense Steam".equals(grade);
        final double steamOpt = f32(16 * f32(rotorFlow));
        final double steamCap = Math.min(INT_CAP, Math.floor(steamOpt * 1.25));
        final DoubleUnaryOperator euFor = steam -> {
            final double raw = plain ? trunc(f32(f32(steam) * 0.5)) : steam;
            if (steam == steamOpt) {
                return scale(raw, eff);
            }
            return Math.max(1, scale(scale(raw, flowEfficiency(steam, steamOpt, 1)), eff));
        };
        if (!dense) {
            final double optimal = Math.min(Math.floor(steamOpt), steamCap);
            final double flow = Math.min(orOptimal(wanted, optimal), steamCap);
            return new TurbineRun(optimal, flow, steamCap, euFor.applyAsDouble(flow), eff);
        }
        final DoubleUnaryOperator steamFor = litres -> Math.min(litres * 1000, steamCap);
        final double below = Math.floor(steamOpt / 1000);
        final double above = Math.ceil(steamOpt / 1000);
        // The whole-litre feed that makes the most EU: usually the one above, since
        // overshooting the optimum costs far less than falling short.
        final double optimal = below >= 1
            && euFor.applyAsDouble(steamFor.applyAsDouble(below)) >= euFor.applyAsDouble(steamFor.applyAsDouble(above))
                ? below
                : above;
        final double maxFlow = Math.ceil(steamCap / 1000);
        final double flow = Math.min(orOptimal(wanted, optimal), maxFlow);
        final double steamPerTick = steamFor.applyAsDouble(flow);
        return new TurbineRun(optimal, flow, maxFlow, euFor.applyAsDouble(steamPerTick), steamPerTick, eff, null);
    }

    /**
     * MTELargeTurbineGas and MTEXLTurbineGas. A rotor whose tight optimal EU/t is under one litre of the fuel burns
     * 1 L/t and makes exactly that, in either fitting; the XL makes one rotor's worth, not sixteen. On the Large, an
     * optimum that truncates to 0 L/t makes nothing.
     */
    private static TurbineRun gasRun(final boolean xl, final double fuelEu, final double rotorFlow,
        final double tightRotorFlow, final double eff, final double baseEfficiency, final double overflowTier,
        @Nullable final Double wanted) {
        final double rotorEuPerTick = f32(f32(tightRotorFlow) * f32(baseEfficiency));
        if (rotorEuPerTick < fuelEu) {
            final double euPerTick = trunc(rotorEuPerTick);
            return new TurbineRun(
                1,
                1,
                1,
                euPerTick,
                null,
                baseEfficiency,
                "Rotor too weak for this fuel: it burns 1 L/t for " + (xl ? "one rotor's " : "")
                    + formatAmount(euPerTick)
                    + " EU/t.");
        }
        final double perRotor = f32(f32(rotorFlow) / fuelEu);
        final double optimal = Math.min(INT_CAP, trunc(xl ? f32(16 * perRotor) : perRotor));
        if (optimal <= 0) {
            return idle(0, eff, "Optimal flow rounds down to 0 L/t on this fuel, so the turbine makes no power.");
        }
        final double maxFlow = Math.min(INT_CAP, trunc(f32(f32(optimal) * f32(xl ? 1.25 : 1.5 * overflowTier))));
        final double flow = Math.min(orOptimal(wanted, optimal), maxFlow);
        double raw = Math.min(INT_CAP, flow * fuelEu);
        if (flow != optimal) {
            raw = scale(raw, flowEfficiency(flow, optimal, xl ? 1 : overflowTier * 3 - 1));
        }
        return new TurbineRun(optimal, flow, maxFlow, scale(raw, eff), eff);
    }

    /**
     * MTELargeTurbinePlasma rounds its optimum up (Math.ceil); MTEXLTurbinePlasma truncates it, so a weak rotor on a
     * dense plasma can take nothing. The XL also derates plasmas weaker than one rotor's optimal EU/t: min(1, (fuelEU
     * x 0.005)^2 / euAtOptimal).
     */
    private static TurbineRun plasmaRun(final boolean xl, final double fuelEu, final double rotorFlow, final double eff,
        final double euAtOptimal, final double overflowTier, @Nullable final Double wanted) {
        final double flowF = f32(rotorFlow);
        final double optimal = Math
            .min(INT_CAP, xl ? trunc(f32(f32(16 * flowF) * 20) / fuelEu) : Math.ceil((flowF * 20) / fuelEu));
        // euPerTurbine is MathUtils.roundToClosestInt of one rotor's tight optimal EU/t.
        final double euPerTurbine = trunc(jsRound(euAtOptimal * 2) / 2);
        final boolean derated = xl && euPerTurbine > 0;
        double derate = 1;
        if (derated) {
            final double magic = f32(fuelEu * f32(0.005));
            derate = Math.min(1, f32(f32(magic * magic) / euPerTurbine));
        }
        if (optimal <= 0) {
            return idle(
                0,
                eff * derate,
                "Optimal flow rounds down to 0 L/s on this plasma, so the turbine makes no power.");
        }
        final double maxFlow = Math
            .min(INT_CAP, trunc(f32(f32(optimal) * (xl ? f32(1.25) : f32(f32(1.5 * overflowTier) + 1)))));
        final double flow = Math.min(orOptimal(wanted, optimal), maxFlow);
        double raw = Math.min(INT_CAP, trunc((fuelEu / 20) * flow));
        if (flow != optimal) {
            raw = xl ? trunc(raw * (1 - Math.abs(f32((flow - optimal) / f32(optimal)))))
                : scale(raw, flowEfficiency(flow, optimal, overflowTier * 3 + 1));
        }
        // The derate multiplies in float even at 1, so large outputs round like the Java's.
        final double euPerTick = derated ? scale(scale(raw, eff), derate) : scale(raw, eff);
        return new TurbineRun(optimal, flow, maxFlow, euPerTick, eff * derate);
    }

    private static final List<TurbineSpec> SPECS = List.of(
        new TurbineSpec(
            "large-steam-turbine",
            "Large Steam Turbine",
            "HV",
            "Steam in, EU out; the rotor decides.",
            TurbineClass.STEAM,
            false,
            "Steam",
            null),
        new TurbineSpec(
            "large-hp-steam-turbine",
            "Large HP Steam Turbine",
            "EV",
            "SH steam in; exhausts plain steam.",
            TurbineClass.STEAM,
            false,
            "SH Steam",
            null),
        new TurbineSpec(
            "large-sc-steam-turbine",
            "Large SC Steam Turbine",
            // GoodGenerator's assembler recipe: IV hull, LuV circuits.
            "LuV",
            "SC steam in; exhausts SH steam.",
            TurbineClass.STEAM,
            false,
            "SC Steam",
            null),
        new TurbineSpec(
            "xl-turbo-steam-turbine",
            "XL Turbo Steam Turbine",
            // The XL unlocks follow their controller's assembler recipe
            // (RecipesMachinesCustom): EV, IV, LuV, ZPM and ZPM circuits and power.
            "EV",
            "Sixteen steam turbines; dense too.",
            TurbineClass.STEAM,
            true,
            "select",
            List.of("Steam", "Dense Steam")),
        new TurbineSpec(
            "xl-turbo-hp-steam-turbine",
            "XL Turbo HP Steam Turbine",
            "IV",
            "Sixteen HP turbines; exhausts steam.",
            TurbineClass.STEAM,
            true,
            "select",
            List.of("SH Steam", "Dense SH Steam")),
        new TurbineSpec(
            "xl-turbo-sc-steam-turbine",
            "XL Turbo SC Steam Turbine",
            "ZPM",
            "Sixteen SC turbines; exhausts SH.",
            TurbineClass.STEAM,
            true,
            "select",
            List.of("SC Steam", "Dense SC Steam")),
        new TurbineSpec(
            "large-gas-turbine",
            "Large Gas Turbine",
            "EV",
            "Gas fuels at rotor efficiency.",
            TurbineClass.GAS,
            false),
        new TurbineSpec(
            "xl-turbo-gas-turbine",
            "XL Turbo Gas Turbine",
            "LuV",
            "Sixteen gas turbines in one.",
            TurbineClass.GAS,
            true),
        new TurbineSpec(
            "large-plasma-generator",
            "Large Plasma Generator",
            "LuV",
            "Plasma to EU and its cooled gas.",
            TurbineClass.PLASMA,
            false),
        new TurbineSpec(
            "xl-turbo-plasma-turbine",
            "XL Turbo Plasma Turbine",
            "ZPM",
            "Sixteen plasma turbines in one.",
            TurbineClass.PLASMA,
            true));

    private static PowerSource buildTurbine(final TurbineSpec spec) {
        final PowerData data = PowerData.get();
        final List<PowerSetting> settings = new ArrayList<>();
        final List<Option> rotors = new ArrayList<>();
        for (final Rotor rotor : data.rotors)
            rotors.add(new Option(rotor.name, rotor.name + " (" + rotor.unlock + ")"));
        settings.add(new PowerSetting.Select("rotor", "Rotor", rotors, spec.xl() ? "HSS-E" : "Carbon"));
        final List<Option> sizes = new ArrayList<>();
        for (final String name : PowerData.ROTOR_SIZE_NAMES) sizes.add(new Option(name, name));
        settings.add(new PowerSetting.Select("size", "Rotor size", sizes, spec.xl() ? "Huge" : "Normal"));
        settings.add(
            new PowerSetting.Select(
                "fitting",
                "Fitting",
                List.of(new Option("tight", "Tight"), new Option("loose", "Loose")),
                "tight"));
        if ("select".equals(spec.steamGrade())) {
            final List<Option> grades = new ArrayList<>();
            for (final Option option : PowerData.fuelOptions(data.steamGrades)) {
                if (spec.steamGradeOptions() == null || spec.steamGradeOptions()
                    .contains(option.key())) grades.add(option);
            }
            settings.add(
                new PowerSetting.Select(
                    "grade",
                    "Steam type",
                    grades,
                    grades.isEmpty() ? "Steam"
                        : grades.get(0)
                            .key()));
        } else if (spec.turbineClass() == TurbineClass.GAS) {
            settings.add(
                new PowerSetting.Select(
                    "fuel",
                    "Fuel",
                    PowerData.fuelOptions(spec.xl() ? data.gasFuelsXl : data.gasFuels),
                    // The XL hard-refuses benzene in the game (and Fox's XL fuel table
                    // matches), so its default is the sheet's own pick.
                    spec.xl() ? "Nitrobenzene" : "Benzene"));
        } else if (spec.turbineClass() == TurbineClass.PLASMA) {
            settings
                .add(new PowerSetting.Select("fuel", "Plasma", PowerData.fuelOptions(data.plasmas), "Helium Plasma"));
        }
        settings.add(
            new PowerSetting.Select(
                "flowMode",
                "Flow",
                List.of(new Option("optimal", "Optimal"), new Option("custom", "Custom")),
                "optimal"));
        settings.add(
            new PowerSetting.Number(
                "customFlow",
                "Custom flow",
                1,
                100_000_000,
                1,
                100,
                spec.turbineClass() == TurbineClass.PLASMA ? "L/s" : "L/t",
                new Condition("flowMode", "custom")));

        return new PowerSource(
            spec.id(),
            spec.name(),
            PowerGroup.TURBINES,
            spec.unlock(),
            spec.blurb(),
            settings,
            read -> {
                final Rotor rotor = data.findRotor(read.select("rotor"));
                final int sizeIndex = Math.max(0, PowerData.ROTOR_SIZE_NAMES.indexOf(read.select("size")));
                final boolean tight = !"loose".equals(read.select("fitting"));
                final RotorClass ladders = classData(rotor, spec.turbineClass());
                final double overflowTier = rotor.overflowTier;

                final String fuelName;
                final double fuelEu;
                if (spec.turbineClass() == TurbineClass.STEAM) {
                    fuelName = "select".equals(spec.steamGrade()) ? read.select("grade") : spec.steamGrade();
                    final Double euPerLiter = PowerData.findFuel(data.steamGrades, fuelName).euPerLiter;
                    fuelEu = euPerLiter != null ? euPerLiter : 0.5;
                } else {
                    fuelName = read.select("fuel");
                    final List<PowerData.Fuel> table = spec.turbineClass() == TurbineClass.GAS
                        ? spec.xl() ? data.gasFuelsXl : data.gasFuels
                        : data.plasmas;
                    final Double euPerLiter = PowerData.findFuel(table, fuelName).euPerLiter;
                    fuelEu = euPerLiter != null ? euPerLiter : 0;
                }

                // The tight value is getBaseEfficiency, which the SC and gas checks read in either fitting.
                final double baseEfficiency = javaBaseEfficiency(
                    pickLadder(ladders.efficiencyTight, sizeIndex),
                    sizeIndex);
                final double efficiency = tight ? baseEfficiency : pickLadder(ladders.efficiencyLoose, sizeIndex);
                final double rotorFlow = pickLadder(tight ? ladders.optimalTight : ladders.optimalLoose, sizeIndex);
                final boolean dense = fuelName.startsWith("Dense");
                final Double wanted = "custom".equals(read.select("flowMode")) ? read.number("customFlow") : null;

                final TurbineRun run;
                if (spec.turbineClass() == TurbineClass.STEAM) {
                    // Every steam grade runs the rotor's steam flow (SC included: MTELargeTurbineSCSteam
                    // uses getOptimalSteamFlow like HP); the XL is x16.
                    run = spec.xl() ? xlSteamRun(fuelName, rotorFlow, efficiency, wanted)
                        : largeSteamRun(fuelName, rotorFlow, overflowTier, efficiency, baseEfficiency, wanted);
                } else if (spec.turbineClass() == TurbineClass.GAS) {
                    run = gasRun(
                        spec.xl(),
                        fuelEu,
                        rotorFlow,
                        pickLadder(ladders.optimalTight, sizeIndex),
                        efficiency,
                        baseEfficiency,
                        overflowTier,
                        wanted);
                } else {
                    run = plasmaRun(
                        spec.xl(),
                        fuelEu,
                        rotorFlow,
                        efficiency,
                        pickLadder(ladders.euAtOptimalTight, sizeIndex),
                        overflowTier,
                        wanted);
                }
                final double euPerTick = run.euPerTick();
                final double flow = run.flow();
                final double maxFlow = run.maxFlow();

                final double lifespanSeconds = rotorLifespanSeconds(
                    spec.xl(),
                    euPerTick,
                    data.rotorDurability(rotor, sizeIndex),
                    damagePerRoll(spec, fuelName, tight));

                final double flowPerSecond = spec.turbineClass() == TurbineClass.PLASMA ? flow : flow * 20;
                final List<Flow> inputs = List.of(liters(fuelName, flowPerSecond));
                final List<Flow> outputs = new ArrayList<>();
                if (flow > 0 && spec.turbineClass() == TurbineClass.STEAM) {
                    final String exhaust = STEAM_EXHAUST.get(fuelName);
                    if (exhaust != null) {
                        // HP and SC return one litre of the next grade down per litre taken
                        // (dense litres for dense steam, even past the cap).
                        outputs.add(liters(exhaust, flowPerSecond));
                    } else {
                        // Plain steam condenses: MTELargeTurbineSteam returns distilled
                        // water at 1 L per 160 L of steam. The XL's dense-steam path uses
                        // its own 160.1 divisor on the steam-equivalent litres it used -
                        // the game's constant, not a typo.
                        final double steamEquivalent = dense
                            ? (run.steamPerTick() != null ? run.steamPerTick() : 0) * 20
                            : flowPerSecond;
                        outputs.add(liters("Distilled Water", steamEquivalent / (dense ? 160.1 : 160)));
                    }
                } else if (flow > 0 && spec.turbineClass() == TurbineClass.PLASMA) {
                    final String exhaust = plasmaExhaust(fuelName);
                    if (exhaust != null) {
                        outputs.add(liters(exhaust, flowPerSecond));
                    }
                }

                final List<String> warnings = new ArrayList<>();
                if (run.warning() != null) {
                    warnings.add(run.warning());
                } else if (wanted != null && wanted > maxFlow) {
                    warnings.add("Flow is capped at " + formatAmount(maxFlow) + "; the turbine will not take more.");
                }

                return new PowerModel(
                    euPerTick,
                    inputs,
                    outputs,
                    List.of(
                        stat("Efficiency", percent(run.efficiency())),
                        stat(
                            "Optimal flow",
                            formatAmount(run.optimal()) + " "
                                + (spec.turbineClass() == TurbineClass.PLASMA ? "L/s" : "L/t")),
                        stat("Rotor lifespan", lifespanHours(lifespanSeconds))),
                    warnings);
            });
    }

    /** Every turbine, in the website's order (turbineSources). */
    public static List<PowerSource> sources() {
        final List<PowerSource> all = new ArrayList<>();
        for (final TurbineSpec spec : SPECS) all.add(buildTurbine(spec));
        return List.copyOf(all);
    }

    private Turbines() {}
}

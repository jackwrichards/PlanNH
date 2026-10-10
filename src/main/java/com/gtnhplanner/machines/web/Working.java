package com.gtnhplanner.machines.web;

import java.util.ArrayList;
import java.util.List;

import com.gtnhplanner.machines.FormulaLine;
import com.gtnhplanner.machines.FormulaLine.Tone;

/**
 * A GregTech card's working, as lines for its gear sheet (the website's power working, power-working.ts, in the
 * formula style of its crop and tree cards): the supply, the coil's heat, the discount, the parallels it pays for, the
 * overclocks the rest buys, and what they make of the time, the draw and the recipes a second. Only the lines that
 * apply;
 * every number from the same functions the card runs on, so the sheet never disagrees with the board.
 */
public final class Working {

    private Working() {}

    /** Knob names the sheet colours: the card's tier and amps, its coil. */
    public static final String TIER = "tier", AMPS = "amps", COIL = "coil";

    public static List<FormulaLine> lines(final Web.Recipe recipe, final Web.Node node, final NodeMath.Result r) {
        final List<FormulaLine> out = new ArrayList<>();
        final Web.Recipe e = r.effectiveRecipe();
        final Overclock.Stats s = r.overclock();
        final boolean multiblock = Power.isMultiblock(e);
        final String tier = s.tier();
        final double voltage = Tiers.maxEuT(tier);
        final double amps = Power.amps(e, node);
        final double pool = voltage * amps;

        // Supply: the hatches' voltage and amps, or the machine's own.
        if (multiblock) out.add(
            FormulaLine.of(
                "supply",
                FormulaLine.number(pool) + " EU/t",
                FormulaLine.knob(FormulaLine.number(voltage), TIER),
                " V · ",
                FormulaLine.knob(FormulaLine.number(amps), AMPS),
                " A"));
        else out.add(
            FormulaLine.of(
                "supply",
                FormulaLine.number(pool) + " EU/t",
                FormulaLine.knob(tier, TIER),
                " machine" + (amps != 1 ? " · " + FormulaLine.number(amps) + " A" : "")));

        final Web.RuntimeVariant runtime = RuntimeCalculation.select(e, node);
        final double base = Math.abs(e.eut), baseTicks = e.durationTicks;
        if (runtime != null) {
            // GT's own overclock calculator gave these: normal steps, shown as such where they come out so.
            final int steps = s.overclockSteps();
            out.add(FormulaLine.of("overclocks", steps == 0 ? "none" : Integer.toString(steps)));
            final double halved = Math.max(1, Math.floor(baseTicks / Math.pow(2, steps)));
            out.add(
                halved == s.durationTicks() && steps > 0 ? FormulaLine.of(
                    "time",
                    ticks(s.durationTicks()),
                    FormulaLine.number(baseTicks) + " / 2",
                    FormulaLine.sup(Integer.toString(steps))) : FormulaLine.of("time", ticks(s.durationTicks())));
            out.add(
                base * Math.pow(4, steps) == Math.abs(s.eut()) && steps > 0
                    ? FormulaLine.of(
                        "draw",
                        FormulaLine.number(Math.abs(s.eut())) + " EU/t",
                        FormulaLine.number(base) + " · 4",
                        FormulaLine.sup(Integer.toString(steps)))
                    : FormulaLine.of("draw", FormulaLine.number(Math.abs(s.eut())) + " EU/t"));
            recipes(out, r, s);
            return out;
        }

        // Heat: the coil over the recipe's need buys perfect overclocks and a discount.
        final int ordinal = Power.effectiveVoltageOrdinal(e, node, Power.runTier(e, node));
        final Integer special = RecipeRules.specialValue(e);
        final RecipeRules.TierControl coil = RecipeRules.coilTierControl(e, node.coilTier);
        final MachineTable.Behaviour b = MachineTable.behaviour(e.machineType);
        if (Heat.isHeatOverclockMachine(e.machineType) && special != null
            && special >= 0
            && coil != null
            && coil.current().heat != null) {
            final double coilHeat = coil.current().heat
                * (b.heat != null && b.heat.coilHeatMultiplier() != null ? b.heat.coilHeatMultiplier() : 1);
            final boolean bonus = b.heat != null && Boolean.TRUE.equals(b.heat.voltageBonus());
            final double machineHeat = bonus ? coilHeat + 100 * Math.max(0, ordinal - 2) : coilHeat;
            final double excess = Math.max(0, machineHeat - special);
            final List<Object> terms = new ArrayList<>();
            terms.add(FormulaLine.knob(FormulaLine.number(coilHeat), COIL));
            if (bonus) terms.add(" + 100·" + Math.max(0, ordinal - 2));
            terms.add(" − " + FormulaLine.number(special));
            out.add(
                FormulaLine.of(
                    "heat",
                    FormulaLine.number(excess) + " K over",
                    machineHeat >= special ? Tone.GOOD : Tone.BAD,
                    terms.toArray()));
        }

        // Discount: the machine's share of the recipe's power, and the coil's 5% per 900 K.
        final double eutMultiplier = MachineEffects.eutMultiplier(e, node);
        final double heatDiscount = Heat.discount(e, node, tier, ordinal);
        if (Math.abs(eutMultiplier * heatDiscount - 1) > 1e-12) {
            final List<Object> terms = new ArrayList<>();
            if (eutMultiplier != 1) terms.add(FormulaLine.number(eutMultiplier));
            if (heatDiscount != 1) {
                if (!terms.isEmpty()) terms.add(" · ");
                terms.add("0.95");
                terms.add(FormulaLine.sup(Long.toString(Math.round(Math.log(heatDiscount) / Math.log(0.95)))));
            }
            out.add(
                FormulaLine.of(
                    "discount",
                    FormulaLine.number(eutMultiplier * heatDiscount * 100) + "% power",
                    terms.toArray()));
        }

        // Parallels: as many as the structure offers and the supply pays for, before any overclock.
        final double structural = MachineEffects.structuralParallels(e, node);
        final double single = Math.ceil(base * eutMultiplier * heatDiscount);
        if (structural > 1 || r.machineParallels() > 1) out.add(
            FormulaLine.of(
                "parallels",
                FormulaLine.number(r.machineParallels()),
                "min(" + FormulaLine.number(
                    structural) + ", ⌊" + FormulaLine.number(pool) + " / " + FormulaLine.number(single) + "⌋)"));

        // Overclocks: whole fours of the supply over the draw, a draw under 32 EU/t billed as 32.
        final double draw = Math.max(Math.ceil(base * eutMultiplier * heatDiscount * r.machineParallels()), 32);
        final int perfect = s.perfectOverclockSteps(), normal = s.overclockSteps() - perfect;
        out.add(
            FormulaLine.of(
                "overclocks",
                s.overclockSteps() == 0 ? "none"
                    : s.overclockSteps() + (perfect > 0 ? " (" + perfect + " perfect)" : ""),
                "⌊log4(" + FormulaLine.number(pool) + " / " + FormulaLine.number(draw) + ")⌋"));

        // Time: perfect steps divide by their factor, normal ones halve; the machine's speed; whole ticks.
        final List<Object> time = new ArrayList<>();
        time.add(FormulaLine.number(baseTicks));
        if (perfect > 0) {
            time.add(" / " + FormulaLine.number(s.perfectSpeedFactor()));
            time.add(FormulaLine.sup(Integer.toString(perfect)));
        }
        if (normal > 0) {
            time.add(" / 2");
            time.add(FormulaLine.sup(Integer.toString(normal)));
        }
        final double durationMultiplier = MachineEffects.durationMultiplier(e, node);
        if (durationMultiplier != 1) time.add(" · " + FormulaLine.number(durationMultiplier));
        // Nothing to work out (no overclock, no speed): the time alone.
        out.add(
            time.size() == 1 ? FormulaLine.of("time", ticks(s.durationTicks()))
                : FormulaLine.of("time", ticks(s.durationTicks()), time.toArray()));

        // Power: the recipe's draw, discounted, times the steps' factor, for each parallel.
        final List<Object> power = new ArrayList<>();
        power.add(FormulaLine.number(base));
        if (Math.abs(eutMultiplier * heatDiscount - 1) > 1e-12)
            power.add(" · " + FormulaLine.number(eutMultiplier * heatDiscount));
        if (perfect > 0) {
            power.add(" · " + FormulaLine.number(s.perfectEuFactor()));
            power.add(FormulaLine.sup(Integer.toString(perfect)));
        }
        if (normal > 0) {
            power.add(" · 4");
            power.add(FormulaLine.sup(Integer.toString(normal)));
        }
        if (r.machineParallels() > 1) power.add(" · " + FormulaLine.number(r.machineParallels()));
        out.add(
            FormulaLine.of(
                "draw",
                FormulaLine.number(Math.abs(s.eut()) * r.machineParallels()) + " EU/t",
                r.stalled() ? Tone.BAD : Tone.PLAIN,
                power.toArray()));
        recipes(out, r, s);
        return out;
    }

    /** Recipes a second: the parallels over the time. */
    private static void recipes(final List<FormulaLine> out, final NodeMath.Result r, final Overclock.Stats s) {
        out.add(
            FormulaLine.of(
                "recipes",
                FormulaLine.number(r.machineParallels() * 20 / s.durationTicks()) + "/s",
                FormulaLine.number(r.machineParallels()) + " · 20 / " + ticks(s.durationTicks())));
    }

    /** Ticks as the website writes them: whole, or 1/n under one. */
    private static String ticks(final double t) {
        return t >= 1 ? FormulaLine.number(t) + " t" : "1/" + Math.round(1 / t) + " t";
    }
}

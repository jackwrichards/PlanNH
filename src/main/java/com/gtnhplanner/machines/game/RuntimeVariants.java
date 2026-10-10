package com.gtnhplanner.machines.game;

import java.util.ArrayList;
import java.util.List;

import com.gtnhplanner.machines.web.Tiers;
import com.gtnhplanner.machines.web.Web;

import gregtech.api.util.OverclockCalculator;

/**
 * A recipe's runtime variants as the website's dataset exports them (its oracle's buildGtRuntimeCalculation, standard
 * profile): GT's own OverclockCalculator run once per tier from the recipe's own, at one amp of that tier and one
 * parallel. The website runs these for every recipe its curated table does not cover (machines/web
 * RuntimeCalculation); computed here from the game the player has.
 */
public final class RuntimeVariants {

    private RuntimeVariants() {}

    /** The oracle's voltages: GT's V up to UXV, then MAX as Long.MAX_VALUE. */
    private static final long[] VOLTAGES = { 8L, 32L, 128L, 512L, 2048L, 8192L, 32768L, 131072L, 524288L, 2097152L,
        8388608L, 33554432L, 134217728L, 536870912L, Long.MAX_VALUE };

    public static Web.RuntimeCalculation of(final double durationTicks, final double eut,
        final List<Web.Resource> outputs) {
        final Web.RuntimeCalculation calc = new Web.RuntimeCalculation();
        calc.oracleEligible = true;
        calc.strict = true;
        calc.variants = new ArrayList<>();
        final long recipeEut = Math.max(0L, (long) eut);
        final int duration = Math.max(1, (int) durationTicks);
        for (int tier = tierFor((long) eut); tier < VOLTAGES.length; tier++) {
            final OverclockCalculator calculator;
            try {
                calculator = new OverclockCalculator().setRecipeEUt(recipeEut)
                    .setEUt(VOLTAGES[tier])
                    .setDuration(duration)
                    .setParallel(1);
                calculator.calculate();
            } catch (final RuntimeException ignored) {
                continue; // the oracle leaves out a tier the calculator refuses
            }
            final int ticks = Math.max(1, calculator.getDuration());
            final long draw = Math.max(0L, calculator.getConsumption());
            final Web.RuntimeVariant v = new Web.RuntimeVariant();
            v.id = "tier-" + Tiers.NAMES[tier].toLowerCase(java.util.Locale.ROOT);
            v.label = Tiers.NAMES[tier];
            v.overclockTier = Tiers.NAMES[tier];
            v.durationTicks = ticks;
            v.eut = draw;
            v.parallel = 1.0;
            v.outputs = outputs;
            calc.variants.add(v);
        }
        calc.status = calc.variants.isEmpty() ? "missing" : "computed";
        return calc;
    }

    /** The oracle's voltageTierForEu: the first tier whose voltage carries |EU/t|. */
    private static int tierFor(final long eut) {
        final long value = Math.max(0L, Math.abs(eut));
        for (int tier = 0; tier < VOLTAGES.length; tier++) if (value <= VOLTAGES[tier]) return tier;
        return VOLTAGES.length - 1;
    }
}

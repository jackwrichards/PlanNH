package com.gtnhplanner.power.sources;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.ToDoubleFunction;

import com.gtnhplanner.power.PowerData;
import com.gtnhplanner.power.PowerModel;
import com.gtnhplanner.power.PowerModel.Flow;
import com.gtnhplanner.power.PowerModel.Stat;
import com.gtnhplanner.power.PowerSetting;

/** The website's sources/helpers.ts: flow and stat builders and its number formats. */
public final class Helpers {

    public static Flow liters(final String name, final double perSecond) {
        return new Flow(name, perSecond, PowerModel.Unit.L);
    }

    public static Flow items(final String name, final double perSecond) {
        return new Flow(name, perSecond, PowerModel.Unit.ITEM);
    }

    public static Stat stat(final String label, final String value) {
        return new Stat(label, value);
    }

    /**
     * {@code Intl.NumberFormat("en-US", { maximumFractionDigits: 2 })}: grouping commas, at most two decimals,
     * halves away from zero. Like ICU it rounds the shortest decimal that round-trips the double, not its exact
     * binary value: 2.485 (stored as 2.48499999...) shows as "2.49", as on the website.
     */
    public static String number(final double value) {
        if (Double.isNaN(value)) return "NaN";
        if (Double.isInfinite(value)) return value > 0 ? "∞" : "-∞";
        // Intl keeps the sign of a negative zero; BigDecimal has none.
        if (value == 0) return 1 / value < 0 ? "-0" : "0";
        final DecimalFormat format = new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(Locale.US));
        format.setRoundingMode(RoundingMode.HALF_UP);
        return format.format(new BigDecimal(Double.toString(value)));
    }

    /** {@link #number} with G and M past a billion and a million; "-" when not finite. */
    public static String formatAmount(final double value) {
        if (!Double.isFinite(value)) return "-";
        if (Math.abs(value) >= 1_000_000_000) return number(value / 1_000_000_000) + "G";
        if (Math.abs(value) >= 1_000_000) return number(value / 1_000_000) + "M";
        return number(value);
    }

    public static String percent(final double value) {
        return number(value * 100) + "%";
    }

    public record TierPower(double voltage, double ampLoss) {}

    /** Voltage and per-amp packet loss for a tier name, from the workbook's own ladder. */
    public static TierPower tierPower(final String tier) {
        for (final PowerData.SingleblockTier row : PowerData.get().singleblockTiers) {
            if (row.tier.equals(tier)) return new TierPower(row.voltage, row.ampLoss);
        }
        return new TierPower(32, 1);
    }

    /** A singleblock family's tier options and its efficiency at each. */
    public record FamilyTiers(List<PowerSetting.Option> options, ToDoubleFunction<String> efficiency) {

        public double efficiencyFor(final String tier) {
            return efficiency.applyAsDouble(tier);
        }
    }

    /** Tier options for a singleblock family: the tiers its efficiency ladder covers. */
    public static FamilyTiers familyTierOptions(final String family) {
        final PowerData data = PowerData.get();
        final Double[] ladder = data.singleblockEfficiency.getOrDefault(family, new Double[0]);
        final List<PowerSetting.Option> options = new ArrayList<>();
        for (int i = 0; i < data.singleblockTiers.size(); i++) {
            if (i < ladder.length && ladder[i] != null) {
                final String tier = data.singleblockTiers.get(i).tier;
                options.add(new PowerSetting.Option(tier, tier));
            }
        }
        return new FamilyTiers(options, tier -> {
            for (int i = 0; i < data.singleblockTiers.size(); i++) {
                if (data.singleblockTiers.get(i).tier.equals(tier)) {
                    return i < ladder.length && ladder[i] != null ? ladder[i] : 1;
                }
            }
            return 1;
        });
    }

    /** Rotor lifespan in whole hours, from seconds. */
    public static String lifespanHours(final double seconds) {
        if (!Double.isFinite(seconds) || seconds <= 0) return "-";
        final double hours = seconds / 3600;
        return hours >= 100 ? Math.round(hours) + "h" : number(hours) + "h";
    }

    private Helpers() {}
}

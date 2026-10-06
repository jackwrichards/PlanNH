package com.sbancuz.plannh.ui.theme;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Number and rate formatting for the board, following Factory Flow's rules so both apps read the same. */
public final class Fmt {

    /** The board's rate unit key. Rates are stored per second; this converts and labels them. */
    public enum RateUnit {

        TICK("/t", 1.0 / 20.0),
        SECOND("/s", 1.0),
        MINUTE("/min", 60.0),
        HOUR("/hr", 3600.0);

        public final String suffix;
        public final double perSecond;

        RateUnit(final String suffix, final double perSecond) {
            this.suffix = suffix;
            this.perSecond = perSecond;
        }

        public RateUnit next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    private static final String[] SUFFIXES = { "", "k", "M", "G", "T", "P" };

    private Fmt() {}

    /**
     * Compact number: exact 0 prints "0"; below 1 keeps 2 significant digits; otherwise 2 decimals (1 from 100,
     * none from 1000 before the suffix steps in) with trailing zeros dropped, then k, M, G... e.g. 6930 -> "6.93k".
     */
    public static String compact(final double value) {
        if (value == 0 || Double.isNaN(value)) return "0";
        final String sign = value < 0 ? "-" : "";
        double v = Math.abs(value);
        if (v < 1) return sign + strip(new BigDecimal(v).round(new java.math.MathContext(2)));
        int tier = 0;
        while (v >= 1000 && tier < SUFFIXES.length - 1) {
            v /= 1000;
            tier++;
        }
        // Rounding up across a boundary (999.999 -> 1000) moves to the next suffix.
        int decimals = v >= 100 ? 1 : 2;
        BigDecimal rounded = BigDecimal.valueOf(v)
            .setScale(decimals, RoundingMode.HALF_UP);
        if (rounded.compareTo(BigDecimal.valueOf(1000)) >= 0 && tier < SUFFIXES.length - 1) {
            tier++;
            rounded = BigDecimal.valueOf(v / 1000)
                .setScale(2, RoundingMode.HALF_UP);
        }
        return sign + strip(rounded) + SUFFIXES[tier];
    }

    /** A per-second rate in the given unit, e.g. "900/hr"; fluids get " L" before the unit ("180k L/hr"). */
    public static String rate(final double perSecond, final RateUnit unit, final boolean fluid) {
        final String number = compact(perSecond * unit.perSecond);
        return fluid ? number + " L" + unit.suffix : number + unit.suffix;
    }

    /**
     * Solved machine count, Factory Flow style: "0"; "<0.001"; 3 decimals under 1, 2 under 10, 1 under 100, trailing
     * zeros dropped; 100 or more rounds up to a whole number (you cannot build part of a machine at that scale).
     */
    public static String machines(final double count) {
        if (count <= 0) return "0";
        if (count < 0.001) return "<0.001";
        if (count >= 100) return Long.toString((long) Math.ceil(count - 1e-9));
        final int decimals = count < 1 ? 3 : count < 10 ? 2 : 1;
        return strip(
            BigDecimal.valueOf(count)
                .setScale(decimals, RoundingMode.HALF_UP));
    }

    /** EU/t with the compact rules, e.g. "6.93k". */
    public static String power(final double euPerTick) {
        return compact(euPerTick);
    }

    private static String strip(final BigDecimal value) {
        final BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0)
            .toPlainString() : stripped.toPlainString();
    }
}

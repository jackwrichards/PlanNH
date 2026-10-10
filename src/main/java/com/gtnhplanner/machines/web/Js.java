package com.gtnhplanner.machines.web;

import java.math.BigDecimal;

/** JavaScript's ways with numbers and strings where the website's maths lean on them. */
final class Js {

    private Js() {}

    /**
     * {@code String(n)}: whole numbers without a point, the rest at their shortest. Exponent forms (under 1e-6 or
     * from 1e21) are Java's, never met in recipe amounts.
     */
    static String str(final double n) {
        if (Double.isNaN(n)) return "NaN";
        if (Double.isInfinite(n)) return n > 0 ? "Infinity" : "-Infinity";
        if (n == Math.rint(n) && Math.abs(n) < 1e21) return Long.toString((long) n);
        final double abs = Math.abs(n);
        if (abs >= 1e-6 && abs < 1e21) return new BigDecimal(Double.toString(n)).stripTrailingZeros()
            .toPlainString();
        return Double.toString(n);
    }

    /** {@code Number(s)}: blank is 0, anything unparsable NaN. */
    static double number(@javax.annotation.Nullable final String s) {
        if (s == null) return 0;
        final String t = s.trim();
        if (t.isEmpty()) return 0;
        if (t.equals("Infinity") || t.equals("+Infinity")) return Double.POSITIVE_INFINITY;
        if (t.equals("-Infinity")) return Double.NEGATIVE_INFINITY;
        // Radix prefixes take no sign in JS: "-0x1A" is NaN.
        if (t.matches("0[xX][0-9a-fA-F]+")) return new java.math.BigInteger(t.substring(2), 16).doubleValue();
        if (t.matches("0[bB][01]+")) return new java.math.BigInteger(t.substring(2), 2).doubleValue();
        if (t.matches("0[oO][0-7]+")) return new java.math.BigInteger(t.substring(2), 8).doubleValue();
        if (!t.matches("[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?")) return Double.NaN;
        return Double.parseDouble(t);
    }

    /** JavaScript's {@code Math.round}: the nearest whole number, halves up towards +infinity. */
    static double round(final double n) {
        if (!Double.isFinite(n)) return n;
        final double floor = Math.floor(n);
        return n - floor >= 0.5 ? floor + 1 : floor;
    }
}

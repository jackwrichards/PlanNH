package com.gtnhplanner.machines;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nullable;

/**
 * One line of a machine's worked formulas, as the website's formula-lines.ts has it: a label, the working with the
 * card's own settings plugged in, and the answer. A term that is a setting's number names that setting (its colour);
 * a superscript is raised. Green or red when the line is a requirement met or not.
 */
public record FormulaLine(String label, List<Term> math, String result, Tone tone) {

    public enum Tone {
        PLAIN,
        GOOD,
        BAD
    }

    /** A piece of the working: text, a setting's number ({@code knob} its key), or a superscript. */
    public record Term(String text, @Nullable String knob, boolean sup) {}

    public static Term text(final String text) {
        return new Term(text, null, false);
    }

    public static Term knob(final Object text, final String knob) {
        return new Term(String.valueOf(text), knob, false);
    }

    public static Term sup(final String text) {
        return new Term(text, null, true);
    }

    /** A line from its terms: strings are text, terms are themselves. */
    public static FormulaLine of(final String label, final String result, final Tone tone, final Object... terms) {
        final List<Term> math = new ArrayList<>();
        for (final Object t : terms) math.add(t instanceof final Term term ? term : text(String.valueOf(t)));
        return new FormulaLine(label, math, result, tone);
    }

    public static FormulaLine of(final String label, final String result, final Object... terms) {
        return of(label, result, Tone.PLAIN, terms);
    }

    /** The working as plain text ({@code ^} before a superscript), as the website's formulaText writes it. */
    public String mathText() {
        final StringBuilder b = new StringBuilder();
        for (final Term t : math) b.append(t.sup() ? "^" + t.text() : t.text());
        return b.toString();
    }

    /** A number as the formulas show it (formulaNumber): whole numbers grouped, fractions to three places. */
    public static String number(final double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return "-";
        final DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(Locale.US);
        if (value == Math.rint(value) && Math.abs(value) < 1e15)
            return new DecimalFormat("#,##0", symbols).format(value);
        final double abs = Math.abs(value);
        final int digits = abs >= 100 ? 1 : abs >= 1 ? 2 : 3;
        final double rounded = new java.math.BigDecimal(value).setScale(digits, java.math.RoundingMode.HALF_UP)
            .doubleValue();
        final StringBuilder pattern = new StringBuilder("#,##0.");
        for (int i = 0; i < digits; i++) pattern.append('#');
        return new DecimalFormat(pattern.toString(), symbols).format(rounded);
    }
}

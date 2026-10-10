package com.gtnhplanner.machines;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.gtnhplanner.machines.FormulaLine.Tone;

/**
 * The Bacterial Vat (bartworks MTEBioVat, GT5U 5.09.54.205; docs/design/bio-vat-and-tgs.md), the website's
 * bacterial-vat.ts in Java: the same settings (keyed as the website keys them), numbers and formulas.
 *
 * Glass of at least the recipe's tier and the radio hatch's sieverts (at least, or exactly) gate a recipe. Overclocks
 * are GT's normal ones. The first fluid in and out scale with how full the output hatch is kept when the next run is
 * checked: M = 1 when it is empty or another fluid, else max(1, ceil(1000(1 - (2f - 1)^2))) + 1, and the run must fit
 * (void protection holds it). The radio hatch burns its material all the time. Pure: the game side gives it the
 * recipe's requirements and the hatch's materials.
 */
public final class BacterialVat {

    public static final String GLASS = "bioVatGlass", OUTPUT_HATCH = "bioVatOutputHatch", FILL = "bioVatFill",
        VOID = "bioVatVoid", RADIO = "bioVatRadio", SHUTTER = "bioVatShutter";

    /** Every setting a vat card keeps, under the website's keys (plans carry them both ways). */
    public static final List<String> SETTING_KEYS = List.of(GLASS, OUTPUT_HATCH, FILL, VOID, RADIO, SHUTTER);

    /** Configuration.Multiblocks.bioVatMaxParallelBonus, which the config cannot change. */
    private static final int MAX_BONUS = 1000;

    private BacterialVat() {}

    /** A recipe's needs: glass tier (HV = 3), sieverts and whether exactly. */
    public record Needs(int glass, int sievert, boolean exact) {}

    /** A radio hatch material: its id as the website writes it, name, sieverts and mass. */
    public record Material(String id, String name, int sievert, int mass) {}

    /** An output hatch: its key, name and litres (0: the ME output hatch, which keeps nothing). */
    public record Hatch(String key, String label, long capacity) {}

    /** MTEHatchOutput: 8000 x 2^tier litres, ULV to UHV; the Giant Output Hatch; an ME output hatch. */
    public static final List<Hatch> HATCHES;

    static {
        final List<Hatch> hatches = new ArrayList<>();
        for (int tier = 0; tier < 10; tier++) hatches.add(
            new Hatch(
                TreeGrowthSimulator.TIER_NAMES[tier].toLowerCase(java.util.Locale.ROOT),
                TreeGrowthSimulator.TIER_NAMES[tier] + " Output Hatch",
                8000L << tier));
        hatches.add(new Hatch("giant", "Giant Output Hatch", 100_000_000L));
        hatches.add(new Hatch("me", "ME Output Hatch", 0));
        HATCHES = List.copyOf(hatches);
    }

    /** The materials in the website's order: by sieverts, then mass, GregTech's own first among equals. */
    public static List<Material> sorted(final List<Material> materials) {
        final List<Material> out = new ArrayList<>(materials);
        out.sort(
            Comparator.comparingInt(Material::sievert)
                .thenComparingInt(Material::mass)
                .thenComparingInt(
                    m -> m.id()
                        .startsWith("gregtech:") ? 0 : 1));
        return out;
    }

    /** The card's settings read. */
    public record Setup(int glass, Hatch hatch, double fill, boolean voidExcess, @Nullable Material radio,
        int shutter) {}

    /** The smallest hatch that holds a full x1001 run on top of being half full; the Giant hatch past that. */
    static Hatch defaultHatch(final long fluidOut) {
        for (final Hatch h : HATCHES)
            if (h.capacity() > 0 && h.capacity() / 2.0 >= (MAX_BONUS + 1) * (double) fluidOut) return h;
        return hatch("giant");
    }

    @Nullable
    public static Hatch hatch(final String key) {
        for (final Hatch h : HATCHES) if (h.key()
            .equals(key)) return h;
        return null;
    }

    /** MTERadioHatch.getSievert: the material's sieverts less the shutter's share, in float. */
    public static int effectiveSievert(final int sievert, final int shutter) {
        return sievert - (int) Math.ceil(sievert / 100f * shutter);
    }

    /** The material and shutter a recipe's radiation is met with by default: the least that does it. */
    static Object[] defaultRadio(final Needs needs, final List<Material> materials) {
        if (needs.sievert() <= 0) return new Object[] { null, 0 };
        for (final Material m : materials) {
            if (m.sievert() < needs.sievert()) continue;
            if (!needs.exact()) return new Object[] { m, 0 };
            for (int shutter = 0; shutter <= 100; shutter++)
                if (effectiveSievert(m.sievert(), shutter) == needs.sievert()) return new Object[] { m, shutter };
        }
        return new Object[] { null, 0 };
    }

    /** The card's settings as stored (the website's keys and values); missing ones are the recipe's defaults. */
    public static Setup setup(final Map<String, ?> settings, final Needs needs, final long fluidOut,
        final List<Material> materials) {
        final String glass = TreeGrowthSimulator.string(settings, GLASS),
            fill = TreeGrowthSimulator.string(settings, FILL), shutter = TreeGrowthSimulator.string(settings, SHUTTER),
            radio = TreeGrowthSimulator.string(settings, RADIO);
        final Object[] fallback = defaultRadio(needs, materials);
        Material chosen = (Material) fallback[0];
        if ("none".equals(radio)) chosen = null;
        else for (final Material m : materials) if (m.id()
            .equals(radio)) chosen = m;
        final Hatch hatch = hatch(TreeGrowthSimulator.string(settings, OUTPUT_HATCH));
        return new Setup(
            parse(glass, Math.max(3, needs.glass())),
            hatch != null ? hatch : defaultHatch(fluidOut),
            Math.min(100, Math.max(0, parseDouble(fill, 50))),
            "void".equals(TreeGrowthSimulator.string(settings, VOID)),
            chosen,
            shutter.isEmpty() ? (int) fallback[1] : Math.min(100, Math.max(0, parse(shutter, 0))));
    }

    private static int parse(final String s, final int fallback) {
        try {
            final int v = (int) Double.parseDouble(s);
            return v > 0 || "0".equals(s.trim()) ? v : fallback;
        } catch (final NumberFormatException e) {
            return fallback;
        }
    }

    private static double parseDouble(final String s, final double fallback) {
        try {
            return s.isEmpty() ? fallback : Double.parseDouble(s);
        } catch (final NumberFormatException e) {
            return fallback;
        }
    }

    /** MTEBioVat.calcMod + 1, from the litres stored in a hatch of this capacity (Java's doubles). */
    public static int multiplierAt(final long stored, final long capacity) {
        if (capacity <= 0 || stored <= 0) return 1;
        final double y = capacity / 2.0;
        final int ret = (int) Math.ceil((-1.0 / y * (stored - y) * (stored - y) + y) / y * MAX_BONUS);
        return Math.max(1, ret) + 1;
    }

    /** The fill's multiplier, whether a run fits on what is stored, and the litres of output a run keeps. */
    public record Fill(long stored, int multiplier, boolean fits, long keptOut) {}

    public static Fill fill(final Setup setup, final long fluidOut) {
        final long capacity = setup.hatch()
            .capacity();
        final long stored = Math.round(capacity * setup.fill() / 100);
        final int multiplier = multiplierAt(stored, capacity);
        final long room = capacity > 0 ? capacity - stored : Long.MAX_VALUE;
        final boolean fits = (double) multiplier * fluidOut <= room;
        final long kept = fits ? multiplier * fluidOut : setup.voidExcess() ? Math.max(0, room) : 0;
        return new Fill(stored, multiplier, fits, kept);
    }

    /** MTERadioHatch.calcDecayTicks: ticks a mass unit of a material of these sieverts lasts. */
    public static long decayTicks(final int sievert) {
        if (sievert == 43) return 5000;
        if (sievert == 61) return 4500;
        if (sievert <= 100) return (long) Math.ceil((8000 * Math.tanh(-sievert / 20.0) + 8000) * 1000);
        return (long) Math.ceil(8000 * Math.tanh(-sievert / 65.0) + 8000);
    }

    /** Items of a material one radio hatch burns a second, running or not. */
    public static double burnPerSecond(final Material m) {
        return 20.0 / (m.mass() * decayTicks(m.sievert()));
    }

    /** Why the recipe cannot run in this vat, if it cannot. */
    @Nullable
    public static String gate(final Needs needs, final Setup setup, final long fluidOut) {
        if (setup.glass() < needs.glass())
            return "Needs " + TreeGrowthSimulator.TIER_NAMES[Math.min(14, needs.glass())] + " glass or better.";
        if (needs.sievert() > 0) {
            final int have = setup.radio() == null ? 0
                : effectiveSievert(
                    setup.radio()
                        .sievert(),
                    setup.shutter());
            if (needs.exact() ? have != needs.sievert() : have < needs.sievert())
                return (needs.exact() ? "Needs exactly " : "Needs at least ") + needs.sievert()
                    + " Sv from the radio hatch (it has "
                    + have
                    + ").";
        }
        if (fluidOut > 0 && !fill(setup, fluidOut).fits() && !setup.voidExcess())
            return "The scaled output does not fit in the output hatch at this fill: void protection holds the vat.";
        return null;
    }

    /** Every number on a vat card, worked from its own settings (the website's getVatFormulas). */
    public static List<FormulaLine> formulas(final Needs needs, final Setup setup, final long fluidIn,
        final long fluidOut, final int baseTicks, final long baseEut, final int runTicks, final long runEut,
        final int overclocks) {
        final Fill fill = fill(setup, fluidOut);
        final List<FormulaLine> lines = new ArrayList<>();
        if (setup.hatch()
            .capacity() == 0)
            lines.add(FormulaLine.of("fill", "1x", FormulaLine.knob("ME", OUTPUT_HATCH), " hatch keeps nothing"));
        else if (fill.stored() <= 0) lines.add(FormulaLine.of("fill", "1x", "an empty hatch"));
        else lines.add(
            FormulaLine.of(
                "fill",
                FormulaLine.number(fill.multiplier()) + "x",
                "⌈1000·(1−(2·",
                FormulaLine.knob(FormulaLine.number(setup.fill() / 100), FILL),
                "−1)",
                FormulaLine.sup("2"),
                ")⌉+1"));
        if (fluidIn > 0) lines.add(
            FormulaLine.of(
                "in",
                FormulaLine.number((double) fluidIn * fill.multiplier()) + " L",
                FormulaLine.number(fluidIn) + " L·" + FormulaLine.number(fill.multiplier())));
        if (fluidOut > 0) {
            lines.add(
                FormulaLine.of(
                    "out",
                    FormulaLine.number((double) fluidOut * fill.multiplier()) + " L",
                    FormulaLine.number(fluidOut) + " L·" + FormulaLine.number(fill.multiplier())));
            if (setup.hatch()
                .capacity() > 0)
                lines.add(
                    FormulaLine.of(
                        "room",
                        fill.fits() ? "fits"
                            : setup.voidExcess()
                                ? "voids " + FormulaLine.number((double) fluidOut * fill.multiplier() - fill.keptOut())
                                    + " L"
                                : "waits",
                        fill.fits() ? Tone.GOOD : Tone.BAD,
                        FormulaLine.number((double) fluidOut * fill.multiplier()) + " ≤ ",
                        FormulaLine.knob(
                            FormulaLine.number(
                                setup.hatch()
                                    .capacity()),
                            OUTPUT_HATCH),
                        "−" + FormulaLine.number(fill.stored())));
        }
        final double seconds = runTicks / 20.0;
        lines.add(
            FormulaLine.of(
                "time",
                FormulaLine.number(runTicks) + "t",
                FormulaLine.number(baseTicks) + "t ÷ 2",
                FormulaLine.sup(String.valueOf(overclocks))));
        if (fluidOut > 0) lines.add(
            FormulaLine.of(
                "rate",
                FormulaLine.number(seconds > 0 ? fill.keptOut() / seconds : 0) + " L/s",
                FormulaLine.number(fill.keptOut()) + " L ÷ " + FormulaLine.number(seconds) + "s"));
        final boolean glassOk = setup.glass() >= needs.glass();
        lines.add(
            FormulaLine.of(
                "glass",
                glassOk ? "ok" : "too low",
                glassOk ? Tone.GOOD : Tone.BAD,
                FormulaLine.knob(TreeGrowthSimulator.TIER_NAMES[Math.min(14, setup.glass())], GLASS),
                " ≥ " + TreeGrowthSimulator.TIER_NAMES[Math.min(14, Math.max(0, needs.glass()))]));
        if (needs.sievert() > 0) {
            final Material m = setup.radio();
            final int have = m == null ? 0 : effectiveSievert(m.sievert(), setup.shutter());
            final boolean met = needs.exact() ? have == needs.sievert() : have >= needs.sievert();
            final String op = needs.exact() ? "=" : "≥";
            lines.add(
                m == null
                    ? FormulaLine.of(
                        "rad",
                        have + " Sv",
                        met ? Tone.GOOD : Tone.BAD,
                        FormulaLine.knob("empty", RADIO),
                        " " + op + " " + needs.sievert())
                    : FormulaLine.of(
                        "rad",
                        have + " Sv",
                        met ? Tone.GOOD : Tone.BAD,
                        FormulaLine.knob(m.sievert(), RADIO),
                        "−⌈",
                        FormulaLine.knob(m.sievert(), RADIO),
                        "·",
                        FormulaLine.knob(setup.shutter(), SHUTTER),
                        "%⌉ " + op + " " + needs.sievert()));
            if (m != null) lines.add(
                FormulaLine.of(
                    "burn",
                    FormulaLine.number(burnPerSecond(m)) + "/s",
                    "20 ÷ (" + m.mass() + "·" + FormulaLine.number(decayTicks(m.sievert())) + "t)"));
        }
        lines.add(
            FormulaLine.of(
                "power",
                FormulaLine.number(runEut) + " EU/t",
                FormulaLine.number(baseEut) + "·4",
                FormulaLine.sup(String.valueOf(overclocks))));
        return lines;
    }
}

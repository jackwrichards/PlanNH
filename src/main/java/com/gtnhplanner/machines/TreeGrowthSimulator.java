package com.gtnhplanner.machines;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nullable;

import com.gtnhplanner.machines.FormulaLine.Tone;

/**
 * The Tree Growth Simulator (GT5U MTETreeFarm, 5.09.54.205; docs/design/bio-vat-and-tgs.md), the website's
 * tree-growth-simulator.ts in Java: the same settings (keyed as the website keys them), numbers and formulas.
 *
 * The sapling sits in the controller and is never used up. Every 100 ticks, whatever the voltage, each output mode that
 * has a tool makes NEI amount x (2t^2 - 2t + 5) x the tool's multiplier, where NEI's amount is the product times the
 * mode's own multiplier and t is the tier of the hatches' V x A rounded up. It draws VP[t]. A mode with no tool makes
 * nothing. Forestry saplings scale by their genes. Pure: the game side gives it each output's mode and the genes.
 */
public final class TreeGrowthSimulator {

    public static final int TICKS = 100;
    private static final int SECONDS = TICKS / 20;

    private TreeGrowthSimulator() {}

    public enum Mode {

        LOG("log", "logs", 5, "tgsLogTool"),
        SAPLING("sapling", "saplings", 5, "tgsSaplingTool"),
        LEAVES("leaves", "leaves", 2, "tgsLeavesTool"),
        FRUIT("fruit", "fruit", 1, "tgsFruitTool");

        public final String key, plural, toolSetting;
        public final int multiplier;

        Mode(final String key, final String plural, final int multiplier, final String toolSetting) {
            this.key = key;
            this.plural = plural;
            this.multiplier = multiplier;
            this.toolSetting = toolSetting;
        }
    }

    public static final String HEIGHT = "tgsHeight", FERTILITY = "tgsSaplings", YIELD = "tgsYield";

    /** Every setting a TGS card keeps, under the website's keys (plans carry them both ways). */
    public static final List<String> SETTING_KEYS = List.of(
        Mode.LOG.toolSetting,
        Mode.SAPLING.toolSetting,
        Mode.LEAVES.toolSetting,
        Mode.FRUIT.toolSetting,
        HEIGHT,
        FERTILITY,
        YIELD);

    /** A tool for a mode; {@code meta} is its GT metatool damage (-1: another mod's, see {@code itemId}). */
    public record Tool(String key, String label, int multiplier, boolean electric, String itemId) {}

    public static final String NONE = "none";

    /** MTETreeFarm.getToolMultiplier. A tool's material changes how long it lasts, never the amount. */
    public static List<Tool> tools(final Mode mode) {
        final List<Tool> out = new ArrayList<>(switch (mode) {
            case LOG -> List.of(
                new Tool("log:saw", "Saw", 1, false, "gregtech:gt.metatool.01@10"),
                new Tool("log:buzzsaw", "Buzzsaw", 2, true, "gregtech:gt.metatool.01@140"),
                new Tool("log:chainsaw", "Chainsaw", 4, true, "gregtech:gt.metatool.01@110"));
            case SAPLING -> List.of(
                new Tool("sapling:branch_cutter", "Branch Cutter", 1, false, "gregtech:gt.metatool.01@30"),
                new Tool("sapling:grafter", "Grafter", 4, false, "forestry:grafter"));
            case LEAVES -> List.of(
                new Tool("leaves:shears", "Shears", 1, false, "minecraft:shears"),
                new Tool("leaves:wire_cutter", "Wire Cutter", 2, false, "gregtech:gt.metatool.01@26"),
                new Tool("leaves:automatic_snips", "Electric Wire Cutter", 4, true, "gregtech:gt.metatool.01@196"));
            case FRUIT -> List.of(new Tool("fruit:knife", "Knife", 1, false, "gregtech:gt.metatool.01@34"));
        });
        out.add(new Tool(mode.key + ":multitool", "Pocket Multitool", 1, false, "gregtech:gt.metatool.01@180"));
        return out;
    }

    /** Forestry's allele values (ForestryMC 4.11.39): name and value. */
    public record Allele(String name, float value) {

        public String key() {
            return name.toLowerCase(Locale.ROOT);
        }
    }

    public static final List<Allele> HEIGHTS = List.of(
        new Allele("Smallest", 0.25f),
        new Allele("Smaller", 0.5f),
        new Allele("Small", 0.75f),
        new Allele("Average", 1f),
        new Allele("Large", 1.25f),
        new Allele("Larger", 1.5f),
        new Allele("Largest", 1.75f),
        new Allele("Gigantic", 2f));
    public static final List<Allele> FERTILITIES = List.of(
        new Allele("Lowest", 0.01f),
        new Allele("Lower", 0.025f),
        new Allele("Low", 0.035f),
        new Allele("Average", 0.05f),
        new Allele("High", 0.1f),
        new Allele("Higher", 0.2f),
        new Allele("Highest", 0.3f));
    public static final List<Allele> YIELDS = List.of(
        new Allele("Lowest", 0.025f),
        new Allele("Lower", 0.05f),
        new Allele("Low", 0.1f),
        new Allele("Average", 0.2f),
        new Allele("High", 0.3f),
        new Allele("Higher", 0.35f),
        new Allele("Highest", 0.4f));

    @Nullable
    public static Allele allele(final List<Allele> alleles, final float value) {
        for (final Allele a : alleles) if (a.value() == value) return a;
        return null;
    }

    @Nullable
    public static Allele allele(final List<Allele> alleles, @Nullable final String key) {
        for (final Allele a : alleles) if (a.key()
            .equals(key)) return a;
        return null;
    }

    /** A Forestry sapling's species, default genes, and products before the genes scale them. */
    public record Forestry(String species, float height, int girth, float fertility, float yield,
        Map<Mode, Integer> base) {}

    /**
     * What a TGS recipe farms: the mode of each of its output ports (in port order), the amount NEI shows for each,
     * and a Forestry sapling's genes.
     */
    public record Tree(List<Mode> modes, List<Integer> amounts, @Nullable Forestry forestry) {}

    /** The card's settings read: a tool per mode (null: none), and the genes. */
    public record Setup(Map<Mode, Tool> tools, float height, float fertility, float yield) {}

    /** The card's settings as stored (the website's keys and values); missing ones are the defaults. */
    public static Setup setup(final Map<String, ?> settings, @Nullable final Tree tree) {
        final Map<Mode, Tool> tools = new EnumMap<>(Mode.class);
        for (final Mode mode : Mode.values()) {
            final String key = string(settings, mode.toolSetting);
            if (NONE.equals(key)) continue;
            Tool tool = tools(mode).get(0);
            for (final Tool t : tools(mode)) if (t.key()
                .equals(key)) tool = t;
            tools.put(mode, tool);
        }
        final Forestry f = tree == null ? null : tree.forestry();
        final Allele h = allele(HEIGHTS, string(settings, HEIGHT)),
            fe = allele(FERTILITIES, string(settings, FERTILITY)), y = allele(YIELDS, string(settings, YIELD));
        return new Setup(
            tools,
            h != null ? h.value() : f != null ? f.height() : 1f,
            fe != null ? fe.value() : f != null ? f.fertility() : 0.05f,
            y != null ? y.value() : f != null ? f.yield() : 0.2f);
    }

    static String string(final Map<String, ?> settings, final String key) {
        final Object v = settings == null ? null : settings.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    /** The game's t: the tier of the hatches' V x A, rounded up, at least LV (GTUtility.getTierExtended). */
    public static int tier(final long poolEu) {
        int t = 0;
        while (t < 14 && voltage(t) < poolEu) t++;
        return Math.max(1, t);
    }

    /** GTValues.V: 8 x 4^t (MAX is Integer.MAX_VALUE - 7). */
    public static long voltage(final int t) {
        return t >= 14 ? 2147483640L : 8L << (2 * t);
    }

    /** 2t^2 - 2t + 5 (MTETreeFarm.getTierMultiplier). */
    public static int tierMultiplier(final int t) {
        return 2 * t * t - 2 * t + 5;
    }

    /** GTValues.VP[t]: the voltage less 1/16. */
    public static long euPerTick(final int t) {
        return voltage(t) * 30 / 32;
    }

    /** MTETreeFarm.getOutputsForForestrySapling: a mode's product at these genes, before the mode's multiplier. */
    @Nullable
    public static Integer forestryProduct(final Forestry f, final Mode mode, final Setup setup) {
        final Integer base = f.base()
            .get(mode);
        if (base == null) return null;
        return switch (mode) {
            case LOG -> {
                final double height = Math.max(3 * (setup.height() - 1), 0) + 1;
                yield (int) (base * height * f.girth());
            }
            case SAPLING -> Math.max(1, (int) (base * (double) (setup.fertility() * 10)));
            case FRUIT -> Math.max(1, (int) (base * (double) (setup.yield() * 10)));
            default -> base;
        };
    }

    /** One run's NEI amount for an output (the product times its mode's multiplier), at the card's genes. */
    public static int neiAmount(final Tree tree, final int output, final Setup setup) {
        final Mode mode = tree.modes()
            .get(output);
        if (tree.forestry() != null) {
            final Integer product = forestryProduct(tree.forestry(), mode, setup);
            if (product != null) return product * mode.multiplier;
        }
        return tree.amounts()
            .get(output);
    }

    /** How far an output's rate moves from NEI's amount: tier, tool and genes (0 with no tool). */
    public static double outputMultiplier(final Tree tree, final int output, final Setup setup, final int t) {
        final Tool tool = setup.tools()
            .get(
                tree.modes()
                    .get(output));
        final int shown = tree.amounts()
            .get(output);
        if (tool == null || shown <= 0) return tool == null ? 0 : 1;
        return (double) neiAmount(tree, output, setup) * tierMultiplier(t) * tool.multiplier() / shown;
    }

    /** Every number on a TGS card, worked from its own settings (the website's getTgsFormulas). */
    public static List<FormulaLine> formulas(final Tree tree, final Map<String, ?> settings, final long voltage,
        final long amps) {
        final int t = tier(voltage * amps);
        final int mult = tierMultiplier(t);
        final List<FormulaLine> lines = new ArrayList<>();
        lines.add(
            FormulaLine.of(
                "tier",
                TIER_NAMES[t] + ", t = " + t,
                FormulaLine.knob(FormulaLine.number(voltage), "tier"),
                " V · ",
                FormulaLine.knob(FormulaLine.number(amps), "amps"),
                " A"));
        lines.add(FormulaLine.of("mult", mult + "x", "2·" + t, FormulaLine.sup("2"), "−2·" + t + "+5"));
        final Setup setup = setup(settings, tree);
        final Forestry f = tree.forestry();
        if (f != null) {
            if (tree.modes()
                .contains(Mode.LOG)
                && f.base()
                    .get(Mode.LOG) != null)
                lines.add(
                    FormulaLine.of(
                        "height",
                        forestryProduct(f, Mode.LOG, setup) + " logs",
                        f.base()
                            .get(Mode.LOG) + "·(3·(",
                        FormulaLine.knob(plain(setup.height()), HEIGHT),
                        "−1)+1)·" + f.girth()));
            if (tree.modes()
                .contains(Mode.SAPLING)
                && f.base()
                    .get(Mode.SAPLING) != null)
                lines.add(
                    FormulaLine.of(
                        "fertility",
                        String.valueOf(forestryProduct(f, Mode.SAPLING, setup)),
                        "max(1, ⌊" + f.base()
                            .get(Mode.SAPLING) + "·",
                        FormulaLine.knob(plain(setup.fertility()), FERTILITY),
                        "·10⌋)"));
            if (tree.modes()
                .contains(Mode.FRUIT)
                && f.base()
                    .get(Mode.FRUIT) != null)
                lines.add(
                    FormulaLine.of(
                        "yield",
                        String.valueOf(forestryProduct(f, Mode.FRUIT, setup)),
                        "max(1, ⌊" + f.base()
                            .get(Mode.FRUIT) + "·",
                        FormulaLine.knob(plain(setup.yield()), YIELD),
                        "·10⌋)"));
        }
        int tools = 0, electric = 0;
        for (int i = 0; i < tree.modes()
            .size(); i++) {
            final Mode mode = tree.modes()
                .get(i);
            final Tool tool = setup.tools()
                .get(mode);
            if (tool == null) {
                lines.add(FormulaLine.of(mode.plural, "0/s", Tone.BAD, "no tool"));
                continue;
            }
            tools++;
            if (tool.electric()) electric++;
            final int nei = neiAmount(tree, i, setup);
            lines.add(
                FormulaLine.of(
                    mode.plural,
                    FormulaLine.number((double) nei * mult * tool.multiplier() / SECONDS) + "/s",
                    nei + "·" + mult + "·",
                    FormulaLine.knob(tool.multiplier(), mode.toolSetting),
                    " ÷ " + SECONDS + "s"));
        }
        lines.add(
            FormulaLine
                .of("power", FormulaLine.number(euPerTick(t)) + " EU/t", FormulaLine.number(voltage(t)) + "·30/32"));
        if (tools > 0) lines.add(
            FormulaLine.of(
                "wear",
                electric > 0 ? "720/h; electric 20 EU/s" : "720 uses/h",
                tools + " tool" + (tools == 1 ? "" : "s") + ", 1 use ÷ " + SECONDS + "s"));
        return lines;
    }

    /** A gene value as JavaScript prints the same float: its shortest decimal. */
    private static String plain(final float v) {
        final String s = Float.toString(v);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    static final String[] TIER_NAMES = { "ULV", "LV", "MV", "HV", "EV", "IV", "LuV", "ZPM", "UV", "UHV", "UEV", "UIV",
        "UMV", "UXV", "MAX" };
}

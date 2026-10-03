package com.sbancuz.plannh.client;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;

import com.sbancuz.plannh.PlanNH;
import com.sbancuz.plannh.data.MachineProfile;
import com.sbancuz.plannh.data.MachineProfileRegistry;
import com.sbancuz.plannh.data.RecipeContext;
import com.sbancuz.plannh.data.SettingDef;
import com.sbancuz.plannh.data.Settings;
import com.sbancuz.plannh.data.properties.RecipeProperty;
import com.sbancuz.plannh.data.provider.GTProvider;
import com.sbancuz.plannh.data.provider.gregtech.GTMachineIndex;
import com.sbancuz.plannh.data.provider.gregtech.GTMachineIndex.NumberSource;
import com.sbancuz.plannh.data.provider.gregtech.GTMachineSpec;
import com.sbancuz.plannh.data.provider.gregtech.GTSettings;
import com.sbancuz.plannh.data.provider.gregtech.StructureState;

import gregtech.api.GregTechAPI;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.RecipeMapWorkable;
import gregtech.api.logic.ModifierKind;
import gregtech.api.logic.ModifierRange;
import gregtech.api.logic.ProcessingInputs;
import gregtech.api.logic.ProcessingSpec;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;
import gregtech.api.recipe.RecipeMap;
import gregtech.api.util.OverclockCalculator;

/**
 * Writes what PlanNH believes about every GregTech multiblock to a Markdown file. No test can produce
 * it: {@code GregTechAPI.METATILEENTITIES} is empty outside a client, so review happens against this.
 */
public class MachineTableCommand extends CommandBase {

    public static final String COMMAND_NAME = "plannh_machines";

    private static final String FILE_NAME = "plannh-machines.md";

    /** Listing these says nothing about a particular machine. By key, because the label is translated. */
    private static final Set<String> ON_EVERY_MACHINE = Set
        .of(Settings.VOLTAGE.key(), Settings.MACHINES.key(), Settings.AMP.key(), GTSettings.ADVANCED);

    /** The heading and blurb a section gets. Display prose, so it lives here rather than on the enum. */
    private record Section(String heading, String explanation) {}

    private static final Map<NumberSource, Section> SECTIONS = new EnumMap<>(
        Map.of(
            NumberSource.DESCRIBER,
            new Section(
                "describer",
                "Uses the machine's own OverclockDescriber, which GregTech hands over as public API."),
            NumberSource.SPEC,
            new Section("spec", "Uses the ProcessingSpec the machine declares."),
            NumberSource.NONE,
            new Section("unmodelled", "No ProcessingSpec interface, so the node keeps the recipe's own numbers.")));

    @Override
    public String getCommandName() {
        return COMMAND_NAME;
    }

    @Override
    public String getCommandUsage(final ICommandSender sender) {
        return "/" + COMMAND_NAME;
    }

    /** Client-side and read-only, so it needs no permission. CommandBase would demand op otherwise. */
    @Override
    public boolean canCommandSenderUseCommand(final ICommandSender sender) {
        return true;
    }

    @Override
    public void processCommand(final ICommandSender sender, final String[] args) {
        try {
            final File out = new File(Minecraft.getMinecraft().mcDataDir, FILE_NAME);
            sender.addChatMessage(new ChatComponentText("PlanNH: wrote " + writeTo(out) + " machines to " + FILE_NAME));
        } catch (final IOException e) {
            PlanNH.LOG.warn("PlanNH: cannot write {}", FILE_NAME, e);
            sender.addChatMessage(new ChatComponentText("PlanNH: could not write " + FILE_NAME + ", see the log"));
        }
    }

    /** Separate from the command so the table can also be produced without a loaded world. */
    public static int writeTo(final File out) throws IOException {
        final ReviewTicks carried = ReviewTicks.from(out);
        try (PrintWriter writer = new PrintWriter(out, StandardCharsets.UTF_8.name())) {
            return write(writer, carried);
        }
    }

    private static int write(final PrintWriter writer, final ReviewTicks carried) {
        final Map<NumberSource, List<Row>> bySource = new LinkedHashMap<>();
        for (final NumberSource source : NumberSource.values()) {
            bySource.put(source, new ArrayList<>());
        }

        int total = 0;
        for (final Row row : collect()) {
            bySource.get(row.source())
                .add(row);
            total++;
        }

        writer.println("# Multiblock config simplifier table");
        writer.println();
        writeSections(writer);
        writeLegend(writer);
        for (final Map.Entry<NumberSource, List<Row>> section : bySource.entrySet()) {
            writeSection(writer, section.getKey(), section.getValue(), carried);
        }
        return total;
    }

    private static void writeSections(final PrintWriter writer) {
        writer.println("## Sections");
        writer.println();
        writer.println("GregTech itself says how a machine behaves: an OverclockDescriber the machine publishes,");
        writer.println("or the ProcessingSpec it declares. The heading says which one a chart reads for that machine.");
        writer.println();
    }

    private static void writeLegend(final PrintWriter writer) {
        writer.println("## Columns");
        writer.println();
        writer.println("- **" + ReviewTicks.COLUMN + "** - review state, not data. Regenerating carries a tick");
        writer.println(
            "  forward only while this machine's numbers are unchanged; anything that moved returns to "
                + ReviewTicks.UNCHECKED
                + ".");
        writer.println("- **numbers** - what a chart plans this machine with, at the structure an untouched node");
        writer.println("  shows: parallel, duration and EU modifiers, energy cost, the two overclock factors, no");
        writer.println("  overclock, machine heat, heat overclock and discount flags, recipe heat, tier skips.");
        writer.println("- **assumes** - numbers planned at the machine's best, which it only reaches while it runs:");
        writer.println("  full momentum, a stable black hole, a unit that has not overheated.");
        writer.println("- **rows** - structure rows a node offers beyond the four every machine shows (Tier,");
        writer.println("  Mach, Amp, Advanced). The goal is the fewest rows that still describe every way this");
        writer.println("  machine can be changed, which is blank only when nothing else changes it.");
        writer.println("- **settings** - structure settings that change a number this machine reports; blank means");
        writer.println("  none do.");
        writer.println("- **modes** - two machines behind one controller. `{map=n}` is the mode each recipemap");
        writer.println("  selects, so the node derives it; `asks` means it cannot, and the node offers the row.");
        writer.println();
    }

    private static void writeSection(final PrintWriter writer, final NumberSource source, final List<Row> rows,
        final ReviewTicks carried) {
        final Section section = SECTIONS.get(source);
        writer.println("## " + section.heading() + " (" + rows.size() + ")");
        writer.println();
        writer.println(section.explanation());
        writer.println();
        if (rows.isEmpty()) return;

        rows.sort(Comparator.comparing(Row::machine));
        writer.println(
            "| " + ReviewTicks.COLUMN
                + " | machine | numbers | assumes | rows | settings | modes | class | recipemaps |");
        writer.println("|---|---|---|---|---|---|---|---|---|");
        for (final Row row : rows) {
            writer.println(
                "| " + carried.forMachine(row.machine(), row.numbers())
                    + " | "
                    + row.machine()
                    + " | "
                    + row.numbers()
                    + " | "
                    + row.assumes()
                    + " | "
                    + row.rows()
                    + " | "
                    + row.settings()
                    + " | "
                    + row.modes()
                    + " | "
                    + row.className()
                    + " | "
                    + row.recipeMaps()
                    + " |");
        }
        writer.println();
    }

    private record Row(NumberSource source, String machine, String numbers, String assumes, String settings,
        String rows, String modes, String className, String recipeMaps) {}

    private static List<Row> collect() {
        final List<Row> rows = new ArrayList<>();
        for (final IMetaTileEntity mte : GregTechAPI.METATILEENTITIES) {
            if (!(mte instanceof final RecipeMapWorkable workable) || !(mte instanceof MTEMultiBlockBase)) continue;
            try {
                rows.add(row(mte, workable));
            } catch (final RuntimeException | LinkageError e) {
                PlanNH.LOG.debug("PlanNH: {} would not describe itself", mte.getClass(), e);
            }
        }
        return rows;
    }

    private static Row row(final IMetaTileEntity mte, final RecipeMapWorkable workable) {
        final GTMachineIndex.MachineEntry entry = GTMachineIndex.byId(mte.getLocalNameKey());

        return new Row(
            source(entry),
            mte.getLocalName(),
            numbers(entry),
            assumes(entry),
            settingNames(entry == null ? null : entry.machine()),
            visibleRows(entry, workable),
            modes(entry),
            mte.getClass()
                .getSimpleName(),
            recipeMaps(workable));
    }

    /**
     * The rows shown before Advanced is ticked. Asked the way the panel asks: a context carrying one of
     * the machine's recipemaps and a settings map naming it, which visibility predicates resolve against.
     */
    private static String visibleRows(@Nullable final GTMachineIndex.MachineEntry entry,
        final RecipeMapWorkable workable) {
        if (entry == null) return "not offered";
        final RecipeMap<?> map = workable.getAvailableRecipeMaps()
            .stream()
            .findFirst()
            .orElse(null);
        if (map == null) return "";

        final RecipeContext ctx = new RecipeContext(Map.<RecipeProperty<?>, Object>of(GTProvider.RECIPE_MAP, map));
        final MachineProfile profile = MachineProfileRegistry.get("gregtech:unified");
        final Map<String, Object> settings = Map.of(GTSettings.MACHINE, entry.id());

        final List<String> labels = new ArrayList<>();
        for (final SettingDef<?> def : profile.visibleSettings(ctx, settings)) {
            if (!ON_EVERY_MACHINE.contains(def.key)) labels.add(def.label);
        }
        return String.join(", ", labels);
    }

    private static String settingNames(@Nullable final GTMachineSpec machine) {
        if (machine == null) return "";
        final List<String> names = new ArrayList<>();
        machine.structure()
            .forEach((kind, range) -> names.add(kind.id + " " + range.min() + "-" + range.max()));
        machine.settings()
            .forEach(setting -> names.add(setting.name()));
        return String.join(", ", names);
    }

    private static String modes(@Nullable final GTMachineIndex.MachineEntry entry) {
        if (entry == null) return "";
        // A machine with one mode has no mode, so it asks nothing and shows nothing.
        if (entry.modes()
            .count() < 2) return "";

        final Map<String, Integer> modes = entry.modes()
            .byRecipeMap();
        final String count = entry.modes()
            .count() + " modes";
        if (modes == null) return count + ", asks";
        // Sorted so two runs of this command diff cleanly.
        return count + " " + new TreeMap<>(modes);
    }

    private static String recipeMaps(final RecipeMapWorkable workable) {
        final List<String> names = new ArrayList<>();
        workable.getAvailableRecipeMaps()
            .forEach(map -> names.add(map.unlocalizedName));
        names.sort(Comparator.naturalOrder());
        return String.join(", ", names);
    }

    /** A machine GregTech registers but the index skipped has no numbers either. */
    private static NumberSource source(@Nullable final GTMachineIndex.MachineEntry entry) {
        return entry == null ? NumberSource.NONE : entry.numberSource();
    }

    /**
     * What a chart actually plans this machine with, so the file doubles as the snapshot a GregTech
     * update is diffed against. A describer's numbers live in GregTech's own object, so those say so.
     */
    private static String numbers(@Nullable final GTMachineIndex.MachineEntry entry) {
        if (entry == null) return "-";
        if (entry.describer() != null) return "describer";
        return entry.machine() == null ? "-" : numbersText(entry.machine());
    }

    /**
     * The values the machine builds up while running, which a chart plans at the spec's best rather than as the machine
     * starts: the top of an ordered kind, the bottom of any other.
     */
    private static String assumes(@Nullable final GTMachineIndex.MachineEntry entry) {
        if (entry == null || entry.machine() == null) return "";
        final List<String> names = new ArrayList<>();
        for (final ModifierRange range : entry.machine()
            .spec()
            .getModifiers()) {
            final ModifierKind kind = range.kind();
            if (kind.source != ModifierKind.Source.RUNTIME) continue;
            names.add(kind.id + "=" + (kind.ordered ? range.max() : range.min()));
        }
        return String.join(", ", names);
    }

    /**
     * Every number a spec resolves to, at the structure an untouched node shows: every value at the
     * spec's best, at the lowest real voltage. All twelve always, in a fixed order, so a
     * GregTech update shows up as a diff on the machines whose numbers moved - a snapshot that omits
     * defaults cannot tell a value leaving its default from a value never set.
     */
    @Nonnull
    public static String numbersText(@Nonnull final GTMachineSpec machine) {
        final ProcessingSpec spec = machine.spec();
        final ProcessingInputs inputs = machine.inputs(StructureState.of(1, 0));
        final ProcessingSpec.OverclockRule overclock = spec.getOverclock(inputs);
        final ProcessingSpec.OverclockRule.Ratio ratio = overclock instanceof final ProcessingSpec.OverclockRule.Ratio r
            ? r
            : ProcessingSpec.OverclockRule.Ratio.STANDARD;
        final ProcessingSpec.Heat heat = spec.getHeat()
            .orElse(null);
        return "par=" + spec.getMaxParallel(inputs)
            + " dur="
            + num(spec.getDurationMultiplier(inputs))
            + " eu="
            + num(spec.getEuModifier(inputs))
            + " cost="
            + num(spec.getEuModifierNotLimitingParallel(inputs))
            + " ocD="
            + num(ratio.durationDivisor())
            + " ocE="
            + num(ratio.euMultiplier())
            + " noOC="
            + (overclock instanceof ProcessingSpec.OverclockRule.None ? 1 : 0)
            + " heat="
            + (heat == null ? 0 : heat.getMachineHeat(inputs))
            + " hOC="
            + (heat != null && heat.rules()
                .contains(ProcessingSpec.HeatRule.OVERCLOCK) ? 1 : 0)
            + " hDisc="
            + (heat != null && heat.rules()
                .contains(ProcessingSpec.HeatRule.DISCOUNT) ? 1 : 0)
            + " rHeat="
            + (heat == null ? -1
                : heat.fixedRecipeHeat()
                    .orElse(-1))
            + " skips="
            + spec.getMaxTierSkips()
                .orElse(OverclockCalculator.DEFAULT_MAX_TIER_SKIPS);
    }

    /** Denominators a GregTech modifier is plausibly built from; 1/3 and 9/4 both fall inside this. */
    private static final int MAX_DENOMINATOR = 64;

    /**
     * The shortest exact rendering: a whole number, two significant digits, or the fraction GregTech
     * wrote the ratio as. Anything that fits none of those prints in full rather than rounded - the
     * cell is what decides whether a hand review still stands, so a value it rounds away is a change
     * nobody is told about. Locale-independent, because the file is read wherever it was generated.
     */
    @Nonnull
    private static String num(final double value) {
        if (value == Math.rint(value) && Math.abs(value) < 1e15) return Long.toString((long) value);

        final BigDecimal rounded = BigDecimal.valueOf(value)
            .round(new MathContext(2));
        if (rounded.doubleValue() == value) return rounded.stripTrailingZeros()
            .toPlainString();

        for (int d = 2; d <= MAX_DENOMINATOR; d++) {
            final double scaled = value * d;
            if (Math.rint(scaled) != 0 && Math.abs(scaled - Math.rint(scaled)) < 1e-9) {
                return (long) Math.rint(scaled) + "/" + d;
            }
        }
        return BigDecimal.valueOf(value)
            .stripTrailingZeros()
            .toPlainString();
    }
}

package com.sbancuz.plannh.data.provider.gregtech;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.ToIntFunction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.sbancuz.plannh.api.RecipePropertyAPI;
import com.sbancuz.plannh.data.ChartMinimums;
import com.sbancuz.plannh.data.MachineProfile;
import com.sbancuz.plannh.data.RecipeContext;
import com.sbancuz.plannh.data.SettingDef;
import com.sbancuz.plannh.data.Settings;
import com.sbancuz.plannh.data.effect.EffectResult;
import com.sbancuz.plannh.data.machine.MachineVariant;
import com.sbancuz.plannh.data.machine.MachineVariants;
import com.sbancuz.plannh.data.provider.GTProvider;

import gregtech.api.enums.GTValues;
import gregtech.api.enums.HeatingCoilLevel;
import gregtech.api.logic.ModifierKind;
import gregtech.api.logic.ModifierRange;
import gregtech.api.logic.ProcessingInputs;
import gregtech.api.logic.ProcessingSpec;
import gregtech.api.logic.ResolvedRecipe;
import gregtech.api.util.GTRecipe;
import gregtech.api.util.GTUtility;
import gregtech.api.util.OverclockCalculator;

/**
 * Settings that only exist for GregTech nodes, kept out of {@link com.sbancuz.plannh.data.Settings}
 * because their option lists come from GT enums and {@code data} must stay loadable without it.
 *
 * <p>
 * These describe the structure a player built - which coil, which solenoid - rather than raw
 * overclock arithmetic. Each row is shown only when the selected machine's spec says it reads that
 * setting, so a node asks for the two or three numbers that machine actually uses instead of the
 * fifteen the old profiles offered.
 */
public final class GTSettings {

    private GTSettings() {}

    /** Shared: any mod's machines are picked through the same row, so a node has one machine key. */
    public static final String MACHINE = Settings.MACHINE.key();
    /** Still GregTech's own: it reveals the raw overclock rows, which no other provider has. */
    public static final String ADVANCED = "gt_advanced";

    // Read off the shared vocabulary rather than repeated as literals, so the key a spec names and
    // the key a node stores cannot drift apart. Sourcing them from a method call also keeps them out
    // of the constant pool, which is what makes a single edit here reach every call site.
    public static final String MODE = Settings.GT_MODE.key();
    /** The coil and pipe casing rows' keys, which {@link #structureKey} gives GregTech's kinds. */
    public static final String COIL = structureKey(ModifierKind.COIL);
    public static final String PIPE_CASING = structureKey(ModifierKind.PIPE_CASING);

    /** Sixteen 4A hatches is past anything GregTech builds, and the row is a plan rather than a limit. */
    private static final int MAX_AMPERAGE = 64;

    /**
     * The machine picker, which is no longer GregTech's own: {@link MachineVariants} builds it from
     * whichever providers offer machines for the node's recipe.
     */
    public static final SettingDef<String> MACHINE_DEF = MachineVariants.pickerDef();

    /**
     * Voltage offered from the lowest tier that can actually run this recipe upward. A machine below
     * the recipe's own EU/t cannot run it at all, so those tiers are not choices, and there is no
     * "off": a GT node always draws power. Unset resolves to the chart's own tier, raised to that
     * minimum, so a fresh node already reads as something buildable rather than as nothing.
     */
    public static final SettingDef<String> VOLTAGE_DEF = SettingDef
        .dynamicEnumDef(Settings.VOLTAGE.key(), "", GTSettings::voltageOptions, name -> name, (v, c) -> v)
        .withDefault(ctx -> GTValues.VN[defaultVoltageTier(recipeEUt(ctx))]);

    @Nonnull
    private static List<String> voltageOptions(final RecipeContext ctx) {
        return voltageOptions(recipeEUt(ctx));
    }

    /**
     * The recipe's own EU/t, for the settings rows, which need it to know which voltage tiers can run the recipe at
     * all.
     */
    public static long recipeEUt(final RecipeContext ctx) {
        return recipeEUt(ctx, ctx.getOrDefault(RecipePropertyAPI.DURATION_TICKS, 0));
    }

    /** As {@link #recipeEUt(RecipeContext)}, falling back on what the chart has computed so far. */
    static long recipeEUt(final RecipeContext ctx, final EffectResult current) {
        final long fromRecipe = recipeEUt(ctx, current.durationTicks());
        return fromRecipe > 0 ? fromRecipe : current.energyPerT();
    }

    private static long recipeEUt(final RecipeContext ctx, final int duration) {
        final Long euPerTick = ctx.getOrDefault(GTProvider.EU_PER_TICK, null);
        if (euPerTick != null && euPerTick > 0) return euPerTick;
        final Long totalEu = ctx.getOrDefault(GTProvider.TOTAL_EU, null);
        if (totalEu != null && totalEu > 0 && duration > 0) return totalEu / duration;
        return 0;
    }

    public static int minimumVoltageTier(final RecipeContext ctx) {
        return minimumVoltageTier(recipeEUt(ctx));
    }

    /** The lowest tier whose voltage covers the recipe's EU/t. */
    public static int minimumVoltageTier(final long recipeEUt) {
        if (recipeEUt <= 0) return 0;
        return Math.min(GTUtility.getTier(recipeEUt), GTValues.VN.length - 2);
    }

    public static int voltageTier(final RecipeContext ctx, final Map<String, Object> settings) {
        return voltageTier(recipeEUt(ctx), settings);
    }

    /** The tier a node runs at: what it stored, or the recipe's minimum when it stored nothing. */
    public static int voltageTier(final long recipeEUt, final Map<String, Object> settings) {
        final String stored = MachineProfile.getString(settings, Settings.VOLTAGE.key(), "");
        final int minimum = minimumVoltageTier(recipeEUt);
        for (int tier = 0; tier < GTValues.VN.length; tier++) {
            if (GTValues.VN[tier].equals(stored)) return Math.max(tier, minimum);
        }
        return defaultVoltageTier(recipeEUt);
    }

    /**
     * The tier a node opens at: the chart's own minimum, raised to the lowest tier that can run this
     * recipe at all. A node the user has not set is planning at whatever this chart plans at, and a
     * recipe too expensive for that still gets a hatch that works.
     */
    public static int defaultVoltageTier(final long recipeEUt) {
        return Math.max(minimumVoltageTier(recipeEUt), chartMinimum(Settings.VOLTAGE, 0));
    }

    /**
     * What a chart says it can build, or the best the game offers when it has not said. Read from the
     * chart on screen rather than handed in: a {@link SettingDef} is given the recipe and the node's
     * own settings, never the node or the graph holding it, and only the active chart draws rows.
     */
    private static int chartMinimum(final Settings setting, final int best) {
        return ChartMinimums.floor(setting, best);
    }

    /**
     * The settings a GregTech chart can set a floor for. Registered rather than listed by the panel that
     * draws them, so that panel names no mod and keeps working on a pack without GregTech.
     */
    public static void registerChartMinimums() {
        for (final ChartFloor floor : CHART_FLOORS) {
            final ModifierRange range = floor.kind()
                .getRange();
            ChartMinimums.register(
                ChartMinimums.Minimum.strongest(
                    floor.setting(),
                    floor.label(),
                    (int) range.min(),
                    (int) range.max(),
                    floor.kind()::label));
        }
        // One below the top of GregTech's own list, matching the tiers the voltage row offers.
        ChartMinimums.register(
            ChartMinimums.Minimum
                .weakest(Settings.VOLTAGE, "Volt", 0, GTValues.VN.length - 2, tier -> GTValues.VN[tier]));
    }

    /**
     * A structure value a whole chart sets a floor for, because it describes how far the world has progressed rather
     * than one machine: a chart planned at Cupronickel that quotes Eternal numbers is wrong everywhere at once.
     *
     * @param label Short enough to sit beside the floor's two steppers
     */
    private record ChartFloor(ModifierKind.IntKind kind, Settings setting, String label) {}

    private static final List<ChartFloor> CHART_FLOORS = List.of(
        new ChartFloor(ModifierKind.COIL, Settings.GT_COIL, "Coil"),
        new ChartFloor(ModifierKind.PIPE_CASING, Settings.GT_PIPE_CASING, "Pipe"));

    /** The tiers offered for a recipe of this cost, lowest usable first. */
    @Nonnull
    public static List<String> voltageOptions(final long recipeEUt) {
        final List<String> names = new ArrayList<>();
        for (int tier = minimumVoltageTier(recipeEUt); tier < GTValues.VN.length - 1; tier++) {
            names.add(GTValues.VN[tier]);
        }
        return names;
    }

    /**
     * Hands the node back to the raw overclock numbers. Also the provenance marker: while it is on,
     * the settings map is what the user meant and no spec may override it.
     */
    public static final SettingDef<Boolean> ADVANCED_DEF = SettingDef
        .boolDef(ADVANCED, false, (v, c) -> v ? "A" : null);

    /**
     * Parallels sit at whatever the structure allows, because running a multiblock below its maximum
     * is almost never what a player wants. Stored 0 means exactly that, so the number follows the
     * machine and its coils instead of freezing at whatever was current when the node was made.
     */
    public static final SettingDef<Integer> PARALLELS_DEF = SettingDef
        .autoIntDefCapped(Settings.PARALLELS.key(), 1, 4096, 1, GTSettings::machineMaxParallel, (v, c) -> "∥" + v);

    /** The selected machine's own parallel count for the structure the node describes. */
    public static int machineMaxParallel(final RecipeContext ctx, final Map<String, Object> settings) {
        return fromSpec(ctx, settings, 1, ResolvedRecipe::maxParallel);
    }

    /**
     * GregTech's numbers for this node's recipe, on the machine it selected, at the structure it describes. Null for a
     * machine without a spec, or a node without a GregTech recipe.
     */
    @Nullable
    public static ResolvedRecipe resolved(final RecipeContext ctx, final Map<String, Object> settings) {
        final Planned planned = planned(ctx, settings);
        return planned == null ? null : planned.resolved();
    }

    /**
     * What a node plans with: its machine's inputs, and the numbers its recipe resolves to there.
     *
     * @param floors     The chart floors the plan read, in {@link #floor(int)} order
     * @param calculator For the rows to read, never to change
     */
    private record Planned(RecipeContext ctx, Map<String, Object> settings, long[] floors,
        GTMachineIndex.MachineEntry entry, ProcessingInputs inputs, @Nullable ResolvedRecipe resolved,
        @Nullable OverclockCalculator calculator) {}

    /**
     * The last node's plan. Every row of a node's panel reads it on every frame, and a panel draws its rows together,
     * so
     * one slot holds it, as {@link GTMachineIndex#candidates} does. Everything the plan reads is in the key.
     */
    @Nullable
    private static Planned lastPlanned;

    @Nullable
    private static Planned planned(final RecipeContext ctx, final Map<String, Object> settings) {
        final GTMachineIndex.MachineEntry entry = GTMachineIndex.selected(ctx, settings);
        if (entry == null || entry.machine() == null) return null;
        final Planned last = lastPlanned;
        if (last != null && last.ctx() == ctx
            && last.entry() == entry
            && last.settings()
                .equals(settings)
            && sameFloors(last.floors())) return last;

        final StructureState state = state(ctx, settings, entry);
        final GTRecipe recipe = ctx.getOrDefault(GTProvider.GT_RECIPE, null);
        final ProcessingInputs inputs = recipe == null ? entry.machine()
            .inputs(state)
            : entry.machine()
                .inputs(state, recipe);
        final ResolvedRecipe resolved = recipe == null ? null
            : entry.machine()
                .spec()
                .resolve(recipe, inputs);
        final long[] floors = new long[CHART_FLOORS.size() + 1];
        for (int i = 0; i < floors.length; i++) floors[i] = floor(i);
        lastPlanned = new Planned(
            ctx,
            new HashMap<>(settings),
            floors,
            entry,
            inputs,
            resolved,
            resolved == null ? null : resolved.toCalculator());
        return lastPlanned;
    }

    private static boolean sameFloors(final long[] floors) {
        for (int i = 0; i < floors.length; i++) if (floors[i] != floor(i)) return false;
        return true;
    }

    /** 0 is the voltage floor, then the {@link #CHART_FLOORS}. */
    private static long floor(final int index) {
        if (index == 0) return chartMinimum(Settings.VOLTAGE, 0);
        final ChartFloor floor = CHART_FLOORS.get(index - 1);
        return chartMinimum(
            floor.setting(),
            (int) floor.kind()
                .getRange()
                .max());
    }

    @Nonnull
    private static StructureState state(final RecipeContext ctx, final Map<String, Object> settings,
        final GTMachineIndex.MachineEntry entry) {
        return resolve(ctx, settings, voltageTier(ctx, settings), mode(ctx, entry, settings));
    }

    /**
     * Reads one number off the machine the node is using, for the structure it describes. Every
     * advanced row resolves this way, so an untouched row reads what the machine actually does
     * instead of a global default that happens to be wrong for it.
     */
    private static int fromSpec(final RecipeContext ctx, final Map<String, Object> settings, final int fallback,
        final ToIntFunction<ResolvedRecipe> reader) {
        final ResolvedRecipe resolved = resolved(ctx, settings);
        return resolved == null ? fallback : reader.applyAsInt(resolved);
    }

    /**
     * Reads one number off the calculator the machine would overclock this node's recipe with, which is what an
     * advanced row overrides.
     */
    private static int fromCalculator(final RecipeContext ctx, final Map<String, Object> settings, final int fallback,
        final ToIntFunction<OverclockCalculator> reader) {
        final Planned planned = planned(ctx, settings);
        return planned == null || planned.calculator() == null ? fallback : reader.applyAsInt(planned.calculator());
    }

    /** A row's percentage, for a value GregTech keeps as a factor. */
    public static int percent(final double factor) {
        return (int) Math.round(100 * factor);
    }

    /** Percentages the rows show; the maths uses the spec's exact doubles, never these. */
    public static final SettingDef<Integer> SPEED_DEF = SettingDef.autoIntDef(
        Settings.SPEED.key(),
        10,
        10000,
        100,
        (ctx, s) -> fromCalculator(ctx, s, 100, c -> percent(1 / c.getDurationModifier())),
        (v, c) -> "⏱" + v + "%");

    public static final SettingDef<Integer> EUT_DISCOUNT_DEF = SettingDef.autoIntDef(
        Settings.EUT_DISCOUNT.key(),
        0,
        100,
        100,
        (ctx, s) -> fromCalculator(ctx, s, 100, c -> percent(c.getEUtDiscount())),
        (v, c) -> "D" + v + "%");

    public static final SettingDef<Integer> EUT_PER_OC_DEF = SettingDef.autoIntDef(
        Settings.EUT_INCREASE_PER_OC.key(),
        100,
        1000,
        400,
        (ctx, s) -> fromCalculator(ctx, s, 400, c -> percent(c.getEUtIncreasePerOC())),
        (v, c) -> "EU×" + (v / 100));

    public static final SettingDef<Integer> DURATION_PER_OC_DEF = SettingDef.autoIntDef(
        Settings.DURATION_DECREASE_PER_OC.key(),
        100,
        1000,
        200,
        (ctx, s) -> fromCalculator(ctx, s, 200, c -> percent(c.getDurationDecreasePerOC())),
        (v, c) -> "Spd×" + (v / 100));

    public static final SettingDef<Integer> MACHINE_HEAT_DEF = SettingDef.autoIntDef(
        Settings.MACHINE_HEAT.key(),
        0,
        100000,
        0,
        (ctx, s) -> fromCalculator(ctx, s, 0, OverclockCalculator::getMachineHeat),
        (v, c) -> "M" + v);

    /** Zero for a machine that ignores heat, which keeps something else in the recipe's special value. */
    public static final SettingDef<Integer> RECIPE_HEAT_DEF = SettingDef.autoIntDef(
        Settings.RECIPE_HEAT.key(),
        0,
        100000,
        0,
        (ctx, s) -> fromCalculator(ctx, s, 0, OverclockCalculator::getRecipeHeat),
        (v, c) -> "R" + v);

    /** GT's heat discount base, per 900K of headroom. */
    public static final SettingDef<Integer> HEAT_DISCOUNT_MULT_DEF = SettingDef.autoIntDef(
        Settings.HEAT_DISCOUNT_MULT.key(),
        0,
        200,
        percent(OverclockCalculator.DEFAULT_HEAT_DISCOUNT_MULTIPLIER),
        (ctx, s) -> fromCalculator(
            ctx,
            s,
            percent(OverclockCalculator.DEFAULT_HEAT_DISCOUNT_MULTIPLIER),
            c -> percent(c.getHeatDiscountMultiplier())),
        null);

    /** A machine that skips no tiers reports 0, which is a real answer. */
    public static final SettingDef<Integer> MAX_TIER_SKIPS_DEF = SettingDef.autoIntDef(
        Settings.MAX_TIER_SKIPS.key(),
        0,
        10,
        1,
        (ctx, s) -> fromCalculator(ctx, s, 1, OverclockCalculator::getMaxTierSkips),
        (v, c) -> "Sk" + v);

    public static final SettingDef<Boolean> PERFECT_OC_DEF = SettingDef.autoBoolDef(
        Settings.PERFECT_OC.key(),
        (ctx, s) -> fromSpec(
            ctx,
            s,
            0,
            r -> r.overclock()
                .rule()
                .equals(ProcessingSpec.OverclockRule.Ratio.PERFECT) ? 1 : 0),
        (v, c) -> v ? "P" : null);

    public static final SettingDef<Boolean> HEAT_OC_DEF = SettingDef.autoBoolDef(
        Settings.HEAT_OC.key(),
        (ctx, s) -> fromCalculator(ctx, s, 0, c -> c.isHeatOC() ? 1 : 0),
        (v, c) -> v ? "H" : null);

    public static final SettingDef<Boolean> HEAT_DISCOUNT_DEF = SettingDef.autoBoolDef(
        Settings.HEAT_DISCOUNT.key(),
        (ctx, s) -> fromCalculator(ctx, s, 0, c -> c.isHeatDiscount() ? 1 : 0),
        (v, c) -> v ? "D" : null);

    public static final SettingDef<Boolean> UNLIMITED_SKIPS_DEF = SettingDef.autoBoolDef(
        Settings.UNLIMITED_SKIPS.key(),
        (ctx, s) -> fromCalculator(ctx, s, 0, c -> c.getMaxTierSkips() == Integer.MAX_VALUE ? 1 : 0),
        (v, c) -> v ? "∞T" : null);

    /**
     * Amperage comes from the machine block itself rather than from a spec formula, and is a floor
     * rather than a ceiling: a multiblock draws whatever its energy hatches supply, so the row must
     * step past what the machine reports on its own.
     */
    public static final SettingDef<Integer> AMP_DEF = SettingDef
        .autoIntDef(Settings.AMP.key(), 1, MAX_AMPERAGE, 1, (ctx, s) -> {
            final GTMachineIndex.MachineEntry entry = GTMachineIndex.selected(ctx, s);
            return entry == null ? 1 : Math.max(1, entry.amperage());
        }, (v, c) -> "A" + v);

    /**
     * How many modes a machine has is the machine's business, not a constant: GregTech ships three-mode
     * multiblocks, and a fixed ceiling of one would leave the third unreachable.
     */
    public static final SettingDef<Integer> MODE_DEF = SettingDef.intDef(MODE, 0, 0, GTSettings::modeCeiling);

    private static int modeCeiling(final RecipeContext ctx, final Map<String, Object> settings) {
        final GTMachineIndex.MachineEntry entry = GTMachineIndex.selected(ctx, settings);
        return entry == null ? 1
            : entry.modes()
                .count() - 1;
    }

    /**
     * The key a structure value of this kind is stored under. GregTech's own kinds keep the bare name, which is what
     * charts saved before kinds were namespaced hold.
     */
    @Nonnull
    public static String structureKey(final ModifierKind kind) {
        final String[] namespaceAndName = kind.id.split(":", 2);
        final String name = namespaceAndName[namespaceAndName.length - 1];
        return namespaceAndName.length == 2 && !namespaceAndName[0].equals("gregtech")
            ? "gt_" + namespaceAndName[0] + "_" + name
            : "gt_" + name;
    }

    /**
     * The row a structure value is edited through: labelled and valued with GregTech's own names for the kind, so a
     * coil row reads as the block a player places, and bounded by the range the selected machine declares.
     */
    @Nonnull
    public static SettingDef<?> structureDef(final ModifierKind kind) {
        return SettingDef.autoIntDef(structureKey(kind), 0, 0, (ctx, s) -> planned(ctx, s, kind), null)
            .withLabelAndRange(
                kind.getName(),
                (ctx, s) -> (int) declaredRange(ctx, s, kind).min(),
                (ctx, s) -> (int) declaredRange(ctx, s, kind).max())
            .withDisplay(value -> kind.label(Long.parseLong(value)));
    }

    /**
     * What an untouched row opens on, which is what the node plans with: the chart's floor, raised to the lowest value
     * that runs the recipe, else the spec's best, as {@link GTMachineSpec#inputs} decides it.
     */
    private static int planned(final RecipeContext ctx, final Map<String, Object> settings, final ModifierKind kind) {
        final Planned planned = planned(ctx, settings);
        if (planned == null) return 0;
        final ProcessingInputs inputs = planned.inputs();
        return switch (kind) {
            case ModifierKind.IntKind tier -> inputs.value(tier);
            case ModifierKind.LongKind amount -> (int) Math.min(Integer.MAX_VALUE, inputs.value(amount));
        };
    }

    @Nonnull
    private static ModifierRange declaredRange(final RecipeContext ctx, final Map<String, Object> settings,
        final ModifierKind kind) {
        final GTMachineIndex.MachineEntry entry = GTMachineIndex.selected(ctx, settings);
        final ModifierRange range = entry == null ? null
            : entry.structure()
                .get(kind);
        return range == null ? new ModifierRange(kind, 0, 0) : range;
    }

    /**
     * The player's structure values, and the chart's floors for the rest: what the chart says it can build, or the
     * best the game offers where the chart has said nothing. The row is right there to move one node off that.
     */
    @Nonnull
    public static StructureState resolve(final RecipeContext ctx, final Map<String, Object> settings,
        final int voltageTier) {
        return resolve(ctx, settings, voltageTier, MachineProfile.getInt(settings, MODE, 0));
    }

    /**
     * As above, with the mode supplied by a caller that already knows the machine. Kept separate so
     * that resolving a structure never reaches the machine index, which a chart does per frame.
     */
    @Nonnull
    public static StructureState resolve(final RecipeContext ctx, final Map<String, Object> settings,
        final int voltageTier, final int mode) {
        final Map<ModifierKind, Long> structure = new HashMap<>();
        for (final ModifierKind kind : ModifierKind.all()) {
            final String key = structureKey(kind);
            if (settings.containsKey(key)) structure.put(kind, (long) MachineProfile.getInt(settings, key, 0));
        }
        final Map<ModifierKind, Long> floors = new HashMap<>();
        for (final ChartFloor floor : CHART_FLOORS) {
            floors.put(
                floor.kind(),
                (long) chartMinimum(
                    floor.setting(),
                    (int) floor.kind()
                        .getRange()
                        .max()));
        }
        return new StructureState(
            voltageTier,
            MachineProfile.getInt(settings, Settings.AMP.key(), 1),
            mode,
            structure,
            floors);
    }

    /**
     * The machine mode this node runs in. A machine that is two machines behind one controller picks
     * between them by recipemap, and the node's recipe already came from one of them, so the answer is
     * read rather than asked for. Everything else falls back to the row.
     */
    public static int mode(final RecipeContext ctx, @Nullable final GTMachineIndex.MachineEntry entry,
        final Map<String, Object> settings) {
        final int implied = entry == null ? -1 : entry.modeFor(ctx.getOrDefault(GTProvider.RECIPE_MAP, null));
        return implied >= 0 ? implied : MachineProfile.getInt(settings, MODE, 0);
    }

    public static boolean isAdvanced(final Map<String, Object> settings) {
        return MachineProfile.getBool(settings, ADVANCED, false);
    }

    /** Shows a setting only when the machine the node selected actually reads it. */
    @Nonnull
    public static BiPredicate<RecipeContext, Map<String, Object>> usesSetting(final Settings setting) {
        final BiPredicate<RecipeContext, Map<String, Object>> machineReadsIt = MachineVariants.usesSetting(setting);
        return (ctx, settings) -> {
            // Two conditions the shared predicate cannot know about: advanced mode replaces these rows
            // with the raw overclock ones, and a recipe that already implies its machine's mode has
            // answered the question the mode row would ask.
            if (isAdvanced(settings)) return false;
            if (setting == Settings.GT_MODE) {
                final GTMachineIndex.MachineEntry entry = GTMachineIndex.selected(ctx, settings);
                if (entry != null && entry.modeFor(ctx.getOrDefault(GTProvider.RECIPE_MAP, null)) >= 0) return false;
            }
            return machineReadsIt.test(ctx, settings);
        };
    }

    /** Shows a structure row only when the machine the node selected reads that kind. */
    @Nonnull
    public static BiPredicate<RecipeContext, Map<String, Object>> usesStructure(final ModifierKind kind) {
        return (ctx, settings) -> {
            if (isAdvanced(settings)) return false;
            final GTMachineIndex.MachineEntry entry = GTMachineIndex.selected(ctx, settings);
            return entry != null && entry.structure()
                .containsKey(kind);
        };
    }

    /** The raw overclock rows, shown only once the user has asked for them. */
    @Nonnull
    public static BiPredicate<RecipeContext, Map<String, Object>> advancedOnly() {
        return (ctx, settings) -> isAdvanced(settings);
    }

    /**
     * The machine is chosen from the node's title bar, not from a settings row - it names what the
     * node is, rather than tuning it. The def still belongs to the profile so the choice serializes;
     * it just never draws.
     */
    @Nonnull
    public static BiPredicate<RecipeContext, Map<String, Object>> neverAsARow() {
        return (ctx, settings) -> false;
    }

    /**
     * A singleblock's tier is the block you placed, so only a multiblock's energy hatch is a choice,
     * and only on one that overclocks. Also shown when nothing resolved, so a node PlanNH cannot
     * identify keeps a usable control.
     */
    @Nonnull
    public static BiPredicate<RecipeContext, Map<String, Object>> voltageEditable() {
        return (ctx, settings) -> multiblockOrUnknown(ctx, settings) && overclocks(ctx, settings);
    }

    /**
     * Amperage is the energy hatches a multiblock was built with, so it is a choice wherever the
     * machine is one. A singleblock draws the amperage its block draws and has nothing to say.
     */
    @Nonnull
    public static BiPredicate<RecipeContext, Map<String, Object>> ampEditable() {
        return (ctx, settings) -> multiblockOrUnknown(ctx, settings) && overclocks(ctx, settings);
    }

    /**
     * A machine that runs every recipe at the recipe's own voltage takes nothing from its energy
     * hatches, so their tier and amperage change no number it reports.
     */
    private static boolean overclocks(final RecipeContext ctx, final Map<String, Object> settings) {
        if (isAdvanced(settings)) return true;
        final GTMachineIndex.MachineEntry entry = GTMachineIndex.selected(ctx, settings);
        // GregTech keeps noOverclock out of modes and tiers, so any structure answers for all of them
        return entry == null || entry.machine() == null
            || !(entry.machine()
                .spec()
                .getOverclock(
                    entry.machine()
                        .inputs(StructureState.of(1, 0))) instanceof ProcessingSpec.OverclockRule.None);
    }

    /**
     * The spec already gives the machine's maximum, but planning for fewer than the structure
     * allows is normal, so the cap stays editable wherever it can exceed one. A multiblock that runs
     * one recipe at a time - the Large Chemical Reactor, the IsaMill - reports a maximum of one, and
     * a row that can only be moved below what the machine does is not a plan anybody draws.
     */
    @Nonnull
    public static BiPredicate<RecipeContext, Map<String, Object>> parallelsEditable() {
        return (ctx, settings) -> multiblockOrUnknown(ctx, settings)
            && (isAdvanced(settings) || machineMaxParallel(ctx, settings) > 1);
    }

    /**
     * Whether the node stands for a multiblock. The machine picker already answers this, so the row is
     * never a question - but it stays a setting, because a node whose machine PlanNH cannot identify
     * still needs a way to say which form factor it is, and because a spec may want to state it.
     * Read through {@link MachineVariant#tieredByBuild()} rather than off a GregTech type, so that the
     * one fact has one authority and the question generalizes to a mod whose build choice is not a
     * hatch.
     */
    public static final SettingDef<Boolean> MULTIBLOCK_DEF = SettingDef
        .autoBoolDef(Settings.GT_MULTIBLOCK.key(), (ctx, s) -> {
            final MachineVariant machine = MachineVariants.selected(ctx, s);
            return machine == null || machine.tieredByBuild() ? 1 : 0;
        }, (v, c) -> v ? "M" : null);

    private static boolean multiblockOrUnknown(final RecipeContext ctx, final Map<String, Object> settings) {
        return isAdvanced(settings) || MULTIBLOCK_DEF.effectiveBool(ctx, settings);
    }

    /**
     * Settings the machine now derives. A chart saved before the picker existed has these tuned by
     * hand, and honouring the spec instead would silently change its numbers, so such a chart
     * opens in advanced mode. Voltage and machine count are deliberately absent: they stay
     * user-owned in both modes, so a chart whose only change was "IV, x4" gets the compact UI.
     */
    private static final List<String> DERIVED_KEYS = List.of(
        Settings.AMP.key(),
        Settings.SPEED.key(),
        Settings.PARALLELS.key(),
        Settings.PERFECT_OC.key(),
        Settings.LASER_OC.key(),
        Settings.NO_OVERCLOCK.key(),
        Settings.UNLIMITED_SKIPS.key(),
        Settings.EUT_DISCOUNT.key(),
        Settings.EUT_INCREASE_PER_OC.key(),
        Settings.DURATION_DECREASE_PER_OC.key(),
        Settings.MAX_OVERCLOCKS.key(),
        Settings.MAX_REGULAR_OC.key(),
        Settings.MAX_TIER_SKIPS.key(),
        Settings.MACHINE_HEAT.key(),
        Settings.RECIPE_HEAT.key(),
        Settings.HEAT_OC.key(),
        Settings.HEAT_DISCOUNT.key(),
        Settings.HEAT_DISCOUNT_MULT.key());

    /**
     * The coil row stored GregTech's coil level name before every structure row stored its kind's own number. A name
     * no coil has is dropped, so the node opens on the chart's coil rather than on Cupronickel.
     */
    private static void migrateCoilName(final Map<String, Object> settings) {
        if (!(settings.get(COIL) instanceof final String name)) return;
        settings.remove(COIL);
        for (final HeatingCoilLevel level : HeatingCoilLevel.values()) {
            if (level.name()
                .equals(name)
                && ModifierKind.COIL.getRange()
                    .contains(level.getTier())) {
                settings.put(COIL, (int) level.getTier());
            }
        }
    }

    public static void migrateLegacyNode(final Map<String, Object> settings) {
        migrateCoilName(settings);
        if (settings.containsKey(ADVANCED) || settings.containsKey(MACHINE)) return;
        for (final String key : DERIVED_KEYS) {
            if (settings.containsKey(key)) {
                settings.put(ADVANCED, true);
                return;
            }
        }
    }

}

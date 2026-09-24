package com.sbancuz.plannh.data.provider.gregtech;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import java.util.function.ToDoubleBiFunction;
import java.util.function.ToIntBiFunction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import net.minecraft.item.ItemStack;
import net.minecraft.util.MathHelper;

import com.sbancuz.plannh.data.ChartMinimums;
import com.sbancuz.plannh.data.MachineConfig;
import com.sbancuz.plannh.data.MachineProfile;
import com.sbancuz.plannh.data.RecipeContext;
import com.sbancuz.plannh.data.SettingDef;
import com.sbancuz.plannh.data.Settings;
import com.sbancuz.plannh.data.TierSetting;
import com.sbancuz.plannh.data.machine.MachineVariant;
import com.sbancuz.plannh.data.machine.MachineVariants;
import com.sbancuz.plannh.data.provider.GTProvider;

import gregtech.api.enums.GTValues;
import gregtech.api.enums.HeatingCoilLevel;
import gregtech.api.enums.ItemList;
import gregtech.api.util.GTUtility;
import gregtech.common.tileentities.machines.multi.MTEIndustrialCuttingMachine.SawbladeTiers;
import kubatech.loaders.ArcFurnaceElectrode;

/**
 * Settings that only exist for GregTech nodes, kept out of {@link com.sbancuz.plannh.data.Settings}
 * because their option lists come from GT enums and {@code data} must stay loadable without it.
 *
 * <p>
 * These describe the structure a player built - which coil, which solenoid - rather than raw
 * overclock arithmetic. Each row is shown only when the selected machine's preset says it reads that
 * setting, so a node asks for the two or three numbers that machine actually uses instead of the
 * fifteen the old profiles offered.
 */
public final class GTSettings {

    private GTSettings() {}

    /** Still GregTech's own: it reveals the raw overclock rows, which no other provider has. */
    public static final String ADVANCED = "gt_advanced";

    /** Rows are plans, not limits. */
    private static final int MAX_AMPERAGE = 64;

    /**
     * Voltage offered from the lowest tier that can actually run this recipe upward. A machine below
     * the recipe's own EU/t cannot run it at all, so those tiers are not choices, and there is no
     * "off": a GT node always draws power. Unset resolves to the chart's own tier, raised to that
     * minimum, so a fresh node already reads as something buildable rather than as nothing.
     */
    public static final SettingDef<String> VOLTAGE_DEF = SettingDef
        .dynamicEnumDef(Settings.VOLTAGE.key(), "", GTSettings::voltageOptions, name -> name, (v, c) -> v)
        .withDefault(ctx -> GTValues.VN[defaultVoltageTier(GTOverclockStep.recipeEUt(ctx))]);

    @Nonnull
    private static List<String> voltageOptions(final RecipeContext ctx) {
        return voltageOptions(GTOverclockStep.recipeEUt(ctx));
    }

    public static int minimumVoltageTier(final RecipeContext ctx) {
        return minimumVoltageTier(GTOverclockStep.recipeEUt(ctx));
    }

    /** The lowest tier whose voltage covers the recipe's EU/t. */
    public static int minimumVoltageTier(final long recipeEUt) {
        if (recipeEUt <= 0) return 0;
        return Math.min(GTUtility.getTier(recipeEUt), GTValues.VN.length - 2);
    }

    public static int voltageTier(final RecipeContext ctx, final Map<String, Object> settings) {
        return voltageTier(GTOverclockStep.recipeEUt(ctx), settings);
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
        return Math.max(minimumVoltageTier(recipeEUt), ChartMinimums.floor(Settings.VOLTAGE, 0));
    }

    /**
     * The settings a GregTech chart can set a floor for. Registered rather than listed by the panel that
     * draws them, so that panel names no mod and keeps working on a pack without GregTech.
     */
    public static void registerChartMinimums() {
        floorStrongest(Settings.GT_COIL, "Coil", COIL_DEF);
        floorStrongest(Settings.GT_PIPE_CASING, "Pipe", PIPE_CASING_DEF);
        // One below the top of GregTech's own list, matching the tiers the voltage row offers.
        ChartMinimums.register(
            ChartMinimums.Minimum
                .weakest(Settings.VOLTAGE, "Volt", 0, GTValues.VN.length - 2, tier -> GTValues.VN[tier]));
    }

    /** A row that both bounds its floor and names its tiers, so the two cannot come from different rows. */
    private static void floorStrongest(final Settings setting, final String label, final TierSetting row) {
        ChartMinimums.register(
            ChartMinimums.Minimum.strongest(setting, label, row.minInt, row.maxInt, row::name));
    }

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
     * the settings map is what the user meant and no preset may override it.
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
        return Math.max(
            1,
            fromPreset(
                ctx,
                settings,
                1,
                (preset, state) -> preset.maxParallel()
                    .applyAsInt(state)));
    }

    /**
     * Reads one number off the machine the node is using, for the structure it describes. Every
     * advanced row resolves this way, so an untouched row reads what the machine actually does
     * instead of a global default that happens to be wrong for it.
     */
    private static int fromPreset(final RecipeContext ctx, final Map<String, Object> settings, final int fallback,
        final ToIntBiFunction<GTMachinePreset, StructureState> reader) {
        final GTMachineIndex.MachineEntry entry = GTMachineIndex.selected(ctx, settings);
        if (entry == null || entry.preset() == null) return fallback;
        return reader
            .applyAsInt(entry.preset(), StructureState.resolve(ctx, settings, voltageTier(ctx, settings), GTMachineIndex.mode(ctx, entry, settings)));
    }

    /** A row showing one of the machine's own factors as the percentage it is named for; the preset keeps the exact double. */
    @Nonnull
    private static SettingDef<Integer> presetPercent(final String key, final int min, final int max, final int fallback,
        final ToDoubleBiFunction<GTMachinePreset, StructureState> reader,
        @Nullable final BiFunction<Integer, MachineConfig, String> badgeFn) {
        return SettingDef
            .autoIntDef(
                key,
                min,
                max,
                (ctx, s) -> fromPreset(
                    ctx,
                    s,
                    fallback,
                    (p, st) -> (int) Math.round(
                        100.0 * reader.applyAsDouble(p, st))),
                badgeFn);
    }

    /** A row showing whether the machine does the thing, for the flags a preset carries rather than computes. */
    @Nonnull
    private static SettingDef<Boolean> presetFlag(final String key,
        final Predicate<GTMachinePreset> reader,
        @Nullable final BiFunction<Boolean, MachineConfig, String> badgeFn) {
        return SettingDef
            .autoBoolDef(
                key,
                (ctx, s) -> fromPreset(ctx, s, 0, (p, st) -> reader.test(p) ? 1 : 0),
                badgeFn);
    }

    /** Percentages the rows show; the maths uses the preset's exact doubles, never these. */
    public static final SettingDef<Integer> SPEED_DEF = presetPercent(
        Settings.SPEED.key(),
        10,
        10000,
        100,
        (p, st) -> 1.0 / p.durationModifier()
            .applyAsDouble(st),
        (v, c) -> "⏱" + v + "%");

    public static final SettingDef<Integer> EUT_DISCOUNT_DEF = presetPercent(
        Settings.EUT_DISCOUNT.key(),
        0,
        100,
        100,
        (p, st) -> p.euModifier()
            .applyAsDouble(st),
        (v, c) -> "D" + v + "%");

    public static final SettingDef<Integer> EUT_PER_OC_DEF = presetPercent(
        Settings.EUT_INCREASE_PER_OC.key(),
        100,
        1000,
        400,
        (p, st) -> p.eutIncreasePerOC()
            .applyAsDouble(st),
        (v, c) -> "EU×" + (v / 100));

    public static final SettingDef<Integer> DURATION_PER_OC_DEF = presetPercent(
        Settings.DURATION_DECREASE_PER_OC.key(),
        100,
        1000,
        200,
        (p, st) -> p.durationDecreasePerOC()
            .applyAsDouble(st),
        (v, c) -> "Spd×" + (v / 100));

    public static final SettingDef<Integer> MACHINE_HEAT_DEF = SettingDef.autoIntDef(
        Settings.MACHINE_HEAT.key(),
        0,
        100000,
        0,
        (ctx, s) -> fromPreset(
            ctx,
            s,
            0,
            (p, st) -> p.machineHeat()
                .applyAsInt(st)),
        (v, c) -> "M" + v);

    /**
     * The heat a recipe demands, which GregTech keeps in the recipe's special value. That field holds
     * whatever each machine wants it to - the Chemical Plant keeps its required casing tier there - so
     * it is only heat for a machine that overclocks on heat. Every other machine reports zero, because
     * a row that shows a number nothing reads is worse than no row.
     */
    public static final SettingDef<Integer> RECIPE_HEAT_DEF = SettingDef
        .autoIntDef(Settings.RECIPE_HEAT.key(), 0, 100000, 0, (ctx, s) -> {
            final GTMachineIndex.MachineEntry entry = GTMachineIndex.selected(ctx, s);
            final GTMachinePreset preset = entry == null ? null : entry.preset();
            if (preset == null || !preset.usesHeat()) return 0;
            return GTPresetApplier.recipeHeat(ctx, preset);
        }, (v, c) -> "R" + v);

    /** GT's own heat discount base, 0.95 per 900K of headroom. */
    public static final SettingDef<Integer> HEAT_DISCOUNT_MULT_DEF = SettingDef
        .autoIntDef(Settings.HEAT_DISCOUNT_MULT.key(), 0, 200, 95, (ctx, s) -> 95, null);

    /**
     * A machine that skips no tiers reports 0, which is a real answer. -1 on the preset means the
     * machine never asked, leaving GT's own default of one.
     */
    public static final SettingDef<Integer> MAX_TIER_SKIPS_DEF = SettingDef.autoIntDef(
        Settings.MAX_TIER_SKIPS.key(),
        0,
        10,
        1,
        (ctx, s) -> fromPreset(
            ctx,
            s,
            1,
            (p, st) -> p.maxTierSkips() == GTMachinePreset.TIER_SKIPS_UNSET ? 1 : p.maxTierSkips()),
        (v, c) -> "Sk" + v);

    public static final SettingDef<Boolean> PERFECT_OC_DEF = SettingDef.autoBoolDef(
        Settings.PERFECT_OC.key(),
        (ctx, s) -> fromPreset(
            ctx,
            s,
            0,
            (p, st) -> p.durationDecreasePerOC()
                .applyAsDouble(st) >= 4.0 ? 1 : 0),
        (v, c) -> v ? "P" : null);

    public static final SettingDef<Boolean> HEAT_OC_DEF = presetFlag(
        Settings.HEAT_OC.key(),
        GTMachinePreset::heatOC,
        (v, c) -> v ? "H" : null);

    public static final SettingDef<Boolean> HEAT_DISCOUNT_DEF = presetFlag(
        Settings.HEAT_DISCOUNT.key(),
        GTMachinePreset::heatDiscount,
        (v, c) -> v ? "D" : null);

    public static final SettingDef<Boolean> UNLIMITED_SKIPS_DEF = presetFlag(
        Settings.UNLIMITED_SKIPS.key(),
        GTMachinePreset::unlimitedTierSkips,
        (v, c) -> v ? "∞T" : null);

    /**
     * Amperage comes from the machine block itself rather than from a preset formula, and is a floor
     * rather than a ceiling: a multiblock draws whatever its energy hatches supply, so the row must
     * step past what the machine reports on its own.
     */
    public static final SettingDef<Integer> AMP_DEF = SettingDef
        .autoIntDef(Settings.AMP.key(), 1, MAX_AMPERAGE, 1, (ctx, s) -> {
            final GTMachineIndex.MachineEntry entry = GTMachineIndex.selected(ctx, s);
            return entry == null ? 1 : Math.max(1, entry.amperage());
        }, (v, c) -> "A" + v);

    /**
     * The machine-owned values the step merges under stored settings. Same defs the rows display,
     * so display and maths agree by construction instead of by discipline; VOLTAGE (a name, not a
     * number) and PARALLELS (a resolved cap, not an effective value) stay bespoke at the call site.
     */
    static final List<SettingDef<?>> OWNED_DEFS = List.of(
        AMP_DEF,
        SPEED_DEF,
        EUT_DISCOUNT_DEF,
        EUT_PER_OC_DEF,
        DURATION_PER_OC_DEF,
        MACHINE_HEAT_DEF,
        RECIPE_HEAT_DEF,
        HEAT_DISCOUNT_MULT_DEF,
        MAX_TIER_SKIPS_DEF,
        PERFECT_OC_DEF,
        HEAT_OC_DEF,
        HEAT_DISCOUNT_DEF,
        UNLIMITED_SKIPS_DEF);

    /** GT counts None and ULV below Cupronickel, which is why its {@code getTier()} subtracts two. */
    public static final int MAX_COIL_TIER = HeatingCoilLevel.getMaxTier();

    /** How hot a coil of this tier runs, in Kelvin. Tiers outside the range clamp to it. */
    public static int coilHeat(final int coilTier) {
        return (int) HeatingCoilLevel.getFromTier((byte) MathHelper.clamp_int(coilTier, 0, MAX_COIL_TIER))
            .getHeat();
    }

    /**
     * The weakest coil that reaches a heat, or the hottest coil when none does. One tier too low and
     * a node opens on a structure that cannot run its recipe.
     */
    public static int coilTierForHeat(final int heat) {
        if (heat <= 0) return 0;
        for (int tier = 0; tier < MAX_COIL_TIER; tier++) {
            if (coilHeat(tier) >= heat) return tier;
        }
        return MAX_COIL_TIER;
    }

    /** GregTech's own translated name for a coil tier, so a row reads as the block a player places. */
    @Nonnull
    public static String coilDisplayName(final int tier) {
        return HeatingCoilLevel.getFromTier((byte) MathHelper.clamp_int(tier, 0, MAX_COIL_TIER))
            .getName();
    }

    /**
     * Stores the coil tier and shows GregTech's material name, because the tier is what maths and
     * saves keep - locale-independent and stable - while "Cupronickel" is what a player built.
     *
     * <p>
     * An untouched row opens on the chart's own coil rather than on the best one, because a coil sets
     * the heat every overclock is counted from and a chart planned at Cupronickel that quotes Eternal
     * numbers is wrong everywhere at once. The whole list stays offered, so one node can still model a
     * hotter build than the rest of the chart.
     */
    public static final TierSetting COIL_DEF = new TierSetting(
        Settings.GT_COIL.key(),
        0,
        MAX_COIL_TIER,
        (ctx, s) -> defaultCoilTier(ctx),
        GTSettings::coilDisplayName,
        null);

    /**
     * The coil a node opens on: the chart's own minimum, raised to whatever the recipe needs to reach
     * its heat. GregTech keeps that heat in the recipe's special value, which a machine that ignores
     * heat uses for something else - but a casing tier or a mode number sits far below the weakest
     * coil's 1801K, so reading it here raises nothing.
     */
    public static int defaultCoilTier(final RecipeContext ctx) {
        return Math.max(ChartMinimums.floor(Settings.GT_COIL, MAX_COIL_TIER), coilTierForRecipe(ctx));
    }

    private static int coilTierForRecipe(final RecipeContext ctx) {
        // Reading the key touches GTProvider, whose eager profile build needs NEI/LWJGL and fails
        // headless (LinkageError); a warmup/test resolve with neither loaded means "nothing chosen yet".
        // TODO: lazy-init GTProvider.PROFILE so keys stay usable headless, then drop this guard.
        final Integer heat;
        try {
            heat = ctx.getOrDefault(GTProvider.SPECIAL_VALUE, null);
        } catch (final RuntimeException | LinkageError outsideAGame) {
            return 0;
        }
        return heat == null ? 0 : coilTierForHeat(heat);
    }

    /** Solenoid tiers are the block meta + 2, so MV is the weakest that exists. */
    public static final int MIN_SOLENOID_TIER = 2;
    /** Read off a registered Block instance by {@code BlockCyclotronCoils.getVoltageTier(meta)}. */
    public static final int MAX_SOLENOID_TIER = 12;
    /** GT computes meta + 1 inside the private {@code GTStructureUtility.getItemPipeCasingTier}. */
    public static final int MAX_ITEM_PIPE_TIER = 8;
    /** Extra Coke Oven slices; also the Dangote tower's height term. Structure shape, not a tier. */
    public static final int MAX_WIDTH = 15;
    /**
     * How many tiers an enum table has. Ordinals are the tiers and per-tier values are never read -
     * the probe asks the machine for those - so the enums are read for their length, by type.
     */
    public static final int MAX_ELECTRODE_TIER = ArcFurnaceElectrode.values().length - 1;
    public static final int MAX_SAWBLADE_TIER = SawbladeTiers.values().length - 1;

    public static final SettingDef<Integer> SOLENOID_DEF = SettingDef.intDef(
        Settings.GT_SOLENOID.key(),
        MAX_SOLENOID_TIER,
        MIN_SOLENOID_TIER,
        MAX_SOLENOID_TIER);
    public static final SettingDef<Integer> ITEM_PIPE_DEF = SettingDef
        .intDef(Settings.GT_ITEM_PIPE.key(), MAX_ITEM_PIPE_TIER, 1, MAX_ITEM_PIPE_TIER);
    /**
     * The pipe casings, weakest first, so tier 1 is Bronze. Both machines that read the setting agree on
     * this order: GT++'s Chemical Plant takes block meta 12 to 15 as tier 1 to 4, and GregTech's steam
     * multiblocks take the same two lowest metas as their tier 1 and 2.
     */
    private static final ItemList[] PIPE_CASINGS = { ItemList.Casing_Pipe_Bronze, ItemList.Casing_Pipe_Steel,
        ItemList.Casing_Pipe_Titanium, ItemList.Casing_Pipe_TungstenSteel };

    public static final int MAX_PIPE_CASING_TIER = PIPE_CASINGS.length;

    /** Filled on first use, because item display names need a registry that is empty at class load. */
    private static final String[] PIPE_CASING_NAMES = new String[PIPE_CASINGS.length];

    /** The pipe casing a node opens on. GregTech attaches no casing requirement to a recipe. */
    public static int defaultPipeCasingTier() {
        return ChartMinimums.floor(Settings.GT_PIPE_CASING, MAX_PIPE_CASING_TIER);
    }

    /**
     * GregTech's own name for the casing at a pipe casing tier, so the row names the block a player
     * places rather than a number only the code uses. Falls back to the tier when the item registry
     * has nothing, which is what a headless run sees.
     */
    @Nonnull
    public static String pipeCasingName(final int tier) {
        final int index = MathHelper.clamp_int(tier, 1, MAX_PIPE_CASING_TIER) - 1;
        if (PIPE_CASING_NAMES[index] == null) {
            PIPE_CASING_NAMES[index] = readItemName(PIPE_CASINGS[index], String.valueOf(index + 1));
        }
        return PIPE_CASING_NAMES[index];
    }

    /** The casing kind, which every row that shows one of these has already said in its own label. */
    private static final String PIPE_CASING_SUFFIX = " Pipe Casing";

    /**
     * The material rather than the whole item name, so a row reads "Pipe Casing Tungstensteel" the way
     * a coil row reads "Coil HSS-S" - GregTech names a coil by its material already, and names a
     * casing by material and kind together. A locale that words it differently keeps the full name,
     * which is long but never wrong.
     */
    @Nonnull
    private static String readItemName(final ItemList item, final String fallback) {
        try {
            final ItemStack stack = item.get(1);
            if (stack == null) return fallback;
            final String name = stack.getDisplayName();
            return name.endsWith(PIPE_CASING_SUFFIX) ? name.substring(0, name.length() - PIPE_CASING_SUFFIX.length())
                : name;
        } catch (final RuntimeException | LinkageError e) {
            return fallback;
        }
    }

    /**
     * Stores GregTech's tier number, which is what the machines read, and shows the casing it means.
     * An untouched row follows the chart, so it is an automatic row rather than one with a fixed
     * default.
     */
    public static final TierSetting PIPE_CASING_DEF = new TierSetting(
        Settings.GT_PIPE_CASING.key(),
        1,
        MAX_PIPE_CASING_TIER,
        (ctx, s) -> defaultPipeCasingTier(),
        GTSettings::pipeCasingName,
        null);
    public static final SettingDef<Integer> SAWBLADE_DEF = SettingDef
        .intDef(Settings.GT_SAWBLADE.key(), MAX_SAWBLADE_TIER, 0, MAX_SAWBLADE_TIER);
    public static final SettingDef<Integer> ELECTRODE_DEF = SettingDef
        .intDef(Settings.GT_ELECTRODE.key(), 0, 0, MAX_ELECTRODE_TIER);
    public static final SettingDef<Integer> STRUCTURE_TIER_DEF = SettingDef.intDef(Settings.GT_STRUCTURE_TIER.key(), 2, 0, 2);
    public static final SettingDef<Integer> WIDTH_DEF = SettingDef
        .intDef(Settings.GT_WIDTH.key(), MAX_WIDTH, 0, MAX_WIDTH);
    public static final SettingDef<Integer> MODE_DEF = SettingDef.intDef(Settings.GT_MODE.key(), 0, 0, GTMachineIndex::modeCeiling);

    /**
     * Every structure row, by the setting it edits. The profile builds its rows from this and the
     * probe sweeps its ranges from it, so the two cannot disagree about what a setting is - and
     * {@link StructureState} resolves every key here, so nothing can be a row without a value.
     */
    private static final Map<Settings, SettingDef<?>> STRUCTURE_ROWS = new EnumMap<>(Settings.class);

    static {
        STRUCTURE_ROWS.put(Settings.GT_COIL, COIL_DEF);
        STRUCTURE_ROWS.put(Settings.GT_SOLENOID, SOLENOID_DEF);
        STRUCTURE_ROWS.put(Settings.GT_ITEM_PIPE, ITEM_PIPE_DEF);
        STRUCTURE_ROWS.put(Settings.GT_PIPE_CASING, PIPE_CASING_DEF);
        STRUCTURE_ROWS.put(Settings.GT_SAWBLADE, SAWBLADE_DEF);
        STRUCTURE_ROWS.put(Settings.GT_ELECTRODE, ELECTRODE_DEF);
        STRUCTURE_ROWS.put(Settings.GT_STRUCTURE_TIER, STRUCTURE_TIER_DEF);
        STRUCTURE_ROWS.put(Settings.GT_WIDTH, WIDTH_DEF);
        STRUCTURE_ROWS.put(Settings.GT_MODE, MODE_DEF);
    }

    /**
     * The structure rows in {@link Settings} order, which is the order a node draws them in. An
     * {@link EnumMap} iterates in ordinal order, so the map and the panel agree without a sort.
     */
    @Nonnull
    public static Map<Settings, SettingDef<?>> structureRows() {
        return STRUCTURE_ROWS;
    }

    /**
     * The row a structure setting is edited through. Throws rather than answering null: every
     * structure setting has a row, so a caller arriving with one that does not is the bug.
     */
    @Nonnull
    public static SettingDef<?> structureDef(final Settings setting) {
        final SettingDef<?> def = STRUCTURE_ROWS.get(setting);
        if (def == null) throw new IllegalArgumentException(setting + " is not a structure setting");
        return def;
    }

    /**
     * Both ends a setting offers, read off the row that offers it. Anything that varies a setting -
     * the probe's sensitivity scan - then covers exactly what the player can reach, and one edit to a
     * row moves both.
     */
    @Nonnull
    public static int[] structureRange(final Settings setting) {
        // A sweep over modes takes its count from the machine, not from a range; the mode row's own
        // ceiling is a function of the selected machine and so cannot answer without one.
        if (setting == Settings.GT_MODE) return new int[] { 0, 1 };
        final SettingDef<?> def = structureDef(setting);
        return new int[] { def.minInt, def.maxInt };
    }

    public static boolean isAdvanced(final Map<String, Object> settings) {
        return MachineProfile.getBool(settings, ADVANCED, false);
    }

    /** Shows a setting only when the machine the node selected actually reads it. */
    @Nonnull
    public static BiPredicate<RecipeContext, Map<String, Object>> usesSetting(final Settings setting) {
        return (ctx, settings) -> {
            // Two conditions the shared predicate cannot know about: advanced mode replaces these rows
            // with the raw overclock ones, and a recipe that already implies its machine's mode has
            // answered the question the mode row would ask.
            if (isAdvanced(settings)) return false;
            final MachineVariant machine = MachineVariants.selected(ctx, settings);
            if (setting == Settings.GT_MODE && machine instanceof final GTMachineIndex.MachineEntry entry) {
                if (entry.modeFor(ctx.getOrDefault(GTProvider.RECIPE_MAP, null)) >= 0) return false;
            }
            return machine != null && machine.settings()
                .contains(setting);
        };
    }

    /** The raw overclock rows, shown only once the user has asked for them. */
    @Nonnull
    public static BiPredicate<RecipeContext, Map<String, Object>> advancedOnly() {
        return (ctx, settings) -> isAdvanced(settings);
    }

    /**
     * The preset already gives the machine's maximum, but planning for fewer than the structure
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
     * still needs a way to say which form factor it is, and because a preset may want to state it.
     * Read through {@link MachineVariant#tieredByBuild()} rather than off a GregTech type, so that the
     * one fact has one authority and the question generalizes to a mod whose build choice is not a
     * hatch.
     */
    public static final SettingDef<Boolean> MULTIBLOCK_DEF = SettingDef
        .autoBoolDef(Settings.GT_MULTIBLOCK.key(), (ctx, s) -> {
            final MachineVariant machine = MachineVariants.selected(ctx, s);
            return machine == null || machine.tieredByBuild() ? 1 : 0;
        }, (v, c) -> v ? "M" : null);

    /**
     * Voltage and amperage are choices only on a multiblock - a singleblock's tier is the block
     * placed. Shown when nothing resolved, so a node PlanNH cannot identify keeps a usable control.
     */
    public static boolean multiblockOrUnknown(final RecipeContext ctx, final Map<String, Object> settings) {
        return isAdvanced(settings) || MULTIBLOCK_DEF.effectiveBool(ctx, settings);
    }

}

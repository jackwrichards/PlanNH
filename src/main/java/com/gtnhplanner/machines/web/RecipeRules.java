package com.gtnhplanner.machines.web;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

/**
 * What runs a recipe and how its settings resolve, as the website does it (src/lib/model/recipe-rules.ts): the
 * recipe's machine handlers (dataset handlers folded into families, a fallback from the map's name, the Auto
 * Workbench for crafting), a handler applied to the recipe, the recipe's special value, and every setting resolved to
 * its rung. The crop farm's placeholder recipe is the website's alone (CropsNH) and is left out.
 */
public final class RecipeRules {

    private RecipeRules() {}

    /** MachineConfigTierControl: a setting resolved against a node, its rungs starting at its minimum. */
    public record TierControl(String id, String label, @Nullable Web.Numeric numeric, Web.TierOption minimum,
        Web.TierOption current, List<Web.TierOption> tiers, int minimumIndex, @Nullable Web.Resource resource) {}

    // region Handlers

    /** getRecipeMachineHandlers: one handler per family, the map's primary machine first. */
    public static List<Web.Handler> machineHandlers(final Web.Recipe recipe) {
        final Map<String, Web.Handler> byFamily = new LinkedHashMap<>();
        final Integer fusionMark = Fusion.isRecipe(recipe) ? Fusion.recipeMark(recipe) : null;
        if (recipe.machineHandlers != null) for (final Web.Handler handler : recipe.machineHandlers) {
            final Web.Handler normalized = Fusion.normalizeHandler(normalizeHandler(handler), recipe);
            final Fusion.Machine fusion = Fusion.machine(normalized.machineType);
            if (fusion != null && fusionMark != null && fusion.mark() < fusionMark) continue;
            final String familyId = (normalized.kind != null ? normalized.kind : "single") + ":"
                + slug(normalized.label);
            byFamily.putIfAbsent(familyId, normalized);
        }
        if (!byFamily.isEmpty()) return new ArrayList<>(byFamily.values());
        // A recipe beyond every reactor stays visibly blocked, never a generic machine.
        if (Fusion.isRecipe(recipe) && recipe.machineHandlers != null && !recipe.machineHandlers.isEmpty()) {
            final List<Web.Handler> out = new ArrayList<>();
            for (final Web.Handler h : recipe.machineHandlers) out.add(Fusion.normalizeHandler(h, recipe));
            return out;
        }
        final String base = familyLabel(recipe.machineType);
        final Web.Handler fallback = new Web.Handler();
        fallback.id = slug(base);
        fallback.label = base;
        fallback.machineType = base;
        fallback.minimumTier = recipe.minimumTier;
        fallback.kind = "single";
        if (isHandCraftingRecipeMap(recipe)) return List.of(autoWorkbenchHandler(), fallback);
        return List.of(Fusion.normalizeHandler(fallback, recipe));
    }

    /** isHandCraftingRecipeMap. */
    public static boolean isHandCraftingRecipeMap(final Web.Recipe recipe) {
        final String normalized = normalizeRecipeMapName(recipeMapName(recipe));
        return normalized.equals("shaped crafting") || normalized.equals("shapeless crafting");
    }

    public static final String AUTO_WORKBENCH_HANDLER_ID = "auto-workbench";

    /** GT++'s Electric Auto Workbench: 2048 EU a craft, so 64 ticks at 32 EU/t at LV. */
    private static Web.Handler autoWorkbenchHandler() {
        final Web.Handler h = new Web.Handler();
        h.id = AUTO_WORKBENCH_HANDLER_ID;
        h.label = "Auto Workbench";
        h.machineType = "Auto Workbench";
        h.minimumTier = "LV";
        h.kind = "single";
        h.durationTicks = 64.0;
        h.eut = 32.0;
        return h;
    }

    private static final Pattern STEAM = Pattern.compile("\\bsteam\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern HIGH_PRESSURE = Pattern.compile("\\bhigh pressure\\b", Pattern.CASE_INSENSITIVE);

    /** isSteamMachineHandler: the steam line burns steam, never EU. */
    public static boolean isSteamHandler(final Web.Handler handler) {
        return STEAM.matcher(handler.label)
            .find()
            || HIGH_PRESSURE.matcher(handler.label)
                .find();
    }

    /** isHighPressureSteamHandler: the steel half of the steam line. */
    public static boolean isHighPressureSteamHandler(final Web.Handler handler) {
        return HIGH_PRESSURE.matcher(handler.label)
            .find();
    }

    /** GT's fixed furnace recipe, which every GT furnace runs in place of vanilla smelting. */
    public static final double GT_FURNACE_RECIPE_TICKS = 128, GT_FURNACE_RECIPE_EUT = 4;

    private static final Pattern SMELTING = Pattern.compile("^(?:smelting|furnace)$", Pattern.CASE_INSENSITIVE);

    /** isSmeltingRecipeMap. */
    public static boolean isSmeltingRecipeMap(final Web.Recipe recipe) {
        return SMELTING.matcher(recipeMapName(recipe).trim())
            .matches();
    }

    /** A bronze steam singleblock runs twice the LV duration; a high pressure one the LV duration. */
    @Nullable
    private static Double steamSingleblockDurationTicks(final Web.Recipe recipe, final Web.Handler handler) {
        if ("multiblock".equals(handler.kind) || !isSteamHandler(handler)) return null;
        final double base = isSmeltingRecipeMap(recipe) ? GT_FURNACE_RECIPE_TICKS : recipe.durationTicks;
        return isHighPressureSteamHandler(handler) ? base : base * 2;
    }

    /** machineCycleTicks: a machine's own fixed cycle (the Extreme Heat Exchanger's 20 ticks). */
    @Nullable
    public static Integer machineCycleTicks(@Nullable final String machineType) {
        final MachineTable.Behaviour b = MachineTable.behaviour(machineType);
        return b != null ? b.cycleTicks : null;
    }

    /** TGS_MACHINE_TYPE. */
    public static final String TGS_MACHINE_TYPE = "Tree Growth Simulator";

    /** tgsEuPerTick: the Tree Growth Simulator draws VP[t], 30/32 of the tier's voltage. */
    static double tgsEuPerTick(final int t) {
        return Math.floor(Tiers.MAX_EUT[Math.min(t, Tiers.MAX_EUT.length - 1)] * 30 / 32);
    }

    /** getSelectedMachineHandler: the node's pick, else the map's primary machine. */
    public static Web.Handler selectedHandler(final Web.Recipe recipe, final Web.Node node) {
        final List<Web.Handler> handlers = machineHandlers(recipe);
        for (final Web.Handler h : handlers) if (h.id.equals(node.machineHandlerId)) return h;
        return handlers.get(0);
    }

    /**
     * applyMachineHandlerToRecipe: the recipe as the picked machine runs it. A machine the table covers seeds from the
     * map's base numbers (the dataset bakes its scraped multipliers into each handler); steam machines draw no EU; the
     * Tree Growth Simulator draws VP[1] and needs LV.
     */
    public static Web.Recipe applyHandler(final Web.Recipe recipe, final Web.Node node) {
        if (recipe.power != null) return recipe;
        final List<Web.Handler> handlers = machineHandlers(recipe);
        Web.Handler handler = handlers.get(0);
        for (final Web.Handler h : handlers) if (h.id.equals(node.machineHandlerId)) {
            handler = h;
            break;
        }
        final List<Web.Control> controls = handler.machineConfigControls != null ? handler.machineConfigControls
            : recipe.machineConfigControls;
        final boolean seedsFromBase = MachineTable.seedsFromBase(handler.machineType);
        final MachineTable.Behaviour behaviour = MachineTable.behaviour(handler.machineType);
        final String minimumTier = behaviour != null && behaviour.recipeTierFromBase ? recipe.minimumTier
            : handler.minimumTier;
        final Integer cycle = machineCycleTicks(handler.machineType);
        final Double handlerDurationTicks = cycle != null ? Double.valueOf(cycle)
            : seedsFromBase ? steamSingleblockDurationTicks(recipe, handler)
                : handler.durationTicks != null ? handler.durationTicks
                    : steamSingleblockDurationTicks(recipe, handler);
        final Double handlerEut = seedsFromBase ? null : handler.eut;
        final boolean tgs = TGS_MACHINE_TYPE.equals(handler.machineType);
        final double eut = tgs ? tgsEuPerTick(1)
            : isSteamHandler(handler) ? 0 : handlerEut != null ? handlerEut : recipe.eut;

        final Web.Recipe out = recipe.copy();
        // The game's ladder was run for the map's own machine: another machine falls back to the port's maths.
        out.runtimeCalculation = handler.id.equals(handlers.get(0).id) ? recipe.runtimeCalculation : null;
        out.machineType = handler.machineType;
        out.minimumTier = tgs ? "LV" : minimumTier;
        out.maximumTier = handler.maximumTier;
        out.availableTiers = handler.availableTiers;
        out.durationTicks = handlerDurationTicks != null ? handlerDurationTicks : recipe.durationTicks;
        out.eut = eut;
        out.machineConfigControls = controls;
        final Web.Profile from = recipe.machineProfile;
        final Web.Profile p = new Web.Profile();
        p.machineType = handler.machineType;
        p.minimumTier = minimumTier;
        p.maximumTier = handler.maximumTier;
        p.availableTiers = handler.availableTiers;
        p.durationTicks = handlerDurationTicks != null ? handlerDurationTicks
            : from != null ? from.durationTicks : null;
        p.eut = handlerEut != null ? handlerEut : from != null ? from.eut : null;
        p.maxParallel = handler.maxParallel != null ? handler.maxParallel : from != null ? from.maxParallel : null;
        p.eutLimit = handler.eutLimit != null ? handler.eutLimit : from != null ? from.eutLimit : null;
        p.perfectOverclock = handler.perfectOverclock != null ? handler.perfectOverclock
            : from != null ? from.perfectOverclock : null;
        p.kind = handler.kind != null ? handler.kind : from != null ? from.kind : null;
        p.notes = handler.notes != null ? handler.notes : from != null ? from.notes : null;
        out.machineProfile = p;
        return out;
    }

    // endregion

    // region Settings

    /** getRecipeCoilTierControl: the heating coil, unless the table hides it. */
    @Nullable
    public static TierControl coilTierControl(final Web.Recipe recipe, @Nullable final String coilTier) {
        if (MachineTable.hiddenControlIds(recipe.machineType)
            .contains("heatingCoil")) return null;
        Web.Control control = null;
        for (final Web.Control c : MachineTable.controls(recipe.machineType, null)) if (c.id.equals("heatingCoil")) {
            control = c;
            break;
        }
        if (control == null) control = find(recipe, "heatingCoil");
        return control != null ? resolve(applyRecipeMinimum(control, recipe), coilTier) : null;
    }

    /** getRecipeMachineConfigTierControls: every other setting, the table's over the dataset's, resolved. */
    public static List<TierControl> configTierControls(final Web.Recipe recipe,
        @Nullable final Map<String, String> machineConfigTiers) {
        final MachineTable.Behaviour b = MachineTable.behaviour(recipe.machineType);
        final Map<String, String> settings = b != null && b.normalizeConfig != null
            ? b.normalizeConfig.apply(machineConfigTiers != null ? machineConfigTiers : Collections.emptyMap())
            : machineConfigTiers;
        final List<Web.Control> controls = dropHidden(
            merge(
                recipe.machineConfigControls != null ? recipe.machineConfigControls : Collections.emptyList(),
                MachineTable.controls(recipe.machineType, recipe)),
            recipe.machineType);
        final List<TierControl> out = new ArrayList<>();
        for (final Web.Control control : controls) {
            if (control.id.equals("heatingCoil")) continue;
            final TierControl resolved = resolve(
                applyRecipeMinimum(control, recipe),
                settings != null ? settings.get(control.id) : null);
            if (resolved != null) out.add(resolved);
        }
        return out;
    }

    /** The table's controls win over the dataset's of the same id; the dataset's others stay. */
    private static List<Web.Control> merge(final List<Web.Control> fromDataset, final List<Web.Control> fromTable) {
        if (fromTable.isEmpty()) return fromDataset;
        final List<Web.Control> out = new ArrayList<>();
        outer: for (final Web.Control c : fromDataset) {
            for (final Web.Control t : fromTable) if (t.id.equals(c.id)) continue outer;
            out.add(c);
        }
        out.addAll(fromTable);
        return out;
    }

    private static List<Web.Control> dropHidden(final List<Web.Control> controls, @Nullable final String machineType) {
        final List<String> hidden = MachineTable.hiddenControlIds(machineType);
        if (hidden.isEmpty()) return controls;
        final List<Web.Control> out = new ArrayList<>();
        for (final Web.Control c : controls) if (!hidden.contains(c.id)) out.add(c);
        return out;
    }

    /**
     * snapNumericSetting: snapped to the step (whole numbers by default) and clamped. {@code toFixed(6)} rounds the
     * exact binary value half up, as BigDecimal does here.
     */
    public static double snapNumeric(final double value, final Web.Numeric numeric) {
        final double max = numeric.max != null ? numeric.max : 9007199254740991d;
        final double snapped = numeric.step != null && numeric.step != 0
            ? new BigDecimal(Js.round(value / numeric.step) * numeric.step).setScale(6, RoundingMode.HALF_UP)
                .doubleValue()
            : (value < 0 ? Math.ceil(value) : Math.floor(value));
        return Math.min(max, Math.max(numeric.min, snapped));
    }

    /** getAdjacentMachineConfigTier: the next rung up or down, never below the minimum. */
    public static String adjacentTier(final TierControl control, final int direction) {
        if (control.numeric != null) return Js.str(
            snapNumeric(
                Js.number(control.current.key) + direction * (control.numeric.step != null ? control.numeric.step : 1),
                control.numeric));
        final int current = indexOf(control.tiers, control.current.key);
        final int minimum = indexOf(control.tiers, control.minimum.key);
        final int next = Math.min(control.tiers.size() - 1, Math.max(Math.max(0, minimum), current + direction));
        return next >= 0 && next < control.tiers.size() ? control.tiers.get(next).key : control.current.key;
    }

    private static final Pattern SPECIAL_VALUE = Pattern
        .compile("special\\s+value\\s*:\\s*(-?\\d+)", Pattern.CASE_INSENSITIVE);

    /** getRecipeSpecialValue: NEI's "Special value: N" line. */
    @Nullable
    public static Integer specialValue(final Web.Recipe recipe) {
        if (recipe.nei == null || recipe.nei.additionalInfo == null) return null;
        for (final String entry : recipe.nei.additionalInfo) {
            final Matcher m = SPECIAL_VALUE.matcher(entry);
            if (m.find()) return Integer.parseInt(m.group(1));
        }
        return null;
    }

    @Nullable
    private static Web.Control find(final Web.Recipe recipe, final String id) {
        if (recipe.machineConfigControls != null)
            for (final Web.Control c : recipe.machineConfigControls) if (c.id.equals(id)) return c;
        return null;
    }

    /**
     * applyControlRecipeMinimum: a control whose minimum is the recipe's special value, as a 1-based rung or as a heat
     * in K the coil must meet.
     */
    private static Web.Control applyRecipeMinimum(final Web.Control control, final Web.Recipe recipe) {
        if (!Boolean.TRUE.equals(control.minimumFromSpecialValue)
            && !Boolean.TRUE.equals(control.minimumHeatFromSpecialValue)) return control;
        final Integer special = specialValue(recipe);
        if (special == null || special < 1) return control;
        Web.TierOption minimum = null;
        if (Boolean.TRUE.equals(control.minimumHeatFromSpecialValue)) {
            for (final Web.TierOption t : control.tiers) if ((t.heat != null ? t.heat : 0) >= special) {
                minimum = t;
                break;
            }
        } else {
            final int i = Math.min(control.tiers.size(), special) - 1;
            if (i >= 0) minimum = control.tiers.get(i);
        }
        if (minimum == null) return control;
        final Web.Control c = control.copy();
        c.minimumKey = minimum.key;
        return c;
    }

    /** resolveMachineConfigTierControl: the node's pick, else the default, else the minimum. */
    @Nullable
    private static TierControl resolve(final Web.Control control, @Nullable final String selectedKey) {
        Web.TierOption minimum = null;
        for (final Web.TierOption t : control.tiers) if (t.key.equals(control.minimumKey)) {
            minimum = t;
            break;
        }
        if (minimum == null && !control.tiers.isEmpty()) minimum = control.tiers.get(0);
        if (minimum == null) return null;

        if (control.numeric != null) {
            final String typed = selectedKey != null && !selectedKey.trim()
                .isEmpty() ? selectedKey.trim()
                    : control.defaultKey != null && !control.defaultKey.isEmpty() ? control.defaultKey
                        : control.minimumKey;
            final double raw = Js.number(typed);
            final double value = Double.isFinite(raw) ? snapNumeric(raw, control.numeric) : control.numeric.min;
            final Web.TierOption current = minimum.copy();
            current.key = Js.str(value);
            current.label = Js.str(value);
            final Web.Resource icon = new Web.Resource();
            if (minimum.resource != null) {
                icon.kind = minimum.resource.kind;
                icon.id = minimum.resource.id;
                icon.amount = minimum.resource.amount;
                icon.chance = minimum.resource.chance;
                icon.consumed = minimum.resource.consumed;
                icon.optional = minimum.resource.optional;
                icon.tooltip = minimum.resource.tooltip;
            }
            icon.displayName = control.label + ": " + Js.str(value);
            current.resource = icon;
            return new TierControl(
                control.id,
                control.label,
                control.numeric,
                minimum,
                current,
                List.of(current),
                0,
                icon);
        }

        final int minimumIndex = Math.max(0, indexOf(control.tiers, minimum.key));
        final List<Web.TierOption> tiers = control.tiers.subList(minimumIndex, control.tiers.size());
        Web.TierOption selected = null, byDefault = null;
        for (final Web.TierOption t : tiers) {
            if (selected == null && t.key.equals(selectedKey)) selected = t;
            if (byDefault == null && t.key.equals(control.defaultKey)) byDefault = t;
        }
        final Web.TierOption current = selected != null ? selected : byDefault != null ? byDefault : minimum;
        return new TierControl(
            control.id,
            control.label,
            null,
            minimum,
            current,
            tiers,
            minimumIndex,
            current.resource);
    }

    private static int indexOf(final List<Web.TierOption> tiers, @Nullable final String key) {
        for (int i = 0; i < tiers.size(); i++) if (tiers.get(i).key.equals(key)) return i;
        return -1;
    }

    // endregion

    // region Names

    /** recipeMapName: the recipe's map, else its machine. */
    public static String recipeMapName(final Web.Recipe recipe) {
        return recipe.source != null && recipe.source.recipeMap != null ? recipe.source.recipeMap : recipe.machineType;
    }

    /** Multiblocks and reactors keep their names; singleblocks fold into their family. */
    private static Web.Handler normalizeHandler(final Web.Handler handler) {
        if ("multiblock".equals(handler.kind) || Fusion.machine(handler.machineType) != null) return handler;
        final Web.Handler h = handler.copy();
        h.label = familyLabel(handler.label);
        h.machineType = familyLabel(handler.machineType);
        return h;
    }

    private static final Pattern TIER_SUFFIX = Pattern
        .compile("\\s+\\((?:ULV|LV|MV|HV|EV|IV|LuV|ZPM|UV|UHV|UEV|UIV|UMV|UXV|OpV|MAX)\\)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMERAL_SUFFIX = Pattern
        .compile("\\s+(?:I|II|III|IV|V|VI|VII|VIII|IX|X)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern GRADE_PREFIX = Pattern.compile(
        "^(?:Basic|Advanced|Elite|Ultimate|Epic|MAX|Turbo|Quick|Instant|Universal)\\s+",
        Pattern.CASE_INSENSITIVE);

    /** machineHandlerFamilyLabel: a singleblock's family, without its tier, numeral, grade or tier name. */
    public static String familyLabel(final String label) {
        if (Fusion.machine(label) != null) return label;
        final String tierless = NUMERAL_SUFFIX.matcher(
            TIER_SUFFIX.matcher(label)
                .replaceFirst(""))
            .replaceFirst("")
            .trim();
        final String direct = FAMILY_ALIASES.get(normalizeMachineLabel(tierless));
        if (direct != null) return direct;
        final String family = GRADE_PREFIX.matcher(tierless)
            .replaceFirst("")
            .trim();
        final String alias = FAMILY_ALIASES.get(normalizeMachineLabel(family));
        return alias != null ? alias : family;
    }

    /** MACHINE_HANDLER_FAMILY_ALIASES: singleblock tier names to their family. */
    private static final Map<String, String> FAMILY_ALIASES = new HashMap<>();

    static {
        final String[][] aliases = { { "alloy integrator", "Alloy Smelter" }, { "amplifabricator", "Matter Amplifier" },
            { "amplicreator", "Matter Amplifier" }, { "assembling machine", "Assembler" },
            { "assembly constructor", "Assembler" }, { "atom stimulator", "Electric Furnace" },
            { "blaze sweatshop t-6350", "Thermal Centrifuge" }, { "can operator", "Canner" },
            { "centrifuge", "Centrifuge" }, { "chemical dunktron", "Chemical Bath" },
            { "chemical perforer", "Chemical Reactor" }, { "chemical performer", "Chemical Reactor" },
            { "circuit assembling machine", "Circuit Assembler" }, { "electric oven", "Electric Furnace" },
            { "electron excitement processor", "Electric Furnace" }, { "exact photon cannon", "Laser Engraver" },
            { "extractinator", "Extractor" }, { "fermentation hastener", "Fermenter" },
            { "fire cyclone", "Thermal Centrifuge" }, { "fluid petrificator", "Fluid Solidifier" },
            { "fraction splitter", "Distillery" }, { "heat infuser", "Fluid Heater" },
            { "impact modulator", "Forge Hammer" }, { "ionizer", "Electrolyzer" },
            { "liquid can actuator", "Fluid Canner" }, { "liquefying sucker", "Fluid Extractor" },
            { "magnetar separator", "Electromagnetic Separator" }, { "magnetism inducer", "Electromagnetic Polarizer" },
            { "matter constrictor", "Compressor" }, { "matter organizer", "Mixer" },
            { "molecular cyclone", "Centrifuge" }, { "molecular disintegrator e-4908", "Electrolyzer" },
            { "molecular separator", "Centrifuge" }, { "molecular tornado", "Centrifuge" },
            { "object divider", "Cutting Machine" }, { "oblitterator", "Recycler" },
            { "ore washing machine", "Ore Washer" }, { "ore washing plant", "Ore Washer" },
            { "polarizer", "Electromagnetic Polarizer" }, { "precision laser engraver", "Laser Engraver" },
            { "pressure cooker", "Autoclave" }, { "pulsation filter", "Sifter" }, { "pulverizer", "Macerator" },
            { "repurposed laundry-washer i-360", "Ore Washer" }, { "scrap-o-matic", "Recycler" },
            { "shape driver", "Extruder" }, { "shape eliminator", "Macerator" },
            { "short circuit heater", "Arc Furnace" }, { "sifting machine", "Sifter" },
            { "singularity compressor", "Compressor" }, { "surface shifter", "Forming Press" },
            { "the oblitterator", "Recycler" }, { "turn-o-matic", "Lathe" }, { "ufo engine", "Microwave" },
            { "unboxinator", "Unpackager" }, { "vacuum extractor", "Extractor" },
            { "wire transfigurator", "Wiremill" } };
        for (final String[] a : aliases) FAMILY_ALIASES.put(a[0], a[1]);
    }

    private static String slug(final String value) {
        return normalizeRecipeMapName(value).replaceAll("[^a-z0-9]+", "-");
    }

    private static String normalizeMachineLabel(final String value) {
        return value.trim()
            .replaceAll("\\s+", " ")
            .toLowerCase(Locale.ROOT);
    }

    /** normalizeRecipeMapName: lower case, without "recipe(s)" or "recipe map", runs of the rest one space. */
    static String normalizeRecipeMapName(final String recipeMap) {
        return recipeMap.trim()
            .toLowerCase(Locale.ROOT)
            .replaceAll("\\brecipes?\\b", "")
            .replaceAll("\\brecipe\\s+map\\b", "")
            .replaceAll("[^a-z0-9]+", " ")
            .replaceAll("\\s+", " ")
            .trim();
    }

    // endregion
}

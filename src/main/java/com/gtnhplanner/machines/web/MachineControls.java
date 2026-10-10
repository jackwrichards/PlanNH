package com.gtnhplanner.machines.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * The machine table's shared settings (machine-table.ts): the control ids its formulas read, the helpers that build a
 * setting from a list of options, and the controls and lookup rows several entries share. Transcribed one for one: an
 * option's position is what the formulas read, and keys, labels and icon ids are the website's. Pure: no game classes.
 */
public final class MachineControls {

    private MachineControls() {}

    // region Control ids

    public static final String COIL = "heatingCoil";
    /** The steam multiblocks' build tier control; the steam bill (power-report.ts) reads the selection. */
    public static final String STEAM_PRESSURE = "steamPressure";
    public static final String PIPE = "pipeCasing";
    public static final String SOLENOID = "solenoidCoil";
    public static final String ITEM_PIPE = "itemPipeCasing";
    public static final String ELECTRODE = "arcElectrode";
    public static final String SAWBLADE = "sawblade";
    public static final String UPGRADE_CHIP = "maceratorUpgrade";
    public static final String CONTAINMENT = "containmentBlockTier";
    public static final String ELECTROMAGNET = "electromagnet";
    /** Fluid Shaper width expansions, 0 through the structure's 6 maximum. */
    public static final String WIDTH_EXPANSION = "widthExpansion";
    public static final String LATEX_SINGULARITY = "latexSingularity";
    /** The Naquadah Fuel Refinery's field restriction coils. */
    public static final String FIELD_COIL = "fieldRestrictionCoil";
    /** The dataset's own coke oven knobs; slice options are keyed "slice-1" upward. */
    public static final String COKE_CASING = "cokeOvenCasing";
    public static final String COKE_SLICES = "cokeOvenSlices";
    public static final String SPIN_MODE = "spinmatronMode";
    public static final String TURBINE_TIER = "sumTurbineTier";
    public static final String SPIN_FUEL = "spinmatronFuel";

    // endregion

    // region Builders

    /**
     * An option of {@link #choiceControl}: a plain label (its icon generated from the control's id, as the website's
     * string options) when {@code icon} is null, else a label with a block to show and that block's full name.
     */
    public record Option(String label, @Nullable String icon, @Nullable String name) {}

    /** choiceControl with plain labels: the option's position is the value the formulas read. */
    static Web.Control choiceControl(final String id, final String label, final String... options) {
        final List<Option> list = new ArrayList<>();
        for (final String option : options) list.add(new Option(option, null, null));
        return choiceControl(id, label, list, 0);
    }

    static Web.Control choiceControl(final String id, final String label, final List<Option> options) {
        return choiceControl(id, label, options, 0);
    }

    /**
     * choiceControl: one of the table's settings from a list of options, in the reference's order. Plain options get
     * a generated {@code factoryflow:machine_config/<slug>_<index>} icon, which the config UI shows as a labelled slot.
     */
    static Web.Control choiceControl(final String id, final String label, final List<Option> options,
        final int defaultIndex) {
        final List<Web.TierOption> tiers = new ArrayList<>();
        for (int index = 0; index < options.size(); index++) {
            final Option option = options.get(index);
            final boolean plain = option.icon() == null;
            final String optionLabel = option.label();
            final String icon = plain ? "factoryflow:machine_config/" + slug(id) + "_" + index : option.icon();
            final String slugged = slug(optionLabel);
            final Web.TierOption tier = new Web.TierOption();
            tier.key = !slugged.isEmpty() ? slugged : "option-" + index;
            tier.label = optionLabel;
            // The block's full name: the config UI also matches icons by name, and a bare "Tin" could land on some
            // other item called Tin.
            tier.resource = item(
                icon,
                plain ? optionLabel : (option.name() != null ? option.name() : optionLabel),
                List.of(label),
                false);
            tiers.add(tier);
        }
        final Web.Control control = new Web.Control();
        control.id = id;
        control.label = label;
        control.minimumKey = tiers.get(0).key;
        control.defaultKey = defaultIndex >= 0 && defaultIndex < tiers.size() ? tiers.get(defaultIndex).key
            : tiers.get(0).key;
        control.tiers = tiers;
        return control;
    }

    /** countControl with the first value as default. */
    static Web.Control countControl(final String id, final String label, final int... values) {
        return countControl(id, label, values, 0);
    }

    /**
     * countControl: a count the reference models as unbounded (slices, catalysts, laser amperage), offered as a
     * discrete ladder keyed by the number itself.
     */
    static Web.Control countControl(final String id, final String label, final int[] values, final int defaultIndex) {
        final Web.Control control = new Web.Control();
        control.id = id;
        control.label = label;
        control.minimumKey = String.valueOf(values[0]);
        control.defaultKey = String
            .valueOf(defaultIndex >= 0 && defaultIndex < values.length ? values[defaultIndex] : values[0]);
        final List<Web.TierOption> tiers = new ArrayList<>();
        for (final int value : values) {
            final Web.TierOption tier = new Web.TierOption();
            tier.key = String.valueOf(value);
            tier.label = String.valueOf(value);
            tier.resource = item(
                "factoryflow:machine_config/" + slug(id) + "_" + value,
                label + ": " + value,
                List.of(label),
                false);
            tiers.add(tier);
        }
        control.tiers = tiers;
        return control;
    }

    /** slug: lower case, every run of anything but a-z and 0-9 one dash, a dash at either end dropped. */
    static String slug(final String value) {
        return value.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("^-|-$", "");
    }

    /** row: a lookup row, clamped so an out-of-range selection cannot crash. */
    static <T> T row(final List<T> table, final int index) {
        return table.get(Math.min(Math.max(0, index), table.size() - 1));
    }

    /** An item resource of one, as the table's controls show them; null fields are left out, as on the website. */
    static Web.Resource item(final String id, final String displayName, @Nullable final List<String> tooltip,
        @Nullable final Boolean consumed) {
        final Web.Resource resource = new Web.Resource();
        resource.kind = "item";
        resource.id = id;
        resource.amount = 1;
        resource.displayName = displayName;
        resource.tooltip = tooltip;
        resource.consumed = consumed;
        return resource;
    }

    /** A copy of the settings with one key set, as the website's {@code {...settings, [key]: value}}. */
    static Map<String, String> with(final Map<String, String> settings, final String key, final String value) {
        final Map<String, String> out = new LinkedHashMap<>(settings);
        out.put(key, value);
        return out;
    }

    // endregion

    // region Pipe casings

    private static final String[] ITEM_PIPE_LABELS = { "Tin", "Brass", "Electrum", "Platinum", "Osmium", "Quantium",
        "Fluxed Electrum", "Black Plutonium" };

    /**
     * ITEM_PIPE_CONTROL: item pipe casings in the reference's order, gt.blockcasings11 metas 0-7, which every item pipe
     * machine reads as tier meta + 1 (tin = 1).
     */
    public static final Web.Control ITEM_PIPE_CONTROL;

    static {
        final List<Option> options = new ArrayList<>();
        for (int meta = 0; meta < ITEM_PIPE_LABELS.length; meta++) {
            final String label = ITEM_PIPE_LABELS[meta];
            options.add(
                new Option(
                    label,
                    meta == 0 ? "gregtech:gt.blockcasings11" : "gregtech:gt.blockcasings11@" + meta,
                    label + " Item Pipe Casing"));
        }
        ITEM_PIPE_CONTROL = choiceControl(ITEM_PIPE, "Item Pipe Casing", options);
    }

    private static final String[] FLUID_PIPE_LABELS = { "Bronze", "Steel", "Titanium", "Tungstensteel" };

    /**
     * FLUID_PIPE_CONTROL: the fluid pipe casings the two machines that read them take, gt.blockcasings2 metas 12-15.
     * Keys match the dataset's control, so a saved pick carries straight over.
     */
    public static final Web.Control FLUID_PIPE_CONTROL;

    static {
        final List<Option> options = new ArrayList<>();
        for (int index = 0; index < FLUID_PIPE_LABELS.length; index++) {
            final String label = FLUID_PIPE_LABELS[index];
            options.add(new Option(label, "gregtech:gt.blockcasings2@" + (12 + index), label + " Pipe Casing"));
        }
        FLUID_PIPE_CONTROL = choiceControl(PIPE, "Pipe Casing", options);
    }

    /** The scraper's fluid pipe ladder, including the two rungs no machine takes. */
    static final List<String> SCRAPED_FLUID_PIPE_KEYS = List
        .of("bronze", "steel", "titanium", "tungstensteel", "ptfe", "pbi");

    /** normalizeFluidPipeSettings: a plan saved on PTFE or PBI keeps the best casing the machine really takes. */
    static Map<String, String> normalizeFluidPipeSettings(final Map<String, String> settings) {
        final String key = settings.get(PIPE);
        return "ptfe".equals(key) || "pbi".equals(key) ? with(settings, PIPE, "tungstensteel") : settings;
    }

    /**
     * normalizeLatheSettings: a lathe saved on the scraped fluid pipe ladder keeps its parallels; both ladders give 8
     * a rung, so the saved rung's position picks the matching item pipe casing.
     */
    static Map<String, String> normalizeLatheSettings(final Map<String, String> settings) {
        if (settings.get(ITEM_PIPE) != null) return settings;
        final String saved = settings.get(PIPE);
        final int position = SCRAPED_FLUID_PIPE_KEYS.indexOf(saved != null ? saved : "");
        final Web.TierOption carried = position >= 0 && position < ITEM_PIPE_CONTROL.tiers.size()
            ? ITEM_PIPE_CONTROL.tiers.get(position)
            : null;
        return carried != null ? with(settings, ITEM_PIPE, carried.key) : settings;
    }

    // endregion

    // region Choice controls and their rows

    public static final Web.Control CONTAINMENT_CONTROL = choiceControl(
        CONTAINMENT,
        "Containment Block",
        "Neutronium",
        "Infinity",
        "Transcendent Metal",
        "SpaceTime",
        "Universum");

    public static final Web.Control UPGRADE_CHIP_CONTROL = choiceControl(
        UPGRADE_CHIP,
        "Upgrade Chip",
        "No Upgrade",
        "Maceration Upgrade Chip");

    /**
     * An Industrial Arc Furnace electrode (kubatech's ArcFurnaceElectrode): speed, parallel limit, the overclock's own
     * speed factor (every step still costs 4x EU/t), and EU modifier.
     */
    public record Electrode(String name, double speed, double parallels, double oc, double power) {}

    /** ELECTRODES. Infinity's parallel limit is unlimited in code; the voltage cap is what really binds. */
    public static final List<Electrode> ELECTRODES = List.of(
        new Electrode("Graphite", 1, 4, 2, 1),
        new Electrode("Tantalum", 1.2, 2, 4, 1.2),
        new Electrode("Molybdenum", 0.9, 16, 3, 0.8),
        new Electrode("Tungsten", 1, 128, 1, 0.75),
        new Electrode("Tungstensteel", 0.8, 256, 1, 0.5),
        new Electrode("Graphene", 2.5, 16, 2, 1),
        new Electrode("YBCO", 1.2, 8, 6, 1),
        new Electrode("Netherite", 2.2, 64, 1.5, 1.25),
        new Electrode("Tritanium", 3, 48, 2, 1.1),
        new Electrode("Infinity", 4.2, Double.POSITIVE_INFINITY, 1, 1),
        new Electrode("Hypogen", 6.5, 256, 1, 1.3),
        new Electrode("Neutronium Nanite", 5, 256, 2, 2),
        new Electrode("Transcendent Nanite", 7.5, 512, 4, 1.5),
        new Electrode("Universium Nanite", 10, 1024, 8, 1));

    /** A sawblade or electromagnet row: speed, EU modifier and parallels. */
    public record Part(String name, double speed, double power, double parallels) {}

    /** SAWBLADES. */
    public static final List<Part> SAWBLADES = List.of(
        new Part("Tungsten Titanium Carbide", 2.5, 0.9, 2),
        new Part("Mysterious Crystal", 3, 0.8, 3),
        new Part("Neutronium", 3.5, 0.7, 4),
        new Part("Transcendent Metal", 4.5, 0.6, 6));

    /** ELECTROMAGNETS. */
    public static final List<Part> ELECTROMAGNETS = List.of(
        new Part("Iron Electromagnet", 1.1, 0.8, 8),
        new Part("Steel Electromagnet", 1.25, 0.75, 24),
        new Part("Neodymium Electromagnet", 1.5, 0.7, 48),
        new Part("Samarium Electromagnet", 2, 0.6, 96),
        new Part("Tengam Electromagnet", 2.5, 0.5, 256));

    public static final Web.Control ELECTRODE_CONTROL = choiceControl(
        ELECTRODE,
        "Electrode",
        ELECTRODES.stream()
            .map(Electrode::name)
            .toArray(String[]::new));
    public static final Web.Control SAWBLADE_CONTROL = choiceControl(
        SAWBLADE,
        "Sawblade",
        SAWBLADES.stream()
            .map(Part::name)
            .toArray(String[]::new));
    public static final Web.Control ELECTROMAGNET_CONTROL = choiceControl(
        ELECTROMAGNET,
        "Electromagnet",
        ELECTROMAGNETS.stream()
            .map(Part::name)
            .toArray(String[]::new));
    public static final Web.Control COOLANT_CONTROL = choiceControl(
        "fridgeCoolant",
        "Coolant",
        "No Coolant",
        "Molten SpaceTime",
        "Spatially Enlarged Fluid",
        "Molten Eternity");
    public static final Web.Control PLASMA_MIXER_PARALLEL_CONTROL = countControl(
        "plasmaMixerParallels",
        "Parallels",
        1,
        2,
        4,
        8,
        16,
        32,
        64,
        128,
        256);
    public static final Web.Control WIDTH_EXPANSION_CONTROL = countControl(
        WIDTH_EXPANSION,
        "Width Expansion",
        0,
        1,
        2,
        3,
        4,
        5,
        6);
    public static final Web.Control LATEX_SINGULARITY_CONTROL = choiceControl(
        LATEX_SINGULARITY,
        "Controller Slot",
        "Empty",
        "Elastic Singularity");

    // endregion

    // region Heating coils

    /** A heating coil: the dataset's key, its label, the heat it gives the machine, and its block. */
    public record Coil(String key, String label, int heat, String block) {}

    /**
     * HEATING_COIL_TIERS: the fourteen heating coils, for machines whose coil the dataset does not offer as a knob.
     * Keys match the dataset's own coil control, so a saved {@code coilTier} carries straight over.
     */
    public static final List<Coil> HEATING_COIL_TIERS = List.of(
        new Coil("cupronickel", "Cupronickel", 1801, "gregtech:gt.blockcasings5"),
        new Coil("kanthal", "Kanthal", 2701, "gregtech:gt.blockcasings5@1"),
        new Coil("nichrome", "Nichrome", 3601, "gregtech:gt.blockcasings5@2"),
        new Coil("tpv", "TPV-Alloy", 4501, "gregtech:gt.blockcasings5@3"),
        new Coil("hss_g", "HSS-G", 5401, "gregtech:gt.blockcasings5@4"),
        new Coil("hss_s", "HSS-S", 6301, "gregtech:gt.blockcasings5@9"),
        new Coil("naquadah", "Naquadah", 7201, "gregtech:gt.blockcasings5@5"),
        new Coil("naquadah_alloy", "Naquadah Alloy", 8101, "gregtech:gt.blockcasings5@6"),
        new Coil("trinium", "Trinium", 9001, "gregtech:gt.blockcasings5@10"),
        new Coil("electrum_flux", "Electrum Flux", 9901, "gregtech:gt.blockcasings5@7"),
        new Coil("awakened_draconium", "Awakened Draconium", 10801, "gregtech:gt.blockcasings5@8"),
        new Coil("infinity", "Infinity", 11701, "gregtech:gt.blockcasings5@11"),
        new Coil("hypogen", "Hypogen", 12601, "gregtech:gt.blockcasings5@12"),
        new Coil("eternal", "Eternal", 13501, "gregtech:gt.blockcasings5@13"));

    /** HEATING_COIL_CONTROL. */
    public static final Web.Control HEATING_COIL_CONTROL;

    static {
        final Web.Control control = new Web.Control();
        control.id = "heatingCoil";
        control.label = "Heating Coil";
        control.minimumKey = "cupronickel";
        control.defaultKey = "cupronickel";
        final List<Web.TierOption> tiers = new ArrayList<>();
        for (final Coil coil : HEATING_COIL_TIERS) {
            final Web.TierOption tier = new Web.TierOption();
            tier.key = coil.key();
            tier.label = coil.label();
            tier.heat = (double) coil.heat();
            tier.resource = item(
                coil.block(),
                coil.label() + " Coil Block",
                List.of("Heating coil tier", "Heat capacity: " + coil.heat() + " K"),
                false);
            tiers.add(tier);
        }
        control.tiers = tiers;
        HEATING_COIL_CONTROL = control;
    }

    /** A field restriction coil: its key, the block's name, and the block (GoodGenerator's FRF_Coil_1..4). */
    public record FieldCoil(String key, String label, String block) {}

    /** FIELD_COIL_TIERS. */
    public static final List<FieldCoil> FIELD_COIL_TIERS = List.of(
        new FieldCoil("t1", "Field Restriction Coil", "goodgenerator:frf_coil_1"),
        new FieldCoil("t2", "Advanced Field Restriction Coil", "goodgenerator:frf_coil_2"),
        new FieldCoil("t3", "Ultimate Field Restriction Coil", "goodgenerator:frf_coil_3"),
        new FieldCoil("t4", "Temporal Field Restriction Coil", "goodgenerator:frf_coil_4"));

    /**
     * FIELD_COIL_CONTROL: each recipe's special value is its minimum coil tier (1-based), which
     * {@code minimumFromSpecialValue} turns into the control's per-recipe floor.
     */
    public static final Web.Control FIELD_COIL_CONTROL;

    static {
        final Web.Control control = new Web.Control();
        control.id = FIELD_COIL;
        // "Field Coil", not the full block name: the config panel's knob labels sit in a half-card column.
        control.label = "Field Coil";
        control.minimumKey = "t1";
        control.defaultKey = "t1";
        control.minimumFromSpecialValue = true;
        final List<Web.TierOption> tiers = new ArrayList<>();
        for (int index = 0; index < FIELD_COIL_TIERS.size(); index++) {
            final FieldCoil coil = FIELD_COIL_TIERS.get(index);
            final Web.TierOption tier = new Web.TierOption();
            tier.key = coil.key();
            tier.label = "T" + (index + 1) + " " + coil.label();
            tier.resource = item(
                coil.block(),
                coil.label(),
                List.of(
                    "Field restriction coil tier " + (index + 1),
                    "Parallels: " + 4 * (index + 1),
                    "One perfect overclock per tier above the recipe's minimum"),
                false);
            tiers.add(tier);
        }
        control.tiers = tiers;
        FIELD_COIL_CONTROL = control;
    }

    // endregion

    // region Spinmatron

    public static final Web.Control SPIN_MODE_CONTROL = choiceControl(SPIN_MODE, "Mode", "Standard", "Light", "Heavy");
    public static final Web.Control SPIN_FUEL_CONTROL = choiceControl(
        SPIN_FUEL,
        "Fuel",
        "Kerosene",
        "Biocatalysed Propulsion Fluid");
    public static final Web.Control TURBINE_TIER_CONTROL = countControl(
        TURBINE_TIER,
        "Sum Turbine Tier",
        1,
        2,
        3,
        4,
        6,
        8,
        12,
        16,
        24,
        32);

    // endregion

    // region Steam multiblocks

    /**
     * STEAM_PRESSURE_CONTROL: bronze or steel build, for the steam multiblocks. Declaring it here replaces the
     * dataset's scraped copy (and its own x0.5 duration effect); keys and icon ids match the dataset's.
     */
    public static final Web.Control STEAM_PRESSURE_CONTROL;

    static {
        final Web.Control control = new Web.Control();
        control.id = STEAM_PRESSURE;
        control.label = "Pressure";
        control.minimumKey = "normal";
        control.defaultKey = "normal";
        final Web.TierOption normal = new Web.TierOption();
        normal.key = "normal";
        normal.label = "Normal Pressure";
        normal.resource = item(
            "factoryflow:machine_config/steam_pressure_normal",
            "Normal Pressure",
            List.of("Bronze build", "Runs at 62.5% of the recipe's speed"),
            null);
        final Web.TierOption high = new Web.TierOption();
        high.key = "high";
        high.label = "High Pressure";
        high.resource = item(
            "factoryflow:machine_config/steam_pressure_high",
            "High Pressure",
            List.of("Steel build", "Twice the speed and twice the steam of bronze"),
            null);
        control.tiers = List.of(normal, high);
        STEAM_PRESSURE_CONTROL = control;
    }

    // endregion

    // region Neutron Activator

    /** NEUTRON_PIPE_CONTROL: checkMachine accepts any pipe height >= 4; it never imposes a top rung. */
    public static final Web.Control NEUTRON_PIPE_CONTROL;

    static {
        final Web.Control control = countControl("speedingPipeCasing", "Pipe height", 4);
        final Web.Numeric numeric = new Web.Numeric();
        numeric.min = 4;
        control.numeric = numeric;
        NEUTRON_PIPE_CONTROL = control;
    }

    // endregion
}

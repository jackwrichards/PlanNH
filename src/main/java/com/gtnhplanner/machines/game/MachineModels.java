package com.gtnhplanner.machines.game;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nullable;

import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import com.gtnhplanner.GtnhPlanner;
import com.gtnhplanner.api.RecipePropertyAPI;
import com.gtnhplanner.data.MachineConfig;
import com.gtnhplanner.data.SettingDef;
import com.gtnhplanner.data.effect.EffectResult;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Port;
import com.gtnhplanner.data.properties.RecipeProperty;
import com.gtnhplanner.data.provider.GTKeys;
import com.gtnhplanner.data.provider.GTProvider;
import com.gtnhplanner.machines.BacterialVat;
import com.gtnhplanner.machines.FormulaLine;
import com.gtnhplanner.machines.TreeGrowthSimulator;
import com.gtnhplanner.machines.TreeGrowthSimulator.Mode;

import bartworks.API.recipe.BartWorksRecipeMaps;
import cpw.mods.fml.common.registry.GameRegistry;
import gregtech.api.enums.Materials;
import gregtech.api.recipe.RecipeMaps;
import gregtech.api.util.GTRecipe;
import gregtech.api.util.GTRecipeConstants;
import gregtech.api.util.recipe.Sievert;
import gregtech.common.items.MetaGeneratedTool01;
import gregtech.common.tileentities.machines.multi.MTETreeFarm;

/**
 * The game's side of the modelled machines (machines/: the Tree Growth Simulator and the Bacterial Vat, as the website
 * models them): what GregTech's recipes say about them, the solver's per-port multipliers after every refresh, the
 * radio hatch's burned material as an input, and the card's settings, formulas and icons. Settings are kept under the
 * website's own keys and values, so plans go between the two unchanged.
 */
public final class MachineModels {

    private MachineModels() {}

    /** A TGS recipe's output modes in port order ("log,sapling,..."), NEI's amounts, and a Forestry sapling's genes. */
    public static final RecipeProperty<String> TGS_MODES = RecipeProperty.<String>builder("gtnhplanner.tgs.modes", "")
        .build();
    public static final RecipeProperty<String> TGS_AMOUNTS = RecipeProperty
        .<String>builder("gtnhplanner.tgs.amounts", "")
        .build();
    public static final RecipeProperty<String> TGS_FORESTRY = RecipeProperty
        .<String>builder("gtnhplanner.tgs.forestry", "")
        .build();

    /** The settings both machines keep, under the website's keys; "" is the recipe's own default. */
    public static final List<SettingDef<String>> SETTINGS = List.of(
        def(Mode.LOG.toolSetting, true),
        def(Mode.SAPLING.toolSetting, true),
        def(Mode.LEAVES.toolSetting, true),
        def(Mode.FRUIT.toolSetting, true),
        def(TreeGrowthSimulator.HEIGHT, true),
        def(TreeGrowthSimulator.FERTILITY, true),
        def(TreeGrowthSimulator.YIELD, true),
        def(BacterialVat.GLASS, false),
        def(BacterialVat.OUTPUT_HATCH, false),
        def(BacterialVat.FILL, false),
        def(BacterialVat.VOID, false),
        def(BacterialVat.RADIO, false),
        def(BacterialVat.SHUTTER, false));

    private static SettingDef<String> def(final String key, final boolean tgs) {
        return SettingDef.enumDef(key, "", List.of(), (v, c) -> null)
            .withVisibility((ctx, s) -> tgs ? isTgs(ctx.properties()) : isVat(ctx.properties()));
    }

    public static boolean isTgs(final Map<RecipeProperty<?>, Object> props) {
        return props.get(GTProvider.RECIPE_MAP) == RecipeMaps.treeGrowthSimulatorFakeRecipes;
    }

    public static boolean isVat(final Map<RecipeProperty<?>, Object> props) {
        return props.get(GTProvider.RECIPE_MAP) == BartWorksRecipeMaps.bacterialVatRecipes;
    }

    public static boolean isModelled(final Node node) {
        return isTgs(node.properties) || isVat(node.properties);
    }

    // region Reading the recipes

    /**
     * A TGS recipe's ports and facts (GTProvider): NEI's outputs by mode, where a missing mode is null in the array, and
     * the Forestry sapling's genes. Its NEI page says 0 EU/t; the machine draws VP[t] and needs LV at least, so the card
     * starts at LV (the effect step puts the real draw in).
     */
    public static void readTgs(final Node node, final GTRecipe r, final Map<RecipeProperty<?>, Object> props) {
        node.outputs.clear();
        final StringBuilder modes = new StringBuilder(), amounts = new StringBuilder();
        for (int i = 0; i < Math.min(4, r.mOutputs.length); i++) {
            if (r.mOutputs[i] == null) continue;
            node.outputs.add(new Port<>(RecipePropertyAPI.ITEM, r.mOutputs[i].copy(), 1f));
            if (modes.length() > 0) {
                modes.append(',');
                amounts.append(',');
            }
            modes.append(Mode.values()[i].key);
            amounts.append(r.mOutputs[i].stackSize);
        }
        props.put(TGS_MODES, modes.toString());
        props.put(TGS_AMOUNTS, amounts.toString());
        props.put(GTKeys.EU_PER_TICK, TreeGrowthSimulator.euPerTick(1));
        props.put(GTKeys.TOTAL_EU, TreeGrowthSimulator.euPerTick(1) * TreeGrowthSimulator.TICKS);
        if (r.mSpecialItems instanceof final ItemStack sapling) {
            final String forestry = forestry(sapling);
            if (forestry != null) props.put(TGS_FORESTRY, forestry);
        }
    }

    /**
     * A Forestry sapling's species, default genes and unscaled products
     * ("species;height;girth;fertility;yield;log=1,..."),
     * read by reflection (Forestry is not compiled against); null for any other sapling.
     */
    @Nullable
    private static String forestry(final ItemStack sapling) {
        if (!"Forestry:sapling".equals(Item.itemRegistry.getNameForObject(sapling.getItem()))) return null;
        try {
            final Object root = Class.forName("forestry.api.arboriculture.TreeManager")
                .getField("treeRoot")
                .get(null);
            final Object tree = root.getClass()
                .getMethod("getMember", ItemStack.class)
                .invoke(root, sapling);
            if (tree == null) return null;
            final Object genome = call(tree, "getGenome");
            final String species = (String) call(tree, "getIdent");
            final EnumMap<MTETreeFarm.Mode, ItemStack> base = MTETreeFarm.treeProductsMap
                .get("Forestry:sapling:" + species);
            if (base == null) return null;
            final StringBuilder counts = new StringBuilder();
            for (final Map.Entry<MTETreeFarm.Mode, ItemStack> e : base.entrySet()) {
                if (counts.length() > 0) counts.append(',');
                counts.append(
                    e.getKey()
                        .name()
                        .toLowerCase(Locale.ROOT))
                    .append('=')
                    .append(e.getValue().stackSize);
            }
            return species + ";"
                + call(genome, "getHeight")
                + ";"
                + call(genome, "getGirth")
                + ";"
                + call(genome, "getFertility")
                + ";"
                + call(genome, "getYield")
                + ";"
                + counts;
        } catch (final ReflectiveOperationException | RuntimeException e) {
            GtnhPlanner.LOG.debug("TGS: no Forestry genes for {}", sapling, e);
            return null;
        }
    }

    private static Object call(final Object on, final String method) throws ReflectiveOperationException {
        return on.getClass()
            .getMethod(method)
            .invoke(on);
    }

    /** The tree a TGS node farms, from its recipe's facts; null when they are missing or out of step with its ports. */
    @Nullable
    public static TreeGrowthSimulator.Tree tree(final Node node) {
        final Object modes = node.properties.get(TGS_MODES), amounts = node.properties.get(TGS_AMOUNTS);
        if (!(modes instanceof final String m) || m.isEmpty() || !(amounts instanceof final String a)) return null;
        final List<Mode> modeList = new ArrayList<>();
        for (final String key : m.split(",")) for (final Mode mode : Mode.values()) if (mode.key.equals(key))
            modeList.add(mode);
        final List<Integer> amountList = new ArrayList<>();
        for (final String n : a.split(",")) amountList.add(Integer.parseInt(n));
        if (modeList.size() != amountList.size() || modeList.size() != node.outputs.size()) return null;
        TreeGrowthSimulator.Forestry forestry = null;
        if (node.properties.get(TGS_FORESTRY) instanceof final String f && !f.isEmpty()) {
            final String[] p = f.split(";");
            final Map<Mode, Integer> base = new EnumMap<>(Mode.class);
            if (p.length > 5) for (final String kv : p[5].split(",")) {
                final String[] pair = kv.split("=");
                for (final Mode mode : Mode.values()) if (mode.key.equals(pair[0]))
                    base.put(mode, Integer.parseInt(pair[1]));
            }
            forestry = new TreeGrowthSimulator.Forestry(
                p[0],
                Float.parseFloat(p[1]),
                Integer.parseInt(p[2]),
                Float.parseFloat(p[3]),
                Float.parseFloat(p[4]),
                base);
        }
        return new TreeGrowthSimulator.Tree(modeList, amountList, forestry);
    }

    /** A vat recipe's needs: glass (HV when the recipe names none), sieverts, exactly or at least. */
    public static BacterialVat.Needs needs(final Node node) {
        final Object glass = node.properties.get(GTProvider.GLASS_TIER);
        final Object sievert = node.properties.get(GTProvider.SIEVERT);
        return new BacterialVat.Needs(
            glass instanceof final Number n ? n.intValue() : 3,
            sievert instanceof final Number n ? n.intValue() : 0,
            Boolean.TRUE.equals(node.properties.get(GTProvider.SIEVERT_EXACT)));
    }

    private static List<BacterialVat.Material> materials;
    private static final Map<String, ItemStack> materialStacks = new HashMap<>();

    /** The radio hatch's materials (its NEI page, bw.recipe.radhatch), in the website's order. */
    public static List<BacterialVat.Material> materials() {
        if (materials != null) return materials;
        final List<BacterialVat.Material> found = new ArrayList<>();
        try {
            for (final GTRecipe r : BartWorksRecipeMaps.radioHatchFakeRecipes.getAllRecipes()) {
                if (r.mInputs == null || r.mInputs.length == 0 || r.mInputs[0] == null) continue;
                final ItemStack stack = r.mInputs[0].copy();
                stack.stackSize = 1;
                final String id = websiteId(stack);
                materialStacks.put(id, stack);
                found.add(
                    new BacterialVat.Material(
                        id,
                        stack.getDisplayName(),
                        r.getMetadataOrDefault(GTRecipeConstants.SIEVERT, new Sievert(0, false)).sievert,
                        r.getMetadataOrDefault(GTRecipeConstants.MASS, 0)));
            }
        } catch (final RuntimeException e) {
            GtnhPlanner.LOG.warn("Radio hatch materials could not be read", e);
        }
        materials = BacterialVat.sorted(found);
        return materials;
    }

    @Nullable
    public static ItemStack materialStack(final String id) {
        materials();
        return materialStacks.get(id);
    }

    /** An item as the website's dataset names it: the registry name in lower case, "@meta" when the meta is not 0. */
    public static String websiteId(final ItemStack s) {
        final String registry = Item.itemRegistry.getNameForObject(s.getItem());
        final String id = registry == null ? "?" : registry.toLowerCase(Locale.ROOT);
        return s.getItemDamage() == 0 ? id : id + "@" + s.getItemDamage();
    }

    // endregion

    // region After every refresh

    /** The radio hatch input each vat node was last given, so a second pass replaces it. */
    private static final Map<Node, Port<?>> burned = new java.util.WeakHashMap<>();

    /** The machine's voltage and amps as the card has them (LV when the card has none). */
    static long[] power(final MachineConfig cfg) {
        final Object v = cfg.settings.get("voltage");
        final long voltage = com.gtnhplanner.data.effect.steps.GTOverclockStep
            .tierNameToVoltage(v instanceof final String s ? s : "OFF");
        return new long[] { voltage > 0 ? voltage : 32, Math.max(1, cfg.getInt("amp")) };
    }

    /**
     * The modelled machines' rates, after a node's ports are read: the solver's per-port multipliers (TGS outputs by
     * tier, tool and genes; the vat's first fluid in and out by the fill), and the radio hatch's material as an input.
     */
    public static void afterRefresh(final Node node) {
        if (node.isPower() || node.machineConfig == null) return;
        // Safe to run again (after a save's settings are read): the last radio input it added goes first.
        final Port<?> earlier = burned.remove(node);
        if (earlier != null) node.inputs.remove(earlier);
        final MachineConfig cfg = node.machineConfig;
        if (isTgs(node.properties)) {
            cfg.outputProductivity.clear();
            final TreeGrowthSimulator.Tree tree = tree(node);
            if (tree == null) return;
            final long[] p = power(cfg);
            final int t = TreeGrowthSimulator.tier(p[0] * p[1]);
            final TreeGrowthSimulator.Setup setup = TreeGrowthSimulator.setup(cfg.settings, tree);
            for (int i = 0; i < node.outputs.size(); i++)
                cfg.outputProductivity.put(i, (float) TreeGrowthSimulator.outputMultiplier(tree, i, setup, t));
        } else if (isVat(node.properties)) {
            cfg.inputConsumption.clear();
            cfg.outputProductivity.clear();
            final int in = firstFluid(node.inputs), out = firstFluid(node.outputs);
            final long fluidOut = out < 0 ? 0
                : (long) node.outputs.get(out)
                    .amount();
            final BacterialVat.Needs needs = needs(node);
            final BacterialVat.Setup setup = BacterialVat.setup(cfg.settings, needs, fluidOut, materials());
            final BacterialVat.Fill fill = BacterialVat.fill(setup, fluidOut);
            if (BacterialVat.gate(needs, setup, fluidOut) != null) {
                // Glass too low, radiation not met, or a run that never fits: the vat holds, as on the website (its
                // recipe gate). Its radio hatch still burns, below.
                for (int i = 0; i < node.inputs.size(); i++) cfg.inputConsumption.put(i, 0f);
                for (int i = 0; i < node.outputs.size(); i++) cfg.outputProductivity.put(i, 0f);
            } else {
                if (in >= 0) cfg.inputConsumption.put(in, (float) fill.multiplier());
                if (out >= 0 && fluidOut > 0) cfg.outputProductivity.put(out, (float) fill.keptOut() / fluidOut);
            }
            if (needs.sievert() > 0 && setup.radio() != null) {
                final ItemStack stack = materialStack(
                    setup.radio()
                        .id());
                final EffectResult effect = cfg.computeEffect(node.properties);
                if (stack != null && effect.durationTicks() > 0) {
                    final Port<ItemStack> port = new Port<>(RecipePropertyAPI.ITEM, stack.copy(), 1f);
                    port.setExactAmount(BacterialVat.burnPerSecond(setup.radio()) * effect.durationTicks() / 20.0);
                    port.fromModel = true;
                    node.inputs.add(port);
                    burned.put(node, port);
                }
            }
        }
    }

    private static int firstFluid(final List<Port<?>> ports) {
        for (int i = 0; i < ports.size(); i++) if (ports.get(i)
            .getValue() instanceof net.minecraftforge.fluids.FluidStack) return i;
        return -1;
    }

    // endregion

    // region The card

    /**
     * The node's worked formulas: the modelled machines' own, any other GregTech card's power working from the
     * website's machine maths (machines/web Working); empty for any other machine.
     */
    public static List<FormulaLine> formulas(final Node node) {
        if (node.isPower() || node.machineConfig == null) return Collections.emptyList();
        final MachineConfig cfg = node.machineConfig;
        if (!isTgs(node.properties) && !isVat(node.properties)) {
            final com.gtnhplanner.machines.web.NodeMath.Result r = WebEffect.result(node, cfg);
            final com.gtnhplanner.machines.web.Web.Recipe recipe = WebEffect.recipe(node);
            return r == null || recipe == null ? Collections.emptyList()
                : com.gtnhplanner.machines.web.Working.lines(recipe, WebCards.node(node, cfg, recipe), r);
        }
        if (isTgs(node.properties)) {
            final TreeGrowthSimulator.Tree tree = tree(node);
            final long[] p = power(cfg);
            return tree == null ? Collections.emptyList() : TreeGrowthSimulator.formulas(tree, cfg.settings, p[0], p[1]);
        }
        if (isVat(node.properties)) {
            final int in = firstFluid(node.inputs), out = firstFluid(node.outputs);
            final long fluidIn = in < 0 ? 0
                : (long) node.inputs.get(in)
                    .amount();
            final long fluidOut = out < 0 ? 0
                : (long) node.outputs.get(out)
                    .amount();
            final BacterialVat.Needs needs = needs(node);
            final BacterialVat.Setup setup = BacterialVat.setup(cfg.settings, needs, fluidOut, materials());
            final Object ticks = node.properties.get(RecipePropertyAPI.DURATION_TICKS),
                eut = node.properties.get(GTKeys.EU_PER_TICK);
            final int baseTicks = ticks instanceof final Number n ? n.intValue() : 0;
            final long baseEut = eut instanceof final Number n ? n.longValue() : 0;
            final EffectResult effect = cfg.computeEffect(node.properties);
            int overclocks = 0;
            while (baseEut > 0 && baseEut << (2 * (overclocks + 1)) <= effect.energyPerT()) overclocks++;
            return BacterialVat.formulas(
                needs,
                setup,
                fluidIn,
                fluidOut,
                baseTicks,
                baseEut,
                effect.durationTicks(),
                effect.energyPerT(),
                overclocks);
        }
        return Collections.emptyList();
    }

    /** A choice of a card's setting: its stored value, name and icon. */
    public record Option(String key, String label, @Nullable ItemStack icon) {}

    /** A setting the card offers: a list to pick from, or a number in a range, in steps of {@code step}. */
    public record Setting(String key, String label, String value, @Nullable ItemStack icon, List<Option> options,
        boolean number, double min, double max, double step, boolean warn) {

        public Setting(final String key, final String label, final String value, @Nullable final ItemStack icon,
            final List<Option> options, final boolean number, final double min, final double max, final boolean warn) {
            this(key, label, value, icon, options, number, min, max, 1, warn);
        }
    }

    /**
     * The node's settings as its card offers them, in the website's order: the modelled machines' own, any other
     * GregTech card's from the website's machine maths (WebSettings); empty for any other machine.
     */
    public static List<Setting> settings(final Node node) {
        final List<Setting> out = new ArrayList<>();
        if (node.isPower() || node.machineConfig == null) return out;
        if (!isTgs(node.properties) && !isVat(node.properties)) return WebSettings.settings(node);
        final MachineConfig cfg = node.machineConfig;
        if (isTgs(node.properties)) {
            final TreeGrowthSimulator.Tree tree = tree(node);
            if (tree == null) return out;
            final TreeGrowthSimulator.Setup setup = TreeGrowthSimulator.setup(cfg.settings, tree);
            for (final Mode mode : Mode.values()) {
                if (!tree.modes()
                    .contains(mode)) continue;
                final List<Option> options = new ArrayList<>();
                for (final TreeGrowthSimulator.Tool tool : TreeGrowthSimulator.tools(mode))
                    options.add(new Option(tool.key(), tool.label() + " " + tool.multiplier() + "x", toolIcon(tool)));
                options.add(new Option(TreeGrowthSimulator.NONE, "No tool", null));
                final TreeGrowthSimulator.Tool now = setup.tools()
                    .get(mode);
                out.add(
                    choice(
                        mode.toolSetting,
                        capital(mode.plural) + " tool",
                        now == null ? TreeGrowthSimulator.NONE : now.key(),
                        options,
                        now == null));
            }
            final TreeGrowthSimulator.Forestry f = tree.forestry();
            if (f != null) {
                if (tree.modes()
                    .contains(Mode.LOG))
                    out.add(gene(TreeGrowthSimulator.HEIGHT, "Height", TreeGrowthSimulator.HEIGHTS, setup.height()));
                if (tree.modes()
                    .contains(Mode.SAPLING))
                    out.add(
                        gene(
                            TreeGrowthSimulator.FERTILITY,
                            "Saplings",
                            TreeGrowthSimulator.FERTILITIES,
                            setup.fertility()));
                if (tree.modes()
                    .contains(Mode.FRUIT))
                    out.add(gene(TreeGrowthSimulator.YIELD, "Yield", TreeGrowthSimulator.YIELDS, setup.yield()));
            }
        } else if (isVat(node.properties)) {
            final int out1 = firstFluid(node.outputs);
            final long fluidOut = out1 < 0 ? 0
                : (long) node.outputs.get(out1)
                    .amount();
            final BacterialVat.Needs needs = needs(node);
            final BacterialVat.Setup setup = BacterialVat.setup(cfg.settings, needs, fluidOut, materials());
            final List<Option> glass = new ArrayList<>();
            for (int g = 3; g <= 12; g++) glass.add(new Option(String.valueOf(g), TIERS[g] + " glass", null));
            out.add(
                choice(
                    BacterialVat.GLASS,
                    "Glass",
                    String.valueOf(setup.glass()),
                    glass,
                    setup.glass() < needs.glass()));
            final List<Option> hatches = new ArrayList<>();
            for (final BacterialVat.Hatch h : BacterialVat.HATCHES) hatches.add(new Option(h.key(), h.label(), null));
            final BacterialVat.Fill fill = BacterialVat.fill(setup, fluidOut);
            out.add(
                choice(
                    BacterialVat.OUTPUT_HATCH,
                    "Output hatch",
                    setup.hatch()
                        .key(),
                    hatches,
                    !fill.fits() && !setup.voidExcess()));
            out.add(
                new Setting(
                    BacterialVat.FILL,
                    "Kept full %",
                    FormulaLine.number(setup.fill()),
                    null,
                    List.of(),
                    true,
                    0,
                    100,
                    false));
            out.add(
                choice(
                    BacterialVat.VOID,
                    "Void protection",
                    setup.voidExcess() ? "void" : "protect",
                    List.of(new Option("protect", "On", null), new Option("void", "Off", null)),
                    false));
            if (needs.sievert() > 0) {
                final List<Option> radio = new ArrayList<>();
                radio.add(new Option("none", "Empty", null));
                for (final BacterialVat.Material m : materials())
                    radio.add(new Option(m.id(), m.name() + " (" + m.sievert() + " Sv)", materialStack(m.id())));
                final int have = setup.radio() == null ? 0
                    : BacterialVat.effectiveSievert(
                        setup.radio()
                            .sievert(),
                        setup.shutter());
                final boolean met = needs.exact() ? have == needs.sievert() : have >= needs.sievert();
                out.add(
                    choice(
                        BacterialVat.RADIO,
                        "Radio hatch",
                        setup.radio() == null ? "none"
                            : setup.radio()
                                .id(),
                        radio,
                        !met));
                out.add(
                    new Setting(
                        BacterialVat.SHUTTER,
                        "Shutter %",
                        String.valueOf(setup.shutter()),
                        null,
                        List.of(),
                        true,
                        0,
                        100,
                        !met));
            }
        }
        return out;
    }

    private static Setting choice(final String key, final String label, final String value, final List<Option> options,
        final boolean warn) {
        Option current = null;
        for (final Option o : options) if (o.key()
            .equals(value)) current = o;
        return new Setting(
            key,
            label,
            current == null ? value : current.label(),
            current == null ? null : current.icon(),
            options,
            false,
            0,
            0,
            warn);
    }

    private static Setting gene(final String key, final String label, final List<TreeGrowthSimulator.Allele> alleles,
        final float now) {
        final List<Option> options = new ArrayList<>();
        for (final TreeGrowthSimulator.Allele a : alleles)
            options.add(new Option(a.key(), a.name() + " (" + a.value() + ")", null));
        final TreeGrowthSimulator.Allele current = TreeGrowthSimulator.allele(alleles, now);
        return choice(key, label, current == null ? "" : current.key(), options, false);
    }

    private static String capital(final String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static final String[] TIERS = { "ULV", "LV", "MV", "HV", "EV", "IV", "LuV", "ZPM", "UV", "UHV", "UEV",
        "UIV", "UMV", "UXV", "MAX" };

    private static final Map<String, ItemStack> toolIcons = new LinkedHashMap<>();

    /** A tool's picture: GregTech's own tool in steel, shears, Forestry's grafter; null when it cannot be made. */
    @Nullable
    private static ItemStack toolIcon(final TreeGrowthSimulator.Tool tool) {
        if (toolIcons.containsKey(tool.itemId())) return toolIcons.get(tool.itemId());
        ItemStack icon = null;
        try {
            final String id = tool.itemId();
            if (id.startsWith("gregtech:gt.metatool.01@") && MetaGeneratedTool01.INSTANCE != null) {
                final int meta = Integer.parseInt(id.substring(id.indexOf('@') + 1));
                icon = MetaGeneratedTool01.INSTANCE.getToolWithStats(
                    meta,
                    1,
                    Materials.Steel,
                    Materials.Steel,
                    tool.electric() ? new long[] { 100_000L, 32L, 1L, -1L } : null);
            } else if ("minecraft:shears".equals(id)) icon = new ItemStack(Items.shears);
            else if ("forestry:grafter".equals(id)) {
                final Item grafter = GameRegistry.findItem("Forestry", "grafter");
                if (grafter != null) icon = new ItemStack(grafter);
            }
        } catch (final RuntimeException e) {
            GtnhPlanner.LOG.debug("No icon for {}", tool.itemId(), e);
        }
        toolIcons.put(tool.itemId(), icon);
        return icon;
    }

    // endregion
}

package com.gtnhplanner.ui.card;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import com.gtnhplanner.api.RecipePropertyAPI;
import com.gtnhplanner.data.MachineConfig;
import com.gtnhplanner.data.effect.EffectResult;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Port;
import com.gtnhplanner.data.flowchart.balancer.Balancer;
import com.gtnhplanner.power.PowerModel;
import com.gtnhplanner.power.PowerRegistry;
import com.gtnhplanner.power.PowerSource;
import com.gtnhplanner.power.game.PowerPorts;

import codechicken.nei.PositionedStack;
import codechicken.nei.recipe.IRecipeHandler;
import codechicken.nei.recipe.RecipeCatalysts;
import codechicken.nei.recipe.RecipeHandlerRef;

/**
 * Everything a recipe card shows, read once from the node, its effect after settings and overclocking, and the
 * solve result. Built on the client thread whenever the plan changes; never per frame.
 */
public final class CardModel {

    public static final String GT_PROFILE = "gregtech:unified";

    /** One port as drawn; {@code wired} when a wire or drawer is on it (or it needs none: nothing is used up). */
    public record PortView(int index, boolean output, ItemStack item, FluidStack fluid, String name, float chance,
        double perSecond, boolean wired, String key) {

        public boolean isFluid() {
            return fluid != null;
        }

        /** EU, a generator's output: drawn as a bolt and shown in EU/t. */
        public boolean isPower() {
            return com.gtnhplanner.ui.Resources.isPower(key);
        }

        /** The stack NEI should see for R/U and tooltips. */
        public ItemStack lookupStack() {
            return item;
        }
    }

    public final Node node;
    public final String machineName;
    /** Machines that run this recipe, from NEI's catalysts; the first is the default picture. */
    public final List<ItemStack> catalysts;
    public final ItemStack machineStack;
    public final boolean gregtech;
    public final String tier;
    public final int amps;
    public final boolean multiblock;
    public final int coilHeat;
    public final boolean usesHeat;
    public final int parallels;
    public final List<PortView> inputs;
    public final List<PortView> outputs;
    public final int durationTicks;
    public final long euPerTick;
    public final double machines;
    public final boolean pinned;
    public final ItemStack circuit;
    /** The recipe's minimum coil heat (K), 0 when it needs none. */
    public final int recipeHeat;
    /** A non-recipe machine's source, model and readings; null on a recipe card. */
    public final PowerView power;
    /**
     * The website's machine maths for a GregTech card (machines/web): the run, its parallels and why it would not
     * start; null on any other card, and on the Tree Growth Simulator and Bacterial Vat (their own models).
     */
    public final com.gtnhplanner.machines.web.NodeMath.Result web;

    /**
     * A power card's source and its model at the card's settings, with the stat lines to show: the model's own, then
     * the flows that could not be ports. {@code source} is null when the plan names a source this build does not know.
     */
    public record PowerView(PowerSource source, PowerModel model, List<PowerModel.Stat> stats) {

        /** EU/t one machine makes; 0 for a parasitic one. */
        public double madePerMachine() {
            return model == null ? 0 : Math.max(0, model.euPerTick());
        }
    }

    private CardModel(final Node node, final String machineName, final List<ItemStack> catalysts,
        final ItemStack machineStack, final boolean gregtech, final String tier, final int amps,
        final boolean multiblock, final int coilHeat, final boolean usesHeat, final int parallels,
        final List<PortView> inputs, final List<PortView> outputs, final int durationTicks, final long euPerTick,
        final double machines, final boolean pinned, final ItemStack circuit, final int recipeHeat,
        final PowerView power, final com.gtnhplanner.machines.web.NodeMath.Result web) {
        this.node = node;
        this.machineName = machineName;
        this.catalysts = catalysts;
        this.machineStack = machineStack;
        this.gregtech = gregtech;
        this.tier = tier;
        this.amps = amps;
        this.multiblock = multiblock;
        this.coilHeat = coilHeat;
        this.usesHeat = usesHeat;
        this.parallels = parallels;
        this.inputs = inputs;
        this.outputs = outputs;
        this.durationTicks = durationTicks;
        this.euPerTick = euPerTick;
        this.machines = machines;
        this.pinned = pinned;
        this.circuit = circuit;
        this.recipeHeat = recipeHeat;
        this.power = power;
        this.web = web;
    }

    public boolean isPower() {
        return power != null;
    }

    /**
     * What one card's recipes (one, or a shared machine's several) ask of the machine's structure, for its ghost in the
     * world: the coil when a recipe needs heat, and the most fluid outputs and item inputs of any of them.
     */
    public static com.gtnhplanner.ui.gt.StructureGhosts.Needs structureNeeds(final List<CardModel> models) {
        int coil = 0, fluids = 0, items = 0;
        for (final CardModel m : models) {
            if (m == null) continue;
            if (m.usesHeat) coil = Math.max(coil, m.coilHeat);
            int f = 0, i = 0;
            for (final PortView p : m.outputs) if (p.isFluid()) f++;
            for (final PortView p : m.inputs) if (!p.isFluid() && !p.isPower() && p.item() != null) i++;
            fluids = Math.max(fluids, f);
            items = Math.max(items, i);
        }
        return new com.gtnhplanner.ui.gt.StructureGhosts.Needs(coil, fluids, items);
    }

    /** EU/t the solved machines make (average): a generator's output, 0 on a recipe card. */
    public double madeEuPerTick() {
        return power == null ? 0 : power.madePerMachine() * machines;
    }

    /** Average EU/t for the solved count (the board's power key may show it as amps). */
    public double powerEuPerTick() {
        return euPerTick * machines;
    }

    /**
     * A GregTech recipe the game would not start: the website's power report says why (too few amps, hatches more
     * than a tier below the recipe, a structural gate). Without it, its EU/t over the tier's voltage and amps.
     */
    public boolean tierTooLow() {
        if (web != null) return web.stalled();
        if (!gregtech || !(node.properties.get(com.gtnhplanner.data.provider.GTKeys.EU_PER_TICK) instanceof final Number eut))
            return false;
        final int t = CardDefaults.tierIndex(tier);
        return t >= 0 && eut.longValue() > (8L << (2 * t)) * (multiblock ? Math.max(1, amps) : 1);
    }

    /** Whether a node's port (output or input, by index) has a wire or drawer on it. */
    public interface Wired {

        boolean test(boolean output, int index);
    }

    public static CardModel of(final Node node, final Balancer.NodeBalance balance, final Wired wired) {
        if (node.isPower()) return power(node, balance, wired);
        final MachineConfig cfg = node.machineConfig;
        final boolean gregtech = GT_PROFILE.equals(cfg.profileId);
        final EffectResult effect = cfg.computeEffect(node.properties);
        final int duration = Math.max(1, effect.durationTicks());
        final com.gtnhplanner.machines.web.NodeMath.Result web = gregtech
            ? com.gtnhplanner.machines.game.WebEffect.result(node, cfg)
            : null;
        final com.gtnhplanner.machines.game.WebSettings.Coil coil = web == null ? null
            : com.gtnhplanner.machines.game.WebSettings.coil(node, cfg, web);

        final RecipeHandlerRef ref = RecipeHandlerRef.of(node.recipeId);
        final List<ItemStack> catalysts = catalysts(ref);
        ItemStack chosen = null;
        for (final ItemStack s : catalysts) {
            if (CardDefaults.matches(s, node.machineName)) {
                chosen = s;
                break;
            }
        }
        final ItemStack machineStack = chosen != null ? chosen : catalysts.isEmpty() ? null : catalysts.get(0);
        final String machineName = machineStack != null ? machineStack.getDisplayName() : node.machineName;

        final double machines = balance == null ? 0 : balance.operations();
        final List<PortView> inputs = ports(node.inputs, false, balance, wired);
        final List<PortView> outputs = ports(node.outputs, true, balance, wired);

        return new CardModel(
            node,
            machineName,
            catalysts,
            machineStack,
            gregtech,
            gregtech ? CardDefaults.stringSetting(cfg, "voltage") : "",
            gregtech ? Math.max(1, CardDefaults.intSetting(cfg, "amp")) : 1,
            web != null ? com.gtnhplanner.machines.web.Power.isMultiblock(web.effectiveRecipe())
                : gregtech && CardDefaults.boolSetting(cfg, "gt_multiblock"),
            web != null ? coil == null ? 0 : coil.heat() : gregtech ? CardDefaults.intSetting(cfg, "machine_heat") : 0,
            web != null ? coil != null
                : gregtech && (node.properties.containsKey(com.gtnhplanner.data.provider.GTKeys.COIL_HEAT)),
            web != null ? (int) Math.min(Integer.MAX_VALUE, web.machineParallels())
                : gregtech ? Math.max(1, CardDefaults.intSetting(cfg, "parallels")) : 1,
            inputs,
            outputs,
            duration,
            effect.energyPerT(),
            machines,
            node.isMachineCountFixed(),
            circuit(ref),
            web != null ? coil == null ? 0 : coil.recipeHeat()
                : node.properties.get(com.gtnhplanner.data.provider.GTKeys.COIL_HEAT) instanceof final Number h
                    ? h.intValue()
                    : 0,
            null,
            web);
    }

    /**
     * A non-recipe machine: its source's name and machine, one craft a second, and its ports from the model. It draws
     * power only when the model is parasitic (net EU/t below zero).
     */
    private static CardModel power(final Node node, final Balancer.NodeBalance balance, final Wired wired) {
        final PowerSource source = PowerRegistry.get(node.powerSource);
        final PowerModel model = node.powerModel;
        final List<PowerModel.Stat> stats = new ArrayList<>();
        if (model != null) {
            stats.addAll(model.stats());
            stats.addAll(PowerPorts.unported(model));
        }
        return new CardModel(
            node,
            source != null ? source.name() : node.machineName,
            Collections.emptyList(),
            PowerPorts.machineStack(node.powerSource),
            false,
            "",
            1,
            false,
            0,
            false,
            1,
            ports(node.inputs, false, balance, wired),
            ports(node.outputs, true, balance, wired),
            PowerPorts.DURATION_TICKS,
            model == null ? 0 : Math.round(Math.max(0, -model.euPerTick())),
            balance == null ? 0 : balance.operations(),
            node.isMachineCountFixed(),
            null,
            0,
            new PowerView(source, model, stats),
            null);
    }

    private static List<PortView> ports(final List<Port<?>> ports, final boolean output,
        final Balancer.NodeBalance balance, final Wired wired) {
        final List<PortView> views = new ArrayList<>(ports.size());
        for (int i = 0; i < ports.size(); i++) {
            final Port<?> port = ports.get(i);
            final Object value = port.getValue();
            final boolean fluid = port.getType()
                .equals(RecipePropertyAPI.FLUID);
            final double perSecond = balance == null ? 0
                : output ? balance.outputPerSecond(i) : balance.inputPerSecond(i);
            views.add(
                new PortView(
                    i,
                    output,
                    fluid ? port.getDisplayStack() : value instanceof final ItemStack stack ? stack : null,
                    fluid ? (FluidStack) value : null,
                    port.getDisplayName(),
                    port.getChance(),
                    perSecond,
                    port.getAmount() == 0 || wired.test(output, i),
                    com.gtnhplanner.ui.Resources.key(port)));
        }
        return views;
    }

    /** The machines NEI says run a node's recipe. */
    public static List<ItemStack> catalystsOf(final Node node) {
        return catalysts(RecipeHandlerRef.of(node.recipeId));
    }

    private static List<ItemStack> catalysts(final RecipeHandlerRef ref) {
        if (ref == null) return Collections.emptyList();
        final List<ItemStack> out = new ArrayList<>();
        for (final PositionedStack ps : RecipeCatalysts.getRecipeCatalysts(ref.handler)) {
            if (ps != null && ps.item != null) out.add(ps.item);
        }
        return out;
    }

    /** GregTech's programmed circuit is not a port (it is never consumed); read it from NEI's ingredient list. */
    private static ItemStack circuit(final RecipeHandlerRef ref) {
        if (ref == null) return null;
        final IRecipeHandler handler = ref.handler;
        for (final PositionedStack ps : handler.getIngredientStacks(ref.recipeIndex)) {
            if (ps == null || ps.item == null) continue;
            final Object name = Item.itemRegistry.getNameForObject(ps.item.getItem());
            if (name != null && name.toString()
                .endsWith("integrated_circuit")) return ps.item;
        }
        return null;
    }
}

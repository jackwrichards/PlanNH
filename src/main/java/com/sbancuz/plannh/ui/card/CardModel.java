package com.sbancuz.plannh.ui.card;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import com.sbancuz.plannh.api.RecipePropertyAPI;
import com.sbancuz.plannh.data.MachineConfig;
import com.sbancuz.plannh.data.effect.EffectResult;
import com.sbancuz.plannh.data.flowchart.Node;
import com.sbancuz.plannh.data.flowchart.Port;
import com.sbancuz.plannh.data.flowchart.balancer.Balancer;

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

    public record PortView(int index, boolean output, ItemStack item, FluidStack fluid, String name, float chance,
        double perSecond) {

        public boolean isFluid() {
            return fluid != null;
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

    private CardModel(final Node node, final String machineName, final List<ItemStack> catalysts,
        final ItemStack machineStack, final boolean gregtech, final String tier, final int amps,
        final boolean multiblock, final int coilHeat, final boolean usesHeat, final int parallels,
        final List<PortView> inputs, final List<PortView> outputs, final int durationTicks, final long euPerTick,
        final double machines, final boolean pinned, final ItemStack circuit, final int recipeHeat) {
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
    }

    /** Average EU/t for the solved count (the board's power key may show it as amps). */
    public double powerEuPerTick() {
        return euPerTick * machines;
    }

    public static CardModel of(final Node node, final Balancer.NodeBalance balance) {
        final MachineConfig cfg = node.machineConfig;
        final boolean gregtech = GT_PROFILE.equals(cfg.profileId);
        final EffectResult effect = cfg.computeEffect(node.properties);
        final int duration = Math.max(1, effect.durationTicks());

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
        final List<PortView> inputs = ports(node.inputs, false, balance);
        final List<PortView> outputs = ports(node.outputs, true, balance);

        return new CardModel(
            node,
            machineName,
            catalysts,
            machineStack,
            gregtech,
            gregtech ? CardDefaults.stringSetting(cfg, "voltage") : "",
            gregtech ? Math.max(1, CardDefaults.intSetting(cfg, "amp")) : 1,
            gregtech && CardDefaults.boolSetting(cfg, "gt_multiblock"),
            gregtech ? CardDefaults.intSetting(cfg, "machine_heat") : 0,
            gregtech && (node.properties.containsKey(com.sbancuz.plannh.data.provider.GTProvider.COIL_HEAT)),
            gregtech ? Math.max(1, CardDefaults.intSetting(cfg, "parallels")) : 1,
            inputs,
            outputs,
            duration,
            effect.energyPerT(),
            machines,
            node.isMachineCountFixed(),
            circuit(ref),
            node.properties.get(com.sbancuz.plannh.data.provider.GTProvider.COIL_HEAT) instanceof final Number h ? h.intValue() : 0);
    }

    private static List<PortView> ports(final List<Port<?>> ports, final boolean output,
        final Balancer.NodeBalance balance) {
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
                    fluid ? port.getDisplayStack() : (ItemStack) value,
                    fluid ? (FluidStack) value : null,
                    port.getDisplayName(),
                    port.getChance(),
                    perSecond));
        }
        return views;
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

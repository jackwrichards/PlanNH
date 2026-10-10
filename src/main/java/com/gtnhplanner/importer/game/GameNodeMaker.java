package com.gtnhplanner.importer.game;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;
import net.minecraftforge.fluids.FluidStack;

import com.gtnhplanner.GtnhPlanner;
import com.gtnhplanner.data.MachineConfig;
import com.gtnhplanner.data.SettingDef;
import com.gtnhplanner.data.Settings;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Port;
import com.gtnhplanner.importer.FfIds;
import com.gtnhplanner.importer.NodeMaker;
import com.gtnhplanner.importer.RecipeIndex.GameRecipe;
import com.gtnhplanner.ui.Resources;
import com.gtnhplanner.ui.card.CardDefaults;

import codechicken.nei.PositionedStack;
import codechicken.nei.recipe.IRecipeHandler;
import codechicken.nei.recipe.RecipeCatalysts;

/**
 * Real nodes for the importer: built from the NEI recipe the way NEI's + builds them ({@link CardDefaults} included),
 * then given the machine Factory Flow ran when the recipe's NEI page offers it. Client thread only.
 */
public final class GameNodeMaker implements NodeMaker {

    @Override
    @Nullable
    public Node make(final GameRecipe recipe, @Nullable final String machineLabel) {
        if (!(recipe.handler() instanceof final IRecipeHandler handler)) return null;
        final Node node;
        try {
            node = new Node(handler, recipe.index(), 0, 0);
        } catch (final RuntimeException e) {
            GtnhPlanner.LOG.warn("Factory Flow import: no node for {} #{}", recipe.handlerName(), recipe.index(), e);
            return null;
        }
        CardDefaults.apply(node);
        if (machineLabel != null) pickMachine(node, handler, machineLabel);
        return node;
    }

    /** The catalyst NEI lists for the recipe whose name is FF's machine, if there is one. */
    private static void pickMachine(final Node node, final IRecipeHandler handler, final String label) {
        final String want = FfIds.slug(label);
        for (final PositionedStack ps : RecipeCatalysts.getRecipeCatalysts(handler)) {
            if (ps == null || ps.item == null) continue;
            final String name = EnumChatFormatting.getTextWithoutFormattingCodes(ps.item.getDisplayName());
            if (name == null || !FfIds.slug(name)
                .equals(want)) continue;
            node.machineName = CardDefaults.itemKey(ps.item);
            return;
        }
    }

    @Override
    public PortInfo describe(final Port<?> port) {
        final Object value = port.getValue();
        final String key = Resources.key(port), label = port.getDisplayName();
        if (value instanceof final FluidStack fluid && fluid.getFluid() != null)
            return new PortInfo("fluid", key, label, List.of(GameIds.fluidId(fluid)));
        if (value instanceof final ItemStack stack && stack.getItem() != null)
            return new PortInfo("item", key, label, GameIds.itemIds(stack));
        if (value instanceof com.gtnhplanner.power.Energy)
            return new PortInfo("power", key, label, List.of("eu"));
        return new PortInfo("", key, label, List.of());
    }

    @Override
    public Node makePower(final String sourceId, final Map<String, String> settings) {
        return com.gtnhplanner.power.PowerRegistry.get(sourceId) == null ? null : Node.power(sourceId, settings, 0, 0);
    }

    @Override
    public Node makeCustomRate(final Map<String, String> settings) {
        final Node node = Node.power(com.gtnhplanner.power.CustomRate.ID, settings, 0, 0);
        return node.inputs.isEmpty() && node.outputs.isEmpty() ? null : node;
    }

    /** Only the settings the node's machine profile has: a crafting card takes its machine count and nothing else. */
    @Override
    public void applySettings(final Node node, final Map<String, Object> settings) {
        final MachineConfig cfg = node.machineConfig;
        final Set<String> known = new HashSet<>();
        for (final SettingDef<?> def : cfg.getProfile()
            .settings()) known.add(def.key);
        known.add(Settings.MACHINES.key());
        for (final Map.Entry<String, Object> e : settings.entrySet()) {
            if (known.contains(e.getKey())) cfg.settings.put(e.getKey(), e.getValue());
        }
        // What the settings decide at refresh (a modelled machine's multipliers, machines/game/MachineModels).
        node.refresh();
    }
}

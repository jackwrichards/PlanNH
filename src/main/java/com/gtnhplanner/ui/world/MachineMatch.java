package com.gtnhplanner.ui.world;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.item.ItemStack;
import net.minecraftforge.oredict.OreDictionary;

import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.MachineGroup;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.provider.GTProvider;
import com.gtnhplanner.power.game.PowerPorts;
import com.gtnhplanner.ui.card.CardDefaults;
import com.gtnhplanner.ui.card.CardModel;
import com.gtnhplanner.ui.gt.GtMachines;

/**
 * Whether a block in the world can be a card's machine: one NEI says runs the card's recipe (every recipe, on a shared
 * machine), and for GregTech's single blocks at a tier that can run it. A power card's block is its generator.
 */
public final class MachineMatch {

    private MachineMatch() {}

    /** Why the block cannot be the card's machine, in a sentence, or null when it can. */
    @Nullable
    public static String refuse(final Graph graph, final Node card, @Nullable final ItemStack block) {
        if (block == null) return "That block is not a machine";
        for (final Node recipe : sections(graph, card)) {
            if (!runs(recipe, block))
                return block.getDisplayName() + " does not run this card's recipe (" + WorldView.cardName(card) + ")";
            final GtMachines.Kind kind = GtMachines.of(block);
            if (kind != null && !kind.multiblock() && !kind.steam() && kind.tier() >= 0) {
                final int needs = CardDefaults.recipeTier(euPerTick(recipe));
                if (kind.tier() < needs && needs < CardDefaults.TIERS.length) return block.getDisplayName() + " is "
                    + CardDefaults.TIERS[kind.tier()]
                    + "; the recipe needs "
                    + CardDefaults.TIERS[needs];
            }
        }
        return null;
    }

    public static boolean fits(final Graph graph, final Node card, @Nullable final ItemStack block) {
        return refuse(graph, card, block) == null;
    }

    /** Every recipe on the card: the shared machine's sections, or the card's own. */
    private static List<Node> sections(final Graph graph, final Node card) {
        for (final Object group : graph.groups.values()) if (group instanceof final MachineGroup g && g.getSections()
            .contains(card.id)) {
                final List<Node> out = new ArrayList<>();
                for (final java.util.UUID id : g.getSections()) {
                    final Node n = graph.nodes.get(id);
                    if (n != null) out.add(n);
                }
                return out;
            }
        return List.of(card);
    }

    private static boolean runs(final Node recipe, final ItemStack block) {
        if (recipe.isPower()) {
            final ItemStack m = PowerPorts.machineStack(recipe.powerSource);
            return m != null && same(m, block);
        }
        for (final ItemStack c : CardModel.catalystsOf(recipe)) if (same(c, block)) return true;
        return false;
    }

    /** The same machine: the item, and its damage unless the catalyst takes any. */
    static boolean same(final ItemStack catalyst, final ItemStack block) {
        return catalyst.getItem() == block.getItem() && (catalyst.getItemDamage() == block.getItemDamage()
            || catalyst.getItemDamage() == OreDictionary.WILDCARD_VALUE);
    }

    private static long euPerTick(final Node node) {
        final Object eut = node.properties.get(GTProvider.EU_PER_TICK);
        return eut instanceof final Number n ? n.longValue() : 0;
    }
}

package com.gtnhplanner.ui.card;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.item.ItemStack;

import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.provider.GTKeys;
import com.gtnhplanner.ui.gt.GtMachines;

import codechicken.nei.recipe.IRecipeHandler;

/**
 * The machines a recipe can go on the board with, as the plan button's menu offers them: GregTech's electric single
 * blocks as one choice whose tier follows the recipe, each multiblock and steam machine on its own, and any other
 * mod's machines as NEI lists them.
 */
public final class MachineChoices {

    /** One way to run a recipe. {@code key} is what is remembered for its tab: {@link #SINGLES} or an item key. */
    public record Choice(String key, ItemStack machine, String label, String detail) {}

    /** The single blocks' choice: the tier is the recipe's, whichever machine that is. */
    public static final String SINGLES = "singles";

    private MachineChoices() {}

    /**
     * A new card's node for an NEI recipe, set up as every new card is, on {@code choice}; without one, on the machine
     * last picked for its tab, else the first choice.
     */
    public static Node newNode(final IRecipeHandler handler, final int recipeIndex, @Nullable final Choice choice) {
        final Node node = new Node(handler, recipeIndex, 0, 0);
        CardDefaults.apply(node);
        Choice use = choice;
        if (use == null) {
            final List<Choice> all = of(node);
            use = find(all, MachinePicks.get(handler));
            // A lone machine outside GregTech: the card keeps the recipe's own name, as it always has.
            if (use == null && !all.isEmpty() && (gregtech(node) || all.size() > 1)) use = all.get(0);
        }
        if (use != null) CardDefaults.useMachine(node, use.machine(), gregtech(node));
        SettingMemory.applyTo(node);
        return node;
    }

    /** What a recipe could run on, in NEI's order. */
    public static List<Choice> of(final IRecipeHandler handler, final int recipeIndex) {
        final Node probe = new Node(handler, recipeIndex, 0, 0);
        CardDefaults.apply(probe);
        return of(probe);
    }

    private static List<Choice> of(final Node node) {
        final boolean gt = gregtech(node);
        final int recipeTier = gt ? CardDefaults.recipeTier(euPerTick(node)) : 0;
        final List<Choice> out = new ArrayList<>();
        int singlesAt = -1, singleTier = -1;
        ItemStack single = null;
        for (final ItemStack m : CardModel.catalystsOf(node)) {
            final GtMachines.Kind kind = gt ? GtMachines.of(m) : null;
            if (kind != null && !kind.multiblock() && !kind.steam() && kind.tier() >= 0) {
                if (singlesAt < 0) {
                    singlesAt = out.size();
                    out.add(null);
                }
                if (single == null || better(kind.tier(), singleTier, recipeTier)) {
                    single = m;
                    singleTier = kind.tier();
                }
                continue;
            }
            final String key = CardDefaults.itemKey(m);
            if (find(out, key) != null) continue;
            final String detail = kind == null ? "" : kind.steam() ? "Steam" : "Multiblock";
            out.add(new Choice(key, m, m.getDisplayName(), detail));
        }
        if (singlesAt >= 0) {
            final String tier = singleTier < CardDefaults.TIERS.length ? CardDefaults.TIERS[singleTier] : "";
            out.set(singlesAt, new Choice(SINGLES, single, single.getDisplayName(), tier));
        }
        return out;
    }

    /** The choice with {@code key}, or null. */
    @Nullable
    public static Choice find(final List<Choice> choices, @Nullable final String key) {
        if (key == null) return null;
        for (final Choice c : choices) if (c != null && key.equals(c.key())) return c;
        return null;
    }

    /**
     * Whether tier {@code a} beats {@code b} for a recipe of tier {@code r}: the lowest that runs it, else the highest.
     */
    private static boolean better(final int a, final int b, final int r) {
        if (a >= r != b >= r) return a >= r;
        return a >= r ? a < b : a > b;
    }

    private static boolean gregtech(final Node node) {
        return CardModel.GT_PROFILE.equals(node.machineConfig.profileId);
    }

    private static long euPerTick(final Node node) {
        final Object eut = node.properties.get(GTKeys.EU_PER_TICK);
        return eut instanceof final Number n ? n.longValue() : 0;
    }
}

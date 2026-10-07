package com.gtnhplanner.importer;

import java.util.List;

import javax.annotation.Nullable;

import com.gtnhplanner.importer.FfPlan.FfRecipe;

/**
 * Where the importer finds in-game recipes. The game's implementation asks NEI (and GregTech's recipe maps); tests
 * hand in fakes. Everything crossing this interface is plain data.
 */
public interface RecipeIndex {

    /** The in-game recipes that could be this FF recipe, best guess first; the matcher picks among them. */
    Lookup find(FfRecipe recipe);

    /**
     * Candidates for one FF recipe.
     *
     * @param note why there are none, when there are none and the index knows (GregTech not loaded, a kind of FF
     *             card the game has no recipe for)
     */
    record Lookup(List<GameRecipe> candidates, @Nullable String note) {

        public static Lookup of(final List<GameRecipe> candidates) {
            return new Lookup(candidates, null);
        }

        public static Lookup none(final String note) {
            return new Lookup(List.of(), note);
        }
    }

    /**
     * One ingredient of an in-game recipe.
     *
     * @param kind     "item" or "fluid"
     * @param ids      every FF-style id the slot takes (normalized, see {@link FfIds}), its own first; ore dictionary
     *                 names as {@code oredict:<name>}
     * @param chance   0..1, 1 for a sure output
     * @param consumed false for what the machine keeps (a programmed circuit, a mold)
     */
    record GameStack(String kind, List<String> ids, double amount, double chance, boolean consumed) {

        public String id() {
            return ids.isEmpty() ? "" : ids.getFirst();
        }
    }

    /**
     * An in-game recipe a node can be built from.
     *
     * @param handler       what the node maker needs to build the node (an NEI handler in game), opaque here
     * @param index         the recipe's index in that handler
     * @param durationTicks null when the game has no number to compare (crafting)
     * @param euPerTick     null when the game has no number to compare
     * @param specialValue  GregTech's special value (heat for coil recipes), null when there is none to compare
     */
    record GameRecipe(Object handler, int index, String handlerName, List<GameStack> inputs, List<GameStack> outputs,
        @Nullable Integer durationTicks, @Nullable Long euPerTick, @Nullable Integer specialValue) {

        /** The programmed circuit setting among the kept inputs, else null. */
        @Nullable
        public Integer circuit() {
            for (final GameStack in : inputs) {
                if (in.consumed()) continue;
                for (final String id : in.ids()) {
                    final Integer c = FfIds.circuitOf(id);
                    if (c != null) return c;
                }
            }
            return null;
        }
    }
}

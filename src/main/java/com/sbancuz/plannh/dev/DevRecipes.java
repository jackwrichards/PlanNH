package com.sbancuz.plannh.dev;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.oredict.OreDictionary;

import com.sbancuz.plannh.api.PlanAPI;
import com.sbancuz.plannh.data.flowchart.Graph;
import com.sbancuz.plannh.data.flowchart.Node;
import com.sbancuz.plannh.data.flowchart.Plan;

import codechicken.nei.PositionedStack;
import codechicken.nei.recipe.GuiCraftingRecipe;
import codechicken.nei.recipe.ICraftingHandler;
import cpw.mods.fml.common.registry.GameRegistry;

/**
 * Dev-only plan seeding for the harness: put a specific real recipe on the active board without clicking through
 * NEI, so tests and screenshots can set up a scenario in one call. Client thread only (NEI handlers).
 */
final class DevRecipes {

    private DevRecipes() {}

    /**
     * Adds the first recipe that makes {@code output}, from a handler whose name contains {@code handlerFilter},
     * with an ingredient whose display name contains {@code inputFilter} (both case-insensitive, empty = any).
     *
     * @param output ore dictionary name ("dustRutile") or registry name ("minecraft:iron_ingot[:meta]")
     */
    static Map<String, Object> addRecipe(final String output, final String handlerFilter, final String inputFilter,
        final int x, final int y) {
        final ItemStack stack = resolveStack(output);
        if (stack == null) throw new IllegalArgumentException("no item for '" + output + "'");
        final String hf = handlerFilter.toLowerCase(Locale.ROOT);
        final String inf = inputFilter.toLowerCase(Locale.ROOT);
        final List<String> seen = new ArrayList<>();
        for (final ICraftingHandler handler : GuiCraftingRecipe.getCraftingHandlers("item", stack)) {
            final String name = handler.getRecipeName()
                .trim();
            seen.add(name + " (" + handler.numRecipes() + ")");
            if (!name.toLowerCase(Locale.ROOT)
                .contains(hf)) continue;
            for (int i = 0; i < handler.numRecipes(); i++) {
                if (!inf.isEmpty() && !hasIngredient(handler, i, inf)) continue;
                final Node node;
                final com.sbancuz.plannh.ui.BoardSession board = com.sbancuz.plannh.ui.BoardSession.current();
                if (board != null) {
                    // The board is open: add it the way NEI's "+" does, placed and auto-wired.
                    node = board.addRecipe(handler, i);
                } else {
                    node = new Node(handler, i, x, y);
                    com.sbancuz.plannh.ui.card.CardDefaults.apply(node);
                    final Graph graph = Plan.getActiveGraph();
                    PlanAPI.recordEdit(graph, () -> graph.addNode(node));
                    PlanAPI.save();
                }
                final Map<String, Object> m = new LinkedHashMap<>();
                m.put("ok", true);
                m.put("node", node.id.toString());
                m.put("machine", node.machineName);
                m.put("handler", name);
                m.put("recipeIndex", i);
                return m;
            }
        }
        throw new IllegalArgumentException("no matching recipe; handlers making it: " + seen);
    }

    /** Removes every node, edge, note and group from the active board, as one undoable edit. */
    static Map<String, Object> clearPlan() {
        final Graph graph = Plan.getActiveGraph();
        PlanAPI.recordEdit(graph, () -> {
            for (final UUID id : new ArrayList<>(graph.nodes.keySet())) graph.removeNode(id);
            for (final UUID id : new ArrayList<>(graph.drawers.keySet())) graph.removeDrawer(id);
            graph.notes.clear();
            graph.groups.clear();
        });
        PlanAPI.save();
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        return m;
    }

    /**
     * What NEI and PlanNH see in the first matching recipe, without touching the plan: NEI's ingredient, result and
     * other stacks (registry name, meta, size), the catalysts, and the ports a node built from it would have.
     */
    static Map<String, Object> recipeInfo(final String output, final String handlerFilter, final String inputFilter) {
        final ItemStack stack = resolveStack(output);
        if (stack == null) throw new IllegalArgumentException("no item for '" + output + "'");
        final String hf = handlerFilter.toLowerCase(Locale.ROOT);
        final String inf = inputFilter.toLowerCase(Locale.ROOT);
        for (final ICraftingHandler handler : GuiCraftingRecipe.getCraftingHandlers("item", stack)) {
            if (!handler.getRecipeName()
                .toLowerCase(Locale.ROOT)
                .contains(hf)) continue;
            for (int i = 0; i < handler.numRecipes(); i++) {
                if (!inf.isEmpty() && !hasIngredient(handler, i, inf)) continue;
                final Map<String, Object> m = new LinkedHashMap<>();
                m.put(
                    "handler",
                    handler.getRecipeName()
                        .trim());
                m.put(
                    "handlerClass",
                    handler.getClass()
                        .getName());
                m.put("recipeIndex", i);
                m.put("ingredients", describe(handler.getIngredientStacks(i)));
                m.put(
                    "result",
                    describe(handler.getResultStack(i) == null ? List.of() : List.of(handler.getResultStack(i))));
                m.put("others", describe(handler.getOtherStacks(i)));
                m.put("catalysts", describe(codechicken.nei.recipe.RecipeCatalysts.getRecipeCatalysts(handler)));
                final Node node = new Node(handler, i, 0, 0);
                final List<String> ports = new ArrayList<>();
                node.inputs.forEach(
                    p -> ports.add("in  " + p.getDisplayName() + " x" + p.getAmount() + " chance " + p.getChance()));
                node.outputs.forEach(
                    p -> ports.add("out " + p.getDisplayName() + " x" + p.getAmount() + " chance " + p.getChance()));
                m.put("ports", ports);
                final List<String> props = new ArrayList<>();
                node.properties.forEach((k, v) -> props.add(k + " = " + v));
                m.put("properties", props);
                return m;
            }
        }
        throw new IllegalArgumentException("no matching recipe");
    }

    private static List<String> describe(final List<PositionedStack> stacks) {
        final List<String> out = new ArrayList<>();
        if (stacks == null) return out;
        for (final PositionedStack ps : stacks) {
            if (ps == null) continue;
            final ItemStack s = ps.item;
            out.add(
                s == null ? "(empty)"
                    : Item.itemRegistry.getNameForObject(s.getItem()) + ":"
                        + s.getItemDamage()
                        + " x"
                        + s.stackSize
                        + " '"
                        + s.getDisplayName()
                        + "' at "
                        + ps.relx
                        + ","
                        + ps.rely
                        + (ps.items.length > 1 ? " (+" + (ps.items.length - 1) + " alts)" : ""));
        }
        return out;
    }

    private static boolean hasIngredient(final ICraftingHandler handler, final int recipe, final String filter) {
        for (final PositionedStack ps : handler.getIngredientStacks(recipe)) {
            for (final ItemStack s : ps.items) {
                if (s != null && s.getDisplayName()
                    .toLowerCase(Locale.ROOT)
                    .contains(filter)) return true;
            }
        }
        return false;
    }

    private static ItemStack resolveStack(final String key) {
        if (key.contains(":")) {
            final String[] parts = key.split(":");
            final Item item = GameRegistry.findItem(parts[0], parts[1]);
            if (item == null) return null;
            return new ItemStack(item, 1, parts.length > 2 ? Integer.parseInt(parts[2]) : 0);
        }
        final List<ItemStack> ores = OreDictionary.getOres(key);
        return ores.isEmpty() ? null
            : ores.get(0)
                .copy();
    }
}

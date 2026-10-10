package com.gtnhplanner.dev;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Plan;
import com.gtnhplanner.machines.game.WebCards;
import com.gtnhplanner.machines.game.WebEffect;
import com.gtnhplanner.machines.web.NodeMath;
import com.gtnhplanner.machines.web.RecipeRules;
import com.gtnhplanner.machines.web.Web;

/**
 * {@code /machinemath}: every card of the active plan as the website's machine maths see it (machines/web): its map,
 * machine and handler, the settings as the website's node fields, and the numbers (tier, ticks, EU/t, parallels,
 * overclocks, power state).
 */
final class DevMachineMath {

    private DevMachineMath() {}

    static Object report() {
        final Graph graph = Plan.getActiveGraph();
        final List<Object> cards = new ArrayList<>();
        int i = 0;
        for (final Node node : graph.getNodes()) {
            final Map<String, Object> c = new LinkedHashMap<>();
            c.put("index", i++);
            c.put("machine", node.machineName);
            if (node.isPower()) {
                c.put("power", node.powerSource);
                cards.add(c);
                continue;
            }
            c.put("map", WebCards.mapId(node));
            final Web.Recipe recipe = WebEffect.recipe(node);
            if (recipe == null) {
                c.put("covered", false);
                cards.add(c);
                continue;
            }
            final Web.Node web = WebCards.node(node, node.machineConfig, recipe);
            final net.minecraft.item.ItemStack stack = WebCards.machineStack(node);
            c.put("machineName", stack == null ? null : stack.getDisplayName());
            c.put("machineType", recipe.machineType);
            c.put("handlers", handlerLabels(recipe));
            c.put("handlerId", web.machineHandlerId);
            final Map<String, Object> settings = new LinkedHashMap<>();
            settings.put("overclockTier", web.overclockTier);
            settings.put("hatchAmps", web.hatchAmps);
            settings.put("energyHatchType", web.energyHatchType);
            settings.put("coilTier", web.coilTier);
            settings.put("machineConfigTiers", web.machineConfigTiers);
            c.put("node", settings);
            final NodeMath.Result r = WebEffect.result(node, node.machineConfig);
            c.put("covered", r != null);
            if (r != null) {
                final Map<String, Object> m = new LinkedHashMap<>();
                m.put("effectiveMachine", r.effectiveRecipe().machineType);
                m.put(
                    "tier",
                    r.overclock()
                        .tier());
                m.put(
                    "durationTicks",
                    r.overclock()
                        .durationTicks());
                m.put(
                    "eut",
                    r.overclock()
                        .eut());
                m.put(
                    "overclocks",
                    r.overclock()
                        .overclockSteps());
                m.put(
                    "perfect",
                    r.overclock()
                        .perfectOverclockSteps());
                m.put("parallels", r.machineParallels());
                m.put("craftsPerSecond", r.operationsPerSecond());
                m.put("euT", r.euT());
                m.put("outputMultipliers", r.outputMultipliers());
                if (r.power() != null) m.put(
                    "power",
                    r.power()
                        .state());
                m.put("stall", r.stall());
                c.put("math", m);
            }
            cards.add(c);
        }
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("cards", cards);
        return out;
    }

    /** Every GregTech recipe map in this game, by whether handlers.json knows its id. */
    static Object maps() {
        final List<String> known = new ArrayList<>(), unknown = new ArrayList<>();
        for (final gregtech.api.recipe.RecipeMap<?> map : gregtech.api.recipe.RecipeMap.ALL_RECIPE_MAPS.values()) {
            final String line = map.unlocalizedName + " ("
                + map.getAllRecipes()
                    .size()
                + ")";
            (WebCards.entry(map.unlocalizedName) != null ? known : unknown).add(line);
        }
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("unknown", unknown);
        out.put("known", known.size());
        return out;
    }

    private static List<String> handlerLabels(final Web.Recipe recipe) {
        final List<String> out = new ArrayList<>();
        for (final Web.Handler h : RecipeRules.machineHandlers(recipe)) out.add(h.id + " (" + h.label + ")");
        return out;
    }
}

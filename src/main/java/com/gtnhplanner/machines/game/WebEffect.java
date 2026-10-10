package com.gtnhplanner.machines.game;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

import javax.annotation.Nullable;

import com.gtnhplanner.data.MachineConfig;
import com.gtnhplanner.data.effect.EffectResult;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.machines.web.NodeMath;
import com.gtnhplanner.machines.web.Web;
import com.gtnhplanner.ui.card.CardModel;

/**
 * A GregTech card's numbers from the website's machine maths ({@link NodeMath}), as the engine reads them: the
 * duration and the machine's draw exactly, its parallels, each output's productivity, and why it would not start. A
 * multiblock's sub-tick duration 1/n runs as one tick with n times the parallels, its draw unchanged. Kept per card
 * until its recipe, machine or settings change.
 */
public final class WebEffect {

    private WebEffect() {}

    private record Built(Object stamp, @Nullable Web.Recipe recipe) {}

    private record Solved(Object key, NodeMath.Result result) {}

    private static final Map<Node, Built> RECIPES = new WeakHashMap<>();
    private static final Map<MachineConfig, Solved> RESULTS = new WeakHashMap<>();

    /** The card's numbers, or null when the website's maths do not cover it (the profile's own effect then runs). */
    @Nullable
    public static EffectResult effect(final Node node, final MachineConfig cfg) {
        final NodeMath.Result r = result(node, cfg);
        if (r == null) return null;
        final double duration = r.overclock()
            .durationTicks();
        final double parallels = Math.min(r.machineParallels(), Integer.MAX_VALUE);
        final double draw = r.overclock()
            .eut() * parallels;
        final EffectResult out;
        if (duration < 1) {
            // GT's calculateMultiplierUnderOneTick: whole recipes a tick, banked as parallels.
            final double perTick = Math.round(1 / duration);
            out = new EffectResult(1, 0, (int) Math.min(Integer.MAX_VALUE, parallels * perTick)).exact(1, draw);
        } else out = new EffectResult(1, 0, (int) parallels).exact(duration, draw);
        final double[] outputs = new double[r.outputMultipliers()
            .size()];
        for (int i = 0; i < outputs.length; i++) outputs[i] = r.outputMultipliers()
            .get(i);
        return out.multipliers(null, outputs)
            .stall(r.stall())
            .detail(r);
    }

    /** The website's working for the card, or null when it does not cover it. */
    @Nullable
    public static NodeMath.Result result(final Node node, final MachineConfig cfg) {
        if (!CardModel.GT_PROFILE.equals(cfg.profileId)) return null;
        final Web.Recipe recipe = recipe(node);
        if (recipe == null) return null;
        final Object key = Arrays.asList(new HashMap<>(cfg.settings), node.machineName, recipe);
        final Solved solved = RESULTS.get(cfg);
        if (solved != null && solved.key.equals(key)) return solved.result;
        final Web.Node web = WebCards.node(node, cfg, recipe);
        if (!NodeMath.covers(recipe, web)) return null;
        final NodeMath.Result result = NodeMath.compute(recipe, web);
        RESULTS.put(cfg, new Solved(key, result));
        return result;
    }

    /** The card's recipe as the website's dataset carries it, built once per recipe. */
    @Nullable
    public static Web.Recipe recipe(final Node node) {
        final Object stamp = Arrays.asList(
            node.recipeId,
            node.handlerRecipeIndex,
            new HashMap<>(node.properties),
            node.inputs.size(),
            node.outputs.size());
        final Built built = RECIPES.get(node);
        if (built != null && built.stamp.equals(stamp)) return built.recipe;
        final Web.Recipe recipe = WebCards.recipe(node);
        RECIPES.put(node, new Built(stamp, recipe));
        return recipe;
    }

    /** Forgets what was built for the card: its recipe was read again. */
    public static void forget(final Node node) {
        RECIPES.remove(node);
        RESULTS.remove(node.machineConfig);
    }
}

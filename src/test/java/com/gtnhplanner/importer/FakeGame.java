package com.gtnhplanner.importer;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Port;
import com.gtnhplanner.harness.GtnhFlowLoader;
import com.gtnhplanner.harness.TestIngredients;
import com.gtnhplanner.importer.FfPlan.FfRecipe;
import com.gtnhplanner.importer.FfPlan.FfSlot;
import com.gtnhplanner.importer.RecipeIndex.GameRecipe;
import com.gtnhplanner.importer.RecipeIndex.GameStack;

/**
 * A game for headless import tests: every FF recipe's in-game twin is the recipe itself, so every card matches, and
 * nodes are built from plain data with one port per resource, as GTNH Planner builds them. Port names carry
 * {@code kind|id}, which {@link #describe} reads back.
 */
final class FakeGame implements RecipeIndex, NodeMaker {

    /** Recipe ids the game "does not have". */
    final Set<String> missing = new HashSet<>();
    /** The machine each node was asked to run on. */
    final Map<UUID, String> machines = new HashMap<>();

    FakeGame() {
        GtnhFlowLoader.ensureDefaultMachineProfile();
    }

    @Override
    public Lookup find(final FfRecipe recipe) {
        if (missing.contains(recipe.id())) return Lookup.none("not in this game");
        return Lookup.of(List.of(twin(recipe)));
    }

    /** The FF recipe as the game would list it. */
    static GameRecipe twin(final FfRecipe r) {
        final List<GameStack> in = new ArrayList<>(), out = new ArrayList<>();
        for (final FfSlot s : r.inputs()) in.add(new GameStack(s.kind(), s.ids(), s.amount(), 1, s.consumed()));
        for (final FfSlot s : r.outputs()) out.add(new GameStack(s.kind(), s.ids(), s.amount(), s.chance(), true));
        return new GameRecipe(
            "fake:" + r.id(),
            0,
            r.machineType(),
            in,
            out,
            r.durationTicks(),
            Math.round(r.eut()),
            r.specialValue() == null ? null : (int) Math.round(r.specialValue()));
    }

    @Override
    public Node make(final GameRecipe recipe, @Nullable final String machineLabel) {
        final Node node = new Node(UUID.randomUUID(), 0, 0);
        node.machineName = recipe.handlerName();
        for (final Map.Entry<String, Double> e : merged(recipe.inputs(), true).entrySet())
            node.inputs.add(TestIngredients.port(e.getKey(), e.getValue()));
        for (final Map.Entry<String, Double> e : merged(recipe.outputs(), false).entrySet())
            node.outputs.add(TestIngredients.port(e.getKey(), e.getValue()));
        if (machineLabel != null) machines.put(node.id, machineLabel);
        return node;
    }

    /** One port per resource, kept inputs left out, like GTProvider and Node.deduplicate. */
    private static Map<String, Double> merged(final List<GameStack> stacks, final boolean inputs) {
        final Map<String, Double> out = new LinkedHashMap<>();
        for (final GameStack s : stacks) {
            if (inputs && !s.consumed()) continue;
            out.merge(s.kind() + "|" + s.id(), s.amount(), Double::sum);
        }
        return out;
    }

    @Override
    public PortInfo describe(final Port<?> port) {
        final String name = TestIngredients.nameOf(port);
        final int bar = name.indexOf('|');
        final String kind = name.substring(0, bar), id = name.substring(bar + 1);
        return new PortInfo(kind, FfIds.toKey(kind, id), id, List.of(id));
    }

    @Override
    public void applySettings(final Node node, final Map<String, Object> settings) {
        node.machineConfig.settings.putAll(settings);
    }

    /** The port of a node holding an FF id, or -1. */
    int port(final Node node, final boolean output, final String id) {
        final List<Port<?>> ports = output ? node.outputs : node.inputs;
        for (int i = 0; i < ports.size(); i++) {
            if (FfIds.same(
                describe(ports.get(i)).ids()
                    .getFirst(),
                id)) return i;
        }
        return -1;
    }

    static String fixture(final String name) {
        try (InputStream in = FakeGame.class.getResourceAsStream("/factory-flow/" + name)) {
            Objects.requireNonNull(in, "missing factory-flow fixture: " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

package com.sbancuz.plannh.data.flowchart;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The undo histories of a plan's slots, kept outside the graphs.
 *
 * <p>
 * An undo replaces the slot's graph with one decoded from a snapshot. While the history lived on
 * the graph, that new graph came with an empty history and a second undo had nothing to go back
 * to. Here the history belongs to the slot: it is found by {@link Graph#slot()}, which the graph
 * an {@link UndoHistory#undo undo} or {@link UndoHistory#redo redo} returns takes over from the one
 * it replaces, so one slot keeps one history however many times its graph is swapped.
 *
 * <p>
 * Owned by {@link Plan} ({@link Plan#history(Graph)}); not persisted. Client thread only.
 */
public final class UndoHistories {

    private final Map<UUID, UndoHistory> bySlot = new HashMap<>();

    /** The history of the slot {@code graph} is, created empty on first ask. */
    public UndoHistory of(final Graph graph) {
        return bySlot.computeIfAbsent(graph.slot(), k -> new UndoHistory());
    }

    /** Drops the history of the slot {@code graph} is, e.g. when the slot is closed. */
    public void forget(final Graph graph) {
        bySlot.remove(graph.slot());
    }

    /** Drops every history whose slot is not one of {@code live}. */
    public void retainOnly(final Collection<Graph> live) {
        final Set<UUID> keep = new HashSet<>();
        for (final Graph graph : live) {
            keep.add(graph.slot());
        }
        bySlot.keySet()
            .retainAll(keep);
    }
}

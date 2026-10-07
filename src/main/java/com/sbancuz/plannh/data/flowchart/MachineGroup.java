package com.sbancuz.plannh.data.flowchart;

import lombok.Getter;
import lombok.Setter;

/**
 * A group whose recipes run on the same machines. A plain {@link Group} frames a part of the chart
 * and says nothing about the hardware; this one is a claim about it, so it holds one machine type
 * only (by recipe handler, never by the display name a player can rewrite), its members share one
 * configuration, and their machine counts add up into a single pool the solver can be asked to fit.
 *
 * <p>
 * A separate type rather than a flag on {@link Group}: the capacity, the single-type rule and the
 * shared settings mean nothing for an ordinary group, and the canvas draws this one differently so
 * the two are not mistaken for each other.
 */
@Getter
@Setter
public class MachineGroup extends Group {

    public static final String TYPE = "machine_group";

    /**
     * How many machines the pool is, or 0 for as many as it takes. A positive capacity caps the
     * group's summed machine time in the solve, so the chart is balanced to fit the hardware that
     * exists instead of being measured after the fact.
     */
    private int machineCapacity;

    /**
     * Whether {@link #machineCapacity} is the count rather than a cap: a shared machine whose count the player
     * pinned runs exactly that many, its recipes' machine time adding up to it.
     */
    private boolean pinned;

    /**
     * The recipes in the order the shared card shows them, top first: the first is the host, whose card it is. Kept
     * alongside the group's node ids, which have no order. Empty in a group from an older save, which is not drawn as
     * a shared card.
     */
    private final java.util.List<java.util.UUID> sections = new java.util.ArrayList<>();

    /** A shared machine: a group drawn as one card, its recipes as sections. */
    public boolean isShared() {
        return sections.size() >= 2;
    }

    /** Adds a recipe as the last section. */
    public void addSection(final java.util.UUID nodeId) {
        if (sections.contains(nodeId)) return;
        sections.add(nodeId);
        getNodeIds().add(nodeId);
    }

    public void removeSection(final java.util.UUID nodeId) {
        sections.remove(nodeId);
        getNodeIds().remove(nodeId);
    }

    public MachineGroup() {
        // GraphData names a fresh chart element after its type, which spells this one "Machine_group".
        setHeader("Machine Group");
    }

    @Override
    public String getType() {
        return TYPE;
    }
}

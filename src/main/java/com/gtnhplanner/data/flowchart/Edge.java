package com.gtnhplanner.data.flowchart;

import java.util.UUID;

import javax.annotation.Nonnull;

public class Edge {

    @Nonnull
    public final UUID id;
    /// Nodes
    @Nonnull
    public final UUID sourceNodeId;
    @Nonnull
    public final UUID targetNodeId;
    /// Recipe source/targets inside the nodes
    public int sourceOutputIndex;
    public int targetInputIndex;

    public Edge(final UUID id, final UUID sourceNodeId, final UUID targetNodeId, final int sourceOutputIndex,
        final int targetInputIndex) {
        this.id = id;
        this.sourceNodeId = sourceNodeId;
        this.targetNodeId = targetNodeId;
        this.sourceOutputIndex = sourceOutputIndex;
        this.targetInputIndex = targetInputIndex;
    }
}

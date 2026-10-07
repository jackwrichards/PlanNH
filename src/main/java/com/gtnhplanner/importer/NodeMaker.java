package com.gtnhplanner.importer;

import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Port;
import com.gtnhplanner.importer.RecipeIndex.GameRecipe;

/**
 * Builds GTNH Planner nodes for matched recipes and tells the importer what their ports hold. The game's implementation
 * builds a real node from the NEI recipe; tests build nodes from plain data.
 */
public interface NodeMaker {

    /**
     * A node for the recipe, with the board's default settings for a new card applied, at (0, 0); null when the
     * game cannot build one.
     *
     * @param machineLabel the machine FF ran the recipe on ("Large Chemical Reactor"), to pick that one of the
     *                     recipe's machines when the game offers it; null for the default
     */
    @Nullable
    Node make(GameRecipe recipe, @Nullable String machineLabel);

    /** What a port of a node made here holds. */
    PortInfo describe(Port<?> port);

    /**
     * Puts the imported machine settings on a node ({@link com.gtnhplanner.data.Settings} keys). The maker drops
     * the ones the node's machine has no use for (a coil on a crafting table).
     */
    void applySettings(Node node, Map<String, Object> settings);

    /**
     * @param kind  "item" or "fluid"
     * @param key   GTNH Planner's resource key for a drawer of this resource ({@code item:<registry name>:<meta>},
     *              {@code fluid:<name>})
     * @param label the name a player reads
     * @param ids   the FF-style ids it answers to, its own first; ore dictionary names as {@code oredict:<name>}
     */
    record PortInfo(String kind, String key, String label, List<String> ids) {}
}

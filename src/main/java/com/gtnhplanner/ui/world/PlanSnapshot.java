package com.gtnhplanner.ui.world;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.ui.card.StructureArt;

/**
 * The plan as the board last drew it, for the views outside the planner (the minimap, the world): every card with its
 * machine, count, flows and linked blocks; every drawer; every wire's path. The board publishes a new one whenever its
 * models or routes change ({@link #publish}); readers take {@link #latest()}. Immutable, client thread only.
 *
 * @param graph        the plan it was taken of (compared by identity)
 * @param graphVersion the plan's version when taken: a different version now means it changed since
 * @param viewX        the board's view centre, world units
 * @param rateUnit     the unit the board shows rates in
 */
public record PlanSnapshot(Graph graph, String planName, long graphVersion, List<Card> cards, List<Box> drawers,
    List<Line> wires, float viewX, float viewY, com.gtnhplanner.ui.theme.Fmt.RateUnit rateUnit) {

    /** What flows through a port or drawer, per second. */
    public record Flow(String name, @Nullable ItemStack item, @Nullable FluidStack fluid, boolean power,
        double perSecond, String key) {}

    /**
     * A card (a shared machine is one, its recipes' flows together), at its place on the board.
     *
     * @param art     its structure picture, or null for its item
     * @param links   its placements in the world, each {dimension, x, y, z}, then a facing and a size when set
     * @param needs   what its recipes ask of its structure, for its ghost
     * @param nodeIds every recipe on it, the card's own first
     */
    public record Card(UUID id, List<UUID> nodeIds, float x, float y, float w, float h, String name,
        @Nullable ItemStack machine, @Nullable StructureArt.Art art, int tint, double machines, boolean pinned,
        List<Flow> inputs, List<Flow> outputs, double euPerTick, double madeEuPerTick, List<int[]> links, String tier,
        int amps, @Nullable ItemStack circuit, List<Setting> settings,
        com.gtnhplanner.ui.gt.StructureGhosts.Needs needs) {}

    /**
     * A setting pinned to the card, as a chip ({@code ui/card/SettingControls}, {@code SettingPins}): the coil, a
     * machine setting, a power card's setting; {@code reading} for a power card's reading; {@code warn} when it stops
     * the recipe (a coil too cold).
     */
    public record Setting(String label, String value, @Nullable ItemStack icon, boolean warn, boolean reading) {}

    /** A drawer: its resource, kind (a source supplies, the rest take) and rate. */
    public record Box(UUID id, float x, float y, float w, float h, String label, @Nullable ItemStack item,
        @Nullable FluidStack fluid, boolean power, com.gtnhplanner.data.flowchart.Drawer.Kind kind, double rate) {

        public boolean source() {
            return kind == com.gtnhplanner.data.flowchart.Drawer.Kind.SOURCE;
        }

        public int tint() {
            return com.gtnhplanner.ui.drawer.DrawerPaint.tint(kind);
        }
    }

    /**
     * A wire: its route on the board, colour and width; {@code flowing} false for one nothing moves along. Between two
     * cards, {@code from} and {@code to} are their recipes' nodes; a drawer's wire has neither.
     */
    public record Line(List<int[]> path, int color, float width, boolean flowing, String resource, @Nullable UUID from,
        @Nullable UUID to) {}

    private static volatile PlanSnapshot latest;
    /** Where the board's view was last centred, kept apart so panning the board needs no new snapshot. */
    private static volatile float lastViewX, lastViewY;

    public static void setView(final float x, final float y) {
        lastViewX = x;
        lastViewY = y;
    }

    public static float lastViewX() {
        return lastViewX;
    }

    public static float lastViewY() {
        return lastViewY;
    }

    public static void publish(final PlanSnapshot snapshot) {
        latest = snapshot;
    }

    @Nullable
    public static PlanSnapshot latest() {
        return latest;
    }

    /** The card holding a node (a recipe on a shared machine is on its host's card), or null. */
    @Nullable
    public Card cardOf(final UUID nodeId) {
        for (final Card c : cards) if (c.nodeIds()
            .contains(nodeId)) return c;
        return null;
    }
}

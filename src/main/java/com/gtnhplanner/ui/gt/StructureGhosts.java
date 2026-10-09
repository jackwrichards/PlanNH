package com.gtnhplanner.ui.gt;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import net.minecraft.item.ItemStack;

import com.gtnhplanner.GtnhPlanner;

/**
 * Whole GregTech multiblocks as see-through ghosts in the world, for cards placed there: the structure built from its
 * definition as the card's picture is ({@link MultiblockRenderer}) and kept, then drawn block by block wherever a card
 * is placed, turned to face the way it was placed. Real blocks hide the ghost where they stand, so a structure half
 * built shows what is left to build.
 *
 * <p>
 * A structure is built as the card needs it ({@link Needs}): GregTech's own structure channels, as the Hologram
 * Projector sets them, carry the card's coil, a distillation tower's height for its recipe's fluid outputs and an
 * assembly line's length for its item inputs; a placement can set the size itself.
 *
 * <p>
 * Safe to load without GregTech: building goes through {@link StructureGhostBuilder} only once
 * {@link MultiblockPictures#available()} said yes.
 */
public final class StructureGhosts {

    /**
     * What a card asks of its structure: its coil (the coil's heat, 0 for none), and the most fluid outputs and item
     * inputs of its recipes (a distillation tower's layers, an assembly line's slices).
     */
    public record Needs(int coilHeat, int fluidOutputs, int itemInputs) {

        public static final Needs NONE = new Needs(0, 0, 0);
    }

    /**
     * A structure's ghost: the structure as built (kept by {@link StructureGhostBuilder}), how many blocks it has, how
     * far it reaches from the controller, in blocks, each way (its front to the south), and its size: the step it was
     * built at and the most there are, both 0 for a structure of one size.
     */
    public record Ghost(Object built, int blocks, int minX, int minY, int minZ, int maxX, int maxY, int maxZ, int size,
        int sizes) {

        /**
         * The box the structure fills, turned to {@code facing}, from the controller block's corner: {x0, y0, z0, x1,
         * y1, z1}.
         */
        public double[] box(final int facing) {
            final int[] a = turn(minX, minZ, facing), b = turn(maxX, maxZ, facing);
            return new double[] { Math.min(a[0], b[0]), minY, Math.min(a[1], b[1]), Math.max(a[0], b[0]) + 1, maxY + 1,
                Math.max(a[1], b[1]) + 1 };
        }

        /** Whether the structure comes in more than one size. */
        public boolean sized() {
            return sizes > 1;
        }
    }

    /** A block's {dx, dz} from the controller, turned to {@code facing}: where it stands in the world. */
    public static int[] turn(final int dx, final int dz, final int facing) {
        return switch (facing & 3) {
            case 1 -> new int[] { -dz, dx };
            case 2 -> new int[] { -dx, -dz };
            case 3 -> new int[] { dz, -dx };
            default -> new int[] { dx, dz };
        };
    }

    /** Built ghosts by controller and channels. */
    private static final Map<String, Ghost> GHOSTS = new HashMap<>();
    /** Structures that cannot be ghosted (not constructable, too big, or building threw): they keep their block. */
    private static final Set<String> NONE = new HashSet<>();
    /** At most one structure built a frame: building one can take tens of milliseconds. */
    private static long builtAt;

    private StructureGhosts() {}

    /**
     * The ghost already built for a controller as a card needs it at a size (0: as its recipe needs), or null; never
     * builds. Render thread.
     */
    @Nullable
    public static Ghost peek(@Nullable final ItemStack controller, final Needs needs, final int size) {
        final String key = key(controller, needs, size);
        return key == null ? null : GHOSTS.get(key);
    }

    /**
     * The ghost for a controller as a card needs it at a size (0: as its recipe needs), built the first time it is
     * asked for (one a frame), or null: not a constructable GregTech multiblock, not built yet this frame, or it cannot
     * be built. Render thread only.
     */
    @Nullable
    public static Ghost get(@Nullable final ItemStack controller, final Needs needs, final int size) {
        final String key = key(controller, needs, size);
        if (key == null || NONE.contains(key)) return null;
        final Ghost known = GHOSTS.get(key);
        if (known != null) return known;
        final long now = System.nanoTime();
        if (now - builtAt < 50_000_000L) return null;
        builtAt = now;
        Ghost made = null;
        try {
            made = StructureGhostBuilder.build(MultiblockRenderer.controllerMeta(controller), needs, size);
        } catch (final LinkageError | RuntimeException e) {
            GtnhPlanner.LOG.warn("Could not ghost multiblock {}", key, e);
        }
        if (made == null) NONE.add(key);
        else GHOSTS.put(key, made);
        return made;
    }

    /**
     * How far above a picked spot a machine's controller goes so the structure's bottom stands on it: 0 for anything
     * without a ghost built yet.
     */
    public static int lift(@Nullable final ItemStack controller, final Needs needs, final int size) {
        final Ghost g = peek(controller, needs, size);
        return g == null ? 0 : -g.minY();
    }

    /** The cache key: the controller's meta and the channels it is built with; null when it is no multiblock. */
    @Nullable
    private static String key(@Nullable final ItemStack controller, final Needs needs, final int size) {
        if (controller == null || !MultiblockPictures.available()) return null;
        try {
            final int meta = MultiblockRenderer.controllerMeta(controller);
            return meta < 0 ? null : meta + " " + StructureGhostBuilder.channels(meta, needs, size);
        } catch (final LinkageError e) {
            return null;
        }
    }

    /**
     * Draws a ghost with its controller on the block at (x, y, z), its front turned to {@code facing} (0 south, 1 west,
     * 2 north, 3 east): see-through, behind real blocks, and leaving out every block already built there. In world
     * space, the camera's offset already applied. Only its outside shows: see {@link #depth}.
     */
    public static void draw(final Ghost ghost, final int x, final int y, final int z, final int facing) {
        depth(ghost, x, y, z, facing);
        colour(ghost, x, y, z, facing);
    }

    /**
     * Several ghosts' first pass: their nearest faces into the depth buffer and no colour, so that {@link #colour}
     * then shows only the outside of each, not its insides through its walls, and a ghost in front hides one behind.
     * Draw every ghost's depth before any's colour. The depth stays, as a real block's would.
     */
    public static void depth(final Ghost ghost, final int x, final int y, final int z, final int facing) {
        try {
            StructureGhostBuilder.draw(ghost, x, y, z, facing, true);
        } catch (final LinkageError | RuntimeException e) {
            GtnhPlanner.LOG.warn("Could not draw a multiblock ghost", e);
        }
    }

    /** A ghost's second pass, after {@link #depth}: its outside, see-through. */
    public static void colour(final Ghost ghost, final int x, final int y, final int z, final int facing) {
        try {
            StructureGhostBuilder.draw(ghost, x, y, z, facing, false);
        } catch (final LinkageError | RuntimeException e) {
            GtnhPlanner.LOG.warn("Could not draw a multiblock ghost", e);
        }
    }
}

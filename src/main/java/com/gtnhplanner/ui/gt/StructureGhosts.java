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
 * is
 * placed, turned to face the way it was placed. Real blocks hide the ghost where they stand,
 * so a structure half built shows what is left to build.
 *
 * <p>
 * Safe to load without GregTech: building goes through {@link StructureGhostBuilder} only once
 * {@link MultiblockPictures#available()} said yes.
 */
public final class StructureGhosts {

    /**
     * A structure's ghost: the structure as built (kept by {@link StructureGhostBuilder}), how many blocks it has, and
     * how far it reaches from the controller, in blocks, each way (its front to the south).
     */
    public record Ghost(Object built, int blocks, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

        /**
         * The box the structure fills, turned to {@code facing}, from the controller block's corner: {x0, y0, z0, x1,
         * y1, z1}.
         */
        public double[] box(final int facing) {
            final int[] a = turn(minX, minZ, facing), b = turn(maxX, maxZ, facing);
            return new double[] { Math.min(a[0], b[0]), minY, Math.min(a[1], b[1]), Math.max(a[0], b[0]) + 1, maxY + 1,
                Math.max(a[1], b[1]) + 1 };
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

    private static final Map<Integer, Ghost> GHOSTS = new HashMap<>();
    /** Controllers that cannot be ghosted (not constructable, too big, or building threw): they keep their block. */
    private static final Set<Integer> NONE = new HashSet<>();
    /** At most one structure built a frame: building one can take tens of milliseconds. */
    private static long builtAt;

    private StructureGhosts() {}

    /** The ghost already built for a controller, or null; never builds. Safe off the render thread. */
    @Nullable
    public static Ghost peek(@Nullable final ItemStack controller) {
        final int meta = meta(controller);
        return meta < 0 ? null : GHOSTS.get(meta);
    }

    /**
     * The ghost for a controller, built the first time it is asked for (one a frame), or null: not a constructable
     * GregTech multiblock, not built yet this frame, or it cannot be built. Render thread only.
     */
    @Nullable
    public static Ghost get(@Nullable final ItemStack controller) {
        final int meta = meta(controller);
        if (meta < 0 || NONE.contains(meta)) return null;
        final Ghost known = GHOSTS.get(meta);
        if (known != null) return known;
        final long now = System.nanoTime();
        if (now - builtAt < 50_000_000L) return null;
        builtAt = now;
        Ghost made = null;
        try {
            made = StructureGhostBuilder.build(meta);
        } catch (final LinkageError | RuntimeException e) {
            GtnhPlanner.LOG.warn("Could not ghost multiblock {}", meta, e);
        }
        if (made == null) NONE.add(meta);
        else GHOSTS.put(meta, made);
        return made;
    }

    /**
     * How far above a picked spot a machine's controller goes so the structure's bottom stands on it: 0 for anything
     * without a ghost built yet.
     */
    public static int lift(@Nullable final ItemStack controller) {
        final Ghost g = peek(controller);
        return g == null ? 0 : -g.minY();
    }

    private static int meta(@Nullable final ItemStack controller) {
        if (controller == null || !MultiblockPictures.available()) return -1;
        try {
            return MultiblockRenderer.controllerMeta(controller);
        } catch (final LinkageError e) {
            return -1;
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

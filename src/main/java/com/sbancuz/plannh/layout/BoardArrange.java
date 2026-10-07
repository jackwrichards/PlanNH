package com.sbancuz.plannh.layout;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.sbancuz.plannh.data.flowchart.Edge;

/**
 * Arrange for the board, after Factory Flow's: the cards go into flow columns with {@link AutoLayout}, and a drawer
 * whose every wire meets one card becomes that card's SATELLITE. It stays out of the columns and is pinned to the
 * card's edge, supplies down its left side and products down its right, stacked in port order, so a card and its
 * drawers read as one block with every drawer lined up. The room they take is reserved beside the card, so nothing
 * else lands on them. Drawers shared between cards stay in the columns. Everything lands on the grid.
 */
public final class BoardArrange {

    /** Between a card and its satellites: room for a short wire and its arrow. */
    public static final int SATELLITE_GAP = 40;
    /** Between satellites stacked on one side. */
    public static final int STACK_GAP = 10;
    public static final int GRID = 20;

    /** A card or a drawer to place. {@code supplies}: a drawer that feeds cards (else one that takes from them). */
    public record Box(AutoLayout.LayoutNode node, boolean drawer, boolean supplies) {

        UUID id() {
            return node.id();
        }
    }

    private BoardArrange() {}

    /** World top-left for every box, relative to an arbitrary origin. */
    public static Map<UUID, int[]> arrange(final Collection<Box> boxes, final Collection<Edge> links) {
        final Map<UUID, Box> byId = new LinkedHashMap<>();
        for (final Box b : boxes) byId.put(b.id(), b);

        // Satellites: drawers whose wires all meet one card, by that card and side, in the order of its ports.
        final Map<UUID, UUID> hostOf = new HashMap<>();
        for (final Box b : boxes) {
            if (!b.drawer()) continue;
            final Set<UUID> ends = new HashSet<>();
            for (final Edge e : links) {
                if (e.sourceNodeId.equals(b.id())) ends.add(e.targetNodeId);
                if (e.targetNodeId.equals(b.id())) ends.add(e.sourceNodeId);
            }
            if (ends.size() != 1) continue;
            final UUID host = ends.iterator()
                .next();
            final Box other = byId.get(host);
            if (other != null && !other.drawer()) hostOf.put(b.id(), host);
        }
        final Map<UUID, List<Box>> left = new HashMap<>(), right = new HashMap<>();
        final Map<UUID, Integer> portOf = new HashMap<>();
        for (final Edge e : links) {
            if (hostOf.containsKey(e.sourceNodeId)) portOf.merge(e.sourceNodeId, e.targetInputIndex, Math::min);
            if (hostOf.containsKey(e.targetNodeId)) portOf.merge(e.targetNodeId, e.sourceOutputIndex, Math::min);
        }
        for (final Map.Entry<UUID, UUID> s : hostOf.entrySet()) {
            final Box b = byId.get(s.getKey());
            (b.supplies() ? left : right).computeIfAbsent(s.getValue(), k -> new ArrayList<>())
                .add(b);
        }
        final Comparator<Box> byPort = Comparator.comparingInt(b -> portOf.getOrDefault(b.id(), 0));
        left.values()
            .forEach(l -> l.sort(byPort));
        right.values()
            .forEach(l -> l.sort(byPort));

        // The columns: every box but the satellites, each card as tall as its stacks and padded by their width.
        final List<AutoLayout.LayoutNode> nodes = new ArrayList<>();
        final Map<UUID, int[]> margins = new HashMap<>();
        // A block: the card and its stacks, each centred on the block's middle.
        final Map<UUID, Integer> blockH = new HashMap<>();
        final Set<UUID> wired = new HashSet<>();
        for (final Edge e : links) {
            wired.add(e.sourceNodeId);
            wired.add(e.targetNodeId);
        }
        final List<Box> shelf = new ArrayList<>();
        for (final Box b : boxes) {
            if (hostOf.containsKey(b.id())) continue;
            if (!wired.contains(b.id())) {
                shelf.add(b);
                continue;
            }
            final List<Box> l = left.getOrDefault(b.id(), List.of()), r = right.getOrDefault(b.id(), List.of());
            final int h = b.node()
                .worldHeight();
            final int tall = Math.max(h, Math.max(stackHeight(l), stackHeight(r)));
            blockH.put(b.id(), tall);
            nodes.add(tall == h ? b.node() : new Taller(b.node(), tall, (tall - h) / 2));
            if (!l.isEmpty() || !r.isEmpty()) margins.put(b.id(), new int[] { reserve(l), reserve(r) });
        }
        final List<Edge> columnLinks = new ArrayList<>();
        for (final Edge e : links) {
            if (!hostOf.containsKey(e.sourceNodeId) && !hostOf.containsKey(e.targetNodeId)) columnLinks.add(e);
        }
        final Map<UUID, int[]> placed = nodes.isEmpty() ? new HashMap<>()
            : AutoLayout.layout(nodes, columnLinks, margins);

        // Each card in the middle of its block, its satellites down its sides, centred on it.
        for (final Map.Entry<UUID, Integer> block : blockH.entrySet()) {
            final int[] at = placed.get(block.getKey());
            if (at == null) continue;
            final Box card = byId.get(block.getKey());
            final int top = at[1], tall = block.getValue();
            at[1] = top + (tall - card.node()
                .worldHeight()) / 2;
            final List<Box> l = left.getOrDefault(block.getKey(), List.of());
            stack(placed, l, at[0] - SATELLITE_GAP, true, top + (tall - stackHeight(l)) / 2);
            final List<Box> r = right.getOrDefault(block.getKey(), List.of());
            stack(
                placed,
                r,
                at[0] + card.node()
                    .worldWidth() + SATELLITE_GAP,
                false,
                top + (tall - stackHeight(r)) / 2);
        }
        shelve(placed, shelf, byId);
        snap(placed, hostOf);
        return placed;
    }

    /** One side's satellites, top down from {@code y}; {@code edge} is the card side they hang off. */
    private static void stack(final Map<UUID, int[]> placed, final List<Box> side, final int edge, final boolean left,
        int y) {
        for (final Box b : side) {
            final int w = b.node()
                .worldWidth();
            placed.put(b.id(), new int[] { left ? edge - w : edge, y });
            y += b.node()
                .worldHeight() + STACK_GAP;
        }
    }

    /** Unwired cards and drawers, as the website keeps them: on a shelf below everything else, left to right. */
    private static void shelve(final Map<UUID, int[]> placed, final List<Box> shelf, final Map<UUID, Box> byId) {
        if (shelf.isEmpty()) return;
        int x0 = 0, y1 = 0;
        if (!placed.isEmpty()) {
            x0 = Integer.MAX_VALUE;
            y1 = Integer.MIN_VALUE;
            for (final Map.Entry<UUID, int[]> e : placed.entrySet()) {
                x0 = Math.min(x0, e.getValue()[0]);
                y1 = Math.max(
                    y1,
                    e.getValue()[1] + byId.get(e.getKey())
                        .node()
                        .worldHeight());
            }
            y1 += SHELF_GAP;
        }
        int x = x0;
        for (final Box b : shelf) {
            placed.put(b.id(), new int[] { x, y1 });
            x += b.node()
                .worldWidth() + STACK_GAP * 2;
        }
    }

    /** Between the plan and the shelf of unwired things below it. */
    public static final int SHELF_GAP = 80;

    /**
     * Every block on the grid, moved as one: a card and its satellites keep their spacing to each other, and the
     * blocks keep theirs.
     */
    private static void snap(final Map<UUID, int[]> placed, final Map<UUID, UUID> hostOf) {
        final Map<UUID, int[]> shift = new HashMap<>();
        for (final Map.Entry<UUID, int[]> e : placed.entrySet()) {
            if (hostOf.containsKey(e.getKey())) continue;
            final int[] p = e.getValue();
            final int dx = Math.round(p[0] / (float) GRID) * GRID - p[0],
                dy = Math.round(p[1] / (float) GRID) * GRID - p[1];
            shift.put(e.getKey(), new int[] { dx, dy });
        }
        for (final Map.Entry<UUID, int[]> e : placed.entrySet()) {
            final UUID host = hostOf.get(e.getKey());
            final int[] d = shift.get(host != null ? host : e.getKey());
            if (d == null) continue;
            e.getValue()[0] += d[0];
            e.getValue()[1] += d[1];
        }
    }

    private static int stackHeight(final List<Box> stack) {
        int h = 0;
        for (final Box b : stack) h += b.node()
            .worldHeight();
        return stack.isEmpty() ? 0 : h + (stack.size() - 1) * STACK_GAP;
    }

    /** The clearance to ask {@link AutoLayout} for beside a card: its widest satellite and the gap to it. */
    private static int reserve(final List<Box> stack) {
        int w = 0;
        for (final Box b : stack) w = Math.max(
            w,
            b.node()
                .worldWidth());
        // AutoLayout credits part of its column gap against what is asked; ask for that on top.
        return stack.isEmpty() ? 0 : w + SATELLITE_GAP + AutoLayout.MARGIN_CREDIT;
    }

    /**
     * A card made as tall as its satellite stacks, for the columns only; it sits {@code offset} down the block, so its
     * ports do too.
     */
    private record Taller(AutoLayout.LayoutNode node, int height, int offset) implements AutoLayout.LayoutNode {

        @Override
        public UUID id() {
            return node.id();
        }

        @Override
        public String machineName() {
            return node.machineName();
        }

        @Override
        public int worldWidth() {
            return node.worldWidth();
        }

        @Override
        public int worldHeight() {
            return height;
        }

        @Override
        public int inputCount() {
            return node.inputCount();
        }

        @Override
        public int outputCount() {
            return node.outputCount();
        }

        @Override
        public int portY(final boolean output, final int index) {
            return offset + node.portY(output, index);
        }
    }
}

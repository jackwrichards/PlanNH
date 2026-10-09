package com.gtnhplanner.bench;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.gtnhplanner.importer.FfHandle;
import com.gtnhplanner.importer.FfIds;
import com.gtnhplanner.importer.FfPlan;

/**
 * A board for the layout benchmark: its cards and drawers as boxes the size the game draws them, where they stand,
 * and its wires end to end. Built from a Factory Flow plan without the game (no NEI, no recipes looked up), so every
 * plan the importer reads can be measured headlessly.
 */
public record BenchBoard(String name, List<Item> items, List<Link> links) {

    /** A card or a drawer: the box, and for a card its input and output rows. */
    public record Item(UUID id, String label, boolean drawer, boolean supplies, int x, int y, int w, int h, int ins,
        int outs) {

        Item at(final int nx, final int ny) {
            return new Item(id, label, drawer, supplies, nx, ny, w, h, ins, outs);
        }
    }

    /** A wire from one box's port to another's. */
    public record Link(UUID id, UUID from, int fromPort, UUID to, int toPort, String resource) {}

    /** The game's card and drawer sizes (see CardLayout and DrawerCard). */
    public static final int CARD_W = 320, DRAWER_W = 136, DRAWER_H = 68, RAILS_Y = 38, ROW = 32, PICTURE = 88;

    /** A card's height as CardLayout works it out, with no pinned settings: its rails (power a row) and the strip. */
    public static int cardHeight(final int rows) {
        return Math.max(rows * ROW, PICTURE) + RAILS_Y + 8;
    }

    /** The same board with boxes moved. */
    public BenchBoard placed(final Map<UUID, int[]> at) {
        final List<Item> moved = new ArrayList<>(items.size());
        for (final Item i : items) {
            final int[] p = at.get(i.id());
            moved.add(p == null ? i : i.at(p[0], p[1]));
        }
        return new BenchBoard(name, moved, links);
    }

    public Map<UUID, Item> byId() {
        final Map<UUID, Item> m = new HashMap<>();
        for (final Item i : items) m.put(i.id(), i);
        return m;
    }

    private static UUID uuid(final String s) {
        return UUID.nameUUIDFromBytes(s.getBytes(StandardCharsets.UTF_8));
    }

    /** A plan's board as the website left it. */
    public static BenchBoard fromPlan(final String name, final FfPlan plan) {
        final Map<String, FfPlan.FfRecipe> recipes = plan.recipesById();
        final List<Item> items = new ArrayList<>();
        // Per card, each section's first input and output port: a shared machine's ports run on down the card.
        final Map<String, int[][]> sectionBase = new HashMap<>();
        final Map<String, List<FfPlan.FfRecipe>> sectionsOf = new HashMap<>();
        for (final FfPlan.FfNode n : plan.nodes()) {
            final List<FfPlan.FfRecipe> sections = new ArrayList<>();
            final FfPlan.FfRecipe first = recipes.get(n.recipeId());
            if (first == null) continue;
            sections.add(first);
            for (final FfPlan.FfSection s : n.extraRecipes()) {
                final FfPlan.FfRecipe r = recipes.get(s.recipeId());
                if (r != null) sections.add(r);
            }
            int ins = 0, outs = 0, rows = 0;
            final int[][] base = new int[sections.size()][];
            for (int s = 0; s < sections.size(); s++) {
                final FfPlan.FfRecipe r = sections.get(s);
                base[s] = new int[] { ins, outs };
                final int i = r.inputs()
                    .size(),
                    o = r.outputs()
                        .size();
                ins += i;
                outs += o;
                rows += Math.max(i + (r.eut() > 0 && !r.power() && sections.size() == 1 ? 1 : 0), o)
                    + (sections.size() > 1 ? 1 : 0);
            }
            sectionBase.put(n.id(), base);
            sectionsOf.put(n.id(), sections);
            items.add(
                new Item(
                    uuid(n.id()),
                    first.name(),
                    false,
                    false,
                    (int) n.x(),
                    (int) n.y(),
                    CARD_W,
                    cardHeight(rows),
                    ins,
                    outs));
        }
        final Set<String> feeding = new HashSet<>();
        for (final FfPlan.FfEdge e : plan.edges()) feeding.add(e.source());
        for (final FfPlan.FfStorage s : plan.storages()) {
            final boolean supplies = feeding.contains(s.id());
            items.add(
                new Item(
                    uuid(s.id()),
                    s.displayName() != null ? s.displayName() : s.resourceId(),
                    true,
                    supplies,
                    (int) s.x(),
                    (int) s.y(),
                    DRAWER_W,
                    DRAWER_H,
                    supplies ? 0 : 1,
                    supplies ? 1 : 0));
        }
        final Set<UUID> known = new HashSet<>();
        for (final Item i : items) known.add(i.id());
        final List<Link> links = new ArrayList<>();
        for (final FfPlan.FfEdge e : plan.edges()) {
            final UUID from = uuid(e.source()), to = uuid(e.target());
            if (!known.contains(from) || !known.contains(to)) continue;
            final int out = port(sectionsOf.get(e.source()), sectionBase.get(e.source()), e.sourceHandle(), true);
            final int in = port(sectionsOf.get(e.target()), sectionBase.get(e.target()), e.targetHandle(), false);
            links.add(new Link(uuid(e.id()), from, out, to, in, e.resourceKind() + ":" + e.resourceId()));
        }
        return new BenchBoard(name, items, links);
    }

    /** The port a handle names on a card (0 on a drawer, or when it cannot tell). */
    private static int port(final List<FfPlan.FfRecipe> sections, final int[][] base, final String handle,
        final boolean output) {
        if (sections == null) return 0;
        final FfHandle h = FfHandle.parse(handle);
        if (h == null) return 0;
        final int s = Math.max(0, Math.min(sections.size() - 1, h.section()));
        final List<FfPlan.FfSlot> slots = output ? sections.get(s)
            .outputs()
            : sections.get(s)
                .inputs();
        int index = h.slot() >= 0 && h.slot() < slots.size() ? h.slot() : 0;
        for (int i = 0; i < slots.size(); i++) {
            if (FfIds.same(
                slots.get(i)
                    .id(),
                h.resourceId())) {
                index = i;
                break;
            }
        }
        return base[s][output ? 1 : 0] + index;
    }
}

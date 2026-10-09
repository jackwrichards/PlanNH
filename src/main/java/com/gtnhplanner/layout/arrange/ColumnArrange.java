package com.gtnhplanner.layout.arrange;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.function.ToDoubleFunction;

/**
 * The arrange's column pass, after Factory Flow's board-arrange.ts. A plan is treated as almost a tree: flow runs left
 * to right, the TRUNK (the chain behind the biggest final product) runs through the middle, and every feeder chain
 * joining it is a SECTION kept as one contiguous band, so a wire's two ends usually share a band. Recycles stay beside
 * their own section, and a vertical pass lines ports up with the ports they feed, weighted by flow. A drawer whose
 * every
 * wire meets one machine rides it as a SATELLITE, pinned to its side at the port it serves (supplies left, products
 * right). Disconnected webs become separate islands, placed by the same engine as meta-cards; unwired cards go on a
 * shelf at the bottom. With the optimiser on, each island is then searched for a better layout ({@link Optimize}).
 *
 * <p>
 * Deterministic: the same cards and wires give the same layout, whatever order the board keeps them in beyond their
 * input order. Everything lands on the 20 px grid. One instance per arrange (it holds that arrange's spacing).
 */
public final class ColumnArrange {

    /** A column corridor grows one cell per this many wires crossing it, up to {@link #COLUMN_GAP_MAX}. */
    private static final int COLUMN_GAP_WIRES_PER_CELL = 3;
    private static final double COLUMN_GAP_MAX = Grid.cells(10);
    /** Island columns wrap to the next line past this width: plans read like text, not one endless ribbon. */
    private static final double ISLAND_ROW_MAX_WIDTH = Grid.cells(240);
    /** Air between parked, unwired cards on the shelf. */
    private static final double SHELF_GAP = Grid.cells(2);

    /** Where every card went, and the rectangle each island landed in (the shelf last), in board coordinates. */
    public record Result(Map<String, Point> positions, List<Island> islands) {}

    /** An island's rectangle; {@code backdrop} false for the shelf and an interchange buffer standing alone. */
    public record Island(double x, double y, double width, double height, boolean backdrop) {}

    // The spacing taste, in px, set per arrange (see taste); the island scale swaps them for a while.
    private double columnGapMin, rowGap, sectionGap, islandGap, satellitePad, satelliteStackGap;
    private boolean pageFold;
    private final boolean optimise;
    private final Prices prices;
    private final ToDoubleFunction<Map<String, Point>> air;
    private final ToDoubleFunction<Map<String, Point>> judge;
    private final BiConsumer<Integer, Integer> searchProgress;

    /**
     * @param spacing        "compact", "normal" or "roomy"
     * @param optimise       run the optimiser on each island (the challenger), else the plain column pass
     * @param prices         the router's prices (the proxy's and the judge's currency)
     * @param air            the air owed between strangers, or null
     * @param judge          the real router's points for a layout (cards absent from it stay where they are), or
     *                       null: the optimiser's proxy picks
     * @param searchProgress the optimiser's progress (done, total), or null
     */
    public ColumnArrange(final String spacing, final boolean optimise, final Prices prices,
        final ToDoubleFunction<Map<String, Point>> air, final ToDoubleFunction<Map<String, Point>> judge,
        final BiConsumer<Integer, Integer> searchProgress) {
        this.optimise = optimise;
        this.prices = prices == null ? Prices.DEFAULT : prices;
        this.air = air;
        this.judge = judge;
        this.searchProgress = searchProgress;
        taste(spacing);
        dials(this.prices);
    }

    /** Compact matches hand-drawn boards: rows a cell apart, columns two, drawers touching. */
    private void taste(final String spacing) {
        final boolean compact = "compact".equals(spacing), roomy = "roomy".equals(spacing);
        rowGap = Grid.cells(compact ? 1 : roomy ? 3 : 2);
        sectionGap = Grid.cells(compact ? 2 : roomy ? 6 : 4);
        columnGapMin = Grid.cells(compact ? 2 : roomy ? 5 : 3);
        // Wide enough that two islands' grounds plus both their two-cell collars fit between any pair with room left.
        islandGap = Grid.cells(compact ? 10 : roomy ? 16 : 12);
        satellitePad = Grid.cells(compact ? 1 : 2);
        satelliteStackGap = Grid.cells(compact ? 0 : 1);
    }

    /** The dials override the taste's spacing only when moved off their defaults. */
    private void dials(final Prices dials) {
        if (dials.arrangeRowGap() != Prices.DEFAULT.arrangeRowGap()) rowGap = Grid.cells(dials.arrangeRowGap());
        if (dials.arrangeColumnGap() != Prices.DEFAULT.arrangeColumnGap())
            columnGapMin = Grid.cells(dials.arrangeColumnGap());
        if (dials.arrangeDrawerGap() != Prices.DEFAULT.arrangeDrawerGap())
            satellitePad = Grid.cells(dials.arrangeDrawerGap());
    }

    /** The row gap in effect, in px (the free placement's least air). */
    public double rowGap() {
        return rowGap;
    }

    /** Runs the same engine at island scale: every gap becomes the island gap while {@code run} runs. */
    private <T> T atIslandScale(final Supplier<T> run) {
        final double savedRow = rowGap, savedSection = sectionGap, savedColumn = columnGapMin, savedPad = satellitePad,
            savedStack = satelliteStackGap;
        // A touch tighter than the outer island gap: islands should cluster, close enough to read as one factory
        // while every ground keeps room for a wire to walk around it.
        final double metaGap = Math.max(islandGap - Grid.cells(2), Grid.cells(8));
        rowGap = sectionGap = columnGapMin = satellitePad = satelliteStackGap = metaGap;
        pageFold = true;
        try {
            return run.get();
        } finally {
            rowGap = savedRow;
            sectionGap = savedSection;
            columnGapMin = savedColumn;
            satellitePad = savedPad;
            satelliteStackGap = savedStack;
            pageFold = false;
        }
    }

    // region The model

    /** A card being placed. */
    private static final class Slot {

        final ArrangeCard card;
        /** Its place in the input: the deterministic tiebreak everywhere. */
        final int index;
        int layer;
        /** Order within its column. */
        int seq;
        /** Its band; a boundary between bands adds air. */
        int section;
        /** On the main line: trunk-to-trunk wires get the straightest runs. */
        boolean trunk;
        double y;

        Slot(final ArrangeCard card, final int index) {
            this.card = card;
            this.index = index;
        }

        /** A stand-in at another height: a fixed point a card's port is drawn toward. */
        Slot at(final double y) {
            final Slot s = new Slot(card, index);
            s.layer = layer;
            s.seq = seq;
            s.section = section;
            s.trunk = trunk;
            s.y = y;
            return s;
        }
    }

    /** A wire between two slots, with its ports' heights below each card's top and its weight. */
    private static final class Link {

        final String id;
        final Slot from, to;
        final double fromAnchor, toAnchor, weight;
        final Double width;

        Link(final String id, final Slot from, final Slot to, final double fromAnchor, final double toAnchor,
            final double weight, final Double width) {
            this.id = id;
            this.from = from;
            this.to = to;
            this.fromAnchor = fromAnchor;
            this.toAnchor = toAnchor;
            this.weight = weight;
            this.width = width;
        }

        Link reversed() {
            return new Link(id, to, from, toAnchor, fromAnchor, weight, width);
        }
    }

    /** Where a satellite rides: which machine, which side, and its top below the machine's top (ports aligned). */
    private record Satellite(Slot anchor, boolean left, double offsetY) {}

    /** One laid-out block (an island), places relative to its own top-left. */
    private static final class Block {

        final List<String> ids = new ArrayList<>();
        final List<double[]> places = new ArrayList<>();
        double width, height;
        int size, minIndex;
        /** The parked-strays shelf: always the bottom row. */
        boolean shelf;
        /** No ground wanted: an interchange buffer standing between islands. */
        boolean plain;
    }

    private static final class IslandGroup {

        List<Slot> members;
        List<Link> links;
        final Map<Slot, Satellite> satellites;
        final boolean interchange;

        IslandGroup(final List<Slot> members, final List<Link> links, final Map<Slot, Satellite> satellites,
            final boolean interchange) {
            this.members = members;
            this.links = links;
            this.satellites = satellites;
            this.interchange = interchange;
        }
    }

    /** A pull toward a bridge's far end: a height in the island, how hard, and the port it pulls. */
    private record Pull(double y, double weight, double anchor) {}

    /** A wire between two islands, still holding the card-level link behind it. */
    private record Bridge(int from, int to, double weight, Link link) {}

    private record Partner(Slot other, double own, double their, double weight) {}

    private record Wish(double wish, double weight) {}

    // endregion

    /** JavaScript's {@code a - b || ...}: the sign of a difference, a zero (or NaN) a tie. */
    private static int by(final double d) {
        return d > 0 ? 1 : d < 0 ? -1 : 0;
    }

    private static <K, V> void push(final Map<K, List<V>> map, final K key, final V value) {
        map.computeIfAbsent(key, k -> new ArrayList<>())
            .add(value);
    }

    /**
     * Lays the cards out. {@code origin} is where the arranged layout's top-left lands (null: where the cards' top-left
     * stands today, so a tidied plan stays in its own neighbourhood).
     */
    public Result arrange(final List<ArrangeCard> cards, final List<ArrangeWire> wires, final Point origin) {
        if (cards.isEmpty()) return new Result(new LinkedHashMap<>(), List.of());
        final Map<String, Slot> slotById = new LinkedHashMap<>();
        for (int i = 0; i < cards.size(); i++) slotById.put(
            cards.get(i)
                .id(),
            new Slot(cards.get(i), i));

        // Wires with a missing end or both ends on one card place nothing.
        final List<Link> links = new ArrayList<>();
        for (final ArrangeWire wire : wires) {
            final Slot from = slotById.get(wire.source()), to = slotById.get(wire.target());
            if (from == null || to == null || from == to) continue;
            links.add(
                new Link(
                    wire.id(),
                    from,
                    to,
                    wire.sourcePortY() != null ? wire.sourcePortY() : from.card.height() / 2,
                    wire.targetPortY() != null ? wire.targetPortY() : to.card.height() / 2,
                    Math.max(wire.weight() != null ? wire.weight() : 1, 0.01),
                    wire.width()));
        }

        // Drawers that serve exactly one machine leave the graph here and come back at the very end, pinned against
        // that machine's side. The layout then only has to solve the machines and the true multi-way buffers.
        final Map<Slot, Satellite> satellitePlans = planSatellites(slotById, links);
        final List<Link> mainLinks = new ArrayList<>();
        for (final Link link : links) {
            if (!satellitePlans.containsKey(link.from) && !satellitePlans.containsKey(link.to)) mainLinks.add(link);
        }
        final Set<Slot> anchors = new HashSet<>();
        for (final Satellite plan : satellitePlans.values()) anchors.add(plan.anchor());

        // Islands: weakly connected components of the wire graph.
        final int[] parent = unionComponents(cards.size(), mainLinks);
        final Map<Integer, List<Slot>> componentSlots = new LinkedHashMap<>();
        for (final Slot slot : slotById.values()) {
            if (satellitePlans.containsKey(slot)) continue;
            push(componentSlots, find(parent, slot.index), slot);
        }

        final List<Slot> parked = new ArrayList<>();
        final List<IslandGroup> islandGroups = new ArrayList<>();
        for (final List<Slot> members : componentSlots.values()) {
            // A card with no wires parks on the shelf, unless satellites ride on it: then it is a one-card island
            // that keeps its drawers.
            if (members.size() == 1 && !touches(mainLinks, members.get(0)) && !anchors.contains(members.get(0))) {
                parked.add(members.get(0));
                continue;
            }
            members.sort((a, b) -> a.index - b.index);
            final Set<Slot> group = new HashSet<>(members);
            final List<Link> groupLinks = new ArrayList<>();
            for (final Link link : mainLinks)
                if (group.contains(link.from) && group.contains(link.to)) groupLinks.add(link);
            final Map<Slot, Satellite> groupSatellites = new LinkedHashMap<>();
            for (final Map.Entry<Slot, Satellite> e : satellitePlans.entrySet()) {
                if (group.contains(
                    e.getValue()
                        .anchor()))
                    groupSatellites.put(e.getKey(), e.getValue());
            }
            islandGroups.add(new IslandGroup(new ArrayList<>(members), groupLinks, groupSatellites, false));
        }
        // A storage that two or more OTHER islands trade through steps out to stand alone as an interchange island,
        // which the island graph places between its users. A plain pass-through between two islands stays put.
        {
            final Map<Slot, Integer> prelim = new HashMap<>();
            for (int g = 0; g < islandGroups.size(); g++)
                for (final Slot slot : islandGroups.get(g).members) prelim.put(slot, g);
            for (final IslandGroup group : new ArrayList<>(islandGroups)) {
                for (final Slot slot : new ArrayList<>(group.members)) {
                    if (group.members.size() < 2 || !slot.card.storage()) continue;
                    final Integer ownGroup = prelim.get(slot);
                    final Set<Integer> otherGroups = new HashSet<>();
                    for (final Link link : mainLinks) {
                        final Slot other = link.from == slot ? link.to : link.to == slot ? link.from : null;
                        if (other == null) continue;
                        final Integer otherGroup = prelim.get(other);
                        if (otherGroup != null && !otherGroup.equals(ownGroup)) otherGroups.add(otherGroup);
                    }
                    if (otherGroups.size() >= 2) {
                        group.members.remove(slot);
                        group.links.removeIf(link -> link.from == slot || link.to == slot);
                        final Map<Slot, Satellite> own = new LinkedHashMap<>();
                        group.satellites.entrySet()
                            .removeIf(e -> {
                                if (e.getValue()
                                    .anchor() != slot) return false;
                                own.put(e.getKey(), e.getValue());
                                return true;
                            });
                        islandGroups.add(new IslandGroup(new ArrayList<>(List.of(slot)), new ArrayList<>(), own, true));
                        prelim.put(slot, islandGroups.size() - 1);
                    }
                }
            }
        }
        islandGroups.sort((a, b) -> {
            final int size = b.members.size() + b.satellites.size() - (a.members.size() + a.satellites.size());
            return size != 0 ? size : a.members.get(0).index - b.members.get(0).index;
        });

        // The island flow before any island is laid out: which island stands upstream of which. Each island's own
        // layout then knows which side its bridge cards should lean toward, so a bridge leaves the facing edge.
        final Map<String, Integer> groupOfCard = new HashMap<>();
        for (int g = 0; g < islandGroups.size(); g++) {
            for (final Slot slot : islandGroups.get(g).members) groupOfCard.put(slot.card.id(), g);
            for (final Slot sat : islandGroups.get(g).satellites.keySet()) groupOfCard.put(sat.card.id(), g);
        }
        final List<Bridge> bridges = new ArrayList<>();
        for (final Link link : mainLinks) {
            final Integer fromGroup = groupOfCard.get(link.from.card.id()),
                toGroup = groupOfCard.get(link.to.card.id());
            if (fromGroup == null || toGroup == null || fromGroup.equals(toGroup)) continue;
            bridges.add(new Bridge(fromGroup, toGroup, link.weight, link));
        }
        final List<Integer> indices = new ArrayList<>();
        for (int g = 0; g < islandGroups.size(); g++) indices.add(g);
        final Map<Integer, Integer> islandLayer = islandFlowLayers(indices, bridges);
        final List<Map<String, Double>> exitsByGroup = new ArrayList<>();
        for (int g = 0; g < islandGroups.size(); g++) exitsByGroup.add(new HashMap<>());
        for (final Bridge bridge : bridges) {
            final int direction = Integer
                .signum(islandLayer.getOrDefault(bridge.to(), 0) - islandLayer.getOrDefault(bridge.from(), 0));
            if (direction == 0) continue;
            exitsByGroup.get(bridge.from())
                .merge(bridge.link().from.card.id(), direction * bridge.weight(), Double::sum);
            exitsByGroup.get(bridge.to())
                .merge(bridge.link().to.card.id(), -direction * bridge.weight(), Double::sum);
        }

        final List<Block> blocks = layoutAll(islandGroups, exitsByGroup, null, links, parked);
        Map<String, double[]> local = assemble(blocks);
        List<double[]> offsets = placeIslands(blocks, bridges, local);

        // Second pass: with every island placed, each bridge's far end is a known point, so every island lays itself
        // out again with its exit cards pulled toward their partners, and the islands are placed again.
        List<Block> finalBlocks = blocks;
        if (!bridges.isEmpty()) {
            final List<Map<String, List<Pull>>> pulls = new ArrayList<>();
            for (int g = 0; g < islandGroups.size(); g++) pulls.add(new LinkedHashMap<>());
            for (final Bridge bridge : bridges) {
                final double[] fromLocal = local.get(bridge.link().from.card.id()),
                    toLocal = local.get(bridge.link().to.card.id());
                // Heavy on purpose: the exit card goes to the partner's level even when its own island would rather
                // keep it.
                final double weight = bridge.weight() * 5;
                push(
                    pulls.get(bridge.from()),
                    bridge.link().from.card.id(),
                    new Pull(
                        offsets.get(bridge.to())[1] + toLocal[1]
                            + bridge.link().toAnchor
                            - offsets.get(bridge.from())[1],
                        weight,
                        bridge.link().fromAnchor));
                push(
                    pulls.get(bridge.to()),
                    bridge.link().to.card.id(),
                    new Pull(
                        offsets.get(bridge.from())[1] + fromLocal[1]
                            + bridge.link().fromAnchor
                            - offsets.get(bridge.to())[1],
                        weight,
                        bridge.link().toAnchor));
            }
            finalBlocks = layoutAll(islandGroups, exitsByGroup, pulls, links, parked);
            local = assemble(finalBlocks);
            offsets = placeIslands(finalBlocks, bridges, local);
        }

        final Point start = origin != null ? origin : topLeft(cards);
        final double originX = Grid.snap(start.x()), originY = Grid.snap(start.y());
        final Map<String, Point> positions = new LinkedHashMap<>();
        final List<Island> islands = new ArrayList<>();
        for (int b = 0; b < finalBlocks.size(); b++) {
            final Block block = finalBlocks.get(b);
            final double blockX = Grid.snap(originX + offsets.get(b)[0]),
                blockY = Grid.snap(originY + offsets.get(b)[1]);
            for (int i = 0; i < block.ids.size(); i++) {
                positions
                    .put(block.ids.get(i), new Point(blockX + block.places.get(i)[0], blockY + block.places.get(i)[1]));
            }
            islands.add(new Island(blockX, blockY, block.width, block.height, !block.shelf && !block.plain));
        }
        return new Result(positions, islands);
    }

    private List<Block> layoutAll(final List<IslandGroup> groups, final List<Map<String, Double>> exits,
        final List<Map<String, List<Pull>>> pulls, final List<Link> allLinks, final List<Slot> parked) {
        final List<Block> built = new ArrayList<>();
        for (int g = 0; g < groups.size(); g++) {
            final IslandGroup group = groups.get(g);
            final Map<String, List<Pull>> pull = pulls == null ? null : pulls.get(g);
            final Block block = layoutIsland(
                group.members,
                group.links,
                group.satellites,
                exits.get(g),
                pull != null && !pull.isEmpty() ? pull : null,
                allLinks);
            if (group.interchange) block.plain = true;
            built.add(block);
        }
        if (!parked.isEmpty()) built.add(layoutShelf(parked, built.isEmpty() ? 0 : built.get(0).width));
        return built;
    }

    private static Map<String, double[]> assemble(final List<Block> blocks) {
        final Map<String, double[]> local = new HashMap<>();
        for (final Block block : blocks)
            for (int i = 0; i < block.ids.size(); i++) local.put(block.ids.get(i), block.places.get(i));
        return local;
    }

    private static Point topLeft(final List<ArrangeCard> cards) {
        double x = Double.POSITIVE_INFINITY, y = Double.POSITIVE_INFINITY;
        for (final ArrangeCard card : cards) {
            x = Math.min(x, card.x());
            y = Math.min(y, card.y());
        }
        return new Point(x, y);
    }

    private static boolean touches(final List<Link> links, final Slot slot) {
        for (final Link l : links) if (l.from == slot || l.to == slot) return true;
        return false;
    }

    /** Union-find over card indexes, unioned along the wires: each index's parent. */
    private static int[] unionComponents(final int count, final List<Link> links) {
        final int[] parent = new int[count];
        for (int i = 0; i < count; i++) parent[i] = i;
        for (final Link link : links) {
            final int a = find(parent, link.from.index), b = find(parent, link.to.index);
            if (a != b) parent[Math.max(a, b)] = Math.min(a, b);
        }
        return parent;
    }

    private static int find(final int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    /**
     * Which storages ride as satellites: a drawer whose every wire meets one other card pins to that card (supply
     * left, catch right) at the port row it serves. Two storages that would pin to each other both stay ordinary
     * cards: a satellite cannot anchor on another satellite.
     */
    private static Map<Slot, Satellite> planSatellites(final Map<String, Slot> slotById, final List<Link> links) {
        final Map<Slot, List<Link>> bySlot = new HashMap<>();
        for (final Link link : links) {
            push(bySlot, link.from, link);
            push(bySlot, link.to, link);
        }
        final Map<Slot, Satellite> plans = new LinkedHashMap<>();
        for (final Slot slot : slotById.values()) {
            if (!slot.card.storage()) continue;
            final List<Link> mine = bySlot.getOrDefault(slot, List.of());
            if (mine.isEmpty()) continue;
            final Set<Slot> partners = new LinkedHashSet<>();
            for (final Link l : mine) partners.add(l.from == slot ? l.to : l.from);
            if (partners.size() != 1) continue;
            final Slot anchor = partners.iterator()
                .next();
            boolean feeds = false, fed = false;
            for (final Link l : mine) {
                if (l.from == slot) feeds = true;
                if (l.to == slot) fed = true;
            }
            // Ports aligned: the satellite's port row meets the machine's port row.
            double sum = 0, weight = 0;
            for (final Link l : mine) {
                final double anchorPort = l.from == slot ? l.toAnchor : l.fromAnchor;
                final double ownPort = l.from == slot ? l.fromAnchor : l.toAnchor;
                sum += (anchorPort - ownPort) * l.weight;
                weight += l.weight;
            }
            plans.put(slot, new Satellite(anchor, feeds && !fed, sum / weight));
        }
        for (final Map.Entry<Slot, Satellite> e : new ArrayList<>(plans.entrySet())) {
            if (plans.containsKey(
                e.getValue()
                    .anchor())) {
                plans.remove(e.getKey());
                plans.remove(
                    e.getValue()
                        .anchor());
            }
        }
        return plans;
    }

    /** The wires of every two-card loop (A feeds B, B feeds A): such pairs stack in one column. */
    private static Set<Link> twoCycleLinks(final List<Link> links) {
        final Set<Long> directions = new HashSet<>();
        for (final Link link : links) directions.add((long) link.from.index << 32 | link.to.index);
        final Set<Link> pairs = new LinkedHashSet<>();
        for (final Link link : links)
            if (directions.contains((long) link.to.index << 32 | link.from.index)) pairs.add(link);
        return pairs;
    }

    // region One island: trunk, sections, columns

    private Block layoutIsland(final List<Slot> members, final List<Link> links, final Map<Slot, Satellite> satellites,
        final Map<String, Double> exits, final Map<String, List<Pull>> pulls, final List<Link> allLinks) {
        // The layout may run twice over the same slots; everything derived is recomputed, so clear the one flag that
        // only ever gets set.
        for (final Slot slot : members) slot.trunk = false;
        // Bridge pulls become phantom partners: a fixed point the exit card's port is drawn toward, so a wire leaving
        // for another island exits from the corner facing it instead of crossing its own island.
        Map<Slot, List<Partner>> extra = null;
        if (pulls != null && !pulls.isEmpty()) {
            extra = new LinkedHashMap<>();
            for (final Slot slot : members) {
                final List<Pull> entries = pulls.get(slot.card.id());
                if (entries == null) continue;
                final List<Partner> list = new ArrayList<>();
                for (final Pull pull : entries)
                    list.add(new Partner(slot.at(pull.y()), pull.anchor(), 0, pull.weight()));
                extra.put(slot, list);
            }
        }
        // Cycles: ranking needs an acyclic graph, so a DFS in input order marks the wires that close each loop; they
        // still pull their ends together vertically later, they just do not constrain columns. Two-card loops are
        // lifted out first and stack in one column.
        final Set<Link> pairs = twoCycleLinks(links);
        final List<Link> forward = splitCoFeeders(breakCycles(members, links, pairs));
        assignLayers(members, forward, pairs, exits);

        // The fold: a big recycle ring flattened into one line leaves its closure wire spanning the whole board, so
        // its far half folds back over the top (out along the bottom deck, back along the top).
        final Set<Slot> topDeck = new HashSet<>(), pinned = new HashSet<>();
        foldBigCycles(members, links, pairs, topDeck, pinned);
        slideTowardWires(members, forward, pinned, exits);
        pullPairsTogether(pairs, forward);
        relaxSharedStorages(members, links, pinned);
        packLayers(members);

        // The page fold (island scale only): a run of columns wider than the page folds back like text lines.
        final Map<Slot, Integer> bandOf = pageFold ? pageFold(members) : new HashMap<>();

        buildBands(members, links, forward);
        if (!topDeck.isEmpty()) {
            // The return deck rides above the line it feeds: its cards keep their order but outrank every bottom-deck
            // seq, and each connected run of deck cards is its own band.
            final int shift = members.size() * 4;
            final Map<Slot, Integer> deckChunk = new HashMap<>();
            int nextChunk = 0;
            for (final Slot slot : members) {
                if (!topDeck.contains(slot) || deckChunk.containsKey(slot)) continue;
                deckChunk.put(slot, nextChunk);
                final List<Slot> queue = new ArrayList<>(List.of(slot));
                for (int head = 0; head < queue.size(); head++) {
                    for (final Link link : links) {
                        final Slot other = link.from == queue.get(head) ? link.to
                            : link.to == queue.get(head) ? link.from : null;
                        if (other != null && topDeck.contains(other) && !deckChunk.containsKey(other)) {
                            deckChunk.put(other, nextChunk);
                            queue.add(other);
                        }
                    }
                }
                nextChunk++;
            }
            for (final Slot slot : members) {
                if (!topDeck.contains(slot)) continue;
                slot.seq -= shift;
                slot.section = -1 - deckChunk.getOrDefault(slot, 0);
            }
        }
        // A pass-through buffer is its own little band: free to sit wherever its two partners pull it, squarely between
        // them, never glued to whichever card the spanning tree happened to hang it on.
        {
            final Map<Slot, Integer> degree = new HashMap<>();
            for (final Link link : links) {
                degree.merge(link.from, 1, Integer::sum);
                degree.merge(link.to, 1, Integer::sum);
            }
            for (int i = 0; i < members.size(); i++) {
                final Slot slot = members.get(i);
                if (slot.card.storage() && degree.getOrDefault(slot, 0) == 2 && !topDeck.contains(slot))
                    slot.section = -1000 - i;
            }
        }
        // Deal the page-fold bands out as stacked rows, each keeping its own seq range and section namespace.
        if (!bandOf.isEmpty()) {
            final int stride = Math.max(members.size(), 1) * 8;
            for (final Slot slot : members) {
                final int band = bandOf.getOrDefault(slot, 0);
                slot.seq += band * stride;
                slot.section += band * 1000003;
            }
        }

        // A provisional vertical pass, then the anti-crossing polish: cards reorder within their column and section to
        // follow their wires' pull, then the column settles again. Then neighbour pairs whose wires cross are flipped.
        placeRows(collectLayers(members), links, extra);
        for (int pass = 0; pass < 3; pass++) {
            polishColumnOrder(collectLayers(members), links, extra);
            placeRows(collectLayers(members), links, extra);
        }
        for (int pass = 0; pass < 3; pass++) {
            if (!transposeToUncross(collectLayers(members), links)) break;
            placeRows(collectLayers(members), links, extra);
        }
        straightenRows(collectLayers(members), links, extra);
        final List<List<Slot>> layers = collectLayers(members);

        // Columns: each as wide as its widest card; the corridor between two grows with the wires that must cross it,
        // and widens where satellites ride (supplies on the left, products on the right).
        final int count = layers.size();
        final double[] columnWidth = new double[count];
        for (int i = 0; i < count; i++)
            for (final Slot slot : layers.get(i)) columnWidth[i] = Math.max(columnWidth[i], slot.card.width());
        final int[] crossings = new int[Math.max(count - 1, 0)];
        for (final Link link : links) {
            final int lo = Math.min(link.from.layer, link.to.layer), hi = Math.max(link.from.layer, link.to.layer);
            for (int i = lo; i < hi; i++) crossings[i]++;
        }
        final double[] leftPad = new double[count], rightPad = new double[count];
        for (final Map.Entry<Slot, Satellite> e : satellites.entrySet()) {
            final double need = e.getKey().card.width() + satellitePad;
            final int layer = e.getValue()
                .anchor().layer;
            if (e.getValue()
                .left()) leftPad[layer] = Math.max(leftPad[layer], need);
            else rightPad[layer] = Math.max(rightPad[layer], need);
        }
        final double[] columnX = new double[count];
        double x = 0;
        for (int i = 0; i < count; i++) {
            x += leftPad[i];
            columnX[i] = x;
            x += columnWidth[i] + rightPad[i];
            if (i < count - 1) {
                final double gap = columnGapMin
                    + Grid.cells(Math.floor(crossings[i] / (double) COLUMN_GAP_WIRES_PER_CELL));
                x += Math.min(gap, COLUMN_GAP_MAX);
            }
        }

        // Normalise to the island's own top-left, each card centred in its column and snapped.
        double top = Double.POSITIVE_INFINITY;
        for (final Slot slot : members) top = Math.min(top, slot.y);
        final Block block = new Block();
        final Map<Slot, double[]> placeBySlot = new HashMap<>();
        for (final Slot slot : members) {
            final double[] place = { Grid.snap(columnX[slot.layer] + (columnWidth[slot.layer] - slot.card.width()) / 2),
                Grid.snap(slot.y - top) };
            block.ids.add(slot.card.id());
            block.places.add(place);
            placeBySlot.put(slot, place);
        }

        // Satellites, pinned to their machine's side at the port row they serve, stacked apart when several share it.
        final Map<String, Slot> slotIndex = new HashMap<>();
        for (final Slot slot : members) slotIndex.put(slot.card.id(), slot);
        for (final Slot sat : satellites.keySet()) slotIndex.put(sat.card.id(), sat);
        final Map<String, List<Map.Entry<Slot, Satellite>>> satGroups = new LinkedHashMap<>();
        for (final Map.Entry<Slot, Satellite> e : satellites.entrySet()) push(
            satGroups,
            e.getValue()
                .anchor().index + ":"
                + (e.getValue()
                    .left() ? "left" : "right"),
            e);
        for (final List<Map.Entry<Slot, Satellite>> group : satGroups.values()) {
            group.sort((a, b) -> {
                final int c = by(
                    a.getValue()
                        .offsetY()
                        - b.getValue()
                            .offsetY());
                return c != 0 ? c : a.getKey().index - b.getKey().index;
            });
            double previousBottom = Double.NEGATIVE_INFINITY;
            for (final Map.Entry<Slot, Satellite> e : group) {
                final Slot sat = e.getKey();
                final Satellite plan = e.getValue();
                final double[] anchorPlace = placeBySlot.get(plan.anchor());
                final double[] place = {
                    Grid.snap(
                        plan.left() ? anchorPlace[0] - satellitePad - sat.card.width()
                            : anchorPlace[0] + plan.anchor().card.width() + satellitePad),
                    Grid.snap(Math.max(anchorPlace[1] + plan.offsetY(), previousBottom + satelliteStackGap)) };
                previousBottom = place[1] + sat.card.height();
                block.ids.add(sat.card.id());
                block.places.add(place);
            }
        }

        // The optimiser starts from the column layout and moves cards to lower a router-shaped score. Every wire
        // inside the island counts, satellite wires included; satellites keep their side.
        if (optimise && allLinks != null && block.ids.size() >= 2)
            optimiseIsland(block, slotIndex, satellites, allLinks);

        // Satellites can poke past the island's top or left edge: pull the block back to its own origin, then measure.
        double minX = 0, minY = 0;
        for (final double[] place : block.places) {
            minX = Math.min(minX, place[0]);
            minY = Math.min(minY, place[1]);
        }
        for (final double[] place : block.places) {
            place[0] -= minX;
            place[1] -= minY;
        }
        for (int i = 0; i < block.ids.size(); i++) {
            final Slot slot = slotIndex.get(block.ids.get(i));
            block.width = Math.max(block.width, block.places.get(i)[0] + slot.card.width());
            block.height = Math.max(block.height, block.places.get(i)[1] + slot.card.height());
        }
        block.size = members.size() + satellites.size();
        block.minIndex = members.get(0).index;
        return block;
    }

    /** Runs the optimiser on a laid-out island and takes its places. */
    private void optimiseIsland(final Block block, final Map<String, Slot> slotIndex,
        final Map<Slot, Satellite> satellites, final List<Link> allLinks) {
        final Set<String> idSet = new HashSet<>(block.ids);
        final List<Optimize.Card> cards = new ArrayList<>();
        for (final String id : block.ids) {
            final Slot slot = slotIndex.get(id);
            final Satellite sat = satellites.get(slot);
            final Slot anchor = sat == null ? null : sat.anchor();
            cards.add(
                new Optimize.Card(
                    id,
                    slot.card.width(),
                    slot.card.height(),
                    slot.card.storage(),
                    anchor != null ? anchor.layer : slot.layer,
                    anchor != null ? anchor.seq : slot.y,
                    anchor != null ? anchor.section : slot.section,
                    sat == null ? null : new Optimize.Satellite(anchor.card.id(), sat.left())));
        }
        final List<ArrangeWire> wires = new ArrayList<>();
        for (final Link link : allLinks) {
            if (!idSet.contains(link.from.card.id()) || !idSet.contains(link.to.card.id())) continue;
            wires.add(
                new ArrangeWire(link.id, link.from.card.id(), link.to.card.id(), null, null, link.weight, link.width));
        }
        if (wires.isEmpty()) return;
        // The judge sees the whole board; the island's finalists are laid where the island stands today (its cards'
        // top-left) so the rest of the board keeps its distance from them.
        double originX = Double.POSITIVE_INFINITY, originY = Double.POSITIVE_INFINITY;
        for (final String id : block.ids) {
            final Slot slot = slotIndex.get(id);
            originX = Math.min(originX, slot.card.x());
            originY = Math.min(originY, slot.card.y());
        }
        final double ox = originX, oy = originY;
        final ToDoubleFunction<Map<String, Point>> islandJudge = judge == null ? null : positions -> {
            final Map<String, Point> placed = new LinkedHashMap<>();
            for (final Map.Entry<String, Point> e : positions.entrySet()) placed.put(
                e.getKey(),
                new Point(
                    e.getValue()
                        .x() + ox,
                    e.getValue()
                        .y() + oy));
            return judge.applyAsDouble(placed);
        };
        final Optimize.Result optimized = Optimize.optimizeIslandLayout(
            cards,
            wires,
            new Optimize.Options(
                (int) Math.round(rowGap / Grid.CELL),
                (int) Math.round(sectionGap / Grid.CELL),
                (int) Math.round(columnGapMin / Grid.CELL),
                (int) Math.round(satellitePad / Grid.CELL),
                null,
                islandJudge,
                prices,
                air,
                searchProgress));
        for (int i = 0; i < optimized.positions()
            .size(); i++) {
            block.places.get(i)[0] = optimized.positions()
                .get(i)
                .x();
            block.places.get(i)[1] = optimized.positions()
                .get(i)
                .y();
        }
    }

    /**
     * The serpentine: columns wider than the page fold back like text lines. Contiguous groups of columns become
     * bands, odd bands reading right to left; layers are remapped within the band. Returns each card's band, empty
     * when there is no fold.
     */
    private Map<Slot, Integer> pageFold(final List<Slot> members) {
        final Map<Slot, Integer> bandOf = new HashMap<>();
        int layerCount = 0;
        for (final Slot slot : members) layerCount = Math.max(layerCount, slot.layer + 1);
        if (layerCount < 2) return bandOf;
        final double[] columnWidth = new double[layerCount];
        for (final Slot slot : members) columnWidth[slot.layer] = Math.max(columnWidth[slot.layer], slot.card.width());
        double total = -columnGapMin;
        for (final double width : columnWidth) total += width + columnGapMin;
        if (total <= ISLAND_ROW_MAX_WIDTH) return bandOf;
        final int[] bandOfLayer = new int[layerCount], positionInBand = new int[layerCount];
        final List<Integer> bandLength = new ArrayList<>();
        double x = 0;
        int band = 0, position = 0;
        for (int layer = 0; layer < layerCount; layer++) {
            if (x > 0 && x + columnWidth[layer] > ISLAND_ROW_MAX_WIDTH) {
                band++;
                x = 0;
                position = 0;
            }
            bandOfLayer[layer] = band;
            positionInBand[layer] = position;
            while (bandLength.size() <= band) bandLength.add(0);
            bandLength.set(band, position + 1);
            x += columnWidth[layer] + columnGapMin;
            position++;
        }
        if (band == 0) return bandOf;
        for (final Slot slot : members) {
            final int b = bandOfLayer[slot.layer], p = positionInBand[slot.layer];
            slot.layer = b % 2 == 0 ? p : bandLength.get(b) - 1 - p;
            bandOf.put(slot, b);
        }
        return bandOf;
    }

    /** Marks the links that close a cycle (a DFS in input order) and returns the rest: the forward links, a DAG. */
    private static List<Link> breakCycles(final List<Slot> members, final List<Link> links, final Set<Link> pairs) {
        final Map<Slot, List<Link>> outgoing = new HashMap<>();
        for (final Link link : links) if (!pairs.contains(link)) push(outgoing, link.from, link);
        final Map<Slot, Integer> state = new HashMap<>();
        final Set<Link> reversed = new HashSet<>();
        for (final Slot start : members) {
            if (state.containsKey(start)) continue;
            // Iterative: a recursive DFS blows the stack on thousand-card boards.
            final List<Object[]> stack = new ArrayList<>();
            stack.add(new Object[] { start, 0 });
            state.put(start, 1);
            while (!stack.isEmpty()) {
                final Object[] frame = stack.get(stack.size() - 1);
                final Slot slot = (Slot) frame[0];
                final List<Link> list = outgoing.getOrDefault(slot, List.of());
                final int next = (Integer) frame[1];
                if (next >= list.size()) {
                    state.put(slot, 2);
                    stack.remove(stack.size() - 1);
                    continue;
                }
                final Link link = list.get(next);
                frame[1] = next + 1;
                final Integer seen = state.get(link.to);
                if (seen != null && seen == 1) reversed.add(link);
                else if (seen == null) {
                    state.put(link.to, 1);
                    stack.add(new Object[] { link.to, 0 });
                }
            }
        }
        final List<Link> forward = new ArrayList<>();
        for (final Link link : links) if (!pairs.contains(link) && !reversed.contains(link)) forward.add(link);
        return forward;
    }

    /**
     * Ranks every card into a column: longest path from the sources, then sweeps that slide cards toward whichever side
     * holds more of their wire weight (so a lone raw source sits beside its consumer, not in column zero).
     */
    private static void assignLayers(final List<Slot> members, final List<Link> forward, final Set<Link> pairs,
        final Map<String, Double> exits) {
        final Map<Slot, List<Link>> incoming = new HashMap<>(), outgoing = new LinkedHashMap<>();
        for (final Link link : forward) {
            push(outgoing, link.from, link);
            push(incoming, link.to, link);
        }
        for (final Slot slot : topologicalOrder(members, outgoing)) {
            int layer = 0;
            for (final Link link : incoming.getOrDefault(slot, List.of())) layer = Math.max(layer, link.from.layer + 1);
            slot.layer = layer;
        }
        slideTowardWires(members, forward, null, exits);
        pullPairsTogether(pairs, forward);
        packLayers(members);
    }

    /**
     * Two machines that feed the same drawer stand on opposite sides of it: ranked left to right alone, both would
     * stack in one column and their wires into the shared drawers would cross wholesale. So for each pair of
     * co-feeders (or co-consumers) with no forward path between them, one machine's drawer wires are reversed for the
     * ranking only, putting it past the drawers; the one with more weight in its other rightward wires keeps the left.
     */
    private static List<Link> splitCoFeeders(final List<Link> forward) {
        final Map<Slot, List<Link>> byStorage = new LinkedHashMap<>();
        for (final Link link : forward) {
            if (link.to.card.storage()) push(byStorage, link.to, link);
            if (link.from.card.storage()) push(byStorage, link.from, link);
        }
        final Map<Slot, List<Link>> outgoing = new HashMap<>();
        for (final Link link : forward) push(outgoing, link.from, link);
        final Set<Slot> turned = new HashSet<>();
        final Set<Long> decided = new HashSet<>();
        for (final Map.Entry<Slot, List<Link>> entry : byStorage.entrySet()) {
            final Slot storage = entry.getKey();
            final List<Slot> feeders = new ArrayList<>(), takers = new ArrayList<>();
            for (final Link l : entry.getValue()) {
                if (l.to == storage) feeders.add(l.from);
                if (l.from == storage) takers.add(l.to);
            }
            for (final List<Slot> group : List.of(feeders, takers)) {
                final List<Slot> machines = new ArrayList<>();
                for (final Slot slot : new LinkedHashSet<>(group)) if (!slot.card.storage()) machines.add(slot);
                if (machines.size() != 2) continue;
                machines.sort((a, b) -> a.index - b.index);
                final Slot p = machines.get(0), q = machines.get(1);
                if (!decided.add((long) p.index << 32 | q.index)) continue;
                if (turned.contains(p) || turned.contains(q) || reaches(outgoing, p, q) || reaches(outgoing, q, p))
                    continue;
                final Set<Slot> shared = new HashSet<>();
                for (final Map.Entry<Slot, List<Link>> other : byStorage.entrySet()) {
                    final Set<Slot> ends = new HashSet<>();
                    for (final Link l : other.getValue()) ends.add(l.from == other.getKey() ? l.to : l.from);
                    if (ends.contains(p) && ends.contains(q)) shared.add(other.getKey());
                }
                turned.add(rightward(outgoing, p, shared) >= rightward(outgoing, q, shared) ? q : p);
            }
        }
        if (turned.isEmpty()) return forward;
        // A card with other forward wires keeps them; only its drawer wires turn.
        final List<Link> out = new ArrayList<>(forward.size());
        for (final Link link : forward) {
            final Slot storageEnd = link.to.card.storage() ? link.to : link.from.card.storage() ? link.from : null;
            if (storageEnd == null) {
                out.add(link);
                continue;
            }
            final Slot machine = storageEnd == link.to ? link.from : link.to;
            out.add(turned.contains(machine) ? link.reversed() : link);
        }
        return out;
    }

    private static boolean reaches(final Map<Slot, List<Link>> outgoing, final Slot from, final Slot to) {
        final Set<Slot> seen = new HashSet<>(List.of(from));
        final List<Slot> queue = new ArrayList<>(List.of(from));
        for (int head = 0; head < queue.size(); head++) {
            for (final Link link : outgoing.getOrDefault(queue.get(head), List.of())) {
                if (link.to == to) return true;
                if (seen.add(link.to)) queue.add(link.to);
            }
        }
        return false;
    }

    private static double rightward(final Map<Slot, List<Link>> outgoing, final Slot slot, final Set<Slot> shared) {
        double sum = 0;
        for (final Link l : outgoing.getOrDefault(slot, List.of())) if (!shared.contains(l.to)) sum += l.weight;
        return sum;
    }

    /**
     * Slides every card toward whichever side holds more of its wire weight, within what its wires allow. Pinned cards
     * (a folded ring) hold still but still anchor their neighbours.
     */
    private static void slideTowardWires(final List<Slot> members, final List<Link> forward, final Set<Slot> pinned,
        final Map<String, Double> exits) {
        final Map<Slot, List<Link>> incoming = new HashMap<>(), outgoing = new HashMap<>();
        for (final Link link : forward) {
            push(outgoing, link.from, link);
            push(incoming, link.to, link);
        }
        for (int pass = 0; pass < 3; pass++) {
            int maxLayer = 0;
            for (final Slot slot : members) maxLayer = Math.max(maxLayer, slot.layer);
            for (final Slot slot : members) {
                if (pinned != null && pinned.contains(slot)) continue;
                final List<Link> ins = incoming.getOrDefault(slot, List.of()),
                    outs = outgoing.getOrDefault(slot, List.of());
                // A card whose wire leaves for another island leans toward the edge it exits from, heavily: it beats
                // the card's own left-to-right preference.
                final double exitBias = (exits == null ? 0 : exits.getOrDefault(slot.card.id(), 0.0)) * 4;
                int lower = 0;
                for (final Link link : ins) lower = Math.max(lower, link.from.layer + 1);
                int upper = Integer.MAX_VALUE;
                for (final Link link : outs) upper = Math.min(upper, link.to.layer - 1);
                // No forward successors normally means stay put, but a card exiting rightward may drift as far as the
                // island reaches.
                if (upper == Integer.MAX_VALUE) upper = exitBias > 0 ? maxLayer : slot.layer;
                if (upper < lower) continue;
                // Total weighted wire length is linear in the card's column, so the best spot is whichever end of the
                // feasible range the heavier side points to; a tie splits the difference, as long wires read worst.
                double inWeight = Math.max(0, -exitBias), outWeight = Math.max(0, exitBias);
                for (final Link link : ins) inWeight += link.weight;
                for (final Link link : outs) outWeight += link.weight;
                if (outWeight > inWeight) slot.layer = upper;
                else if (inWeight > outWeight) slot.layer = lower;
                else if (inWeight > 0 && outWeight > 0) slot.layer = (int) Math.round((lower + upper) / 2.0);
            }
        }
    }

    /** Two-card loops share a column, stacked: the member with fewer other wires adopts the other's column. */
    private static void pullPairsTogether(final Set<Link> pairs, final List<Link> forward) {
        final Map<Slot, Integer> degree = new HashMap<>();
        for (final Link link : forward) {
            degree.merge(link.from, 1, Integer::sum);
            degree.merge(link.to, 1, Integer::sum);
        }
        for (final Link link : pairs) {
            if (link.from.index >= link.to.index) continue;
            final Slot a = link.from, b = link.to;
            if (degree.getOrDefault(a, 0) >= degree.getOrDefault(b, 0)) b.layer = a.layer;
            else a.layer = b.layer;
        }
    }

    /**
     * A drawer serving several machines belongs between them, not right of them all: its column becomes the
     * weight-averaged column of its partners.
     */
    private static void relaxSharedStorages(final List<Slot> members, final List<Link> links, final Set<Slot> pinned) {
        final Map<Slot, List<Link>> bySlot = new HashMap<>();
        for (final Link link : links) {
            push(bySlot, link.from, link);
            push(bySlot, link.to, link);
        }
        for (final Slot slot : members) {
            if (!slot.card.storage() || pinned.contains(slot)) continue;
            final List<Link> mine = bySlot.getOrDefault(slot, List.of());
            final Set<Slot> partners = new HashSet<>();
            for (final Link l : mine) partners.add(l.from == slot ? l.to : l.from);
            if (partners.size() < 2) continue;
            double sum = 0, weight = 0;
            for (final Link link : mine) {
                final Slot other = link.from == slot ? link.to : link.from;
                sum += other.layer * link.weight;
                weight += link.weight;
            }
            slot.layer = (int) Math.round(sum / weight);
        }
    }

    /** Layers come out sparse after tightening and folding: close the holes. */
    private static void packLayers(final List<Slot> members) {
        final List<Integer> used = new ArrayList<>(
            new java.util.TreeSet<>(
                members.stream()
                    .map(s -> s.layer)
                    .toList()));
        final Map<Integer, Integer> packed = new HashMap<>();
        for (int i = 0; i < used.size(); i++) packed.put(used.get(i), i);
        for (final Slot slot : members) slot.layer = packed.getOrDefault(slot.layer, 0);
    }

    /**
     * Strongly connected components (iterative Tarjan, input order): the cards that can all reach each other, a recycle
     * ring and everything riding it.
     */
    private static Map<Slot, Integer> strongComponents(final List<Slot> members, final List<Link> links) {
        final Map<Slot, List<Slot>> outgoing = new HashMap<>();
        for (final Link link : links) push(outgoing, link.from, link.to);
        final Map<Slot, Integer> component = new HashMap<>(), indexOf = new HashMap<>(), low = new HashMap<>();
        final Set<Slot> onStack = new HashSet<>();
        final List<Slot> tarjan = new ArrayList<>();
        int nextIndex = 0, nextComponent = 0;
        for (final Slot start : members) {
            if (indexOf.containsKey(start)) continue;
            final List<Object[]> work = new ArrayList<>();
            work.add(new Object[] { start, 0 });
            indexOf.put(start, nextIndex);
            low.put(start, nextIndex);
            nextIndex++;
            tarjan.add(start);
            onStack.add(start);
            while (!work.isEmpty()) {
                final Object[] frame = work.get(work.size() - 1);
                final Slot slot = (Slot) frame[0];
                final List<Slot> near = outgoing.getOrDefault(slot, List.of());
                final int next = (Integer) frame[1];
                if (next < near.size()) {
                    final Slot other = near.get(next);
                    frame[1] = next + 1;
                    if (!indexOf.containsKey(other)) {
                        indexOf.put(other, nextIndex);
                        low.put(other, nextIndex);
                        nextIndex++;
                        tarjan.add(other);
                        onStack.add(other);
                        work.add(new Object[] { other, 0 });
                    } else if (onStack.contains(other)) low.put(slot, Math.min(low.get(slot), indexOf.get(other)));
                    continue;
                }
                work.remove(work.size() - 1);
                if (!work.isEmpty()) {
                    final Slot up = (Slot) work.get(work.size() - 1)[0];
                    low.put(up, Math.min(low.get(up), low.get(slot)));
                }
                if (low.get(slot)
                    .equals(indexOf.get(slot))) {
                    while (true) {
                        final Slot popped = tarjan.remove(tarjan.size() - 1);
                        onStack.remove(popped);
                        component.put(popped, nextComponent);
                        if (popped == slot) break;
                    }
                    nextComponent++;
                }
            }
        }
        return component;
    }

    /**
     * Folds each big ring back over itself: for every strongly connected group of four or more cards, every fold point
     * is tried (cards past it mirror onto a return deck heading back left), and the fold whose total wire span is
     * smallest is kept if it beats the unfolded line. Fills the cards on top decks and the cards held still.
     */
    private static void foldBigCycles(final List<Slot> members, final List<Link> links, final Set<Link> pairs,
        final Set<Slot> topDeck, final Set<Slot> pinned) {
        final Map<Slot, Integer> component = strongComponents(members, links);
        final Map<Integer, List<Slot>> groups = new LinkedHashMap<>();
        for (final Slot slot : members) push(groups, component.getOrDefault(slot, -1), slot);
        for (final List<Slot> group : groups.values()) {
            if (group.size() < 4) continue;
            final Set<Slot> inGroup = new HashSet<>(group);
            final List<Link> inner = new ArrayList<>();
            for (final Link link : links)
                if (inGroup.contains(link.from) && inGroup.contains(link.to) && !pairs.contains(link)) inner.add(link);
            int minLayer = Integer.MAX_VALUE;
            for (final Slot slot : group) minLayer = Math.min(minLayer, slot.layer);
            final Map<Slot, Integer> flat = new HashMap<>();
            int span = 0;
            for (final Slot slot : group) {
                flat.put(slot, slot.layer - minLayer);
                span = Math.max(span, slot.layer - minLayer);
            }
            // Squared span: one wire across four columns is far worse than four wires hopping one each. A same-column
            // link wraps around the cards, so it prices like a two-column hop.
            int bestM = -1;
            long bestCost = foldCost(inner, flat, -1);
            for (int m = 0; m < span; m++) {
                boolean negative = false;
                for (final Slot slot : group) if (folded(flat.get(slot), m) < 0) negative = true;
                if (negative) continue;
                final long cost = foldCost(inner, flat, m);
                if (cost < bestCost) {
                    bestCost = cost;
                    bestM = m;
                }
            }
            if (bestM < 0) continue;
            for (final Slot slot : group) {
                pinned.add(slot);
                final int f = flat.get(slot);
                if (f > bestM) {
                    slot.layer = minLayer + 2 * bestM + 1 - f;
                    topDeck.add(slot);
                }
            }
        }
    }

    /** A card's column within its ring folded at {@code m} (-1: not folded). */
    private static int folded(final int f, final int m) {
        return m < 0 || f <= m ? f : 2 * m + 1 - f;
    }

    private static long foldCost(final List<Link> inner, final Map<Slot, Integer> flat, final int m) {
        long sum = 0;
        for (final Link link : inner) {
            final int d = Math.abs(folded(flat.get(link.from), m) - folded(flat.get(link.to), m));
            sum += d == 0 ? 4 : (long) d * d;
        }
        return sum;
    }

    private static List<Slot> topologicalOrder(final List<Slot> members, final Map<Slot, List<Link>> outgoing) {
        final Map<Slot, Integer> indegree = new HashMap<>();
        for (final Slot slot : members) indegree.put(slot, 0);
        for (final List<Link> list : outgoing.values())
            for (final Link link : list) indegree.merge(link.to, 1, Integer::sum);
        // A plain queue seeded in input order keeps the walk deterministic.
        final List<Slot> queue = new ArrayList<>();
        for (final Slot slot : members) if (indegree.get(slot) == 0) queue.add(slot);
        final List<Slot> order = new ArrayList<>();
        for (int head = 0; head < queue.size(); head++) {
            final Slot slot = queue.get(head);
            order.add(slot);
            for (final Link link : outgoing.getOrDefault(slot, List.of())) {
                final int remaining = indegree.getOrDefault(link.to, 0) - 1;
                indegree.put(link.to, remaining);
                if (remaining == 0) queue.add(link.to);
            }
        }
        return order;
    }

    /**
     * Sections: grow a spanning tree from the main product (the final sink with the most machinery behind it),
     * heaviest wires first. The TRUNK is the chain of largest subtrees down from that root; every subtree hanging off
     * it is a SECTION. One in-order walk assigns {@code seq} (cards sort within their column by it, so a subtree stays
     * contiguous in every column it touches) and {@code section} (which buys air between bands); sections are balanced
     * on both sides of the trunk so the main line runs through the middle.
     */
    private void buildBands(final List<Slot> members, final List<Link> links, final List<Link> forward) {
        // How many wires actually touch each card: a two-wire buffer can be a tree leaf without being a bud.
        final Map<Slot, Integer> wireDegree = new HashMap<>();
        for (final Link link : links) {
            wireDegree.merge(link.from, 1, Integer::sum);
            wireDegree.merge(link.to, 1, Integer::sum);
        }
        // Undirected adjacency, parallel wires merged, heaviest first.
        final Map<Slot, List<Partner>> adjacency = new HashMap<>();
        {
            final Map<Slot, Map<Slot, Double>> paired = new LinkedHashMap<>();
            for (final Link link : links) {
                paired.computeIfAbsent(link.from, k -> new LinkedHashMap<>())
                    .merge(link.to, link.weight, Double::sum);
                paired.computeIfAbsent(link.to, k -> new LinkedHashMap<>())
                    .merge(link.from, link.weight, Double::sum);
            }
            for (final Map.Entry<Slot, Map<Slot, Double>> row : paired.entrySet()) {
                final List<Partner> near = new ArrayList<>();
                for (final Map.Entry<Slot, Double> e : row.getValue()
                    .entrySet()) near.add(new Partner(e.getKey(), 0, 0, e.getValue()));
                near.sort((a, b) -> {
                    final int c = by(b.weight() - a.weight());
                    return c != 0 ? c : a.other().index - b.other().index;
                });
                adjacency.put(row.getKey(), near);
            }
        }

        // The root: among cards nothing forward flows out of, the one with the most cards feeding it. A machine
        // outranks a drawer: growing the tree from a chest scrambles the bands behind it.
        final Set<Slot> hasForwardOut = new HashSet<>();
        for (final Link link : forward) hasForwardOut.add(link.from);
        final List<Slot> allSinks = new ArrayList<>(), machineSinks = new ArrayList<>();
        for (final Slot slot : members) if (!hasForwardOut.contains(slot)) {
            allSinks.add(slot);
            if (!slot.card.storage()) machineSinks.add(slot);
        }
        final List<Slot> sinks = machineSinks.isEmpty() ? allSinks : machineSinks;
        final Map<Slot, List<Link>> feeders = new HashMap<>();
        for (final Link link : forward) push(feeders, link.to, link);
        Slot root = members.get(0);
        int rootReach = -1;
        for (final Slot sink : sinks) {
            final Set<Slot> seen = new HashSet<>(List.of(sink));
            final List<Slot> queue = new ArrayList<>(List.of(sink));
            for (int head = 0; head < queue.size(); head++) {
                for (final Link link : feeders.getOrDefault(queue.get(head), List.of()))
                    if (seen.add(link.from)) queue.add(link.from);
            }
            if (seen.size() > rootReach) {
                root = sink;
                rootReach = seen.size();
            }
        }

        // Spanning tree from the root, heaviest neighbours claimed first.
        final Map<Slot, List<Slot>> children = new HashMap<>();
        {
            final Set<Slot> visited = new HashSet<>(List.of(root));
            final List<Slot> stack = new ArrayList<>(List.of(root));
            while (!stack.isEmpty()) {
                final Slot slot = stack.remove(stack.size() - 1);
                // In reverse, so the heaviest neighbour is popped (visited) first.
                final List<Partner> near = adjacency.getOrDefault(slot, List.of());
                for (int i = near.size() - 1; i >= 0; i--) {
                    final Slot other = near.get(i)
                        .other();
                    if (visited.add(other)) {
                        push(children, slot, other);
                        stack.add(other);
                    }
                }
            }
        }

        // Subtree sizes and rough band heights, bottom up without recursion, then the trunk: the biggest child down.
        final Map<Slot, Integer> subtreeSize = new HashMap<>();
        final Map<Slot, Double> subtreeHeight = new HashMap<>();
        {
            final List<Slot> order = new ArrayList<>();
            final List<Slot> stack = new ArrayList<>(List.of(root));
            while (!stack.isEmpty()) {
                final Slot slot = stack.remove(stack.size() - 1);
                order.add(slot);
                stack.addAll(children.getOrDefault(slot, List.of()));
            }
            for (int i = order.size() - 1; i >= 0; i--) {
                int size = 1;
                double height = order.get(i).card.height() + rowGap;
                for (final Slot child : children.getOrDefault(order.get(i), List.of())) {
                    size += subtreeSize.getOrDefault(child, 1);
                    height += subtreeHeight.getOrDefault(child, 0.0);
                }
                subtreeSize.put(order.get(i), size);
                subtreeHeight.put(order.get(i), height);
            }
        }
        final Set<Slot> onTrunk = new LinkedHashSet<>(List.of(root));
        for (Slot slot = root; slot != null;) {
            Slot next = null;
            for (final Slot child : children.getOrDefault(slot, List.of())) {
                if (next == null || subtreeSize.getOrDefault(child, 1) > subtreeSize.getOrDefault(next, 1))
                    next = child;
            }
            if (next != null) onTrunk.add(next);
            slot = next;
        }
        for (final Slot slot : onTrunk) slot.trunk = true;

        // Which trunk-child subtree each off-trunk card hangs from, and how hard that branch holds onto the trunk:
        // strongly coupled branches (recycles, heavy feeds) sit nearest the trunk, loose ones drift outward.
        final Map<Slot, Slot> branchRoot = new HashMap<>();
        for (final Slot trunkSlot : onTrunk) {
            for (final Slot child : children.getOrDefault(trunkSlot, List.of())) {
                if (onTrunk.contains(child)) continue;
                final List<Slot> queue = new ArrayList<>(List.of(child));
                branchRoot.put(child, child);
                for (int head = 0; head < queue.size(); head++) {
                    for (final Slot grand : children.getOrDefault(queue.get(head), List.of())) {
                        branchRoot.put(grand, child);
                        queue.add(grand);
                    }
                }
            }
        }
        final Map<Slot, Double> coupling = new HashMap<>();
        for (final Link link : links) {
            final boolean fromTrunk = onTrunk.contains(link.from), toTrunk = onTrunk.contains(link.to);
            if (fromTrunk == toTrunk) continue;
            final Slot branch = branchRoot.get(fromTrunk ? link.to : link.from);
            if (branch != null) coupling.merge(branch, link.weight, Double::sum);
        }

        // The in-order walk. At a trunk card, side sections split above and below it, most coupled nearest the trunk;
        // elsewhere children keep claim order.
        int seqCounter = 0, sectionCounter = 0;
        final List<int[]> phases = new ArrayList<>();
        final List<Slot> slots = new ArrayList<>();
        // A frame: {section, phase} with its slot alongside.
        slots.add(root);
        phases.add(new int[] { 0, 0 });
        while (!slots.isEmpty()) {
            final Slot slot = slots.remove(slots.size() - 1);
            final int[] frame = phases.remove(phases.size() - 1);
            final int section = frame[0];
            if (frame[1] == 1) {
                slot.seq = seqCounter++;
                slot.section = section;
                continue;
            }
            final List<Slot> kids = children.getOrDefault(slot, List.of());
            if (!onTrunk.contains(slot)) {
                // A section interior: the card, then its children in claim order, all in the section.
                slot.seq = seqCounter++;
                slot.section = section;
                for (int i = kids.size() - 1; i >= 0; i--) {
                    slots.add(kids.get(i));
                    phases.add(new int[] { section, 0 });
                }
                continue;
            }
            // A trunk card: the trunk continuation goes through the middle. Buds (one-wire leaves) keep the trunk's
            // band beside their machine; other branches become sections in coupling order, each dealt to whichever
            // side is shorter so the trunk stays vertically centred.
            Slot trunkChild = null;
            for (final Slot child : kids) if (onTrunk.contains(child)) {
                trunkChild = child;
                break;
            }
            final List<Slot> sides = new ArrayList<>();
            for (final Slot child : kids) if (child != trunkChild) sides.add(child);
            sides.sort((a, b) -> {
                int c = by(coupling.getOrDefault(b, 0.0) - coupling.getOrDefault(a, 0.0));
                if (c != 0) return c;
                c = subtreeSize.getOrDefault(b, 1) - subtreeSize.getOrDefault(a, 1);
                return c != 0 ? c : a.index - b.index;
            });
            final List<Slot> above = new ArrayList<>(), below = new ArrayList<>();
            final List<Integer> aboveSection = new ArrayList<>(), belowSection = new ArrayList<>();
            double aboveHeight = 0, belowHeight = 0;
            // A bud is a true one-wire leaf; a pass-through buffer can be a tree leaf while carrying two wires, and
            // glued here it would drag its other wire across the island, so it becomes a section instead.
            final List<Slot> ordered = new ArrayList<>();
            for (final Slot child : sides) if (isLeafBud(child, subtreeSize, wireDegree)) ordered.add(child);
            for (final Slot child : sides) if (!isLeafBud(child, subtreeSize, wireDegree)) ordered.add(child);
            // Buds first, so they hold the places nearest the trunk card while the sections stack outward past them.
            for (final Slot child : ordered) {
                int childSection = section;
                if (!isLeafBud(child, subtreeSize, wireDegree)) childSection = ++sectionCounter;
                final double height = subtreeHeight.getOrDefault(child, 0.0);
                if (aboveHeight <= belowHeight) {
                    above.add(0, child);
                    aboveSection.add(0, childSection);
                    aboveHeight += height;
                } else {
                    below.add(child);
                    belowSection.add(childSection);
                    belowHeight += height;
                }
            }
            // Pushed in reverse of the wanted visit order (it is a stack): below branches, the trunk continuation, this
            // card, then above.
            for (int i = below.size() - 1; i >= 0; i--) {
                slots.add(below.get(i));
                phases.add(new int[] { belowSection.get(i), 0 });
            }
            if (trunkChild != null) {
                slots.add(trunkChild);
                phases.add(new int[] { section, 0 });
            }
            slots.add(slot);
            phases.add(new int[] { section, 1 });
            for (int i = above.size() - 1; i >= 0; i--) {
                slots.add(above.get(i));
                phases.add(new int[] { aboveSection.get(i), 0 });
            }
        }
    }

    private static boolean isLeafBud(final Slot child, final Map<Slot, Integer> subtreeSize,
        final Map<Slot, Integer> wireDegree) {
        return subtreeSize.getOrDefault(child, 1) == 1 && wireDegree.getOrDefault(child, 0) <= 1;
    }

    private static List<List<Slot>> collectLayers(final List<Slot> members) {
        int count = 0;
        for (final Slot slot : members) count = Math.max(count, slot.layer);
        count++;
        final List<List<Slot>> layers = new ArrayList<>(count);
        for (int i = 0; i < count; i++) layers.add(new ArrayList<>());
        for (final Slot slot : members) layers.get(slot.layer)
            .add(slot);
        for (final List<Slot> layer : layers) layer.sort((a, b) -> a.seq != b.seq ? a.seq - b.seq : a.index - b.index);
        return layers;
    }

    private static Map<Slot, List<Partner>> partners(final List<Link> links, final Map<Slot, List<Partner>> extra) {
        final Map<Slot, List<Partner>> partners = new HashMap<>();
        for (final Link link : links) {
            // The main line must read ruler-straight, so a trunk-to-trunk wire pulls several times harder.
            final double emphasis = link.from.trunk && link.to.trunk ? 3 : 1;
            push(partners, link.from, new Partner(link.to, link.fromAnchor, link.toAnchor, link.weight * emphasis));
            push(partners, link.to, new Partner(link.from, link.toAnchor, link.fromAnchor, link.weight * emphasis));
        }
        // Phantom partners: fixed anchors (bridge pulls toward another island).
        if (extra != null) for (final Map.Entry<Slot, List<Partner>> e : extra.entrySet())
            for (final Partner p : e.getValue()) push(partners, e.getKey(), p);
        return partners;
    }

    /** Where a card's wires would put it, port to port, weighted by flow. */
    private static Wish wishFor(final Slot slot, final List<Partner> list) {
        if (list == null || list.isEmpty()) return new Wish(slot.y, 0.1);
        double sum = 0, total = 0;
        for (final Partner p : list) {
            sum += (p.other().y + p.their() - p.own()) * p.weight();
            total += p.weight();
        }
        return new Wish(sum / total, total);
    }

    /**
     * The anti-crossing pass: within one column every section moves as one block to its members' mean pull, and
     * members reorder inside their block by their own pull, so a band can move but never split. The column's seq
     * numbers are dealt back out in the new order.
     */
    private static void polishColumnOrder(final List<List<Slot>> layers, final List<Link> links,
        final Map<Slot, List<Partner>> extra) {
        final Map<Slot, List<Partner>> partners = partners(links, extra);
        for (final List<Slot> layer : layers) {
            if (layer.size() < 2) continue;
            final Map<Slot, Wish> wish = new HashMap<>();
            for (final Slot slot : layer) wish.put(slot, wishFor(slot, partners.get(slot)));
            final Map<Integer, List<Slot>> groups = new LinkedHashMap<>();
            for (final Slot slot : layer) push(groups, slot.section, slot);
            final List<Object[]> blocks = new ArrayList<>();
            for (final List<Slot> inGroup : groups.values()) {
                inGroup.sort((a, b) -> {
                    final int c = by(
                        wish.get(a)
                            .wish()
                            - wish.get(b)
                                .wish());
                    return c != 0 ? c : a.seq - b.seq;
                });
                double sum = 0, weight = 0;
                int minSeq = Integer.MAX_VALUE;
                for (final Slot slot : inGroup) {
                    sum += wish.get(slot)
                        .wish()
                        * wish.get(slot)
                            .weight();
                    weight += wish.get(slot)
                        .weight();
                    minSeq = Math.min(minSeq, slot.seq);
                }
                blocks.add(new Object[] { inGroup, weight > 0 ? sum / weight : 0.0, minSeq });
            }
            blocks.sort((a, b) -> {
                final int c = by((double) a[1] - (double) b[1]);
                return c != 0 ? c : (int) a[2] - (int) b[2];
            });
            final List<Integer> seqs = new ArrayList<>();
            for (final Slot slot : layer) seqs.add(slot.seq);
            Collections.sort(seqs);
            int next = 0;
            for (final Object[] block : blocks) {
                @SuppressWarnings("unchecked")
                final List<Slot> cards = (List<Slot>) block[0];
                for (final Slot slot : cards) slot.seq = seqs.get(next++);
            }
        }
    }

    /**
     * The vertical pass: every card gets a y that lines its ports up with its wire partners' ports, with no overlap in
     * a column and section air kept. Each sweep works out every card's wish and settles each column exactly (weighted
     * least squares under keep-order-and-gaps is isotonic regression; see settleLine).
     */
    private void placeRows(final List<List<Slot>> layers, final List<Link> links,
        final Map<Slot, List<Partner>> extra) {
        final Map<Slot, List<Partner>> partners = partners(links, extra);
        // First stacking: straight down in order, so every wish starts from a legal picture.
        for (final List<Slot> layer : layers) {
            double y = 0;
            Slot previous = null;
            for (final Slot slot : layer) {
                if (previous != null) y += gapBetween(previous, slot);
                slot.y = y;
                y += slot.card.height();
                previous = slot;
            }
        }
        for (int sweep = 0; sweep < 8; sweep++) {
            final boolean downward = sweep % 2 == 0;
            for (int i = 0; i < layers.size(); i++) {
                final List<Slot> layer = layers.get(downward ? i : layers.size() - 1 - i);
                final List<Wish> wishes = new ArrayList<>(layer.size());
                for (final Slot slot : layer) wishes.add(wishFor(slot, partners.get(slot)));
                settleColumn(layer, wishes);
            }
        }
        // Pull the whole island up so its top card sits at zero.
        double top = Double.POSITIVE_INFINITY;
        for (final List<Slot> layer : layers) for (final Slot slot : layer) top = Math.min(top, slot.y);
        for (final List<Slot> layer : layers) for (final Slot slot : layer) slot.y -= top;
    }

    /** The air owed between two vertically adjacent cards in one column. */
    private double gapBetween(final Slot upper, final Slot lower) {
        return upper.section == lower.section ? rowGap : sectionGap;
    }

    /**
     * Snaps every card to the grid, then onto the exact line of its heaviest wire when that is a nudge of two cells or
     * less and its column neighbours keep their air.
     */
    private void straightenRows(final List<List<Slot>> layers, final List<Link> links,
        final Map<Slot, List<Partner>> extra) {
        final Map<Slot, List<Partner>> partners = partners(links, extra);
        for (final List<Slot> layer : layers) for (final Slot slot : layer) slot.y = Grid.snap(slot.y);
        for (int sweep = 0; sweep < 2; sweep++) {
            final boolean leftToRight = sweep % 2 == 0;
            for (int i = 0; i < layers.size(); i++) {
                final List<Slot> layer = layers.get(leftToRight ? i : layers.size() - 1 - i);
                for (int row = 0; row < layer.size(); row++) {
                    final Slot slot = layer.get(row);
                    double bestY = 0, bestWeight = 0;
                    boolean found = false;
                    for (final Partner p : partners.getOrDefault(slot, List.of())) {
                        final double aligned = p.other().y + p.their() - p.own();
                        if (aligned % Grid.CELL != 0) continue;
                        final double distance = Math.abs(aligned - slot.y);
                        if (distance == 0 || distance > Grid.cells(2)) continue;
                        if (!found || p.weight() > bestWeight) {
                            bestY = aligned;
                            bestWeight = p.weight();
                            found = true;
                        }
                    }
                    if (!found) continue;
                    final Slot previous = row > 0 ? layer.get(row - 1) : null,
                        next = row + 1 < layer.size() ? layer.get(row + 1) : null;
                    final double lowest = previous != null
                        ? previous.y + previous.card.height() + gapBetween(previous, slot)
                        : Double.NEGATIVE_INFINITY;
                    final double highest = next != null ? next.y - slot.card.height() - gapBetween(slot, next)
                        : Double.POSITIVE_INFINITY;
                    if (bestY >= lowest && bestY <= highest) slot.y = bestY;
                }
            }
        }
    }

    /**
     * Adjacent-pair transposition against the real wires: for every pair of vertical neighbours, count their wires'
     * crossings as placed and as swapped, and keep strictly better swaps. Returns whether any helped.
     */
    private static boolean transposeToUncross(final List<List<Slot>> layers, final List<Link> links) {
        // Same-column links count too: a stacked pair's wrap-around wires cost nothing while stacked and cross
        // everything between them once a swap separates them, which keeps stacked pairs together.
        final Map<Slot, List<Link>> linksOf = new HashMap<>();
        for (final Link link : links) {
            push(linksOf, link.from, link);
            push(linksOf, link.to, link);
        }
        final Map<Slot, Double> still = new HashMap<>();
        boolean flippedAny = false;
        for (final List<Slot> layer : layers) {
            for (int i = 0; i + 1 < layer.size(); i++) {
                final Slot upper = layer.get(i), lower = layer.get(i + 1);
                final Set<Link> both = new LinkedHashSet<>(linksOf.getOrDefault(upper, List.of()));
                both.addAll(linksOf.getOrDefault(lower, List.of()));
                final List<Link> involved = new ArrayList<>(both);
                if (involved.isEmpty()) continue;
                // The swap trades their centres; exact stacking is the settle's job.
                final double upperCentre = upper.y + upper.card.height() / 2,
                    lowerCentre = lower.y + lower.card.height() / 2;
                final Map<Slot, Double> swapped = new HashMap<>();
                swapped.put(upper, lowerCentre - upper.card.height() / 2);
                swapped.put(lower, upperCentre - lower.card.height() / 2);
                if (crossingCount(involved, links, swapped) < crossingCount(involved, links, still)) {
                    final int seq = upper.seq;
                    upper.seq = lower.seq;
                    lower.seq = seq;
                    layer.set(i, lower);
                    layer.set(i + 1, upper);
                    upper.y = swapped.get(upper);
                    lower.y = swapped.get(lower);
                    flippedAny = true;
                }
            }
        }
        return flippedAny;
    }

    private static int crossingCount(final List<Link> involved, final List<Link> links,
        final Map<Slot, Double> override) {
        int total = 0;
        for (int a = 0; a < involved.size(); a++) {
            for (final Link other : links) {
                final int b = involved.indexOf(other);
                if (b >= 0 && b <= a) continue;
                if (crossed(involved.get(a), other, override)) total++;
            }
        }
        return total;
    }

    /**
     * Columns only exist later: for crossing tests each layer is a vertical line, wires leave right and arrive left.
     */
    private static final double X_SPAN = 10000;

    private static double[] pointOf(final Link link, final boolean from, final Map<Slot, Double> override) {
        final Slot slot = from ? link.from : link.to;
        final double anchor = from ? link.fromAnchor : link.toAnchor;
        return new double[] { slot.layer * X_SPAN + (from ? X_SPAN - 100 : 100),
            override.getOrDefault(slot, slot.y) + anchor };
    }

    private static boolean crossed(final Link first, final Link second, final Map<Slot, Double> override) {
        if (first.from == second.from || first.from == second.to || first.to == second.from || first.to == second.to)
            return false;
        final double[] a1 = pointOf(first, true, override), a2 = pointOf(first, false, override);
        final double[] b1 = pointOf(second, true, override), b2 = pointOf(second, false, override);
        final double d1 = turn(b1, b2, a1), d2 = turn(b1, b2, a2), d3 = turn(a1, a2, b1), d4 = turn(a1, a2, b2);
        return (d1 > 0 && d2 < 0 || d1 < 0 && d2 > 0) && (d3 > 0 && d4 < 0 || d3 < 0 && d4 > 0);
    }

    private static double turn(final double[] p, final double[] q, final double[] r) {
        return (q[0] - p[0]) * (r[1] - p[1]) - (q[1] - p[1]) * (r[0] - p[0]);
    }

    /** The card-column settle: order and section-aware gaps, solved exactly. */
    private void settleColumn(final List<Slot> layer, final List<Wish> wishes) {
        if (layer.isEmpty()) return;
        final double[] spacing = new double[layer.size()];
        for (int i = 1; i < layer.size(); i++)
            spacing[i] = layer.get(i - 1).card.height() + gapBetween(layer.get(i - 1), layer.get(i));
        final double[] ys = settleLine(wishes, spacing);
        for (int i = 0; i < layer.size(); i++) layer.get(i).y = ys[i];
    }

    /**
     * Weighted isotonic regression with fixed spacings (pool adjacent violators): places a line of items as close to
     * their wishes as their order and least distances allow, exactly, in linear time. {@code spacing[i]} is the least
     * distance from item i-1's top to item i's top.
     */
    private static double[] settleLine(final List<Wish> wishes, final double[] spacing) {
        final int n = wishes.size();
        final double[] starts = new double[n];
        double acc = 0;
        for (int i = 0; i < n; i++) {
            if (i > 0) acc += spacing[i];
            starts[i] = acc;
        }
        final double[] poolMean = new double[n], poolWeight = new double[n];
        final int[] poolCount = new int[n];
        int pools = 0;
        for (int i = 0; i < n; i++) {
            double mean = wishes.get(i)
                .wish() - starts[i];
            double weight = wishes.get(i)
                .weight();
            int count = 1;
            while (pools > 0 && poolMean[pools - 1] >= mean) {
                pools--;
                mean = (poolMean[pools] * poolWeight[pools] + mean * weight) / (poolWeight[pools] + weight);
                weight += poolWeight[pools];
                count += poolCount[pools];
            }
            poolMean[pools] = mean;
            poolWeight[pools] = weight;
            poolCount[pools] = count;
            pools++;
        }
        final double[] ys = new double[n];
        int index = 0;
        for (int p = 0; p < pools; p++) for (int i = 0; i < poolCount[p]; i++) {
            ys[index] = poolMean[p] + starts[index];
            index++;
        }
        return ys;
    }

    // endregion

    // region Islands

    /**
     * Which island stands upstream of which: net flow per pair picks the direction, a DFS drops ring-closing edges,
     * longest path deals columns, and two slide passes pull each island toward its heavier side.
     */
    private static Map<Integer, Integer> islandFlowLayers(final List<Integer> indices, final List<Bridge> edges) {
        final Map<Integer, Integer> layerOf = new LinkedHashMap<>();
        for (final int index : indices) layerOf.put(index, 0);
        if (edges.isEmpty()) return layerOf;
        final Map<String, double[]> net = new LinkedHashMap<>();
        for (final Bridge edge : edges) {
            final String key = Math.min(edge.from(), edge.to()) + ":" + Math.max(edge.from(), edge.to());
            final double[] entry = net.get(key);
            if (entry == null) net.put(key, new double[] { edge.from(), edge.to(), edge.weight() });
            else entry[2] += (int) entry[0] == edge.from() ? edge.weight() : -edge.weight();
        }
        final List<int[]> forward = new ArrayList<>();
        final List<Double> forwardWeight = new ArrayList<>();
        final List<Object[]> sorted = new ArrayList<>();
        for (final double[] e : net.values()) {
            sorted.add(
                e[2] >= 0 ? new Object[] { (int) e[0], (int) e[1], e[2] }
                    : new Object[] { (int) e[1], (int) e[0], -e[2] });
        }
        sorted.sort((a, b) -> (int) a[0] != (int) b[0] ? (int) a[0] - (int) b[0] : (int) a[1] - (int) b[1]);
        for (final Object[] e : sorted) {
            forward.add(new int[] { (int) e[0], (int) e[1] });
            forwardWeight.add((double) e[2]);
        }
        final Map<Integer, List<Integer>> outgoing = new HashMap<>();
        for (int k = 0; k < forward.size(); k++) push(outgoing, forward.get(k)[0], k);
        final Map<Integer, Integer> state = new HashMap<>();
        final List<Integer> kept = new ArrayList<>();
        for (final int start : indices) {
            if (state.containsKey(start)) continue;
            final List<int[]> stack = new ArrayList<>();
            stack.add(new int[] { start, 0 });
            state.put(start, 1);
            while (!stack.isEmpty()) {
                final int[] frame = stack.get(stack.size() - 1);
                final List<Integer> list = outgoing.getOrDefault(frame[0], List.of());
                if (frame[1] >= list.size()) {
                    state.put(frame[0], 2);
                    stack.remove(stack.size() - 1);
                    continue;
                }
                final int edge = list.get(frame[1]++);
                final Integer seen = state.get(forward.get(edge)[1]);
                if (seen == null) {
                    state.put(forward.get(edge)[1], 1);
                    kept.add(edge);
                    stack.add(new int[] { forward.get(edge)[1], 0 });
                } else if (seen == 2) kept.add(edge);
            }
        }
        {
            final Map<Integer, Integer> indegree = new HashMap<>();
            for (final int index : indices) indegree.put(index, 0);
            for (final int edge : kept) indegree.merge(forward.get(edge)[1], 1, Integer::sum);
            final List<Integer> queue = new ArrayList<>();
            for (final int index : indices) if (indegree.getOrDefault(index, 0) == 0) queue.add(index);
            for (int head = 0; head < queue.size(); head++) {
                for (final int edge : kept) {
                    final int[] e = forward.get(edge);
                    if (e[0] != queue.get(head)) continue;
                    layerOf.put(e[1], Math.max(layerOf.get(e[1]), layerOf.get(e[0]) + 1));
                    final int remaining = indegree.getOrDefault(e[1], 0) - 1;
                    indegree.put(e[1], remaining);
                    if (remaining == 0) queue.add(e[1]);
                }
            }
        }
        for (int pass = 0; pass < 2; pass++) {
            for (final int island : indices) {
                int lower = 0, upper = Integer.MAX_VALUE;
                double inWeight = 0, outWeight = 0;
                for (final int edge : kept) {
                    final int[] e = forward.get(edge);
                    if (e[1] == island) {
                        lower = Math.max(lower, layerOf.get(e[0]) + 1);
                        inWeight += forwardWeight.get(edge);
                    }
                    if (e[0] == island) {
                        upper = Math.min(upper, layerOf.get(e[1]) - 1);
                        outWeight += forwardWeight.get(edge);
                    }
                }
                if (upper == Integer.MAX_VALUE) upper = layerOf.get(island);
                if (upper < lower) continue;
                if (outWeight > inWeight) layerOf.put(island, upper);
                else if (inWeight > outWeight) layerOf.put(island, lower);
                else if (inWeight > 0 && outWeight > 0) layerOf.put(island, (int) Math.round((lower + upper) / 2.0));
            }
        }
        return layerOf;
    }

    /**
     * Where each island stands: the blocks run through the same column engine as meta-cards (block sizes, bridge
     * endpoints as ports), so satellites, stacked pairs, folds and the uncrossing passes apply to islands too. Islands
     * with no bridges pack in rows below; the shelf of strays takes the last row. One offset {x, y} per block.
     */
    private List<double[]> placeIslands(final List<Block> blocks, final List<Bridge> bridges,
        final Map<String, double[]> localPlace) {
        final List<double[]> offsets = new ArrayList<>();
        for (int i = 0; i < blocks.size(); i++) offsets.add(new double[] { 0, 0 });
        final List<Slot> metaSlots = new ArrayList<>();
        for (int i = 0; i < blocks.size(); i++) metaSlots.add(
            new Slot(
                new ArrangeCard("#" + i, 0, 0, blocks.get(i).width, blocks.get(i).height, blocks.get(i).plain),
                i));
        final List<Link> metaLinks = new ArrayList<>();
        for (final Bridge bridge : bridges) metaLinks.add(
            new Link(
                null,
                metaSlots.get(bridge.from()),
                metaSlots.get(bridge.to()),
                localPlace.get(bridge.link().from.card.id())[1] + bridge.link().fromAnchor,
                localPlace.get(bridge.link().to.card.id())[1] + bridge.link().toAnchor,
                bridge.weight(),
                null));

        return atIslandScale(() -> {
            final Map<String, Slot> slotById = new LinkedHashMap<>();
            for (final Slot slot : metaSlots) slotById.put(slot.card.id(), slot);
            final Map<Slot, Satellite> plans = planSatellites(slotById, metaLinks);
            final List<Link> mainLinks = new ArrayList<>();
            for (final Link link : metaLinks)
                if (!plans.containsKey(link.from) && !plans.containsKey(link.to)) mainLinks.add(link);
            final Set<Slot> anchors = new HashSet<>();
            for (final Satellite plan : plans.values()) anchors.add(plan.anchor());
            final Set<Slot> linked = new HashSet<>();
            for (final Link link : mainLinks) {
                linked.add(link.from);
                linked.add(link.to);
            }
            final int[] parent = unionComponents(metaSlots.size(), mainLinks);
            final Map<Integer, List<Slot>> componentSlots = new LinkedHashMap<>();
            final List<Integer> loose = new ArrayList<>();
            for (final Slot slot : metaSlots) {
                if (plans.containsKey(slot) || blocks.get(slot.index).shelf) continue;
                if (!linked.contains(slot) && !anchors.contains(slot)) {
                    loose.add(slot.index);
                    continue;
                }
                push(componentSlots, find(parent, slot.index), slot);
            }
            final List<Block> metaBlocks = new ArrayList<>();
            for (final List<Slot> members : componentSlots.values()) {
                members.sort((a, b) -> a.index - b.index);
                final Set<Slot> memberSet = new HashSet<>(members);
                final List<Link> componentLinks = new ArrayList<>();
                for (final Link link : mainLinks)
                    if (memberSet.contains(link.from) && memberSet.contains(link.to)) componentLinks.add(link);
                final Map<Slot, Satellite> componentSatellites = new LinkedHashMap<>();
                for (final Map.Entry<Slot, Satellite> e : plans.entrySet()) if (memberSet.contains(
                    e.getValue()
                        .anchor()))
                    componentSatellites.put(e.getKey(), e.getValue());
                metaBlocks.add(layoutIsland(members, componentLinks, componentSatellites, new HashMap<>(), null, null));
            }
            metaBlocks.sort((a, b) -> a.size != b.size ? b.size - a.size : a.minIndex - b.minIndex);

            // The trading constellations stack first, then the islands wired to nothing in rows, then the shelf.
            double cursorY = 0, width = 0;
            for (final Block metaBlock : metaBlocks) {
                for (int i = 0; i < metaBlock.ids.size(); i++) {
                    final int index = Integer.parseInt(
                        metaBlock.ids.get(i)
                            .substring(1));
                    offsets
                        .set(index, new double[] { metaBlock.places.get(i)[0], cursorY + metaBlock.places.get(i)[1] });
                }
                width = Math.max(width, metaBlock.width);
                cursorY += metaBlock.height + islandGap;
            }
            final double rowTarget = Math.max(width, Grid.cells(120));
            double rowX = 0, rowHeight = 0;
            for (final int index : loose) {
                if (rowX > 0 && rowX + blocks.get(index).width > rowTarget) {
                    rowX = 0;
                    cursorY += rowHeight + islandGap;
                    rowHeight = 0;
                }
                offsets.set(index, new double[] { rowX, cursorY });
                rowX += blocks.get(index).width + islandGap;
                rowHeight = Math.max(rowHeight, blocks.get(index).height);
            }
            if (rowHeight > 0) cursorY += rowHeight + islandGap;
            for (int index = 0; index < blocks.size(); index++) {
                if (!blocks.get(index).shelf) continue;
                offsets.set(index, new double[] { 0, cursorY });
                cursorY += blocks.get(index).height + islandGap;
            }
            double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
            for (final double[] offset : offsets) {
                minX = Math.min(minX, offset[0]);
                minY = Math.min(minY, offset[1]);
            }
            for (final double[] offset : offsets) {
                offset[0] -= minX;
                offset[1] -= minY;
            }
            return offsets;
        });
    }

    /** The shelf: cards wired to nothing, parked in tidy rows, alike sizes together. */
    private static Block layoutShelf(final List<Slot> parked, final double mainWidth) {
        final List<Slot> sorted = new ArrayList<>(parked);
        sorted.sort((a, b) -> {
            int c = by(b.card.height() - a.card.height());
            if (c != 0) return c;
            c = by(b.card.width() - a.card.width());
            return c != 0 ? c : a.index - b.index;
        });
        final double targetWidth = Math.max(mainWidth, Grid.cells(60));
        final Block block = new Block();
        double x = 0, y = 0, rowHeight = 0;
        int minIndex = Integer.MAX_VALUE;
        for (final Slot slot : sorted) {
            if (x > 0 && x + slot.card.width() > targetWidth) {
                x = 0;
                y += rowHeight + SHELF_GAP;
                rowHeight = 0;
            }
            block.ids.add(slot.card.id());
            block.places.add(new double[] { x, y });
            block.width = Math.max(block.width, x + slot.card.width());
            block.height = Math.max(block.height, y + slot.card.height());
            rowHeight = Math.max(rowHeight, slot.card.height());
            x += slot.card.width() + SHELF_GAP;
            minIndex = Math.min(minIndex, slot.index);
        }
        block.size = sorted.size();
        block.minIndex = minIndex;
        block.shelf = true;
        return block;
    }

    // endregion
}

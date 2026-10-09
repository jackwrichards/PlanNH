package com.gtnhplanner.layout.arrange;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.ToDoubleFunction;

/**
 * The arrange's proxy score and search: takes an island the layered pass has placed and rearranges its cards to lower
 * the score the router's wires would get. As Factory Flow's board-arrange-optimize.ts.
 *
 * <p>
 * Every wire gets a proxy route shaped like the router's ({@link Proxy#path}: leave at the rim point facing the far
 * card, run straight for the clean cells, the shortest eight-direction way with one centred diagonal, land straight),
 * scored in the router's own points: length, bends at turn45 and turn90, crossings at the crossing price weighing the
 * heavier wire, all times the wire's weight, plus a detour estimate for each card a proxy path runs through.
 *
 * <p>
 * The search keeps the column discipline: a state is the card order per column, each column's vertical offset, the
 * air above each card, and each satellite's slide along its machine's side. A placer derives positions from that, so
 * every trial is tidy. Simulated annealing over swaps, column hops flow allows, moves to a partner's side and offset
 * nudges lowers the proxy; the judge (the real router, when the caller has one) then scores the best few and the
 * fewest points wins.
 *
 * <p>
 * Pure and deterministic: the random numbers are seeded from the island's ids, so one island always comes out the
 * same. Move for move the website's search (the same random draws, moves, acceptances and tie-breaks), but for one
 * thing: the website, given no judge, has a stand-in route the plain rim docks with the real router; here no judge
 * means no judging, and the proxy's best wins.
 */
public final class Optimize {

    /**
     * A drawer pinned to one machine's side keeps to that side (supplies left, catches right). It slides along the side
     * and stacks.
     *
     * @param anchorId the machine it rides
     * @param left     whether it rides the machine's left side (else its right)
     */
    public record Satellite(String anchorId, boolean left) {}

    /**
     * A card of the island, as the layered pass left it.
     *
     * @param storage   whether it is a drawer
     * @param layer     its column from the layered pass; satellites carry their anchor's
     * @param seq       its order within the column from the layered pass (any monotone key)
     * @param section   the feeder section the layered pass put it in (null: none); sections keep air between them
     * @param satellite the machine side it is pinned to (null: it stands in a column)
     */
    public record Card(String id, double width, double height, boolean storage, int layer, double seq, Integer section,
        Satellite satellite) {}

    /**
     * How to search. Every field may be null, for its default.
     *
     * @param rowGapCells       air between stacked cards, in cells (default 2)
     * @param sectionGapCells   air between stacked cards of different sections, in cells (default: the row gap)
     * @param columnGapCells    least corridor between columns, in cells (default 3)
     * @param satellitePadCells air between a satellite and its machine, in cells (default 2)
     * @param trials            annealing trials (default: scales with the island, up to the prices' search trials)
     * @param judge             the judge of the finalists: given every card's top-left, the points the board's real
     *                          wires would score there (the caller builds it on the board's own route requests, and
     *                          adds the air itself). Null: no judging, the proxy's best wins
     * @param prices            the router's prices (default {@link Prices#DEFAULT})
     * @param air               the air owed between strangers at these positions ({@link Air#term}), added to the
     *                          proxy score
     * @param onProgress        told where the search is (trials done, trials in all) every 250 trials, for a loader
     */
    public record Options(Integer rowGapCells, Integer sectionGapCells, Integer columnGapCells,
        Integer satellitePadCells, Integer trials, ToDoubleFunction<Map<String, Point>> judge, Prices prices,
        ToDoubleFunction<Map<String, Point>> air, BiConsumer<Integer, Integer> onProgress) {

        /** Every option at its default. */
        public static Options defaults() {
            return new Options(null, null, null, null, null, null, null, null, null);
        }

        /** The spacing, in cells (each may be null, for its default). */
        public Options withGaps(final Integer rowCells, final Integer sectionCells, final Integer columnCells,
            final Integer satelliteCells) {
            return new Options(
                rowCells,
                sectionCells,
                columnCells,
                satelliteCells,
                trials,
                judge,
                prices,
                air,
                onProgress);
        }

        public Options withTrials(final Integer count) {
            return new Options(
                rowGapCells,
                sectionGapCells,
                columnGapCells,
                satellitePadCells,
                count,
                judge,
                prices,
                air,
                onProgress);
        }

        public Options withJudge(final ToDoubleFunction<Map<String, Point>> points) {
            return new Options(
                rowGapCells,
                sectionGapCells,
                columnGapCells,
                satellitePadCells,
                trials,
                points,
                prices,
                air,
                onProgress);
        }

        public Options withPrices(final Prices dials) {
            return new Options(
                rowGapCells,
                sectionGapCells,
                columnGapCells,
                satellitePadCells,
                trials,
                judge,
                dials,
                air,
                onProgress);
        }

        public Options withAir(final ToDoubleFunction<Map<String, Point>> term) {
            return new Options(
                rowGapCells,
                sectionGapCells,
                columnGapCells,
                satellitePadCells,
                trials,
                judge,
                prices,
                term,
                onProgress);
        }

        public Options withOnProgress(final BiConsumer<Integer, Integer> progress) {
            return new Options(
                rowGapCells,
                sectionGapCells,
                columnGapCells,
                satellitePadCells,
                trials,
                judge,
                prices,
                air,
                progress);
        }
    }

    /**
     * What the judge saw of one finalist.
     *
     * @param score          its proxy score
     * @param proxyCrossings the crossings between its proxy paths
     * @param points         the judge's points
     */
    public record Finalist(double score, int proxyCrossings, double points) {}

    /**
     * The search's answer.
     *
     * @param positions every card's new top-left in px, in the input cards' order, normalised so the least x and the
     *                  least y are 0
     * @param before    the proxy score of the layered pass's layout
     * @param after     the proxy score of the search's best layout
     * @param points    the judge's points for the winner (null when nothing was judged)
     * @param finalists what the judge saw, in proxy score order (empty when nothing was judged)
     */
    public record Result(List<Point> positions, double before, double after, Double points, List<Finalist> finalists) {}

    /** Per px of the layout's bounding box (its width plus its height): tidy is compact. */
    private static final double SPRAWL = 0.4;
    /**
     * Whether the search may hop cards between columns (within what flow allows, see {@code mayStandIn}). Hops toward
     * partners shorten long wires: longest-path ranking spreads a board wide.
     */
    private static final boolean ALLOW_COLUMN_MOVES = true;
    /** The judge is asked only about islands with at most this many wires. */
    private static final int JUDGE_MAX_LINKS = 120;
    /** The steps the polish tries for column offsets, corridors and the air above cards, in cells. */
    private static final int[] POLISH_STEPS = { -1, 1, -2, 2, -4, 4 };
    /** The steps the polish tries for a satellite's slide, in cells. */
    private static final int[] SLIDE_STEPS = { -1, 1, -2, 2 };
    private static final int[] NONE = {};
    private static final Point ORIGIN = new Point(0, 0);

    private Optimize() {}

    /**
     * Rearranges one island to lower its proxy score, keeping its columns tidy, and lets the judge (if any) pick among
     * the best structurally different layouts the search found.
     */
    public static Result optimizeIslandLayout(final List<Card> cards, final List<ArrangeWire> wires,
        final Options options) {
        return new Search(cards, wires, options != null ? options : Options.defaults()).run();
    }

    /**
     * The proxy score of a finished layout (the same wire terms the search uses, plus the air), for choosing between
     * layouts when no router judge is at hand.
     *
     * @param positions every card's top-left by id (a card missing from it stands at 0, 0)
     * @param prices    the router's prices (null: {@link Prices#DEFAULT})
     * @param air       the air term (null: none)
     */
    public static double scoreLayoutProxy(final List<ArrangeCard> cards, final List<ArrangeWire> wires,
        final Map<String, Point> positions, final Prices prices, final ToDoubleFunction<Map<String, Point>> air) {
        final Prices dials = prices != null ? prices : Prices.DEFAULT;
        final int n = cards.size();
        final Map<String, Integer> index = new HashMap<>();
        final Proxy.Rect[] rects = new Proxy.Rect[n];
        for (int i = 0; i < n; i++) {
            final ArrangeCard card = cards.get(i);
            index.put(card.id(), i);
            final Point p = positions.get(card.id());
            rects[i] = Proxy.Rect.of(p != null ? p.x() : 0, p != null ? p.y() : 0, card.width(), card.height());
        }
        final List<double[]> paths = new ArrayList<>();
        final double[] weights = new double[wires.size()];
        double sum = 0;
        for (final ArrangeWire wire : wires) {
            final Integer a = index.get(wire.source()), b = index.get(wire.target());
            if (a == null || b == null || a.equals(b)) continue;
            final double[] path = Proxy.path(rects[a], rects[b]);
            final double weight = weightOf(wire);
            weights[paths.size()] = weight;
            paths.add(path);
            sum += (Proxy.length(path) + Proxy.bends(path, dials) + Proxy.blocked(path, rects, a, b, dials)) * weight;
        }
        for (int i = 0; i < paths.size(); i++) {
            for (int j = i + 1; j < paths.size(); j++) {
                sum += Proxy.crossings(paths.get(i), paths.get(j)) * dials.crossing()
                    * Math.max(weights[i], weights[j]);
            }
        }
        return sum + (air != null ? air.applyAsDouble(positions) : 0);
    }

    /**
     * A wire's weight: the router's own when its width is known, the caller's scale (flow, log-compressed) otherwise.
     */
    private static double weightOf(final ArrangeWire wire) {
        if (wire.width() != null) return Proxy.wireWeight(wire.width());
        return Math.max(wire.weight() != null ? wire.weight() : 1, 0.01);
    }

    /** The layout state the search permutes. */
    private static final class State {

        /** Card indices per column, top to bottom, the columns one after another. Satellites are not here. */
        final int[] order;
        /** How many cards each column holds. */
        final int[] size;
        /** Vertical offset of each column, in cells. */
        final int[] columnOffset;
        /** Extra corridor before each column, in cells: how islands part sideways. */
        final int[] columnPad;
        /** Extra air above each card, in cells. */
        final int[] padBefore;
        /** Satellite: offset of its top from its anchor's top, in cells. */
        final int[] satelliteOffset;

        State(final int columnCards, final int layers, final int cards) {
            order = new int[columnCards];
            size = new int[layers];
            columnOffset = new int[layers];
            columnPad = new int[layers];
            padBefore = new int[cards];
            satelliteOffset = new int[cards];
        }

        State copy() {
            final State copy = new State(order.length, size.length, padBefore.length);
            copyTo(copy);
            return copy;
        }

        void copyTo(final State to) {
            System.arraycopy(order, 0, to.order, 0, order.length);
            System.arraycopy(size, 0, to.size, 0, size.length);
            System.arraycopy(columnOffset, 0, to.columnOffset, 0, columnOffset.length);
            System.arraycopy(columnPad, 0, to.columnPad, 0, columnPad.length);
            System.arraycopy(padBefore, 0, to.padBefore, 0, padBefore.length);
            System.arraycopy(satelliteOffset, 0, to.satelliteOffset, 0, satelliteOffset.length);
        }

        /** Whether both stand the same cards in the same columns, in the same order. */
        boolean sameColumns(final State other) {
            return Arrays.equals(size, other.size) && Arrays.equals(order, other.order);
        }

        /** Where column {@code layer} starts in {@link #order}. */
        int start(final int layer) {
            int start = 0;
            for (int c = 0; c < layer; c++) start += size[c];
            return start;
        }

        int cardAt(final int layer, final int i) {
            return order[start(layer) + i];
        }

        void swap(final int layer, final int i, final int k) {
            final int start = start(layer);
            final int card = order[start + i];
            order[start + i] = order[start + k];
            order[start + k] = card;
        }

        /** Takes the {@code i}th card out of column {@code layer}; an {@link #insert} must follow. */
        void remove(final int layer, final int i) {
            final int at = start(layer) + i;
            System.arraycopy(order, at + 1, order, at, order.length - at - 1);
            size[layer]--;
        }

        /** Puts {@code card} into column {@code layer} as its {@code i}th card, after a {@link #remove}. */
        void insert(final int layer, final int i, final int card) {
            final int at = start(layer) + i;
            System.arraycopy(order, at, order, at + 1, order.length - at - 1);
            order[at] = card;
            size[layer]++;
        }

        /** The index of {@code card} in column {@code layer} (-1: not there). */
        int indexIn(final int layer, final int card) {
            final int start = start(layer);
            for (int i = 0; i < size[layer]; i++) if (order[start + i] == card) return i;
            return -1;
        }

        /** The column {@code card} stands in (-1: none). */
        int columnOf(final int card) {
            int p = 0;
            for (int layer = 0; layer < size.length; layer++) {
                final int end = p + size[layer];
                for (; p < end; p++) if (order[p] == card) return layer;
            }
            return -1;
        }
    }

    /** A finalist: the best-scoring state of one column arrangement. */
    private static final class Candidate {

        double score;
        final State state;

        Candidate(final double score, final State state) {
            this.score = score;
            this.state = state;
        }
    }

    /** Ascending score, as JavaScript sorts by {@code a.score - b.score}: ties (and NaN) keep their order. */
    private static final Comparator<Candidate> BY_SCORE = (a, b) -> {
        final double d = a.score - b.score;
        return d < 0 ? -1 : d > 0 ? 1 : 0;
    };

    /** One search over one island: the state, the placer, the incremental score and the moves. */
    private static final class Search {

        private final Options options;
        private final Prices prices;
        /** The air term (null: none). */
        private final ToDoubleFunction<Map<String, Point>> air;
        private final int n;
        private final String[] ids;
        private final double[] width, height, seq;
        private final boolean[] storage;
        private final Integer[] section;
        /** Each card's height in whole cells, rounded up. */
        private final int[] heightCells;

        /** The wires between two different cards of the island, as card indices, and each one's weight. */
        private final int linkCount;
        private final int[] linkA, linkB;
        private final double[] linkWeight;
        /** Each card's links, in link order. */
        private final int[][] linksOf;

        private final double rowGap, sectionGap, columnGap, satellitePad;

        /** Each satellite's anchor (-1: the card stands in a column). */
        private final int[] anchorOf;
        /** Each anchor's satellites in seq order (none for every other card). */
        private final int[][] satellitesOf;
        /** Every satellite, in card order. */
        private final int[] satellites;
        /** Whether a satellite rides its anchor's left side. */
        private final boolean[] leftSide;

        private final int layerCount;
        private final State state;
        /** The state before the move in hand, put back when the move is turned down. */
        private final State saved;
        /** Every column card, in the layered pass's columns and order. */
        private final int[] columnCards;

        /** Every card's top-left, as the placer derives it from the state. */
        private final double[] px, py;
        private final double[] leftPad, rightPad, widths, columnX;

        // Scoring, incremental: paths, lengths, bends, blocks and the pairwise crossings are kept, and a trial
        // re-scores only what its moved cards touched. The sprawl and air terms are cheap and global.
        private final Proxy.Rect[] rect;
        /** Where every card stood at the last score (NaN before the first). */
        private final double[] prevX, prevY;
        private final double[][] paths;
        /** Each link's proxy path's bounding box: min x, min y, max x, max y from [4 * l]. */
        private final double[] box;
        private final double[] lengthOf, bendsOf, blockedOf;
        /** Crossings between the proxy paths of links l and m, kept at [min * linkCount + max]. */
        private final byte[] pairCross;
        /** For each link l, how many links after it its proxy path crosses: the rows {@link #total} must read. */
        private final int[] crossedAfter;
        /** The cards that moved in the score in hand, and where each stood before. */
        private final int[] moved;
        private final Proxy.Rect[] movedFrom;
        /** The links the score in hand re-lays, and which of them have had their crossings counted. */
        private final int[] touchedList;
        private final boolean[] touched, counted;
        /** The cards near the path being priced. */
        private final Proxy.Rect[] near;
        private int lastProxyCrossings;

        /** Every card's top-left by id, kept current for the air term (null without one). */
        private final Map<String, Point> live, liveView;
        /** Whether a card is the last with its id, the one a map by id keeps. */
        private final boolean[] ownsId;

        private final List<Candidate> candidates = new ArrayList<>();
        private final int[] group;
        private final boolean[] inGroup;
        private Rng random;
        private double current, best;
        private State bestState;
        private boolean improved;

        Search(final List<Card> cards, final List<ArrangeWire> wires, final Options options) {
            this.options = options;
            prices = options.prices() != null ? options.prices() : Prices.DEFAULT;
            air = options.air();
            n = cards.size();
            ids = new String[n];
            width = new double[n];
            height = new double[n];
            seq = new double[n];
            storage = new boolean[n];
            section = new Integer[n];
            heightCells = new int[n];
            final int[] layerOf = new int[n];
            final Satellite[] riding = new Satellite[n];
            final Map<String, Integer> index = new HashMap<>();
            for (int i = 0; i < n; i++) {
                final Card card = cards.get(i);
                ids[i] = card.id();
                width[i] = card.width();
                height[i] = card.height();
                seq[i] = card.seq();
                storage[i] = card.storage();
                section[i] = card.section();
                heightCells[i] = (int) Math.ceil(card.height() / Grid.CELL);
                layerOf[i] = card.layer();
                riding[i] = card.satellite();
                index.put(card.id(), i);
            }

            final int[] a = new int[wires.size()], b = new int[wires.size()];
            final double[] weight = new double[wires.size()];
            int count = 0;
            for (final ArrangeWire wire : wires) {
                final Integer from = index.get(wire.source()), to = index.get(wire.target());
                if (from == null || to == null || from.equals(to)) continue;
                a[count] = from;
                b[count] = to;
                weight[count] = weightOf(wire);
                count++;
            }
            linkCount = count;
            linkA = Arrays.copyOf(a, count);
            linkB = Arrays.copyOf(b, count);
            linkWeight = Arrays.copyOf(weight, count);
            final int[] degree = new int[n];
            for (int l = 0; l < count; l++) {
                degree[linkA[l]]++;
                degree[linkB[l]]++;
            }
            linksOf = new int[n][];
            for (int i = 0; i < n; i++) linksOf[i] = new int[degree[i]];
            Arrays.fill(degree, 0);
            for (int l = 0; l < count; l++) {
                linksOf[linkA[l]][degree[linkA[l]]++] = l;
                linksOf[linkB[l]][degree[linkB[l]]++] = l;
            }

            final Integer rowCells = options.rowGapCells();
            final Integer sectionCells = options.sectionGapCells() != null ? options.sectionGapCells() : rowCells;
            rowGap = Grid.cells(rowCells != null ? rowCells : 2);
            sectionGap = Grid.cells(sectionCells != null ? sectionCells : 2);
            columnGap = Grid.cells(options.columnGapCells() != null ? options.columnGapCells() : 3);
            satellitePad = Grid.cells(options.satellitePadCells() != null ? options.satellitePadCells() : 2);

            // Satellites hang off anchors; everything else lives in the columns.
            anchorOf = new int[n];
            Arrays.fill(anchorOf, -1);
            leftSide = new boolean[n];
            final int[] riders = new int[n];
            int satelliteCount = 0;
            for (int i = 0; i < n; i++) {
                final Satellite satellite = riding[i];
                if (satellite == null) continue;
                final Integer anchor = index.get(satellite.anchorId());
                if (anchor == null || riding[anchor] != null) continue;
                anchorOf[i] = anchor;
                leftSide[i] = satellite.left();
                riders[anchor]++;
                satelliteCount++;
            }
            satellitesOf = new int[n][];
            for (int i = 0; i < n; i++) satellitesOf[i] = riders[i] == 0 ? NONE : new int[riders[i]];
            Arrays.fill(riders, 0);
            satellites = new int[satelliteCount];
            satelliteCount = 0;
            for (int i = 0; i < n; i++) {
                if (anchorOf[i] < 0) continue;
                satellites[satelliteCount++] = i;
                satellitesOf[anchorOf[i]][riders[anchorOf[i]]++] = i;
            }

            int maxLayer = 0;
            for (int i = 0; i < n; i++) maxLayer = Math.max(maxLayer, layerOf[i]);
            layerCount = maxLayer + 1;
            final int[] perLayer = new int[layerCount];
            int columnCount = 0;
            for (int i = 0; i < n; i++) {
                if (anchorOf[i] >= 0) continue;
                if (layerOf[i] < 0) {
                    throw new IllegalArgumentException("Card " + ids[i] + " stands in column " + layerOf[i]);
                }
                perLayer[layerOf[i]]++;
                columnCount++;
            }
            state = new State(columnCount, layerCount, n);
            System.arraycopy(perLayer, 0, state.size, 0, layerCount);
            final int[] fill = new int[layerCount];
            for (int layer = 1; layer < layerCount; layer++) fill[layer] = fill[layer - 1] + perLayer[layer - 1];
            for (int i = 0; i < n; i++) if (anchorOf[i] < 0) state.order[fill[layerOf[i]]++] = i;
            for (int layer = 0; layer < layerCount; layer++) {
                final int start = state.start(layer);
                sortBySeq(state.order, start, start + state.size[layer]);
            }
            // Satellites start stacked in seq order along their side.
            for (final int[] sats : satellitesOf) {
                if (sats.length == 0) continue;
                sortBySeq(sats, 0, sats.length);
                int offset = 0;
                for (final int sat : sats) {
                    state.satelliteOffset[sat] = offset;
                    offset += heightCells[sat] + 1;
                }
            }
            saved = state.copy();
            columnCards = state.order.clone();

            px = new double[n];
            py = new double[n];
            leftPad = new double[layerCount];
            rightPad = new double[layerCount];
            widths = new double[layerCount];
            columnX = new double[layerCount];

            rect = new Proxy.Rect[n];
            prevX = new double[n];
            prevY = new double[n];
            Arrays.fill(prevX, Double.NaN);
            Arrays.fill(prevY, Double.NaN);
            paths = new double[linkCount][];
            box = new double[4 * linkCount];
            lengthOf = new double[linkCount];
            bendsOf = new double[linkCount];
            blockedOf = new double[linkCount];
            pairCross = new byte[linkCount * linkCount];
            crossedAfter = new int[linkCount];
            moved = new int[n];
            movedFrom = new Proxy.Rect[n];
            touchedList = new int[linkCount];
            touched = new boolean[linkCount];
            counted = new boolean[linkCount];
            near = new Proxy.Rect[n];

            if (air != null) {
                live = new LinkedHashMap<>();
                ownsId = new boolean[n];
                for (int i = 0; i < n; i++) {
                    live.put(ids[i], ORIGIN);
                    ownsId[i] = index.get(ids[i]) == i;
                }
                liveView = Collections.unmodifiableMap(live);
            } else {
                live = null;
                liveView = null;
                ownsId = null;
            }
            group = new int[n];
            inGroup = new boolean[n];
        }

        /** Sorts {@code cards[from, to)} by seq, then by index, as the layered pass ordered them. */
        private void sortBySeq(final int[] cards, final int from, final int to) {
            final Integer[] boxed = new Integer[to - from];
            for (int i = from; i < to; i++) boxed[i - from] = cards[i];
            Arrays.sort(boxed, (x, y) -> {
                final double d = seq[x] - seq[y];
                return d < 0 ? -1 : d > 0 ? 1 : Integer.compare(x, y);
            });
            for (int i = from; i < to; i++) cards[i] = boxed[i - from];
        }

        Result run() {
            current = score();
            final double before = current;
            if (n < 2 || linkCount == 0) return new Result(normalise(), before, before, null, List.of());
            best = current;
            bestState = state.copy();
            // Finalists are structurally distinct: one per column arrangement (which card stands in which column, in
            // what order), the best-scoring state of each. Six near-copies of one layout differing by a nudge told the
            // router nothing; six different layouts give it a real choice.
            candidates.add(new Candidate(current, state.copy()));
            random = new Rng(Rng.hashIds(Arrays.asList(ids)));
            final int trials = options.trials() != null ? options.trials()
                : Math.min(prices.searchTrials(), 600 * n + 3000);
            if (columnCards.length == 0) return new Result(normalise(), before, before, null, List.of());
            anneal(trials);

            bestState.copyTo(state);
            current = score();
            polish();
            if (current < best - 1e-9) {
                best = current;
                state.copyTo(bestState);
                remember(current);
            }

            // The judge scores the finalists: fewest points wins.
            State winner = bestState;
            Double points = null;
            final List<Finalist> finalists = new ArrayList<>();
            final ToDoubleFunction<Map<String, Point>> judge = options.judge();
            if (judge != null && linkCount <= JUDGE_MAX_LINKS) {
                double bestReal = Double.POSITIVE_INFINITY, bestScore = Double.POSITIVE_INFINITY;
                for (final Candidate candidate : candidates) {
                    candidate.state.copyTo(state);
                    score();
                    final double real = judge.applyAsDouble(positionMap());
                    finalists.add(new Finalist(candidate.score, lastProxyCrossings, real));
                    if (real < bestReal || real == bestReal && candidate.score < bestScore) {
                        bestReal = real;
                        bestScore = candidate.score;
                        winner = candidate.state;
                    }
                }
                points = bestReal;
            }
            winner.copyTo(state);
            place();
            return new Result(normalise(), before, best, points, Collections.unmodifiableList(finalists));
        }

        /**
         * Simulated annealing: random moves, each kept when it lowers the score or, with a chance that cools from half
         * a crossing to 4 points over the trials, when it raises it. Every state kept is remembered for the finalists.
         */
        private void anneal(final int trials) {
            final double startTemperature = prices.crossing() / 2;
            final double endTemperature = 4;
            final BiConsumer<Integer, Integer> onProgress = options.onProgress();
            for (int trial = 0; trial < trials; trial++) {
                if (onProgress != null && trial % 250 == 0) onProgress.accept(trial, trials);
                final double temperature = startTemperature
                    * StrictMath.pow(endTemperature / startTemperature, (double) trial / trials);
                state.copyTo(saved);
                final double kind = random.next();
                boolean legal = true;
                if (kind < 0.25) {
                    // Swap two cards in one column.
                    final int layer = pick(layerCount);
                    final int size = state.size[layer];
                    if (size < 2) continue;
                    final int i = pick(size);
                    int k = pick(size);
                    if (k == i) k = (i + 1) % size;
                    state.swap(layer, i, k);
                } else if (kind < 0.4 && ALLOW_COLUMN_MOVES) {
                    // Move a card into any column flow allows.
                    final int layer = pick(layerCount);
                    final int size = state.size[layer];
                    if (size == 0) continue;
                    final int i = pick(size);
                    final int to = pick(layerCount);
                    if (to == layer) continue;
                    final int card = state.cardAt(layer, i);
                    if (!mayStandIn(card, layer, to)) continue;
                    state.remove(layer, i);
                    state.insert(to, pick(state.size[to] + 1), card);
                } else if (kind < 0.5) {
                    // Set a card down beside one of its partners: the partner's column, right above or below it.
                    final int card = columnCards[pick(columnCards.length)];
                    final int[] own = linksOf[card];
                    if (own.length == 0) continue;
                    final int link = own[pick(own.length)];
                    final int partner = linkA[link] == card ? linkB[link] : linkA[link];
                    final int who = anchorOf[partner] >= 0 ? anchorOf[partner] : partner;
                    if (who == card) continue;
                    final int from = state.columnOf(card);
                    final int to = state.columnOf(who);
                    if (from < 0 || to < 0) continue;
                    if (to != from && !mayStandIn(card, from, to)) continue;
                    state.remove(from, state.indexIn(from, card));
                    state.insert(to, state.indexIn(to, who) + (random.next() < 0.5 ? 0 : 1), card);
                } else if (kind < 0.62) {
                    // Nudge a column up or down.
                    final int layer = pick(layerCount);
                    final int reach = 1 + pick(6);
                    state.columnOffset[layer] += random.next() < 0.5 ? -reach : reach;
                } else if (kind < 0.7) {
                    // Widen or narrow the corridor before a column: islands part sideways.
                    final int layer = pick(layerCount);
                    final int reach = 1 + pick(4);
                    final int pad = state.columnPad[layer] + (random.next() < 0.5 ? -reach : reach);
                    state.columnPad[layer] = Math.max(0, pad);
                } else if (kind < 0.76) {
                    // Shift a card and its direct partners down (or up) together: a cluster moves as one instead of
                    // one card at a time.
                    final int card = columnCards[pick(columnCards.length)];
                    int members = 0;
                    group[members++] = card;
                    inGroup[card] = true;
                    for (final int link : linksOf[card]) {
                        final int other = linkA[link] == card ? linkB[link] : linkA[link];
                        final int member = anchorOf[other] >= 0 ? anchorOf[other] : other;
                        if (inGroup[member]) continue;
                        inGroup[member] = true;
                        group[members++] = member;
                    }
                    final int reach = 1 + pick(4);
                    final int step = reach * (random.next() < 0.5 ? -1 : 1);
                    for (int g = 0; g < members; g++) {
                        final int member = group[g];
                        inGroup[member] = false;
                        if (anchorOf[member] >= 0) continue;
                        state.padBefore[member] = Math.max(0, state.padBefore[member] + step);
                    }
                } else if (kind < 0.88) {
                    // More or less air above a card.
                    final int i = columnCards[pick(columnCards.length)];
                    final int reach = 1 + pick(4);
                    state.padBefore[i] = Math.max(0, state.padBefore[i] + (random.next() < 0.5 ? -reach : reach));
                } else {
                    // Slide a satellite along its machine's side.
                    if (satellites.length == 0) continue;
                    final int sat = satellites[pick(satellites.length)];
                    final int anchor = anchorOf[sat];
                    final int reach = 1 + pick(4);
                    final int limit = heightCells[anchor];
                    final int slid = state.satelliteOffset[sat] + (random.next() < 0.5 ? -reach : reach);
                    state.satelliteOffset[sat] = Math.max(-2, Math.min(limit, slid));
                    legal = satellitesLegal(anchor);
                }
                if (!legal) {
                    saved.copyTo(state);
                    continue;
                }
                final double next = score();
                final double delta = next - current;
                if (delta <= 0 || random.next() < StrictMath.exp(-delta / temperature)) {
                    current = next;
                    if (current < best - 1e-9) {
                        best = current;
                        state.copyTo(bestState);
                    }
                    remember(current);
                } else {
                    saved.copyTo(state);
                }
            }
        }

        /** A random index below {@code count}. */
        private int pick(final int count) {
            return (int) Math.floor(random.next() * count);
        }

        /**
         * The finisher: from the best state, every single-step move that helps is taken until none does. Annealing
         * gets close; this lands it, and it is what makes tight spacing come out tight every time.
         */
        private void polish() {
            for (int round = 0; round < 40; round++) {
                improved = false;
                for (int layer = 0; layer < layerCount; layer++) {
                    for (final int step : POLISH_STEPS) {
                        state.copyTo(saved);
                        state.columnOffset[layer] += step;
                        keepIfBetter();
                        final int pad = state.columnPad[layer] + step;
                        if (pad >= 0) {
                            state.copyTo(saved);
                            state.columnPad[layer] = pad;
                            keepIfBetter();
                        }
                    }
                    // Swap neighbours down the column. As on the website, the first swap turned down ends the column's
                    // swaps for the round: there the loop holds the column it started with, a turned-down move puts a
                    // fresh copy in its place, and the swaps after it change nothing.
                    for (int i = 0; i + 1 < state.size[layer]; i++) {
                        state.copyTo(saved);
                        state.swap(layer, i, i + 1);
                        if (!keepIfBetter()) break;
                    }
                }
                for (final int i : columnCards) {
                    for (final int step : POLISH_STEPS) {
                        final int pad = state.padBefore[i] + step;
                        if (pad < 0) continue;
                        state.copyTo(saved);
                        state.padBefore[i] = pad;
                        keepIfBetter();
                    }
                }
                for (int i = 0; i < n; i++) {
                    if (anchorOf[i] < 0) continue;
                    for (final int step : SLIDE_STEPS) {
                        state.copyTo(saved);
                        state.satelliteOffset[i] += step;
                        if (satellitesLegal(anchorOf[i])) keepIfBetter();
                        else saved.copyTo(state);
                    }
                }
                if (!improved) break;
            }
        }

        /** Scores the move just made: keeps it when it helps, else puts the saved state back. */
        private boolean keepIfBetter() {
            final double next = score();
            if (next < current - 1e-9) {
                current = next;
                improved = true;
                return true;
            }
            saved.copyTo(state);
            return false;
        }

        /**
         * Remembers the state as a finalist: the best state of its column arrangement, among the best few
         * arrangements.
         */
        private void remember(final double value) {
            Candidate known = null;
            for (final Candidate candidate : candidates) {
                if (candidate.state.sameColumns(state)) {
                    known = candidate;
                    break;
                }
            }
            if (known != null) {
                if (value < known.score - 1e-9) {
                    known.score = value;
                    state.copyTo(known.state);
                    candidates.sort(BY_SCORE);
                }
                return;
            }
            candidates.add(new Candidate(value, state.copy()));
            candidates.sort(BY_SCORE);
            if (candidates.size() > Math.max(1, prices.finalists())) candidates.remove(candidates.size() - 1);
        }

        /** Satellites on one side must not overlap each other. */
        private boolean satellitesLegal(final int anchor) {
            final int[] sats = satellitesOf[anchor];
            for (int i = 0; i < sats.length; i++) {
                for (int k = i + 1; k < sats.length; k++) {
                    final int a = sats[i], b = sats[k];
                    if (leftSide[a] != leftSide[b]) continue;
                    final int aTop = state.satelliteOffset[a], aBottom = aTop + heightCells[a];
                    final int bTop = state.satelliteOffset[b], bBottom = bTop + heightCells[b];
                    if (aTop < bBottom + 1 && bTop < aBottom + 1) return false;
                }
            }
            return true;
        }

        /** Which column a card (or, for a satellite, its anchor) stands in now (-1: none). */
        private int layerOfCard(final int i) {
            return state.columnOf(anchorOf[i] >= 0 ? anchorOf[i] : i);
        }

        /**
         * May {@code card}, standing in {@code from}, stand in column {@code to}? A machine keeps flow reading left to
         * right: every feeder in an earlier column, every taker in a later one (a wire already running backwards does
         * not constrain). A drawer may stand anywhere between its first and last partner's column, inclusive, so it
         * can sit between two machines stacked in one column.
         */
        private boolean mayStandIn(final int card, final int from, final int to) {
            if (storage[card]) {
                int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
                for (final int link : linksOf[card]) {
                    final int other = linkA[link] == card ? linkB[link] : linkA[link];
                    final int otherLayer = layerOfCard(other);
                    if (otherLayer < 0) continue;
                    lo = Math.min(lo, otherLayer);
                    hi = Math.max(hi, otherLayer);
                }
                return lo == Integer.MAX_VALUE || to >= lo && to <= hi;
            }
            for (final int link : linksOf[card]) {
                final boolean feeds = linkA[link] == card;
                final int other = feeds ? linkB[link] : linkA[link];
                final int otherLayer = layerOfCard(other);
                if (otherLayer < 0) continue;
                // A machine may share a column with a drawer it trades with but never pass beyond it; it may not even
                // draw level with a partner machine.
                final int reach = storage[other] ? 0 : 1;
                if (feeds) {
                    if (otherLayer > from && otherLayer < to + reach) return false;
                } else if (otherLayer < from && otherLayer > to - reach) {
                    return false;
                }
            }
            return true;
        }

        /** Derives every card's top-left from the state. */
        private void place() {
            // Column widths include the satellites riding on either side.
            Arrays.fill(leftPad, 0);
            Arrays.fill(rightPad, 0);
            Arrays.fill(widths, 0);
            int p = 0;
            for (int layer = 0; layer < layerCount; layer++) {
                for (final int end = p + state.size[layer]; p < end; p++) {
                    final int i = state.order[p];
                    widths[layer] = Math.max(widths[layer], width[i]);
                    for (final int sat : satellitesOf[i]) {
                        final double need = width[sat] + satellitePad;
                        if (leftSide[sat]) leftPad[layer] = Math.max(leftPad[layer], need);
                        else rightPad[layer] = Math.max(rightPad[layer], need);
                    }
                }
            }
            double x = 0;
            for (int layer = 0; layer < layerCount; layer++) {
                x += leftPad[layer] + state.columnPad[layer] * Grid.CELL;
                columnX[layer] = x;
                x += widths[layer] + rightPad[layer] + columnGap;
            }
            p = 0;
            for (int layer = 0; layer < layerCount; layer++) {
                double y = state.columnOffset[layer] * Grid.CELL;
                int previous = -1;
                for (final int end = p + state.size[layer]; p < end; p++) {
                    final int i = state.order[p];
                    if (previous >= 0 && !Objects.equals(section[previous], section[i])) y += sectionGap - rowGap;
                    previous = i;
                    y += state.padBefore[i] * Grid.CELL;
                    // A machine with satellites on a side needs room above for the ones that ride higher than it.
                    int rise = 0;
                    for (final int sat : satellitesOf[i]) {
                        rise = Math.max(rise, -state.satelliteOffset[sat] * Grid.CELL);
                    }
                    y += rise;
                    px[i] = Grid.snap(columnX[layer] + (widths[layer] - width[i]) / 2);
                    py[i] = Grid.snap(y);
                    double bottom = y + height[i];
                    for (final int sat : satellitesOf[i]) {
                        final double beside = leftSide[sat] ? px[i] - satellitePad - width[sat]
                            : px[i] + width[i] + satellitePad;
                        px[sat] = Grid.snap(beside);
                        py[sat] = Grid.snap(y + state.satelliteOffset[sat] * Grid.CELL);
                        bottom = Math.max(bottom, py[sat] + height[sat]);
                    }
                    y = bottom + rowGap;
                }
            }
        }

        /**
         * Scores the current state; only what moved since the last call is redone.
         *
         * <p>
         * Work whose answer is known is skipped, which changes no score by so much as a rounding: two paths whose
         * bounding boxes stand apart do not cross, a card clear of a path's bounding box adds no detour to it, and a
         * wire that did not move keeps its detour unless a moved card stood, or now stands, near its path.
         */
        private double score() {
            place();
            int movedCount = 0;
            for (int i = 0; i < n; i++) {
                if (px[i] == prevX[i] && py[i] == prevY[i]) continue;
                movedFrom[movedCount] = rect[i];
                moved[movedCount++] = i;
                prevX[i] = px[i];
                prevY[i] = py[i];
                rect[i] = Proxy.Rect.of(px[i], py[i], width[i], height[i]);
                if (live != null && ownsId[i]) live.put(ids[i], new Point(px[i], py[i]));
            }
            if (movedCount == 0) return total();
            int touchedCount = 0;
            for (int k = 0; k < movedCount; k++) {
                for (final int l : linksOf[moved[k]]) {
                    if (touched[l]) continue;
                    touched[l] = true;
                    touchedList[touchedCount++] = l;
                }
            }
            for (int t = 0; t < touchedCount; t++) scoreLink(touchedList[t]);
            for (int t = 0; t < touchedCount; t++) crossRow(touchedList[t]);
            // A moved card may block, or unblock, wires that never touch it.
            if (movedCount < n) {
                final Proxy.Rect reach = movedReach(movedCount);
                for (int l = 0; l < linkCount; l++) {
                    if (touched[l] || clear(l, reach) || !nearMoved(l, movedCount)) continue;
                    blockedOf[l] = blocked(l);
                }
            }
            for (int t = 0; t < touchedCount; t++) {
                touched[touchedList[t]] = false;
                counted[touchedList[t]] = false;
            }
            return total();
        }

        /** Lays link {@code l}'s proxy path and prices its length, bends and the cards it runs through. */
        private void scoreLink(final int l) {
            final double[] path = Proxy.path(rect[linkA[l]], rect[linkB[l]]);
            paths[l] = path;
            double minX = path[0], minY = path[1], maxX = path[0], maxY = path[1];
            for (int i = 2; i < path.length; i += 2) {
                minX = Math.min(minX, path[i]);
                minY = Math.min(minY, path[i + 1]);
                maxX = Math.max(maxX, path[i]);
                maxY = Math.max(maxY, path[i + 1]);
            }
            box[4 * l] = minX;
            box[4 * l + 1] = minY;
            box[4 * l + 2] = maxX;
            box[4 * l + 3] = maxY;
            lengthOf[l] = Proxy.length(path) * linkWeight[l];
            bendsOf[l] = Proxy.bends(path, prices) * linkWeight[l];
            blockedOf[l] = blocked(l);
        }

        /**
         * {@link Proxy#blocked} for link {@code l}, asked only about the cards near its path: the others add nothing,
         * so the sum is the same additions in the same order.
         */
        private double blocked(final int l) {
            int count = 0;
            for (int r = 0; r < n; r++) {
                if (r != linkA[l] && r != linkB[l] && !clear(l, rect[r])) near[count++] = rect[r];
            }
            final double cost = count == 0 ? 0 : Proxy.blocked(paths[l], Arrays.copyOf(near, count), -1, -1, prices);
            return cost * linkWeight[l];
        }

        /**
         * Whether link {@code l}'s path stays clear of the margin {@link Proxy#blocked} keeps around {@code card}: its
         * bounding box stands more than a px outside it. NaN anywhere answers no.
         */
        private boolean clear(final int l, final Proxy.Rect card) {
            final int at = 4 * l;
            return box[at + 2] < card.left() - Grid.CELL - 1 || box[at] > card.right() + Grid.CELL + 1
                || box[at + 3] < card.top() - Grid.CELL - 1
                || box[at + 1] > card.bottom() + Grid.CELL + 1;
        }

        /** The box around every moved card, where it stood and where it stands. */
        private Proxy.Rect movedReach(final int movedCount) {
            double left = Double.POSITIVE_INFINITY, top = Double.POSITIVE_INFINITY;
            double right = Double.NEGATIVE_INFINITY, bottom = Double.NEGATIVE_INFINITY;
            for (int k = 0; k < movedCount; k++) {
                final Proxy.Rect from = movedFrom[k], to = rect[moved[k]];
                if (from == null) return new Proxy.Rect(Double.NaN, Double.NaN, Double.NaN, Double.NaN);
                left = Math.min(left, Math.min(from.left(), to.left()));
                top = Math.min(top, Math.min(from.top(), to.top()));
                right = Math.max(right, Math.max(from.right(), to.right()));
                bottom = Math.max(bottom, Math.max(from.bottom(), to.bottom()));
            }
            return new Proxy.Rect(left, top, right, bottom);
        }

        /** Whether a moved card stood, or now stands, near link {@code l}'s path. */
        private boolean nearMoved(final int l, final int movedCount) {
            for (int k = 0; k < movedCount; k++) {
                final Proxy.Rect from = movedFrom[k];
                if (from == null || !clear(l, from) || !clear(l, rect[moved[k]])) return true;
            }
            return false;
        }

        /**
         * Counts the crossings between link {@code l}'s proxy path and every other's. A pair of links both re-laid in
         * this score is counted once (crossings count the same either way round).
         */
        private void crossRow(final int l) {
            final double[] path = paths[l];
            final int at = 4 * l;
            final double minX = box[at] - 1, minY = box[at + 1] - 1, maxX = box[at + 2] + 1, maxY = box[at + 3] + 1;
            for (int m = 0; m < linkCount; m++) {
                if (m == l || counted[m]) continue;
                final int other = 4 * m;
                final boolean apart = box[other + 2] < minX || box[other] > maxX
                    || box[other + 3] < minY
                    || box[other + 1] > maxY;
                final int lo = Math.min(l, m), cell = lo * linkCount + Math.max(l, m);
                final int was = pairCross[cell], count = apart ? 0 : Proxy.crossings(path, paths[m]);
                if (count == was) continue;
                pairCross[cell] = (byte) count;
                if (was == 0) crossedAfter[lo]++;
                else if (count == 0) crossedAfter[lo]--;
            }
            counted[l] = true;
        }

        /** The sprawl and the air: cheap, and over the whole island. */
        private double globalTerms() {
            double sum = 0;
            double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
            double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < n; i++) {
                final Proxy.Rect r = rect[i];
                minX = Math.min(minX, r.left());
                minY = Math.min(minY, r.top());
                maxX = Math.max(maxX, r.right());
                maxY = Math.max(maxY, r.bottom());
            }
            sum += (maxX - minX + (maxY - minY)) * SPRAWL;
            // The air owed between strangers: what makes islands (Air).
            if (air != null) sum += air.applyAsDouble(liveView);
            return sum;
        }

        /** The score from the kept terms; also counts the proxy crossings. */
        private double total() {
            double sum = globalTerms();
            int crossings = 0;
            for (int l = 0; l < linkCount; l++) {
                sum += lengthOf[l] + bendsOf[l] + blockedOf[l];
                // Only the links this one crosses add anything, so the row ends with the last of them.
                final int row = l * linkCount;
                for (int m = l + 1, left = crossedAfter[l]; left > 0; m++) {
                    final int count = pairCross[row + m];
                    if (count == 0) continue;
                    left--;
                    crossings += count;
                    // A crossing weighs the heavier of its two wires, as in the points.
                    sum += count * prices.crossing() * Math.max(linkWeight[l], linkWeight[m]);
                }
            }
            lastProxyCrossings = crossings;
            return sum;
        }

        /** A fresh map of every card's top-left by id, for the judge. */
        private Map<String, Point> positionMap() {
            final Map<String, Point> map = new LinkedHashMap<>();
            for (int i = 0; i < n; i++) map.put(ids[i], new Point(px[i], py[i]));
            return map;
        }

        /** Every card's top-left, moved so the least x and the least y are 0. */
        private List<Point> normalise() {
            double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
            for (int i = 0; i < n; i++) {
                minX = Math.min(minX, px[i]);
                minY = Math.min(minY, py[i]);
            }
            final List<Point> out = new ArrayList<>(n);
            for (int i = 0; i < n; i++) out.add(new Point(px[i] - minX, py[i] - minY));
            return Collections.unmodifiableList(out);
        }
    }
}

package com.gtnhplanner.layout.arrange;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.ToDoubleFunction;

import com.gtnhplanner.layout.arrange.Proxy.Rect;

/**
 * Free placement: the arrange's third candidate, with no layers or bands. As Factory Flow's board-arrange-free.ts.
 * Deterministic, in three stages:
 * <ol>
 * <li>Stress. Every pair of cards gets an ideal distance (the shortest wire path, each hop worth the two cards' sizes
 * plus a gap), and stress SGD (Zheng, Pawar and Goodman, 2018) finds continuous positions matching them: partners
 * touch, cards ten hops apart stand ten cards apart.</li>
 * <li>The grid. Overlaps are pushed apart along their least penetration, then cards are set on the cell grid one at a
 * time, nearest the centre first, each on the free spot closest to its stress position.</li>
 * <li>The search. Simulated annealing over free moves (beside a partner with port rows aligned, a nudge, a swap, a
 * machine with its drawers, a whole side of a bridge wire), scored in the router's points ({@link Proxy}) plus
 * stranger air ({@link Air}) and the {@link Dials} terms. Scoring is incremental: a trial re-prices only the wires the
 * moved cards touch or cross.</li>
 * </ol>
 * <p>
 * Machines stand in columns at a pitch of one machine width plus a drawer corridor; rows are any cell. The search runs
 * twice, like a chip placer: free to any cell first (it finds the structure), then machines are legalised onto columns
 * and a cooler search repairs the damage. Annealing on the lattice from the start does worse: a column hop is too big
 * a step.
 * <p>
 * Drawers are placed by pattern, not searched: a drawer wired to one machine is its bud and stands in a touching line
 * on its side (supplies left, products right, in port order, centred on the ports); a drawer wired to exactly two
 * machines stands in a line between them (the corridor when they stand side by side, a row in the gap when stacked).
 * The search moves machines (and drawers with three or more partners) and the lines follow.
 * <p>
 * Wired components are laid out one at a time and packed side by side; unwired cards go on a shelf underneath.
 * <p>
 * The arithmetic is the website's, operation for operation: JavaScript's rounding, insertion-ordered maps and sets,
 * stable sorts with the same tie-breaks, the same seeded random stream, fdlibm ({@link StrictMath}, which V8 ports) for
 * exp, log, sin and cos, and V8's own hypot in the stress stage, so a board arranges as it does on the website. The
 * answer depends only on the board, never on where the cards stand or on the JVM, so two players with one plan get
 * one arrangement.
 */
public final class FreeArrange {

    /** Air between two wired components, and between them and the shelf, in cells. */
    private static final int COMPONENT_GAP_CELLS = 8;
    /** Stress SGD iterations. */
    private static final int STRESS_ITERATIONS = 40;
    /** How far a neighbour may be, beyond the edges, and still be aligned with. */
    private static final double TIDY_REACH = Grid.cells(12);
    /** Annealing trials for the whole board when the options name none. */
    private static final int DEFAULT_TRIALS = 20000;
    /** The least air between any two cards, in cells, when the options name none. */
    private static final int DEFAULT_GAP_CELLS = 1;
    /** The air beside a partner, in cells, when the options name none. */
    private static final int DEFAULT_BESIDE_CELLS = 2;
    private static final int[] NONE = {};

    /**
     * How to arrange; every field may be null for the website's default.
     *
     * @param prices      the router's prices, the score's currency (null: {@link Prices#DEFAULT})
     * @param trials      annealing trials for the whole board, split across the components by size (null: 20000)
     * @param gapCells    the least air between any two cards, in cells: the taste's row gap (null: 1)
     * @param besideCells the air a card keeps from a partner it is set beside, in cells: the taste's column gap
     *                    (null: 2)
     * @param onProgress  told (trials done, trials in all) as the search runs (null: nobody)
     */
    public record Options(Prices prices, Integer trials, Integer gapCells, Integer besideCells,
        BiConsumer<Integer, Integer> onProgress) {}

    /** A wired component's box (with a backdrop), or the shelf of unwired cards (without one, always last). */
    public record Island(double x, double y, double width, double height, boolean backdrop) {}

    /**
     * The arrangement.
     *
     * @param positions every card's new top-left by id, the bounding box starting at 0,0
     * @param islands   each wired component's box, the shelf of unwired cards last
     */
    public record Result(Map<String, Point> positions, List<Island> islands) {}

    /**
     * The readability terms the router does not price, in one record so a harness can sweep them (the website's
     * FREE_DIALS).
     *
     * @param tidy            points per px a card's edge misses lining up with a neighbour's: stacked cards want a
     *                        shared left or right edge, side-by-side cards a shared top or bottom or port row. Rows and
     *                        columns emerge because agreeing on edges is cheaper.
     * @param tidyCapCells    the most one axis of a card's tidiness can cost, in cells
     * @param flow            points per px a wire's target centre falls short of one card width right of its source's
     *                        (outputs leave right, inputs enter left)
     * @param flowFlat        the flat price a backward wire also pays: without it a chain folded into a triangle and a
     *                        fan-out ringed its hub, because those wires were shorter
     * @param flowFlatColumns the flat backward price when both ends are column cards
     * @param flowFlatCycle   the flat backward price of a wire inside a cycle (both ends in one strongly connected
     *                        component), since a recycle loop must run backwards somewhere
     * @param sprawl          points per px of the layout's bounding-box perimeter
     */
    public record Dials(double tidy, double tidyCapCells, double flow, double flowFlat, double flowFlatColumns,
        double flowFlatCycle, double sprawl) {

        /** The website's dials. */
        public static final Dials DEFAULT = new Dials(1, 4, 0.35, 400, 1500, 150, 0.4);
    }

    /**
     * Every term of the free placement's score for a layout (see {@link #explain}), in the router's points.
     *
     * @param own       the wires' lengths, bends and the cards they run through, each wire times its weight
     * @param flow      the wires' flow prices (see {@link Dials#flow}), times their weights
     * @param crossings each crossing at the crossing price, times the heavier wire's weight
     * @param air       stranger air ({@link Air})
     * @param sprawl    the bounding box's perimeter price
     * @param tidy      every card's tidiness
     * @param total     the sum, the score the search lowers
     */
    public record Explanation(double own, double flow, double crossings, double air, double sprawl, double tidy,
        double total) {}

    private FreeArrange() {}

    /** Lays the board out with the website's dials. */
    public static Result arrange(final List<ArrangeCard> cards, final List<ArrangeWire> wires, final Options options) {
        return arrange(cards, wires, options, Dials.DEFAULT);
    }

    /**
     * Lays the board out: every card's new top-left, normalised so the bounding box starts at 0,0, and the islands.
     * Where the cards stand today is never read. {@code dials} null: {@link Dials#DEFAULT}.
     */
    public static Result arrange(final List<ArrangeCard> cards, final List<ArrangeWire> wires, final Options options,
        final Dials dials) {
        final Options o = options != null ? options : new Options(null, null, null, null, null);
        final Settings settings = new Settings(
            o.prices() != null ? o.prices() : Prices.DEFAULT,
            dials != null ? dials : Dials.DEFAULT,
            Grid.cells(o.gapCells() != null ? o.gapCells() : DEFAULT_GAP_CELLS),
            Grid.cells(o.besideCells() != null ? o.besideCells() : DEFAULT_BESIDE_CELLS));
        final int trials = o.trials() != null ? o.trials() : DEFAULT_TRIALS;
        final BiConsumer<Integer, Integer> onProgress = o.onProgress();
        final int count = cards.size();
        final Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < count; i++) index.put(
            cards.get(i)
                .id(),
            i);
        final List<List<Integer>> adjacency = new ArrayList<>(count);
        for (int i = 0; i < count; i++) adjacency.add(new ArrayList<>());
        final List<Link> usable = new ArrayList<>();
        for (final ArrangeWire wire : wires) {
            final Integer a = index.get(wire.source()), b = index.get(wire.target());
            if (a == null || b == null || a.equals(b)) continue;
            adjacency.get(a)
                .add(b);
            adjacency.get(b)
                .add(a);
            usable.add(new Link(a, b, wire));
        }
        // Components: wired webs, and the shelf of cards with no wires at all.
        final int[] component = new int[count];
        Arrays.fill(component, -1);
        final List<int[]> components = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            if (component[i] >= 0 || adjacency.get(i)
                .isEmpty()) continue;
            final List<Integer> members = new ArrayList<>();
            members.add(i);
            component[i] = components.size();
            for (int head = 0; head < members.size(); head++) {
                for (final int next : adjacency.get(members.get(head))) {
                    if (component[next] < 0) {
                        component[next] = components.size();
                        members.add(next);
                    }
                }
            }
            components.add(toArray(members));
        }
        final List<Integer> shelf = new ArrayList<>();
        for (int i = 0; i < count; i++) if (component[i] < 0) shelf.add(i);
        // Bigger components first: they set the row, and they get the trials.
        components
            .sort((a, b) -> a.length != b.length ? Integer.compare(b.length, a.length) : Integer.compare(a[0], b[0]));
        int wired = 0;
        for (final int[] members : components) wired += members.length;
        final int totalCards = wired > 0 ? wired : 1;
        int progressDone = 0;
        final List<Block> blocks = new ArrayList<>();
        final int[] localIndex = new int[count];
        for (final int[] members : components) {
            final List<ArrangeCard> local = new ArrayList<>(members.length);
            for (int k = 0; k < members.length; k++) {
                local.add(cards.get(members[k]));
                localIndex[members[k]] = k;
            }
            final int id = component[members[0]];
            final List<Link> localWires = new ArrayList<>();
            for (final Link link : usable) {
                if (component[link.a()] == id)
                    localWires.add(new Link(localIndex[link.a()], localIndex[link.b()], link.wire()));
            }
            final int share = (int) Math.max(200, jsRound((double) trials * members.length / totalCards));
            final int offset = progressDone;
            final Point[] positions = new Component(settings, local, localWires.toArray(new Link[0]))
                .layout(share, done -> { if (onProgress != null) onProgress.accept(offset + done, trials); });
            progressDone += share;
            double left = Double.POSITIVE_INFINITY, top = Double.POSITIVE_INFINITY;
            for (final Point p : positions) {
                left = Math.min(left, p.x());
                top = Math.min(top, p.y());
            }
            final Point[] normalised = new Point[positions.length];
            for (int k = 0; k < positions.length; k++)
                normalised[k] = new Point(positions[k].x() - left, positions[k].y() - top);
            double width = Double.NEGATIVE_INFINITY, height = Double.NEGATIVE_INFINITY;
            for (int k = 0; k < normalised.length; k++) {
                width = Math.max(
                    width,
                    normalised[k].x() + local.get(k)
                        .width());
                height = Math.max(
                    height,
                    normalised[k].y() + local.get(k)
                        .height());
            }
            blocks.add(new Block(members, normalised, width, height));
        }
        // Pack the components in a row, wrapping like text past a wide page.
        final Map<String, Point> out = new LinkedHashMap<>();
        final List<Island> islands = new ArrayList<>();
        final double gap = Grid.cells(COMPONENT_GAP_CELLS);
        double pageWidth = Grid.cells(240);
        for (final Block block : blocks) pageWidth = Math.max(pageWidth, block.width());
        double cursorX = 0, cursorY = 0, rowHeight = 0;
        for (final Block block : blocks) {
            if (cursorX > 0 && cursorX + block.width() > pageWidth) {
                cursorX = 0;
                cursorY += rowHeight + gap;
                rowHeight = 0;
            }
            for (int k = 0; k < block.ids().length; k++) {
                out.put(
                    cards.get(block.ids()[k])
                        .id(),
                    new Point(cursorX + block.positions()[k].x(), cursorY + block.positions()[k].y()));
            }
            islands.add(new Island(cursorX, cursorY, block.width(), block.height(), true));
            cursorX += block.width() + gap;
            rowHeight = Math.max(rowHeight, block.height());
        }
        // The shelf: unwired cards in a row underneath, in their input order.
        if (!shelf.isEmpty()) {
            double x = 0;
            final double y = !blocks.isEmpty() ? cursorY + rowHeight + gap : 0;
            double height = 0;
            for (final int i : shelf) {
                final ArrangeCard card = cards.get(i);
                out.put(card.id(), new Point(x, y));
                x += card.width() + Grid.cells(2);
                height = Math.max(height, card.height());
            }
            islands.add(new Island(0, y, Math.max(0, x - Grid.cells(2)), height, false));
        }
        return new Result(out, islands);
    }

    /**
     * Every term of the free placement's score for a layout, priced the way the search prices it, for tuning by hand.
     * The wires and the tidiness are taken across the whole board; a card missing from {@code positions} stands at
     * 0,0 (but is skipped by the air term). {@code gapCells} is the arrange's least air (the website prices with
     * whatever its last arrange used, 1 by default); null prices or dials are the defaults.
     */
    public static Explanation explain(final List<ArrangeCard> cards, final List<ArrangeWire> wires,
        final Map<String, Point> positions, final Prices prices, final int gapCells, final Dials dials) {
        final Prices p = prices != null ? prices : Prices.DEFAULT;
        final Settings settings = new Settings(
            p,
            dials != null ? dials : Dials.DEFAULT,
            Grid.cells(gapCells),
            Grid.cells(DEFAULT_BESIDE_CELLS));
        final Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < cards.size(); i++) index.put(
            cards.get(i)
                .id(),
            i);
        final List<Link> local = new ArrayList<>();
        for (final ArrangeWire wire : wires) {
            final Integer a = index.get(wire.source()), b = index.get(wire.target());
            if (a == null || b == null || a.equals(b)) continue;
            local.add(new Link(a, b, wire));
        }
        final Component board = new Component(settings, cards, local.toArray(new Link[0]));
        final Rect[] rects = new Rect[board.n];
        for (int i = 0; i < board.n; i++) {
            final Point at = positions.get(board.ids[i]);
            final double x = at != null ? at.x() : 0, y = at != null ? at.y() : 0;
            rects[i] = new Rect(x, y, x + board.w[i], y + board.h[i]);
        }
        final double[] flats = board.flatPrices(board.columnLattice());
        final double[][] paths = new double[board.m][];
        for (int wi = 0; wi < board.m; wi++) paths[wi] = Proxy.path(rects[board.wa[wi]], rects[board.wb[wi]]);
        double own = 0, flow = 0;
        for (int wi = 0; wi < board.m; wi++) {
            final int a = board.wa[wi], b = board.wb[wi];
            own += (Proxy.length(paths[wi]) + Proxy.bends(paths[wi], p) + Proxy.blocked(paths[wi], rects, a, b, p))
                * board.weights[wi];
            flow += board.flowAt(rects[a], rects[b], flats[wi]) * board.weights[wi];
        }
        double crossings = 0;
        for (int i = 0; i < board.m; i++) {
            for (int k = i + 1; k < board.m; k++) {
                crossings += Proxy.crossings(paths[i], paths[k]) * p.crossing()
                    * Math.max(board.weights[i], board.weights[k]);
            }
        }
        final double air = Air.term(cards, wires, p.islandAir())
            .applyAsDouble(positions);
        final PortRows rows = board.portRows();
        double tidy = 0;
        for (int k = 0; k < board.n; k++) tidy += board.tidyAt(k, rects, rows);
        final double sprawl = board.sprawlOf(rects);
        return new Explanation(own, flow, crossings, air, sprawl, tidy, own + flow + crossings + air + sprawl + tidy);
    }

    /* ---------------------------------------------------------------------------------------------------------- */
    /* The pieces. */
    /* ---------------------------------------------------------------------------------------------------------- */

    /** A usable wire, its two ends as indices into the cards being laid out. */
    private record Link(int a, int b, ArrangeWire wire) {}

    /** One call's settings: the least air ({@code gap}) and the air beside a partner, in px. */
    private record Settings(Prices prices, Dials dials, double gap, double beside) {}

    /** A component laid out on its own: its members (board indices) and their top-lefts, its box from 0,0. */
    private record Block(int[] ids, Point[] positions, double width, double height) {}

    /** A trial move: these cards to these top-lefts. */
    private record Move(int[] cards, Point[] to) {}

    /**
     * What a card may stand on. On the column lattice, machines' left edges sit on multiples of the pitch: the widest
     * common machine width plus a corridor for the widest drawer with a cell of air each side (two cells of air when
     * there are no drawers at all). Column cards are the wide ones, by size rather than by role: a small tile with no
     * role stamped on it still lives in the corridors. Off the lattice every card is a cell card and the pitch is a
     * cell.
     */
    private record Lattice(double pitch, boolean[] column) {

        /** A card's sideways step: a column, or a cell. */
        double stepX(final int i) {
            return column[i] ? pitch : Grid.CELL;
        }
    }

    /** How a drawer is placed by pattern. */
    private sealed interface Placement permits Bud,Pair {
    }

    /**
     * A drawer wired to one machine: it stands in the line on that machine's side ({@code right} for products, left
     * for supplies), ordered by {@code port}, its mean port row on the machine.
     */
    private record Bud(int machine, boolean right, double port) implements Placement {}

    /** A drawer wired to exactly two machines, standing between them; the ports are its mean rows on each. */
    private record Pair(int a, int b, double portA, double portB) implements Placement {}

    /** A drawer in a line or row, ordered by its port row. */
    private record Member(int d, double key) {}

    private static final Comparator<Member> BY_KEY = (p, q) -> {
        final int byKey = Double.compare(p.key(), q.key());
        return byKey != 0 ? byKey : Integer.compare(p.d(), q.d());
    };

    /** The drawer patterns of one component (header: drawers are placed by pattern). */
    private static final class Patterns {

        /** How each card is placed; null for a card the search moves. */
        final Placement[] placement;
        /** The derived drawers attached to each machine (its buds, its shared drawers). */
        final int[][] attached;
        /** The machines each machine shares a drawer with. */
        final int[][] pairPartners;

        /** No patterns: every card is moved by the search. */
        Patterns(final int n) {
            placement = new Placement[n];
            attached = new int[n][];
            pairPartners = new int[n][];
            Arrays.fill(attached, NONE);
            Arrays.fill(pairPartners, NONE);
        }
    }

    /**
     * Each card's port rows toward the partners it has a wire with both ends measured: its own row, then the
     * partner's. A later wire between the same two cards replaces an earlier one.
     */
    private static final class PortRows {

        /** Per card, its measured partners in ascending order. */
        final int[][] partner;
        final double[][] own, theirs;

        PortRows(final int[][] partner, final double[][] own, final double[][] theirs) {
            this.partner = partner;
            this.own = own;
            this.theirs = theirs;
        }
    }

    /* ---------------------------------------------------------------------------------------------------------- */
    /* One wired component. */
    /* ---------------------------------------------------------------------------------------------------------- */

    /** The cards and wires of one component (or, for {@link #explain}, of a whole board), and every stage on them. */
    private static final class Component {

        final Prices prices;
        final Dials dials;
        /** The least air between any two cards, and the air beside a partner, in px. */
        final double gap, beside;
        /** The most one axis of tidiness may cost, in px. */
        final double tidyCap;
        final List<ArrangeCard> cards;
        final int n, m;
        final String[] ids;
        final double[] w, h;
        final Link[] links;
        final int[] wa, wb;
        /** Each wire's weight: from its width when known, else its own weight, at least 0.01. */
        final double[] weights;
        /** The wires on each card, in wire order. */
        final int[][] wiresOf;

        Component(final Settings settings, final List<ArrangeCard> cards, final Link[] links) {
            this.prices = settings.prices();
            this.dials = settings.dials();
            this.gap = settings.gap();
            this.beside = settings.beside();
            this.tidyCap = Grid.cells(dials.tidyCapCells());
            this.cards = cards;
            this.n = cards.size();
            this.m = links.length;
            this.ids = new String[n];
            this.w = new double[n];
            this.h = new double[n];
            for (int i = 0; i < n; i++) {
                final ArrangeCard card = cards.get(i);
                ids[i] = card.id();
                w[i] = card.width();
                h[i] = card.height();
            }
            this.links = links;
            this.wa = new int[m];
            this.wb = new int[m];
            this.weights = new double[m];
            final int[] degree = new int[n];
            for (int wi = 0; wi < m; wi++) {
                wa[wi] = links[wi].a();
                wb[wi] = links[wi].b();
                weights[wi] = weightOf(links[wi].wire());
                degree[wa[wi]]++;
                degree[wb[wi]]++;
            }
            this.wiresOf = new int[n][];
            for (int i = 0; i < n; i++) wiresOf[i] = new int[degree[i]];
            final int[] fill = new int[n];
            for (int wi = 0; wi < m; wi++) {
                wiresOf[wa[wi]][fill[wa[wi]]++] = wi;
                wiresOf[wb[wi]][fill[wb[wi]]++] = wi;
            }
        }

        /** The far end of wire {@code wi} from card {@code i}. */
        int other(final int wi, final int i) {
            return wa[wi] == i ? wb[wi] : wa[wi];
        }

        Rect rectAt(final int i, final Point p) {
            return new Rect(p.x(), p.y(), p.x() + w[i], p.y() + h[i]);
        }

        /** The component laid out: every card's top-left, in component order. */
        Point[] layout(final int trials, final IntConsumer onProgress) {
            final Rng random = new Rng(Rng.hashIds(Arrays.asList(ids)));
            final Lattice lattice = columnLattice();
            final Lattice anyCell = new Lattice(Grid.CELL, new boolean[n]);
            final Point[] continuous = orientForFlow(stressLayout(random));
            if (n < 2) return settleOnGrid(continuous, lattice);
            // The global search runs with every drawer free: the patterns need the column corridors to exist, and off
            // the lattice a drawer line collides with whatever stands beside its machine, so most trials were refused
            // and the search went nowhere. The patterns come in with the columns.
            final Patterns patterns = planPatterns(lattice);
            final Patterns loose = new Patterns(n);
            final Point[] grid = settleOnGrid(continuous, anyCell);
            final int globalTrials = (int) jsRound(trials * 0.6);
            final Point[] placed = anneal(grid, globalTrials, random, onProgress, anyCell, 1, loose);
            final Point[] legal = settleWithPatterns(legalise(placed, lattice), patterns);
            final Point[] done = anneal(
                legal,
                trials - globalTrials,
                random,
                d -> onProgress.accept(globalTrials + d),
                lattice,
                0.15,
                patterns);
            return settleWithPatterns(done, patterns);
        }

        /** The column lattice (see {@link Lattice}). */
        Lattice columnLattice() {
            // The most common machine width, the widest of the most common; 22 cells when there are no machines.
            final Map<Double, Integer> widths = new LinkedHashMap<>();
            for (int i = 0; i < n; i++) {
                if (!cards.get(i)
                    .storage()) widths.merge(w[i] + 0.0, 1, Integer::sum);
            }
            double machineWidth = Grid.cells(22);
            int most = 0;
            for (final Map.Entry<Double, Integer> entry : widths.entrySet()) {
                final int count = entry.getValue();
                final double width = entry.getKey();
                if (count > most || count == most && width > machineWidth) {
                    most = count;
                    machineWidth = width;
                }
            }
            // The corridor: a drawer, standing the beside air from the machine it is wired to and the least air from
            // the next column.
            boolean drawers = false;
            double widest = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < n; i++) {
                if (w[i] < 0.6 * machineWidth) {
                    drawers = true;
                    widest = Math.max(widest, w[i]);
                }
            }
            final double corridor = drawers ? widest + beside + gap : beside;
            final double pitch = Grid.snap(machineWidth + corridor);
            final boolean[] column = new boolean[n];
            for (int i = 0; i < n; i++) column[i] = w[i] >= 0.6 * machineWidth;
            return new Lattice(pitch, column);
        }

        /** A card's position quantised to what it may stand on: a column, or a cell. */
        Point quantise(final Lattice lattice, final int i, final double x, final double y) {
            return new Point(
                lattice.column()[i] ? jsRound(x / lattice.pitch()) * lattice.pitch() : Grid.snap(x),
                Grid.snap(y));
        }

        Point quantise(final Lattice lattice, final int i, final Point p) {
            return quantise(lattice, i, p.x(), p.y());
        }

        /**
         * Machines onto their columns, nearest the middle first, each on the free lattice spot closest to where the
         * global search left it; drawers keep their cells unless a machine now needs the spot.
         */
        Point[] legalise(final Point[] positions, final Lattice lattice) {
            double sumX = 0, sumY = 0;
            for (int i = 0; i < n; i++) sumX = sumX + positions[i].x() + w[i] / 2;
            for (int i = 0; i < n; i++) sumY = sumY + positions[i].y() + h[i] / 2;
            final double cx = sumX / n, cy = sumY / n;
            final List<int[]> keys = new ArrayList<>(n);
            final double[] distance = new double[n];
            for (int i = 0; i < n; i++) {
                distance[i] = hypot(positions[i].x() + w[i] / 2 - cx, positions[i].y() + h[i] / 2 - cy);
                keys.add(new int[] { i, lattice.column()[i] ? 0 : 1 });
            }
            keys.sort((a, b) -> {
                if (a[1] != b[1]) return Integer.compare(a[1], b[1]);
                final int byDistance = Double.compare(distance[a[0]], distance[b[0]]);
                return byDistance != 0 ? byDistance : Integer.compare(a[0], b[0]);
            });
            final Rect[] placed = new Rect[n];
            final Point[] out = new Point[n];
            int count = 0;
            for (final int[] key : keys) {
                final int i = key[0];
                final Point want = quantise(lattice, i, positions[i]);
                final Point found = nearestFree(want, w[i], h[i], placed, count, -1, gap, 60, lattice.stepX(i));
                final Point spot = found != null ? found : want;
                out[i] = spot;
                placed[count++] = rectAt(i, spot);
            }
            return out;
        }

        /* ------------------------------------------------------------------------------------------------------ */
        /* Drawer patterns (header): buds and shared drawers, placed by rule. */
        /* ------------------------------------------------------------------------------------------------------ */

        /**
         * Which drawers are placed by pattern: a cell card whose every wire meets a column card is a bud when they all
         * meet one machine and a shared drawer when they meet two. A drawer wired to another cell card, or to three
         * machines or more, is moved by the search.
         */
        Patterns planPatterns(final Lattice lattice) {
            final Patterns patterns = new Patterns(n);
            final List<List<Integer>> attached = new ArrayList<>(n), pairPartners = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                attached.add(new ArrayList<>());
                pairPartners.add(new ArrayList<>());
            }
            for (int d = 0; d < n; d++) {
                if (lattice.column()[d] || wiresOf[d].length == 0) continue;
                final Map<Integer, List<Double>> partners = new LinkedHashMap<>();
                boolean foreign = false;
                for (final int wi : wiresOf[d]) {
                    final int other = other(wi, d);
                    if (!lattice.column()[other]) {
                        foreign = true;
                        break;
                    }
                    // The port row on the machine's side, the machine's mid-height when unmeasured.
                    final ArrangeWire wire = links[wi].wire();
                    final Double measured = wa[wi] == other ? wire.sourcePortY() : wire.targetPortY();
                    partners.computeIfAbsent(other, k -> new ArrayList<>())
                        .add(measured != null ? measured : h[other] / 2);
                }
                if (foreign) continue;
                if (partners.size() == 1) {
                    final Map.Entry<Integer, List<Double>> only = partners.entrySet()
                        .iterator()
                        .next();
                    final int machine = only.getKey();
                    // A supply (every wire runs drawer to machine) stands on the left.
                    boolean supply = true;
                    for (final int wi : wiresOf[d]) {
                        if (wa[wi] != d) {
                            supply = false;
                            break;
                        }
                    }
                    patterns.placement[d] = new Bud(machine, !supply, mean(only.getValue()));
                    attached.get(machine)
                        .add(d);
                } else if (partners.size() == 2) {
                    final Iterator<Map.Entry<Integer, List<Double>>> entries = partners.entrySet()
                        .iterator();
                    final Map.Entry<Integer, List<Double>> first = entries.next(), second = entries.next();
                    final int a = first.getKey(), b = second.getKey();
                    patterns.placement[d] = new Pair(a, b, mean(first.getValue()), mean(second.getValue()));
                    attached.get(a)
                        .add(d);
                    attached.get(b)
                        .add(d);
                    if (!pairPartners.get(a)
                        .contains(b))
                        pairPartners.get(a)
                            .add(b);
                    if (!pairPartners.get(b)
                        .contains(a))
                        pairPartners.get(b)
                            .add(a);
                }
            }
            for (int i = 0; i < n; i++) {
                patterns.attached[i] = toArray(attached.get(i));
                patterns.pairPartners[i] = toArray(pairPartners.get(i));
            }
            return patterns;
        }

        /**
         * Where the drawers attached to {@code machines} (and to the machines they share drawers with) stand, given
         * every card's position through {@code at}. Lines are built whole: a machine's right line holds its product
         * buds and the shared drawers of every partner standing to its right, in port order, touching, centred on the
         * ports they serve and kept within the machine's height; a row between a machine and the one below it
         * likewise. In the order the website derives them.
         */
        LinkedHashMap<Integer, Point> derivePatterns(final Patterns patterns, final int[] machines,
            final IntFunction<Point> at) {
            final LinkedHashMap<Integer, Point> out = new LinkedHashMap<>();
            final Set<Integer> scope = new LinkedHashSet<>();
            for (final int machine : machines) {
                scope.add(machine);
                for (final int partner : patterns.pairPartners[machine]) scope.add(partner);
            }
            // Lines are keyed by machine and side (2m, 2m + 1 for the right), rows by the upper and the lower machine.
            final Map<Long, List<Member>> lines = new LinkedHashMap<>(), rows = new LinkedHashMap<>();
            final boolean[] seen = new boolean[n];
            for (final int machine : scope) {
                for (final int d : patterns.attached[machine]) {
                    if (seen[d]) continue;
                    seen[d] = true;
                    final Placement place = patterns.placement[d];
                    if (place instanceof Bud bud) {
                        add(lines, lineKey(bud.machine(), bud.right()), d, bud.port());
                        continue;
                    }
                    final Pair pair = (Pair) place;
                    final Rect ra = rectAt(pair.a(), at.apply(pair.a())), rb = rectAt(pair.b(), at.apply(pair.b()));
                    if (rb.left() >= ra.right()) add(lines, lineKey(pair.a(), true), d, pair.portA());
                    else if (ra.left() >= rb.right()) add(lines, lineKey(pair.b(), true), d, pair.portB());
                    else if (rb.top() >= ra.bottom()) add(rows, rowKey(pair.a(), pair.b()), d, pair.portA());
                    else if (ra.top() >= rb.bottom()) add(rows, rowKey(pair.b(), pair.a()), d, pair.portB());
                    else if ((ra.left() + ra.right()) / 2 <= (rb.left() + rb.right()) / 2)
                        add(lines, lineKey(pair.a(), true), d, pair.portA());
                    else add(lines, lineKey(pair.b(), true), d, pair.portB());
                }
            }
            for (final Map.Entry<Long, List<Member>> entry : lines.entrySet()) {
                final int machine = (int) (entry.getKey() >> 1);
                final boolean right = (entry.getKey() & 1) != 0;
                final Rect r = rectAt(machine, at.apply(machine));
                final List<Member> members = entry.getValue();
                members.sort(BY_KEY);
                double height = 0, width = Double.NEGATIVE_INFINITY, ports = 0;
                for (final Member member : members) height += h[member.d()];
                for (final Member member : members) width = Math.max(width, w[member.d()]);
                for (final Member member : members) ports += member.key();
                final double meanPort = ports / members.size();
                final double y0 = Grid.snap(
                    Math.min(
                        Math.max(r.top() + meanPort - height / 2, r.top()),
                        Math.max(r.top(), r.bottom() - height)));
                // Right: beside the machine. Left: at the far side of the corridor, the same x the previous column's
                // right line uses, so two lines in one corridor can only clash in y.
                final double x = right ? r.right() + beside : r.left() - width - gap;
                double y = y0;
                for (final Member member : members) {
                    out.put(member.d(), new Point(Grid.snap(x), y));
                    y += h[member.d()];
                }
            }
            for (final Map.Entry<Long, List<Member>> entry : rows.entrySet()) {
                final int upperCard = (int) (entry.getKey() >>> 32), lowerCard = (int) (long) entry.getKey();
                final Rect upper = rectAt(upperCard, at.apply(upperCard));
                final Rect lower = rectAt(lowerCard, at.apply(lowerCard));
                final List<Member> members = entry.getValue();
                members.sort(BY_KEY);
                double width = 0;
                for (final Member member : members) width += w[member.d()];
                final double left = Math.max(upper.left(), lower.left());
                final double right = Math.min(upper.right(), lower.right());
                final double x0 = Grid.snap(
                    right > left
                        ? Math.min(Math.max((left + right) / 2 - width / 2, left), Math.max(left, right - width))
                        : left);
                final double y = Grid.snap(upper.bottom() + gap);
                double x = x0;
                for (final Member member : members) {
                    out.put(member.d(), new Point(x, y));
                    x += w[member.d()];
                }
            }
            return out;
        }

        /**
         * Every derived drawer put where its pattern says, from the machines as they stand; a drawer whose place is
         * taken (two lines meeting in one corridor, a row with no room) is freed: handed the nearest empty spot and
         * moved by the search like any other card from then on.
         */
        Point[] settleWithPatterns(final Point[] positions, final Patterns patterns) {
            final Point[] out = positions.clone();
            final List<Integer> withDrawers = new ArrayList<>();
            for (int i = 0; i < n; i++) if (patterns.attached[i].length > 0) withDrawers.add(i);
            final LinkedHashMap<Integer, Point> derived = derivePatterns(patterns, toArray(withDrawers), i -> out[i]);
            for (final Map.Entry<Integer, Point> entry : derived.entrySet()) out[entry.getKey()] = entry.getValue();
            // Lines that meet in one corridor stack: two machines side by side put their products and supplies in the
            // same corridor, and the later line slides down until it clears the one above rather than being broken up.
            final Map<Double, List<Integer>> byCorridor = new LinkedHashMap<>();
            for (final int d : derived.keySet()) byCorridor.computeIfAbsent(out[d].x() + 0.0, k -> new ArrayList<>())
                .add(d);
            for (final List<Integer> list : byCorridor.values()) {
                list.sort((a, b) -> {
                    final int byY = Double.compare(out[a].y(), out[b].y());
                    return byY != 0 ? byY : Integer.compare(a, b);
                });
                double floor = Double.NEGATIVE_INFINITY;
                for (final int d : list) {
                    if (out[d].y() < floor) out[d] = new Point(out[d].x(), floor);
                    floor = out[d].y() + h[d];
                }
            }
            // Conflicts, in a fixed order: the later drawer of a clashing pair is freed.
            final int[] order = toArray(new ArrayList<>(derived.keySet()));
            Arrays.sort(order);
            for (final int d : order) {
                if (patterns.placement[d] == null) continue;
                final Rect mine = rectAt(d, out[d]);
                boolean clash = false;
                for (int o = 0; o < n; o++) {
                    if (o == d) continue;
                    final double air = patterns.placement[o] != null && derived.containsKey(o) ? 0 : gap;
                    if (overlaps(mine, rectAt(o, out[o]), air)) {
                        clash = true;
                        break;
                    }
                }
                if (!clash) continue;
                freePlacement(patterns, d);
                final Rect[] others = new Rect[n];
                for (int o = 0; o < n; o++) others[o] = rectAt(o, out[o]);
                final Point spot = nearestFree(out[d], w[d], h[d], others, n, d, gap, 80, Grid.CELL);
                if (spot != null) out[d] = spot;
            }
            return out;
        }

        /* ------------------------------------------------------------------------------------------------------ */
        /* Stage 1: stress. */
        /* ------------------------------------------------------------------------------------------------------ */

        /** The ideal centre-to-centre distance of two cards that share a wire. */
        double pitch(final int a, final int b) {
            return (Math.max(w[a], h[a]) + Math.max(w[b], h[b])) / 2 + beside;
        }

        /** Card centres matching graph distance as closely as stress SGD can. */
        Point[] stressLayout(final Rng random) {
            final int[][] edgeTo = new int[n][];
            final double[][] edgeLength = new double[n][];
            for (int i = 0; i < n; i++) {
                edgeTo[i] = new int[wiresOf[i].length];
                edgeLength[i] = new double[wiresOf[i].length];
            }
            {
                final int[] fill = new int[n];
                for (int wi = 0; wi < m; wi++) {
                    final int a = wa[wi], b = wb[wi];
                    final double length = pitch(a, b);
                    edgeTo[a][fill[a]] = b;
                    edgeLength[a][fill[a]++] = length;
                    edgeTo[b][fill[b]] = a;
                    edgeLength[b][fill[b]++] = length;
                }
            }
            // All-pairs shortest paths by Dijkstra from each card (n is small).
            final double[][] distance = new double[n][];
            for (int source = 0; source < n; source++) {
                final double[] dist = new double[n];
                Arrays.fill(dist, Double.POSITIVE_INFINITY);
                final boolean[] done = new boolean[n];
                dist[source] = 0;
                for (int round = 0; round < n; round++) {
                    int best = -1;
                    for (int i = 0; i < n; i++) {
                        if (!done[i] && dist[i] < Double.POSITIVE_INFINITY && (best < 0 || dist[i] < dist[best]))
                            best = i;
                    }
                    if (best < 0) break;
                    done[best] = true;
                    for (int e = 0; e < edgeTo[best].length; e++) {
                        final double through = dist[best] + edgeLength[best][e];
                        if (through < dist[edgeTo[best][e]]) dist[edgeTo[best][e]] = through;
                    }
                }
                distance[source] = dist;
            }
            // Start on a circle in breadth-first order from the best-connected card, never from where the cards stand:
            // the answer for a board must not depend on how it was left, or two players with one plan would get two
            // arrangements. The seeded shuffles below are the only randomness.
            final double[] x = new double[n], y = new double[n];
            {
                final List<Integer> byDegree = new ArrayList<>(n);
                for (int i = 0; i < n; i++) byDegree.add(i);
                byDegree.sort(
                    (a, b) -> edgeTo[a].length != edgeTo[b].length ? Integer.compare(edgeTo[b].length, edgeTo[a].length)
                        : Integer.compare(a, b));
                final int[] order = new int[n];
                final boolean[] seen = new boolean[n];
                int count = 0;
                for (final int root : byDegree) {
                    if (seen[root]) continue;
                    seen[root] = true;
                    order[count++] = root;
                    for (int head = count - 1; head < count; head++) {
                        for (final int to : edgeTo[order[head]]) {
                            if (!seen[to]) {
                                seen[to] = true;
                                order[count++] = to;
                            }
                        }
                    }
                }
                double step = 0;
                for (int wi = 0; wi < m; wi++) step += pitch(wa[wi], wb[wi]);
                step = m > 0 ? step / m : Grid.cells(24);
                final double radius = Math.max(step, n * step / (2 * Math.PI));
                for (int k = 0; k < count; k++) {
                    final double angle = 2 * Math.PI * k / n;
                    x[order[k]] = radius * StrictMath.cos(angle);
                    y[order[k]] = radius * StrictMath.sin(angle);
                }
            }
            // The pairs with a finite distance, packed as i * n + k.
            final int[] pairs = new int[n * (n - 1) / 2];
            int pairCount = 0;
            double wMin = Double.POSITIVE_INFINITY, wMax = 0;
            for (int i = 0; i < n; i++) {
                for (int k = i + 1; k < n; k++) {
                    final double d = distance[i][k];
                    if (!Double.isFinite(d) || d <= 0) continue;
                    pairs[pairCount++] = i * n + k;
                    final double weight = 1 / (d * d);
                    wMin = Math.min(wMin, weight);
                    wMax = Math.max(wMax, weight);
                }
            }
            if (pairCount == 0) return points(x, y);
            final double etaMax = 1 / wMin;
            final double etaMin = 0.1 / wMax;
            final double lambda = StrictMath.log(etaMax / etaMin) / Math.max(1, STRESS_ITERATIONS - 1);
            for (int t = 0; t < STRESS_ITERATIONS; t++) {
                final double eta = etaMax * StrictMath.exp(-lambda * t);
                // Fisher-Yates with the seeded generator: deterministic shuffles.
                for (int i = pairCount - 1; i > 0; i--) {
                    final int j = (int) Math.floor(random.next() * (i + 1));
                    final int swap = pairs[i];
                    pairs[i] = pairs[j];
                    pairs[j] = swap;
                }
                for (int p = 0; p < pairCount; p++) {
                    final int i = pairs[p] / n, k = pairs[p] % n;
                    final double d = distance[i][k];
                    final double mu = Math.min(1, eta / (d * d));
                    final double dx = x[i] - x[k];
                    final double dy = y[i] - y[k];
                    double mag = hypot(dx, dy);
                    if (mag == 0 || Double.isNaN(mag)) mag = 1e-6;
                    final double r = (mag - d) / 2 * mu;
                    final double rx = dx / mag * r;
                    final double ry = dy / mag * r;
                    x[i] -= rx;
                    y[i] -= ry;
                    x[k] += rx;
                    y[k] += ry;
                }
            }
            return points(x, y);
        }

        /**
         * Stress is blind to direction: any reflection or rotation scores the same. Of the eight, keep the one where
         * the fewest wires run backwards (weighted), so the search starts from a board that already flows.
         */
        Point[] orientForFlow(final Point[] centres) {
            Point[] best = centres;
            double bestCost = Double.POSITIVE_INFINITY;
            for (int variant = 0; variant < 8; variant++) {
                final Point[] mapped = new Point[n];
                for (int i = 0; i < n; i++) mapped[i] = orient(variant, centres[i]);
                double cost = 0;
                for (int wi = 0; wi < m; wi++)
                    cost += weights[wi] * Math.max(0, mapped[wa[wi]].x() - mapped[wb[wi]].x());
                if (cost < bestCost - 1e-9) {
                    bestCost = cost;
                    best = mapped;
                }
            }
            return best;
        }

        /* ------------------------------------------------------------------------------------------------------ */
        /* Stage 2: the grid. */
        /* ------------------------------------------------------------------------------------------------------ */

        /** Continuous centres to non-overlapping grid top-lefts. */
        Point[] settleOnGrid(final Point[] centres, final Lattice lattice) {
            final double[] tx = new double[n], ty = new double[n];
            for (int i = 0; i < n; i++) {
                tx[i] = centres[i].x() - w[i] / 2;
                ty[i] = centres[i].y() - h[i] / 2;
            }
            // Push overlapping pairs apart along their least penetration.
            for (int pass = 0; pass < 400; pass++) {
                boolean moved = false;
                for (int i = 0; i < n; i++) {
                    for (int k = i + 1; k < n; k++) {
                        final double aLeft = tx[i], aTop = ty[i], aRight = tx[i] + w[i], aBottom = ty[i] + h[i];
                        final double bLeft = tx[k], bTop = ty[k], bRight = tx[k] + w[k], bBottom = ty[k] + h[k];
                        if (!overlaps(aLeft, aTop, aRight, aBottom, bLeft, bTop, bRight, bBottom, gap)) continue;
                        final double px = Math.min(aRight + gap - bLeft, bRight + gap - aLeft);
                        final double py = Math.min(aBottom + gap - bTop, bBottom + gap - aTop);
                        moved = true;
                        if (px < py) {
                            final int dir = (aLeft + aRight) / 2 <= (bLeft + bRight) / 2 ? 1 : -1;
                            tx[i] -= dir * px / 2;
                            tx[k] += dir * px / 2;
                        } else {
                            final int dir = (aTop + aBottom) / 2 <= (bTop + bBottom) / 2 ? 1 : -1;
                            ty[i] -= dir * py / 2;
                            ty[k] += dir * py / 2;
                        }
                    }
                }
                if (!moved) break;
            }
            // Onto the grid: nearest the middle first, each on the closest free spot.
            double sumX = 0, sumY = 0;
            for (int i = 0; i < n; i++) sumX = sumX + tx[i] + w[i] / 2;
            for (int i = 0; i < n; i++) sumY = sumY + ty[i] + h[i] / 2;
            final double cx = sumX / n, cy = sumY / n;
            final double[] distance = new double[n];
            final List<Integer> order = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                distance[i] = hypot(tx[i] + w[i] / 2 - cx, ty[i] + h[i] / 2 - cy);
                order.add(i);
            }
            order.sort((a, b) -> {
                final int byDistance = Double.compare(distance[a], distance[b]);
                return byDistance != 0 ? byDistance : Integer.compare(a, b);
            });
            final Rect[] placed = new Rect[n];
            final Point[] out = new Point[n];
            int count = 0;
            for (final int i : order) {
                final Point want = quantise(lattice, i, tx[i], ty[i]);
                final Point found = nearestFree(want, w[i], h[i], placed, count, -1, gap, 60, lattice.stepX(i));
                final Point spot = found != null ? found : want;
                out[i] = spot;
                placed[count++] = rectAt(i, spot);
            }
            return out;
        }

        /* ------------------------------------------------------------------------------------------------------ */
        /* The terms, standalone, so the search and the explainer price alike. */
        /* ------------------------------------------------------------------------------------------------------ */

        PortRows portRows() {
            final List<Map<Integer, double[]>> rows = new ArrayList<>(n);
            for (int i = 0; i < n; i++) rows.add(new HashMap<>());
            for (final Link link : links) {
                final Double source = link.wire()
                    .sourcePortY(),
                    target = link.wire()
                        .targetPortY();
                if (source != null && target != null) {
                    rows.get(link.a())
                        .put(link.b(), new double[] { source, target });
                    rows.get(link.b())
                        .put(link.a(), new double[] { target, source });
                }
            }
            final int[][] partner = new int[n][];
            final double[][] own = new double[n][], theirs = new double[n][];
            for (int i = 0; i < n; i++) {
                final Map<Integer, double[]> mine = rows.get(i);
                partner[i] = mine.keySet()
                    .stream()
                    .mapToInt(Integer::intValue)
                    .sorted()
                    .toArray();
                own[i] = new double[partner[i].length];
                theirs[i] = new double[partner[i].length];
                for (int k = 0; k < partner[i].length; k++) {
                    final double[] pair = mine.get(partner[i][k]);
                    own[i][k] = pair[0];
                    theirs[i][k] = pair[1];
                }
            }
            return new PortRows(partner, own, theirs);
        }

        /** The tidiness owed by card {@code k} at these rects (see {@link Dials#tidy}). */
        double tidyAt(final int k, final Rect[] rects, final PortRows portRows) {
            final Rect me = rects[k];
            double misX = Double.POSITIVE_INFINITY, misY = Double.POSITIVE_INFINITY;
            for (int c = 0; c < rects.length; c++) {
                if (c == k) continue;
                final Rect other = rects[c];
                // Stacked: x-ranges overlap, within reach vertically, so share a left or right edge.
                if (me.left() < other.right() && other.left() < me.right()) {
                    final double gapY = Math.max(other.top() - me.bottom(), me.top() - other.bottom());
                    if (gapY <= TIDY_REACH) {
                        misX = Math.min(
                            misX,
                            Math.min(Math.abs(me.left() - other.left()), Math.abs(me.right() - other.right())));
                    }
                }
                // Beside: y-ranges overlap, within reach horizontally, so share a top or bottom edge or a port row.
                if (me.top() < other.bottom() && other.top() < me.bottom()) {
                    final double gapX = Math.max(other.left() - me.right(), me.left() - other.right());
                    if (gapX <= TIDY_REACH) {
                        misY = Math.min(
                            misY,
                            Math.min(Math.abs(me.top() - other.top()), Math.abs(me.bottom() - other.bottom())));
                        final int row = Arrays.binarySearch(portRows.partner[k], c);
                        if (row >= 0) misY = Math.min(
                            misY,
                            Math.abs(me.top() + portRows.own[k][row] - (other.top() + portRows.theirs[k][row])));
                    }
                }
            }
            final double x = misX == Double.POSITIVE_INFINITY ? 0 : Math.min(misX, tidyCap);
            final double y = misY == Double.POSITIVE_INFINITY ? 0 : Math.min(misY, tidyCap);
            return dials.tidy() * (x + y);
        }

        /** The flow price of a wire from rect {@code a} to rect {@code b} (see {@link Dials#flow}). */
        double flowAt(final Rect a, final Rect b, final double flat) {
            final double forward = (a.right() - a.left() + (b.right() - b.left())) / 2 + gap;
            final double ahead = (b.left() + b.right()) / 2 - (a.left() + a.right()) / 2;
            return dials.flow() * Math.max(0, forward - ahead) + (ahead <= 0 ? flat : 0);
        }

        /**
         * Each wire's flat backward price: the cycle price for a wire whose ends share a strongly connected component
         * (Tarjan's, over the directed wires; iterative, in the recursive visiting order), else the column or plain
         * price.
         */
        double[] flatPrices(final Lattice lattice) {
            final int[][] out = new int[n][];
            {
                final int[] degree = new int[n];
                for (int wi = 0; wi < m; wi++) degree[wa[wi]]++;
                for (int i = 0; i < n; i++) out[i] = new int[degree[i]];
                final int[] fill = new int[n];
                for (int wi = 0; wi < m; wi++) out[wa[wi]][fill[wa[wi]]++] = wb[wi];
            }
            final int[] index = new int[n], low = new int[n], component = new int[n];
            Arrays.fill(index, -1);
            Arrays.fill(component, -1);
            final boolean[] onStack = new boolean[n];
            final int[] stack = new int[n], callNode = new int[n], callNext = new int[n];
            int stackSize = 0, next = 0, count = 0;
            for (int root = 0; root < n; root++) {
                if (index[root] >= 0) continue;
                int depth = 0;
                callNode[0] = root;
                callNext[0] = 0;
                index[root] = low[root] = next++;
                stack[stackSize++] = root;
                onStack[root] = true;
                while (depth >= 0) {
                    final int v = callNode[depth];
                    if (callNext[depth] < out[v].length) {
                        final int to = out[v][callNext[depth]++];
                        if (index[to] < 0) {
                            index[to] = low[to] = next++;
                            stack[stackSize++] = to;
                            onStack[to] = true;
                            depth++;
                            callNode[depth] = to;
                            callNext[depth] = 0;
                        } else if (onStack[to]) {
                            low[v] = Math.min(low[v], index[to]);
                        }
                        continue;
                    }
                    if (low[v] == index[v]) {
                        int popped;
                        do {
                            popped = stack[--stackSize];
                            onStack[popped] = false;
                            component[popped] = count;
                        } while (popped != v);
                        count++;
                    }
                    depth--;
                    if (depth >= 0) low[callNode[depth]] = Math.min(low[callNode[depth]], low[v]);
                }
            }
            final double[] flats = new double[m];
            for (int wi = 0; wi < m; wi++) {
                final int a = wa[wi], b = wb[wi];
                flats[wi] = component[a] == component[b] ? dials.flowFlatCycle()
                    : lattice.column()[a] && lattice.column()[b] ? dials.flowFlatColumns() : dials.flowFlat();
            }
            return flats;
        }

        /** The sprawl price of a layout (see {@link Dials#sprawl}). */
        double sprawlOf(final Rect[] rects) {
            double left = Double.POSITIVE_INFINITY, top = Double.POSITIVE_INFINITY;
            double right = Double.NEGATIVE_INFINITY, bottom = Double.NEGATIVE_INFINITY;
            for (final Rect rect : rects) {
                left = Math.min(left, rect.left());
                top = Math.min(top, rect.top());
                right = Math.max(right, rect.right());
                bottom = Math.max(bottom, rect.bottom());
            }
            return dials.sprawl() * 2 * (right - left + (bottom - top));
        }

        /* ------------------------------------------------------------------------------------------------------ */
        /* Stage 3: the search. */
        /* ------------------------------------------------------------------------------------------------------ */

        /**
         * Simulated annealing from {@code start}; {@code heat} scales the starting temperature: 1 for a search, less
         * for a repair. Returns the best layout seen.
         */
        Point[] anneal(final Point[] start, final int trials, final Rng random, final IntConsumer onProgress,
            final Lattice lattice, final double heat, final Patterns patterns) {
            boolean anyFree = false;
            for (int i = 0; i < n; i++) if (patterns.placement[i] == null) anyFree = true;
            if (!anyFree) return start.clone();
            return new Search(this, start, random, lattice, patterns).run(trials, heat, onProgress);
        }
    }

    /**
     * One annealing run over a component. It keeps books: each wire's proxy path, its bounding box and its own price
     * (length, bends, cards it runs through, flow), the crossings, each card's tidiness, the air and the sprawl, so a
     * trial re-prices only what the moved cards can change and is taken back exactly when refused.
     */
    private static final class Search {

        private final Component c;
        private final int n, m;
        private final double[] w, h;
        private final int[] wa, wb;
        private final int[][] wiresOf;
        private final double[] weights;
        private final Prices prices;
        private final double gap;
        private final Rng random;
        private final Lattice lattice;
        private final Patterns patterns;
        /** The cards the search moves; derived drawers follow their machines. */
        private final int[] freeCards;
        private final Point[] pos;
        private final Rect[] rects;
        /**
         * Every card's top-left by id, kept in step with {@link #pos} for the air term. Ids are unique within a
         * component (a wire names the last card with an id, so an earlier namesake is unwired and goes on the shelf).
         */
        private final Map<String, Point> positions = new HashMap<>();
        private final ToDoubleFunction<Map<String, Point>> air;
        /** Each card's partners, once each, in wire order. */
        private final int[][] neighbours;
        /** The smaller side of each bridge wire, when it is two cards or more. */
        private final List<int[]> bridgeSides = new ArrayList<>();
        private final double[][] paths;
        private final double[] boxLeft, boxTop, boxRight, boxBottom;
        private final double[] own;
        private final double[] flats;
        private final PortRows portRows;
        private final double[] tidy;
        private double ownTotal, crossings, extras, tidyTotal, score, best;
        private Point[] bestPos;
        // The trial on the books, until it is committed or undone.
        private int[] pendingMoved;
        private final Point[] savedPos;
        private final double[][] savedPaths;
        private final double[] savedOwn, savedTidy;
        private final int[] touched, blockedOnly, tidyCards;
        private int touchedCount, blockedCount, tidyCount;
        private double pendingOldPart, pendingNewPart, pendingNewExtras, pendingTidyDelta;
        /** The score change of the last attempt that was not refused. */
        private double delta;
        // Scratch: marks by stamp (a mark equal to the stamp is set), and the swept boxes of a trial.
        private final int[] movedMark, touchedMark, overrideMark;
        private final Point[] overrideAt;
        private int stamp;
        private double[] swept;

        Search(final Component c, final Point[] start, final Rng random, final Lattice lattice,
            final Patterns patterns) {
            this.c = c;
            this.n = c.n;
            this.m = c.m;
            this.w = c.w;
            this.h = c.h;
            this.wa = c.wa;
            this.wb = c.wb;
            this.wiresOf = c.wiresOf;
            this.weights = c.weights;
            this.prices = c.prices;
            this.gap = c.gap;
            this.random = random;
            this.lattice = lattice;
            this.patterns = patterns;
            final List<Integer> free = new ArrayList<>();
            for (int i = 0; i < n; i++) if (patterns.placement[i] == null) free.add(i);
            this.freeCards = toArray(free);
            this.pos = start.clone();
            this.rects = new Rect[n];
            for (int i = 0; i < n; i++) {
                rects[i] = c.rectAt(i, pos[i]);
                positions.put(c.ids[i], pos[i]);
            }
            final List<ArrangeWire> componentWires = new ArrayList<>(m);
            for (final Link link : c.links) componentWires.add(link.wire());
            this.air = Air.term(c.cards, componentWires, prices.islandAir());
            this.neighbours = new int[n][];
            for (int i = 0; i < n; i++) {
                final Set<Integer> unique = new LinkedHashSet<>();
                for (final int wi : wiresOf[i]) unique.add(c.other(wi, i));
                neighbours[i] = toArray(new ArrayList<>(unique));
            }
            findBridgeSides();

            this.paths = new double[m][];
            this.boxLeft = new double[m];
            this.boxTop = new double[m];
            this.boxRight = new double[m];
            this.boxBottom = new double[m];
            this.own = new double[m];
            this.flats = c.flatPrices(lattice);
            // Tidiness per card: recomputed only for cards near a move.
            this.portRows = c.portRows();
            this.tidy = new double[n];
            for (int k = 0; k < n; k++) tidy[k] = c.tidyAt(k, rects, portRows);
            tidyTotal = 0;
            for (int k = 0; k < n; k++) tidyTotal += tidy[k];
            for (int wi = 0; wi < m; wi++) {
                paths[wi] = Proxy.path(rects[wa[wi]], rects[wb[wi]]);
                updateBox(wi);
                own[wi] = priceOwn(wi);
            }
            crossings = 0;
            for (int i = 0; i < m; i++) {
                for (int k = i + 1; k < m; k++) crossings += crossingsOf(i, k) * crossPrice(i, k);
            }
            ownTotal = 0;
            for (int wi = 0; wi < m; wi++) ownTotal += own[wi];
            extras = air.applyAsDouble(positions) + c.sprawlOf(rects);
            score = ownTotal + crossings + extras + tidyTotal;
            best = score;
            bestPos = pos.clone();

            this.savedPos = new Point[n];
            this.savedPaths = new double[m][];
            this.savedOwn = new double[m];
            this.savedTidy = new double[n];
            this.touched = new int[m];
            this.blockedOnly = new int[m];
            this.tidyCards = new int[n];
            this.movedMark = new int[n];
            this.touchedMark = new int[m];
            this.overrideMark = new int[n];
            this.overrideAt = new Point[n];
            this.swept = new double[32];
        }

        /**
         * Bridges: wires whose loss would split the component. The cards on the smaller side of one form a natural
         * island, and shifting them as one is the move that lets an island drift out (the air term asks for it; no
         * one-card move can do it, because every card in the cluster holds the others in place). Found by a
         * depth-first search, iterative but visiting in the recursive order, so the bridges come out in the website's
         * order.
         */
        private void findBridgeSides() {
            final int[] disc = new int[n], low = new int[n];
            Arrays.fill(disc, -1);
            final int[] callNode = new int[n], callVia = new int[n], callNext = new int[n];
            final List<Integer> bridges = new ArrayList<>();
            int time = 0;
            for (int root = 0; root < n; root++) {
                if (disc[root] >= 0) continue;
                int depth = 0;
                callNode[0] = root;
                callVia[0] = -1;
                callNext[0] = 0;
                disc[root] = low[root] = time++;
                while (depth >= 0) {
                    final int u = callNode[depth];
                    if (callNext[depth] < wiresOf[u].length) {
                        final int wi = wiresOf[u][callNext[depth]++];
                        if (wi == callVia[depth]) continue;
                        final int v = c.other(wi, u);
                        if (disc[v] < 0) {
                            disc[v] = low[v] = time++;
                            depth++;
                            callNode[depth] = v;
                            callVia[depth] = wi;
                            callNext[depth] = 0;
                        } else {
                            low[u] = Math.min(low[u], disc[v]);
                        }
                        continue;
                    }
                    final int via = callVia[depth];
                    depth--;
                    if (depth >= 0) {
                        final int parent = callNode[depth];
                        low[parent] = Math.min(low[parent], low[u]);
                        if (low[u] > disc[parent]) bridges.add(via);
                    }
                }
            }
            for (final int wi : bridges) {
                // The side reachable from one end without the bridge; keep the smaller.
                final int[] left = side(wa[wi], wi), right = side(wb[wi], wi);
                final int[] smaller = left.length <= right.length ? left : right;
                if (smaller.length >= 2 && smaller.length < n) bridgeSides.add(smaller);
            }
        }

        /** The cards reachable from {@code start} without wire {@code bridge}, in breadth-first order. */
        private int[] side(final int start, final int bridge) {
            final boolean[] seen = new boolean[n];
            final int[] list = new int[n];
            int count = 0;
            seen[start] = true;
            list[count++] = start;
            for (int head = 0; head < count; head++) {
                for (final int x : wiresOf[list[head]]) {
                    if (x == bridge) continue;
                    final int v = c.other(x, list[head]);
                    if (!seen[v]) {
                        seen[v] = true;
                        list[count++] = v;
                    }
                }
            }
            return Arrays.copyOf(list, count);
        }

        private double crossPrice(final int i, final int k) {
            return prices.crossing() * Math.max(weights[i], weights[k]);
        }

        /**
         * The proper crossings between two wires' paths. A crossing point lies inside both paths' boxes, so wires whose
         * boxes stand apart cannot cross and skip the segment-by-segment count; the answer is the same.
         */
        private int crossingsOf(final int i, final int k) {
            if (boxLeft[i] > boxRight[k] || boxLeft[k] > boxRight[i]
                || boxTop[i] > boxBottom[k]
                || boxTop[k] > boxBottom[i]) return 0;
            return Proxy.crossings(paths[i], paths[k]);
        }

        /** A wire's own price: length, bends, the cards it runs through and its flow, times its weight. */
        private double priceOwn(final int wi) {
            final double[] path = paths[wi];
            return (Proxy.length(path) + Proxy.bends(path, prices)
                + Proxy.blocked(path, rects, wa[wi], wb[wi], prices)
                + c.flowAt(rects[wa[wi]], rects[wb[wi]], flats[wi])) * weights[wi];
        }

        private void setRect(final int i, final Point p) {
            pos[i] = p;
            rects[i] = c.rectAt(i, p);
            positions.put(c.ids[i], p);
        }

        private void updateBox(final int wi) {
            final double[] path = paths[wi];
            double left = Double.POSITIVE_INFINITY, top = Double.POSITIVE_INFINITY;
            double right = Double.NEGATIVE_INFINITY, bottom = Double.NEGATIVE_INFINITY;
            for (int p = 0; p < path.length; p += 2) {
                left = Math.min(left, path[p]);
                top = Math.min(top, path[p + 1]);
                right = Math.max(right, path[p]);
                bottom = Math.max(bottom, path[p + 1]);
            }
            boxLeft[wi] = left;
            boxTop[wi] = top;
            boxRight[wi] = right;
            boxBottom[wi] = bottom;
        }

        /** Drawers in a pattern line touch; everything else keeps the gap. */
        private double gapBetween(final int i, final int o) {
            return patterns.placement[i] != null && patterns.placement[o] != null ? 0 : gap;
        }

        /**
         * Tries moving the cards in {@code moved} to {@code to}: false, with nothing changed, when a card would
         * overlap something; else the move is applied, its score change is in {@link #delta}, and the caller keeps
         * it with {@link #commit} or takes it back with {@link #undo}.
         */
        private boolean attempt(final int[] moved, final Point[] to) {
            final int count = moved.length;
            stamp++;
            for (final int i : moved) movedMark[i] = stamp;
            for (int k = 0; k < count; k++) {
                final int i = moved[k];
                final double left = to[k].x(), top = to[k].y(), right = left + w[i], bottom = top + h[i];
                for (int o = 0; o < n; o++) {
                    if (movedMark[o] == stamp) continue;
                    final Rect r = rects[o];
                    if (overlaps(left, top, right, bottom, r.left(), r.top(), r.right(), r.bottom(), gapBetween(i, o)))
                        return false;
                }
                for (int j = 0; j < k; j++) {
                    final int other = moved[j];
                    final double oLeft = to[j].x(), oTop = to[j].y();
                    if (overlaps(
                        left,
                        top,
                        right,
                        bottom,
                        oLeft,
                        oTop,
                        oLeft + w[other],
                        oTop + h[other],
                        gapBetween(i, other))) return false;
                }
            }
            // The wires re-priced: those on the moved cards, and those whose path the cards stood across before or
            // stand across now.
            touchedCount = 0;
            for (final int i : moved) {
                for (final int wi : wiresOf[i]) {
                    if (touchedMark[wi] != stamp) {
                        touchedMark[wi] = stamp;
                        touched[touchedCount++] = wi;
                    }
                }
            }
            final int sweptCount = 2 * count;
            if (swept.length < 4 * sweptCount) swept = new double[8 * sweptCount];
            for (int k = 0; k < count; k++) {
                final int i = moved[k];
                final Rect was = rects[i];
                final int s = 8 * k;
                swept[s] = was.left() - Grid.CELL;
                swept[s + 1] = was.top() - Grid.CELL;
                swept[s + 2] = was.right() + Grid.CELL;
                swept[s + 3] = was.bottom() + Grid.CELL;
                swept[s + 4] = to[k].x() - Grid.CELL;
                swept[s + 5] = to[k].y() - Grid.CELL;
                swept[s + 6] = to[k].x() + w[i] + Grid.CELL;
                swept[s + 7] = to[k].y() + h[i] + Grid.CELL;
            }
            blockedCount = 0;
            for (int wi = 0; wi < m; wi++) {
                if (touchedMark[wi] == stamp) continue;
                for (int s = 0; s < 4 * sweptCount; s += 4) {
                    if (overlaps(
                        boxLeft[wi],
                        boxTop[wi],
                        boxRight[wi],
                        boxBottom[wi],
                        swept[s],
                        swept[s + 1],
                        swept[s + 2],
                        swept[s + 3],
                        0)) {
                        blockedOnly[blockedCount++] = wi;
                        break;
                    }
                }
            }
            // Cards whose tidiness may change: the moved ones and every card within reach of where they stood or
            // where they land.
            tidyCount = 0;
            for (int k = 0; k < n; k++) {
                if (movedMark[k] == stamp) {
                    tidyCards[tidyCount++] = k;
                    continue;
                }
                final Rect r = rects[k];
                for (int s = 0; s < 4 * sweptCount; s += 4) {
                    if (overlaps(
                        r.left(),
                        r.top(),
                        r.right(),
                        r.bottom(),
                        swept[s] - TIDY_REACH,
                        swept[s + 1] - TIDY_REACH,
                        swept[s + 2] + TIDY_REACH,
                        swept[s + 3] + TIDY_REACH,
                        0)) {
                        tidyCards[tidyCount++] = k;
                        break;
                    }
                }
            }
            double oldTidy = 0;
            for (int a = 0; a < tidyCount; a++) oldTidy += tidy[tidyCards[a]];
            double oldPart = 0;
            for (int a = 0; a < touchedCount; a++) oldPart += own[touched[a]];
            for (int a = 0; a < blockedCount; a++) oldPart += own[blockedOnly[a]];
            oldPart = addCrossings(oldPart);
            // Apply.
            for (int k = 0; k < count; k++) {
                savedPos[k] = pos[moved[k]];
                setRect(moved[k], to[k]);
            }
            for (int a = 0; a < touchedCount; a++) {
                final int wi = touched[a];
                savedPaths[wi] = paths[wi];
                savedOwn[wi] = own[wi];
                paths[wi] = Proxy.path(rects[wa[wi]], rects[wb[wi]]);
                updateBox(wi);
                own[wi] = priceOwn(wi);
            }
            for (int a = 0; a < blockedCount; a++) {
                final int wi = blockedOnly[a];
                savedOwn[wi] = own[wi];
                own[wi] = priceOwn(wi);
            }
            double newPart = 0;
            for (int a = 0; a < touchedCount; a++) newPart += own[touched[a]];
            for (int a = 0; a < blockedCount; a++) newPart += own[blockedOnly[a]];
            newPart = addCrossings(newPart);
            double newTidy = 0;
            for (int a = 0; a < tidyCount; a++) {
                final int k = tidyCards[a];
                savedTidy[k] = tidy[k];
                tidy[k] = c.tidyAt(k, rects, portRows);
                newTidy += tidy[k];
            }
            final double newExtras = air.applyAsDouble(positions) + c.sprawlOf(rects);
            delta = newPart - oldPart + (newExtras - extras) + (newTidy - oldTidy);
            // Book the change; the caller reverts by undo if it refuses.
            pendingMoved = moved;
            pendingOldPart = oldPart;
            pendingNewPart = newPart;
            pendingNewExtras = newExtras;
            pendingTidyDelta = newTidy - oldTidy;
            return true;
        }

        /** {@code part} plus the crossings of every touched wire, each touched pair counted once. */
        private double addCrossings(double part) {
            for (int a = 0; a < touchedCount; a++) {
                final int wi = touched[a];
                for (int o = 0; o < m; o++) {
                    if (o == wi) continue;
                    if (touchedMark[o] == stamp && o < wi) continue;
                    part += crossingsOf(wi, o) * crossPrice(wi, o);
                }
            }
            return part;
        }

        private void commit() {
            double oldOwn = 0;
            for (int a = 0; a < touchedCount; a++) oldOwn += savedOwn[touched[a]];
            for (int a = 0; a < blockedCount; a++) oldOwn += savedOwn[blockedOnly[a]];
            double newOwn = 0;
            for (int a = 0; a < touchedCount; a++) newOwn += own[touched[a]];
            for (int a = 0; a < blockedCount; a++) newOwn += own[blockedOnly[a]];
            ownTotal += newOwn - oldOwn;
            crossings += pendingNewPart - newOwn - (pendingOldPart - oldOwn);
            extras = pendingNewExtras;
            tidyTotal += pendingTidyDelta;
            score = ownTotal + crossings + extras + tidyTotal;
            pendingMoved = null;
        }

        private void undo() {
            for (int k = 0; k < pendingMoved.length; k++) setRect(pendingMoved[k], savedPos[k]);
            for (int a = 0; a < touchedCount; a++) {
                final int wi = touched[a];
                paths[wi] = savedPaths[wi];
                updateBox(wi);
                own[wi] = savedOwn[wi];
            }
            for (int a = 0; a < blockedCount; a++) own[blockedOnly[a]] = savedOwn[blockedOnly[a]];
            for (int a = 0; a < tidyCount; a++) tidy[tidyCards[a]] = savedTidy[tidyCards[a]];
            pendingMoved = null;
        }

        private int pick(final int max) {
            return (int) Math.floor(random.next() * max);
        }

        /** The free spot nearest {@code want} for card {@code i}, within three steps, ignoring the card itself. */
        private Point fitNear(final int i, final Point want) {
            return nearestFree(c.quantise(lattice, i, want), w[i], h[i], rects, n, i, gap, 3, lattice.stepX(i));
        }

        /**
         * One of the spots beside partner {@code p} for card {@code i} (four sides, rows aligned: top, bottom, middle
         * and, when the wire's two port rows are known, the port rows lined up, the straight shot the router prices
         * lowest), picked at random as the website picks from its list of them.
         */
        private Point besideSpot(final int i, final int p, final int wi) {
            final Point at = pos[p];
            final ArrangeWire wire = c.links[wi].wire();
            final Double ownPort = wa[wi] == i ? wire.sourcePortY() : wire.targetPortY();
            final Double theirPort = wa[wi] == p ? wire.sourcePortY() : wire.targetPortY();
            final int rowCount = ownPort != null && theirPort != null ? 4 : 3;
            final int spot = pick(2 * rowCount + 6);
            final double x, y;
            if (spot < 2 * rowCount) {
                y = switch (spot / 2) {
                    case 0 -> at.y();
                    case 1 -> at.y() + h[p] - h[i];
                    case 2 -> at.y() + (h[p] - h[i]) / 2;
                    default -> at.y() + theirPort - ownPort;
                };
                x = spot % 2 == 0 ? at.x() - w[i] - c.beside : at.x() + w[p] + c.beside;
            } else {
                final int column = spot - 2 * rowCount;
                x = switch (column / 2) {
                    case 0 -> at.x();
                    case 1 -> at.x() + w[p] - w[i];
                    default -> at.x() + (w[p] - w[i]) / 2;
                };
                y = column % 2 == 0 ? at.y() - h[i] - gap : at.y() + h[p] + gap;
            }
            return c.quantise(lattice, i, x, y);
        }

        /**
         * A move of free cards, completed: every derived drawer attached to a moved machine (or to a machine sharing a
         * drawer with one) is re-placed from the machines' new positions and rides along in the same trial.
         */
        private Move expand(final Move move) {
            final int[] moved = move.cards();
            int machineCount = 0;
            for (final int i : moved) {
                if (patterns.attached[i].length > 0 || patterns.pairPartners[i].length > 0) machineCount++;
            }
            if (machineCount == 0) return move;
            final int[] machines = new int[machineCount];
            machineCount = 0;
            for (final int i : moved) {
                if (patterns.attached[i].length > 0 || patterns.pairPartners[i].length > 0)
                    machines[machineCount++] = i;
            }
            stamp++;
            final int mark = stamp;
            for (int k = 0; k < moved.length; k++) {
                overrideMark[moved[k]] = mark;
                overrideAt[moved[k]] = move.to()[k];
            }
            final LinkedHashMap<Integer, Point> derived = c
                .derivePatterns(patterns, machines, i -> overrideMark[i] == mark ? overrideAt[i] : pos[i]);
            int extra = 0;
            for (final int d : derived.keySet()) if (overrideMark[d] != mark) extra++;
            final int[] allMoved = Arrays.copyOf(moved, moved.length + extra);
            final Point[] allTo = Arrays.copyOf(move.to(), moved.length + extra);
            int k = moved.length;
            for (final Map.Entry<Integer, Point> entry : derived.entrySet()) {
                if (overrideMark[entry.getKey()] == mark) continue;
                allMoved[k] = entry.getKey();
                allTo[k++] = entry.getValue();
            }
            return new Move(allMoved, allTo);
        }

        private boolean isOwnDrawer(final int i, final int p) {
            for (final int d : patterns.attached[i]) if (d == p) return true;
            return false;
        }

        private static Move single(final int i, final Point to) {
            return new Move(new int[] { i }, new Point[] { to });
        }

        private Move shifted(final int[] group, final double dx, final double dy) {
            final Point[] to = new Point[group.length];
            for (int k = 0; k < group.length; k++) to[k] = new Point(pos[group[k]].x() + dx, pos[group[k]].y() + dy);
            return new Move(group, to);
        }

        private int[] freeOnly(final int[] cards) {
            int count = 0;
            for (final int g : cards) if (patterns.placement[g] == null) count++;
            final int[] out = new int[count];
            count = 0;
            for (final int g : cards) if (patterns.placement[g] == null) out[count++] = g;
            return out;
        }

        private boolean anyColumn(final int[] group) {
            for (final int g : group) if (lattice.column()[g]) return true;
            return false;
        }

        /** A random move of free cards, or null for a roll that found nothing to do. */
        private Move propose() {
            final double roll = random.next();
            final int i = freeCards[pick(freeCards.length)];
            if (roll < 0.45) {
                // Beside a partner (never beside a drawer of its own: that follows it).
                final int[] partnerWires = new int[wiresOf[i].length];
                int count = 0;
                for (final int wi : wiresOf[i]) if (!isOwnDrawer(i, c.other(wi, i))) partnerWires[count++] = wi;
                if (count == 0) return null;
                final int wi = partnerWires[pick(count)];
                final int p = c.other(wi, i);
                final Point spot = fitNear(i, besideSpot(i, p, wi));
                return spot != null ? single(i, spot) : null;
            }
            if (roll < 0.7) {
                // A nudge: cells vertically; a column, or cells for a drawer, sideways.
                final int sign = random.next() < 0.5 ? -1 : 1;
                if (random.next() < 0.5) {
                    final double step = lattice.column()[i] ? lattice.pitch() : (1 + pick(6)) * Grid.CELL;
                    return single(i, new Point(pos[i].x() + sign * step, pos[i].y()));
                }
                return single(i, new Point(pos[i].x(), pos[i].y() + sign * (1 + pick(6)) * Grid.CELL));
            }
            if (roll < 0.9) {
                // A swap.
                final int k = freeCards[pick(freeCards.length)];
                if (k == i) return null;
                return new Move(
                    new int[] { i, k },
                    new Point[] { c.quantise(lattice, i, pos[k]), c.quantise(lattice, k, pos[i]) });
            }
            if (!bridgeSides.isEmpty() && random.next() < 0.5) {
                // A whole side of a bridge wire, shifted together.
                final int[] group = freeOnly(bridgeSides.get(pick(bridgeSides.size())));
                if (group.length == 0) return null;
                final int sign = random.next() < 0.5 ? -1 : 1;
                final boolean columnMove = anyColumn(group);
                final double dx = random.next() < 0.5
                    ? sign * (columnMove ? lattice.pitch() : (1 + pick(6)) * Grid.CELL)
                    : 0;
                final double dy = dx == 0 ? sign * (1 + pick(6)) * Grid.CELL : 0;
                return shifted(group, dx, dy);
            }
            // A neighbourhood shifted together, by a column or by cells.
            final int[] around = new int[neighbours[i].length + 1];
            around[0] = i;
            System.arraycopy(neighbours[i], 0, around, 1, neighbours[i].length);
            final int[] group = freeOnly(around);
            final int sign = random.next() < 0.5 ? -1 : 1;
            final boolean columnMove = anyColumn(group);
            final double dx = random.next() < 0.5 ? sign * (columnMove ? lattice.pitch() : (1 + pick(4)) * Grid.CELL)
                : 0;
            final double dy = dx == 0 ? sign * (1 + pick(4)) * Grid.CELL : 0;
            return shifted(group, dx, dy);
        }

        Point[] run(final int trials, final double heat, final IntConsumer onProgress) {
            // Temperature from the moves themselves: the median uphill step, so the schedule means the same on a tiny
            // board as on a huge one.
            final double[] samples = new double[60];
            int sampled = 0;
            for (int s = 0; s < 200 && sampled < 60; s++) {
                final Move proposed = propose();
                if (proposed == null) continue;
                final Move move = expand(proposed);
                if (!attempt(move.cards(), move.to())) continue;
                undo();
                if (delta > 0) samples[sampled++] = delta;
            }
            Arrays.sort(samples, 0, sampled);
            final double t0 = Math.max(1, (sampled > 0 ? samples[sampled / 2] * 2 : 200) * heat);
            final double tEnd = t0 / 2000;
            final double cool = StrictMath.log(tEnd / t0) / Math.max(1, trials);
            for (int trial = 0; trial < trials; trial++) {
                final double temperature = t0 * StrictMath.exp(cool * trial);
                final Move proposed = propose();
                if (proposed == null) continue;
                final Move move = expand(proposed);
                if (!attempt(move.cards(), move.to())) continue;
                if (delta <= 0 || random.next() < StrictMath.exp(-delta / temperature)) {
                    commit();
                    if (score < best - 1e-6) {
                        best = score;
                        bestPos = pos.clone();
                    }
                } else {
                    undo();
                }
                if (trial % 500 == 0) onProgress.accept(trial);
            }
            onProgress.accept(trials);
            return bestPos;
        }
    }

    /* ---------------------------------------------------------------------------------------------------------- */
    /* Helpers. */
    /* ---------------------------------------------------------------------------------------------------------- */

    /** A drawer leaves its pattern and becomes a card the search moves. */
    private static void freePlacement(final Patterns patterns, final int d) {
        final Placement place = patterns.placement[d];
        if (place == null) return;
        patterns.placement[d] = null;
        if (place instanceof Bud bud) {
            patterns.attached[bud.machine()] = without(patterns.attached[bud.machine()], d);
            return;
        }
        final Pair pair = (Pair) place;
        patterns.attached[pair.a()] = without(patterns.attached[pair.a()], d);
        patterns.attached[pair.b()] = without(patterns.attached[pair.b()], d);
        boolean stillShared = false;
        for (final int x : patterns.attached[pair.a()]) {
            if (patterns.placement[x] instanceof Pair other && (other.a() == pair.b() || other.b() == pair.b())) {
                stillShared = true;
                break;
            }
        }
        if (!stillShared) {
            patterns.pairPartners[pair.a()] = without(patterns.pairPartners[pair.a()], pair.b());
            patterns.pairPartners[pair.b()] = without(patterns.pairPartners[pair.b()], pair.a());
        }
    }

    /**
     * The free grid spot nearest {@code want} for a card of this size, within {@code radius} steps: rings by Manhattan
     * distance, the nearest point of a ring first. {@code others} are the first {@code count} rects, the one at index
     * {@code skip} ignored; null when every spot is taken.
     */
    private static Point nearestFree(final Point want, final double width, final double height, final Rect[] others,
        final int count, final int skip, final double gap, final int radius, final double stepX) {
        if (fits(want.x(), want.y(), width, height, others, count, skip, gap)) return want;
        for (int r = 1; r <= radius; r++) {
            double bestX = 0, bestY = 0, bestD = Double.POSITIVE_INFINITY;
            boolean found = false;
            for (int dx = -r; dx <= r; dx++) {
                final int dy = r - Math.abs(dx);
                for (int side = dy == 0 ? 1 : 0; side < 2; side++) {
                    final int sy = dy == 0 ? 0 : side == 0 ? -1 : 1;
                    final double x = want.x() + dx * stepX;
                    final double y = want.y() + sy * dy * Grid.CELL;
                    final double d = dx * dx + dy * dy;
                    if (d < bestD && fits(x, y, width, height, others, count, skip, gap)) {
                        bestX = x;
                        bestY = y;
                        bestD = d;
                        found = true;
                    }
                }
            }
            if (found) return new Point(bestX, bestY);
        }
        return null;
    }

    private static boolean fits(final double x, final double y, final double width, final double height,
        final Rect[] others, final int count, final int skip, final double gap) {
        final double right = x + width, bottom = y + height;
        for (int o = 0; o < count; o++) {
            if (o == skip) continue;
            final Rect other = others[o];
            if (overlaps(x, y, right, bottom, other.left(), other.top(), other.right(), other.bottom(), gap))
                return false;
        }
        return true;
    }

    private static boolean overlaps(final Rect a, final Rect b, final double gap) {
        return overlaps(a.left(), a.top(), a.right(), a.bottom(), b.left(), b.top(), b.right(), b.bottom(), gap);
    }

    /** Whether two boxes overlap or stand closer than {@code gap}. */
    private static boolean overlaps(final double aLeft, final double aTop, final double aRight, final double aBottom,
        final double bLeft, final double bTop, final double bRight, final double bBottom, final double gap) {
        return aLeft < bRight + gap && bLeft < aRight + gap && aTop < bBottom + gap && bTop < aBottom + gap;
    }

    /** A point under one of the eight reflections and rotations, the identity first. */
    private static Point orient(final int variant, final Point p) {
        return switch (variant) {
            case 0 -> new Point(p.x(), p.y());
            case 1 -> new Point(-p.x(), p.y());
            case 2 -> new Point(p.x(), -p.y());
            case 3 -> new Point(-p.x(), -p.y());
            case 4 -> new Point(p.y(), p.x());
            case 5 -> new Point(-p.y(), p.x());
            case 6 -> new Point(p.y(), -p.x());
            default -> new Point(-p.y(), -p.x());
        };
    }

    /** A wire's weight in the points: from its width when known, else its own weight (1 when unset), at least 0.01. */
    private static double weightOf(final ArrangeWire wire) {
        return wire.width() != null ? Proxy.wireWeight(wire.width())
            : Math.max(wire.weight() != null ? wire.weight() : 1, 0.01);
    }

    private static double mean(final List<Double> values) {
        double sum = 0;
        for (final double v : values) sum += v;
        return sum / values.size();
    }

    private static void add(final Map<Long, List<Member>> map, final long key, final int d, final double order) {
        map.computeIfAbsent(key, k -> new ArrayList<>())
            .add(new Member(d, order));
    }

    private static long lineKey(final int machine, final boolean right) {
        return 2L * machine + (right ? 1 : 0);
    }

    private static long rowKey(final int upper, final int lower) {
        return (long) upper << 32 | lower;
    }

    private static int[] without(final int[] values, final int value) {
        int count = 0;
        for (final int v : values) if (v != value) count++;
        if (count == values.length) return values;
        final int[] out = new int[count];
        count = 0;
        for (final int v : values) if (v != value) out[count++] = v;
        return out;
    }

    private static int[] toArray(final List<Integer> values) {
        final int[] out = new int[values.size()];
        for (int i = 0; i < out.length; i++) out[i] = values.get(i);
        return out;
    }

    private static Point[] points(final double[] x, final double[] y) {
        final Point[] out = new Point[x.length];
        for (int i = 0; i < x.length; i++) out[i] = new Point(x[i], y[i]);
        return out;
    }

    /** JavaScript's Math.round: the nearest integer, halves toward positive infinity. */
    static double jsRound(final double v) {
        final double floor = Math.floor(v);
        return v - floor >= 0.5 ? floor + 1 : floor;
    }

    /**
     * Math.hypot of two numbers as V8 computes it (each scaled by the larger, the squares Kahan-summed, the root
     * scaled back), which can differ from {@link Math#hypot} in the last bit.
     */
    static double hypot(final double a, final double b) {
        return Proxy.hypot(a, b);
    }
}

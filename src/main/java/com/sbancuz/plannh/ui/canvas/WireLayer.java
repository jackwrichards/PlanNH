package com.sbancuz.plannh.ui.canvas;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.sbancuz.plannh.data.flowchart.Drawer;
import com.sbancuz.plannh.data.flowchart.Edge;
import com.sbancuz.plannh.data.flowchart.Graph;
import com.sbancuz.plannh.data.flowchart.Node;
import com.sbancuz.plannh.data.flowchart.Port;
import com.sbancuz.plannh.data.flowchart.balancer.BalanceResult;
import com.sbancuz.plannh.data.flowchart.balancer.Balancer;
import com.sbancuz.plannh.gui.ArrowRouter;
import com.sbancuz.plannh.ui.BoardSession;
import com.sbancuz.plannh.ui.Resources;
import com.sbancuz.plannh.ui.card.CardLayout;
import com.sbancuz.plannh.ui.card.RecipeCard;
import com.sbancuz.plannh.ui.drawer.DrawerCard;
import com.sbancuz.plannh.ui.theme.Hyb;

/**
 * The board's wires, Factory Flow style: one per resource between two objects, routed on a grid with right angles
 * around cards and drawers, drawn in the resource's colour, thicker with more flow, with arrowheads along the run.
 * Everything is in world space; the canvas draws this under the cards with its pan and zoom applied.
 */
final class WireLayer {

    enum Kind {
        /** Card output to card input. */
        EDGE,
        /** A drawer and the port it holds. */
        LINK
    }

    record Wire(UUID key, Kind kind, @Nullable Edge edge, @Nullable Drawer drawer, @Nullable Drawer.Link link,
        int color, float width, double perSecond, List<int[]> path) {}

    private static final ArrowRouter ROUTER = new ArrowRouter(6, 12);
    private static final int CHEVRON_EVERY = 64;

    private final BoardSession session;
    private List<Wire> wires = List.of();
    private long builtSignature = Long.MIN_VALUE;

    WireLayer(final BoardSession session) {
        this.session = session;
    }

    List<Wire> wires(final Map<UUID, RecipeCard> cards, final Map<UUID, DrawerCard> drawers) {
        final long signature = signature(cards);
        if (signature != builtSignature) {
            builtSignature = signature;
            wires = build(cards, drawers);
        }
        return wires;
    }

    /** Moves with the plan, the latest answer, and any card whose height changed since the last build. */
    private long signature(final Map<UUID, RecipeCard> cards) {
        long sig = session.graph()
            .version() * 31 + System.identityHashCode(session.result());
        for (final RecipeCard card : cards.values()) {
            if (card.layout() != null) sig = sig * 31 + card.layout().height;
        }
        return sig;
    }

    private List<Wire> build(final Map<UUID, RecipeCard> cards, final Map<UUID, DrawerCard> drawers) {
        final Graph graph = session.graph();
        final List<ArrowRouter.Rect> obstacles = new ArrayList<>();
        for (final RecipeCard card : cards.values()) {
            if (card.model() == null || card.layout() == null) continue;
            final Node n = card.model().node;
            obstacles.add(new ArrowRouter.Rect(n.x, n.y, CardLayout.W, card.layout().height));
        }
        for (final Drawer d : graph.getDrawers()) {
            obstacles.add(new ArrowRouter.Rect(d.getX(), d.getY(), DrawerCard.W, DrawerCard.H));
        }

        final List<ArrowRouter.Request> requests = new ArrayList<>();
        final List<Wire> pending = new ArrayList<>();
        final BalanceResult result = session.result();
        for (final Edge e : graph.getEdges()) {
            final Node src = graph.nodes.get(e.sourceNodeId), dst = graph.nodes.get(e.targetNodeId);
            if (src == null || dst == null
                || e.sourceOutputIndex >= src.outputs.size()
                || e.targetInputIndex >= dst.inputs.size()) continue;
            final Port<?> port = src.outputs.get(e.sourceOutputIndex);
            final double flow = edgeFlow(result, e);
            requests.add(
                new ArrowRouter.Request(
                    e.id,
                    src.x + CardLayout.W,
                    src.y + CardLayout.anchorY(e.sourceOutputIndex),
                    dst.x,
                    dst.y + CardLayout.anchorY(e.targetInputIndex)));
            pending.add(new Wire(e.id, Kind.EDGE, e, null, null, colorOf(port), width(flow, port), flow, null));
        }
        for (final Drawer d : graph.getDrawers()) {
            final boolean source = d.getKind()
                .linksInputs();
            for (final Drawer.Link link : d.getLinks()) {
                final Node n = graph.nodes.get(link.nodeId());
                if (n == null) continue;
                final List<Port<?>> ports = source ? n.inputs : n.outputs;
                if (link.portIndex() >= ports.size()) continue;
                final Port<?> port = ports.get(link.portIndex());
                final UUID key = UUID.nameUUIDFromBytes(
                    (d.getId() + ":" + link.nodeId() + ":" + link.portIndex()).getBytes(StandardCharsets.UTF_8));
                final int portY = n.y + CardLayout.anchorY(link.portIndex());
                if (source) {
                    requests.add(
                        new ArrowRouter.Request(
                            key,
                            d.getX() + DrawerCard.W,
                            d.getY() + DrawerCard.ANCHOR_Y,
                            n.x,
                            portY));
                } else {
                    requests.add(
                        new ArrowRouter.Request(
                            key,
                            n.x + CardLayout.W,
                            portY,
                            d.getX(),
                            d.getY() + DrawerCard.ANCHOR_Y));
                }
                final double flow = linkFlow(result, n, source, link.portIndex());
                pending.add(new Wire(key, Kind.LINK, null, d, link, colorOf(port), width(flow, port), flow, null));
            }
        }

        final Map<UUID, List<int[]>> routes = ROUTER.route(obstacles, requests);
        final List<Wire> built = new ArrayList<>(pending.size());
        for (int i = 0; i < pending.size(); i++) {
            final Wire w = pending.get(i);
            List<int[]> path = routes.get(w.key());
            if (path == null || path.size() < 2) path = elbow(requests.get(i));
            built.add(
                new Wire(w.key(), w.kind(), w.edge(), w.drawer(), w.link(), w.color(), w.width(), w.perSecond(), path));
        }
        return built;
    }

    private static List<int[]> elbow(final ArrowRouter.Request r) {
        final int mid = (r.sx() + r.dx()) / 2;
        return List.of(
            new int[] { r.sx(), r.sy() },
            new int[] { mid, r.sy() },
            new int[] { mid, r.dy() },
            new int[] { r.dx(), r.dy() });
    }

    private static double edgeFlow(@Nullable final BalanceResult result, final Edge e) {
        if (result == null) return 0;
        if (result instanceof final BalanceResult.Solved solved && solved.auto() != null) {
            final Double f = solved.auto().edgeFlowsPerSecond.get(e.id);
            if (f != null) return f;
        }
        final Balancer.NodeBalance b = result.nodeBalances()
            .get(e.sourceNodeId);
        return b == null ? 0 : b.outputPerSecond(e.sourceOutputIndex);
    }

    private static double linkFlow(@Nullable final BalanceResult result, final Node n, final boolean input,
        final int port) {
        if (result == null) return 0;
        final Balancer.NodeBalance b = result.nodeBalances()
            .get(n.id);
        if (b == null) return 0;
        return input ? b.inputPerSecond(port) : b.outputPerSecond(port);
    }

    /** The resource's colour, lifted toward grey when it is too dark to see on the board. */
    private static int colorOf(final Port<?> port) {
        final int c = port.getArrowColor();
        int r = c >> 16 & 0xFF, g = c >> 8 & 0xFF, b = c & 0xFF;
        final double luma = (0.299 * r + 0.587 * g + 0.114 * b) / 255;
        if (luma < 0.4) {
            final double t = (0.4 - luma) / 0.4 * 0.6;
            r = (int) (r + (0xB0 - r) * t);
            g = (int) (g + (0xB2 - g) * t);
            b = (int) (b + (0xB8 - b) * t);
        }
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    /** 1 px when nothing flows, then thicker with the logarithm of the flow; fluids count per 1000 L. */
    private static float width(final double perSecond, final Port<?> port) {
        if (!(perSecond > 0)) return 1;
        final double units = Resources.isFluid(Resources.key(port)) ? perSecond / 1000 : perSecond;
        return (float) Math.min(6, 2 + Math.log10(1 + units * 20));
    }

    // region Drawing

    void draw(final List<Wire> wires) {
        for (final Wire w : wires) drawWire(w.path(), w.color(), w.width(), w.perSecond() > 0);
    }

    static void drawWire(final List<int[]> path, final int color, final float width, final boolean flowing) {
        final int under = 0xA0101114;
        for (int i = 1; i < path.size(); i++) segment(path.get(i - 1), path.get(i), width + 2, under);
        final int fill = flowing ? color : (color & 0x00FFFFFF) | 0x80000000;
        for (int i = 1; i < path.size(); i++) segment(path.get(i - 1), path.get(i), width, fill);
        if (path.size() < 2) return;
        final int dark = darker(fill);
        for (int i = 1; i < path.size(); i++) {
            final int[] a = path.get(i - 1), b = path.get(i);
            final int len = Math.abs(b[0] - a[0]) + Math.abs(b[1] - a[1]);
            if (len < CHEVRON_EVERY) continue;
            final int dx = Integer.signum(b[0] - a[0]), dy = Integer.signum(b[1] - a[1]);
            for (int at = CHEVRON_EVERY / 2; at < len - 16; at += CHEVRON_EVERY) {
                arrow(a[0] + dx * at, a[1] + dy * at, dx, dy, Math.max(3, width + 1), dark);
            }
        }
        final int[] end = path.get(path.size() - 1), before = path.get(path.size() - 2);
        arrow(
            end[0],
            end[1],
            Integer.signum(end[0] - before[0]),
            Integer.signum(end[1] - before[1]),
            Math.max(4, width + 2),
            fill);
    }

    private static int darker(final int argb) {
        final int r = (argb >> 16 & 0xFF) * 3 / 5, g = (argb >> 8 & 0xFF) * 3 / 5, b = (argb & 0xFF) * 3 / 5;
        return argb & 0xFF000000 | r << 16 | g << 8 | b;
    }

    private static void segment(final int[] a, final int[] b, final float width, final int color) {
        final float h = width / 2;
        if (a[1] == b[1]) {
            final int x0 = Math.min(a[0], b[0]), x1 = Math.max(a[0], b[0]);
            Hyb.rect(x0 - h, a[1] - h, x1 - x0 + width, width, color);
        } else if (a[0] == b[0]) {
            final int y0 = Math.min(a[1], b[1]), y1 = Math.max(a[1], b[1]);
            Hyb.rect(a[0] - h, y0 - h, width, y1 - y0 + width, color);
        } else {
            // Not orthogonal (the router never does this, a fallback might): draw it as an elbow.
            segment(a, new int[] { b[0], a[1] }, width, color);
            segment(new int[] { b[0], a[1] }, b, width, color);
        }
    }

    /** A filled arrowhead with its tip at (x, y), pointing along (dx, dy), {@code size} long. */
    private static void arrow(final int x, final int y, final int dx, final int dy, final float size, final int color) {
        final int len = Math.round(size * 1.5f);
        for (int k = 0; k < len; k++) {
            final float half = (len - k) * size / (2f * len) + 0.5f;
            if (dx != 0) Hyb.rect(x - dx * (len - k), y - half, 1, half * 2, color);
            else Hyb.rect(x - half, y - dy * (len - k), half * 2, 1, color);
        }
    }

    // endregion

    /** The wire within a few world pixels of a point, topmost first. */
    @Nullable
    Wire hit(final float wx, final float wy) {
        for (int i = wires.size() - 1; i >= 0; i--) {
            final Wire w = wires.get(i);
            final float tolerance = Math.max(3, w.width() / 2 + 2);
            final List<int[]> path = w.path();
            for (int k = 1; k < path.size(); k++) {
                final int[] a = path.get(k - 1), b = path.get(k);
                final float x0 = Math.min(a[0], b[0]) - tolerance, x1 = Math.max(a[0], b[0]) + tolerance;
                final float y0 = Math.min(a[1], b[1]) - tolerance, y1 = Math.max(a[1], b[1]) + tolerance;
                if (wx >= x0 && wx <= x1 && wy >= y0 && wy <= y1) return w;
            }
        }
        return null;
    }
}

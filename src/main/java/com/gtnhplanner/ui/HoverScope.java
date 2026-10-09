package com.gtnhplanner.ui;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import com.gtnhplanner.data.flowchart.Drawer;
import com.gtnhplanner.data.flowchart.Edge;
import com.gtnhplanner.data.flowchart.Graph;

/**
 * What glows while the mouse is on something, as on the website: a port lights its own wires and what is at their far
 * ends; a drawer its wires and the ports they reach; a wire itself and its two ends. Only an overview row lights a
 * resource everywhere it flows ({@link #resource}).
 */
public final class HoverScope {

    public static final HoverScope NONE = new HoverScope(null);

    /** The overview's row: every port, drawer and wire carrying it. Null for a scope of particular things. */
    @Nullable
    private final String resource;
    private final Set<String> ports = new HashSet<>(), links = new HashSet<>();
    private final Set<UUID> drawers = new HashSet<>(), edges = new HashSet<>();

    private HoverScope(@Nullable final String resource) {
        this.resource = resource;
    }

    /** Everything carrying a resource (the overview's row under the mouse). */
    public static HoverScope resource(final String key) {
        return key == null || key.isEmpty() ? NONE : new HoverScope(key);
    }

    private static String port(final UUID node, final boolean output, final int index) {
        return node + (output ? ":o:" : ":i:") + index;
    }

    private static String link(final UUID drawer, final UUID node, final int index) {
        return drawer + ":" + node + ":" + index;
    }

    /** A port: it, every wire on it, and the port or drawer at each wire's far end. */
    public static HoverScope ofPort(final Graph graph, final UUID node, final boolean output, final int index) {
        final HoverScope s = new HoverScope(null);
        s.ports.add(port(node, output, index));
        for (final Edge e : graph.getEdges()) {
            if (output && e.sourceNodeId.equals(node) && e.sourceOutputIndex == index) {
                s.edges.add(e.id);
                s.ports.add(port(e.targetNodeId, false, e.targetInputIndex));
            } else if (!output && e.targetNodeId.equals(node) && e.targetInputIndex == index) {
                s.edges.add(e.id);
                s.ports.add(port(e.sourceNodeId, true, e.sourceOutputIndex));
            }
        }
        for (final Drawer d : graph.getDrawers()) {
            if (d.getKind()
                .linksInputs() == output) continue;
            for (final Drawer.Link l : d.getLinks()) if (l.nodeId()
                .equals(node) && l.portIndex() == index) {
                    s.drawers.add(d.getId());
                    s.links.add(link(d.getId(), node, index));
                }
        }
        return s;
    }

    /** A drawer: it, its wires and the ports they reach. */
    public static HoverScope ofDrawer(final Drawer d) {
        final HoverScope s = new HoverScope(null);
        s.drawers.add(d.getId());
        final boolean inputs = d.getKind()
            .linksInputs();
        for (final Drawer.Link l : d.getLinks()) {
            s.links.add(link(d.getId(), l.nodeId(), l.portIndex()));
            s.ports.add(port(l.nodeId(), !inputs, l.portIndex()));
        }
        return s;
    }

    /** A wire between two cards, and the ports at its ends. */
    public static HoverScope ofEdge(final Edge e) {
        final HoverScope s = new HoverScope(null);
        s.edges.add(e.id);
        s.ports.add(port(e.sourceNodeId, true, e.sourceOutputIndex));
        s.ports.add(port(e.targetNodeId, false, e.targetInputIndex));
        return s;
    }

    /** A drawer's wire, the drawer and the port at its other end. */
    public static HoverScope ofLink(final Drawer d, final Drawer.Link l) {
        final HoverScope s = new HoverScope(null);
        s.drawers.add(d.getId());
        s.links.add(link(d.getId(), l.nodeId(), l.portIndex()));
        s.ports.add(
            port(
                l.nodeId(),
                !d.getKind()
                    .linksInputs(),
                l.portIndex()));
        return s;
    }

    public boolean isEmpty() {
        return resource == null && ports.isEmpty() && drawers.isEmpty() && edges.isEmpty() && links.isEmpty();
    }

    /** Whether a card's port glows; {@code key} is its resource. */
    public boolean port(final UUID node, final boolean output, final int index, final String key) {
        return resource != null ? resource.equals(key) : ports.contains(port(node, output, index));
    }

    public boolean drawer(final Drawer d) {
        return resource != null ? resource.equals(d.getResourceKey()) : drawers.contains(d.getId());
    }

    /** Whether a wire glows: between two cards ({@code edge}), or a drawer's ({@code drawer} and its {@code link}). */
    public boolean wire(@Nullable final Edge edge, @Nullable final Drawer drawer, @Nullable final Drawer.Link link,
        final String key) {
        if (resource != null) return resource.equals(key);
        if (edge != null) return edges.contains(edge.id);
        return drawer != null && link != null && links.contains(link(drawer.getId(), link.nodeId(), link.portIndex()));
    }
}

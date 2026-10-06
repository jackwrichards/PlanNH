package com.sbancuz.plannh.data.flowchart;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A drawer on the board: one resource crossing the plan's boundary, with what the player wants of
 * it. A {@link Kind#SOURCE} supplies node inputs (Oil, Water, a dust from the ME system); a
 * {@link Kind#PRODUCT} takes node outputs and carries the target; a {@link Kind#BYPRODUCT} takes
 * whatever surplus arrives and asks for nothing; {@link Kind#TRASH} voids what arrives.
 *
 * <p>
 * The {@link Rule} and {@link #getRate() rate} constrain the total flow through the drawer's
 * {@link Link links}: for a product, what the linked outputs deliver to it; for a source, what it
 * supplies to the linked inputs. Only sources and products have a rule the solver honours; a
 * byproduct or trash keeps the rule it had (so cycling back restores it) but the solver treats it
 * as {@link Rule#ANY} - see {@link #effectiveRule()}.
 *
 * <p>
 * Minecraft-free on purpose: the resource is remembered as an opaque {@link #getResourceKey() key}
 * the client writes and resolves (e.g. {@code "item:<registry name>:<meta>"} or
 * {@code "fluid:<fluid name>"}), plus a cached {@link #getLabel() label}, so a drawer survives with
 * no links and a save loads where the resource no longer exists.
 *
 * <p>
 * Edits made through the setters do not move the graph's version: call {@link Graph#touch()} after
 * changing a drawer that is on a graph, or use the graph's own drawer methods, which do.
 */
public class Drawer extends GraphData {

    public static final String TYPE = "drawer";

    /** What the drawer is for; decides which side of a node its links are on. */
    public enum Kind {

        /** Supplies node inputs. Links point at input ports. */
        SOURCE,
        /** What the player wants. Links take output ports. */
        PRODUCT,
        /** Takes surplus and never asks for more. Links take output ports. */
        BYPRODUCT,
        /** Voids what arrives. Links take output ports. */
        TRASH;

        /** True when this kind's links are node INPUT ports (only {@link #SOURCE}). */
        public boolean linksInputs() {
            return this == SOURCE;
        }

        /** True when the solver honours this kind's rule (sources and products). */
        public boolean hasRule() {
            return this == SOURCE || this == PRODUCT;
        }

        /**
         * The board's cycle key: product, byproduct, trash, then product again. A source has no
         * cycle (its links are inputs) and stays a source.
         */
        public Kind next() {
            return switch (this) {
                case SOURCE -> SOURCE;
                case PRODUCT -> BYPRODUCT;
                case BYPRODUCT -> TRASH;
                case TRASH -> PRODUCT;
            };
        }
    }

    /** What the drawer's total flow must satisfy against {@link #getRate()}. */
    public enum Rule {
        /** No constraint; the drawer reads what the plan does. */
        ANY,
        /** total >= rate. */
        AT_LEAST,
        /** total == rate. */
        EXACTLY,
        /** total <= rate. */
        AT_MOST
    }

    /**
     * One node port the drawer is attached to. The direction is the drawer's: a source's links are
     * input ports, every other kind's are output ports. Immutable; equal by value.
     */
    public static final class Link {

        private final UUID nodeId;
        private final int portIndex;

        public Link(final UUID nodeId, final int portIndex) {
            this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
            this.portIndex = portIndex;
        }

        public UUID nodeId() {
            return nodeId;
        }

        public int portIndex() {
            return portIndex;
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) return true;
            if (!(o instanceof final Link other)) return false;
            return portIndex == other.portIndex && nodeId.equals(other.nodeId);
        }

        @Override
        public int hashCode() {
            return 31 * nodeId.hashCode() + portIndex;
        }

        @Override
        public String toString() {
            return nodeId + "#" + portIndex;
        }
    }

    private Kind kind = Kind.PRODUCT;
    private Rule rule = Rule.ANY;
    /** Units per second (items/s, or L/s for fluids). Meaningless under {@link Rule#ANY}. */
    private double rate;
    private List<Link> links = new ArrayList<>();
    /** Opaque resource identity the client writes and resolves; never parsed by the engine. */
    private String resourceKey = "";
    /** The resource's display name as last resolved by the client, for when it cannot resolve. */
    private String label = "";

    /** A product drawer with no resource, no links and no rule; also Gson's constructor. */
    public Drawer() {
        super(UUID.randomUUID());
    }

    public Drawer(final Kind kind, final String resourceKey) {
        this();
        this.kind = Objects.requireNonNull(kind, "kind");
        this.resourceKey = Objects.requireNonNull(resourceKey, "resourceKey");
    }

    /** Only for tests and loaders that need a fixed id. */
    public Drawer(final UUID id, final Kind kind, final String resourceKey) {
        super(id);
        this.kind = Objects.requireNonNull(kind, "kind");
        this.resourceKey = Objects.requireNonNull(resourceKey, "resourceKey");
    }

    @Override
    public String getType() {
        return TYPE;
    }

    public Kind getKind() {
        return kind;
    }

    /**
     * Changes the kind. Switching between a source and an output kind would flip what every link
     * means (inputs versus outputs), so that drops the links; cycling among product, byproduct and
     * trash keeps them.
     */
    public void setKind(final Kind kind) {
        Objects.requireNonNull(kind, "kind");
        if (kind.linksInputs() != this.kind.linksInputs()) links.clear();
        this.kind = kind;
    }

    public Rule getRule() {
        return rule;
    }

    public void setRule(final Rule rule) {
        this.rule = Objects.requireNonNull(rule, "rule");
    }

    public double getRate() {
        return rate;
    }

    /** Units per second; negative or non-finite rates are stored as 0. */
    public void setRate(final double rate) {
        this.rate = Double.isFinite(rate) && rate > 0 ? rate : 0;
    }

    /** Sets the rule and the rate together, the way the drawer's rate box does. */
    public void setTarget(final Rule rule, final double rate) {
        setRule(rule);
        setRate(rate);
    }

    /** The links, read-only; change them through {@link #addLink}/{@link #removeLink} or the graph. */
    public List<Link> getLinks() {
        return Collections.unmodifiableList(links);
    }

    /** Adds a link unless it is already there; true when it was added. */
    public boolean addLink(final Link link) {
        Objects.requireNonNull(link, "link");
        if (links.contains(link)) return false;
        links.add(link);
        return true;
    }

    public boolean removeLink(final Link link) {
        return links.remove(link);
    }

    /** Drops every link to {@code nodeId}; true when any went. */
    public boolean removeLinksTo(final UUID nodeId) {
        return links.removeIf(
            l -> l.nodeId()
                .equals(nodeId));
    }

    public boolean isLinked(final UUID nodeId, final int portIndex) {
        return links.contains(new Link(nodeId, portIndex));
    }

    public String getResourceKey() {
        return resourceKey;
    }

    public void setResourceKey(final String resourceKey) {
        this.resourceKey = Objects.requireNonNull(resourceKey, "resourceKey");
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(final String label) {
        this.label = Objects.requireNonNull(label, "label");
    }

    /** The rule the solver applies: the stored rule for sources and products, ANY otherwise. */
    public Rule effectiveRule() {
        return kind.hasRule() ? rule : Rule.ANY;
    }

    /**
     * Whether this drawer sets the plan's scale on its own: an at-least or exactly rule with a
     * positive rate. An at-most rule only bounds, and a zero rate is satisfied by doing nothing.
     */
    public boolean isAnchor() {
        final Rule r = effectiveRule();
        return (r == Rule.AT_LEAST || r == Rule.EXACTLY) && rate > 0;
    }

    /**
     * Repairs what a hand-edited or older save can leave behind: unknown enum names (read as null),
     * a missing link list, links without a node, a non-finite rate.
     */
    void sanitize() {
        if (kind == null) kind = Kind.PRODUCT;
        if (rule == null) rule = Rule.ANY;
        if (!Double.isFinite(rate) || rate < 0) rate = 0;
        if (resourceKey == null) resourceKey = "";
        if (label == null) label = "";
        final List<Link> clean = new ArrayList<>();
        if (links != null) {
            for (final Link link : links) {
                if (link != null && link.nodeId != null && !clean.contains(link)) clean.add(link);
            }
        }
        links = clean;
    }

    /** Whether this drawer holds the port, given its own direction. */
    public boolean holds(final UUID nodeId, final int portIndex, final boolean input) {
        return kind.linksInputs() == input && isLinked(nodeId, portIndex);
    }
}

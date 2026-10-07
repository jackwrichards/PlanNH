package com.gtnhplanner.importer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Everything an import could not carry over as it was, for the player to read: cards left out and why, recipes taken
 * with differences, features dropped or turned into something else, wires that found no port.
 */
public final class ImportReport {

    public enum Kind {
        /** A card left out because no in-game recipe is the plan's recipe. */
        UNMATCHED,
        /** A card whose in-game recipe differs from the plan's in amounts or timing. */
        FUZZY,
        /** Something of the plan with no GTNH Planner counterpart, left out. */
        DROPPED,
        /** Something of the plan carried over as something else. */
        CONVERTED,
        /** A wire or a drawer link that found no port. */
        WIRE,
        /** Worth knowing; nothing was lost. */
        NOTE
    }

    /** @param subject what it is about: a card's recipe name, a drawer's resource, "Plan" */
    public record Entry(Kind kind, String subject, String message) {

        @Override
        public String toString() {
            return kind + " " + subject + ": " + message;
        }
    }

    private final List<Entry> entries = new ArrayList<>();
    private int cards, wires, drawers;

    public void add(final Kind kind, final String subject, final String message) {
        entries.add(new Entry(kind, subject, message));
    }

    public List<Entry> entries() {
        return Collections.unmodifiableList(entries);
    }

    public List<Entry> entries(final Kind kind) {
        final List<Entry> out = new ArrayList<>();
        for (final Entry e : entries) if (e.kind() == kind) out.add(e);
        return out;
    }

    /** True when nothing was left out or changed (notes aside). */
    public boolean isClean() {
        for (final Entry e : entries) if (e.kind() != Kind.NOTE) return false;
        return true;
    }

    void setCounts(final int cards, final int wires, final int drawers) {
        this.cards = cards;
        this.wires = wires;
        this.drawers = drawers;
    }

    public int cards() {
        return cards;
    }

    public int wires() {
        return wires;
    }

    public int drawers() {
        return drawers;
    }

    /** One line: what came over and how much did not. */
    public String summary() {
        final int unmatched = entries(Kind.UNMATCHED).size(), fuzzy = entries(Kind.FUZZY).size();
        final StringBuilder s = new StringBuilder().append(cards)
            .append(cards == 1 ? " card, " : " cards, ")
            .append(wires)
            .append(wires == 1 ? " wire, " : " wires, ")
            .append(drawers)
            .append(drawers == 1 ? " drawer" : " drawers");
        if (unmatched > 0) s.append("; ")
            .append(unmatched)
            .append(" left out (no recipe in game)");
        if (fuzzy > 0) s.append("; ")
            .append(fuzzy)
            .append(" with different numbers");
        return s.toString();
    }

    @Override
    public String toString() {
        final StringBuilder s = new StringBuilder(summary());
        for (final Entry e : entries) s.append('\n')
            .append(e);
        return s.toString();
    }
}

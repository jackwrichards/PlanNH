package com.sbancuz.plannh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.sbancuz.plannh.data.flowchart.Edge;
import com.sbancuz.plannh.layout.AutoLayout.LayoutNode;
import com.sbancuz.plannh.layout.BoardArrange;
import com.sbancuz.plannh.layout.BoardArrange.Box;

/**
 * Arrange as on the website: a drawer that serves one card is pinned beside it, lined up with its siblings, instead
 * of being laid out as a column of its own (which centred it in a column of cards, staggered it, and pushed the next
 * card off to the side).
 */
class BoardArrangeTest {

    private record N(UUID id, String machineName, int worldWidth, int worldHeight, int inputCount, int outputCount)
        implements LayoutNode {}

    private static final int CARD_W = 320, DRAWER_W = 136, DRAWER_H = 68;

    private final List<Box> boxes = new ArrayList<>();
    private final List<Edge> links = new ArrayList<>();

    private UUID card(final String name, final int h, final int ins, final int outs) {
        final UUID id = UUID.nameUUIDFromBytes(name.getBytes());
        boxes.add(new Box(new N(id, name, CARD_W, h, ins, outs), false, false));
        return id;
    }

    private UUID drawer(final String name, final boolean supplies) {
        final UUID id = UUID.nameUUIDFromBytes(name.getBytes());
        boxes.add(new Box(new N(id, name, DRAWER_W, DRAWER_H, supplies ? 0 : 1, supplies ? 1 : 0), true, supplies));
        return id;
    }

    private void wire(final UUID from, final int out, final UUID to, final int in) {
        links.add(new Edge(UUID.randomUUID(), from, to, out, in));
    }

    /** The user's board: a separator with eleven products, feeding a reactor that has supplies of its own. */
    private final UUID separator = card("separator", 300, 1, 12);
    private final UUID reactor = card("reactor", 200, 4, 2);
    private final List<UUID> products = new ArrayList<>(), supplies = new ArrayList<>();

    {
        wire(separator, 0, reactor, 0);
        for (int i = 0; i < 11; i++) {
            final UUID d = drawer("product " + i, false);
            products.add(d);
            wire(separator, i + 1, d, 0);
        }
        for (int i = 0; i < 3; i++) {
            final UUID d = drawer("supply " + i, true);
            supplies.add(d);
            wire(d, 0, reactor, i + 1);
        }
    }

    @Test
    void aCardsOwnDrawersLineUpDownItsSidesInPortOrder() {
        final Map<UUID, int[]> at = BoardArrange.arrange(boxes, links);
        final int[] sep = at.get(separator);
        int y = Integer.MIN_VALUE;
        for (final UUID d : products) {
            assertEquals(sep[0] + CARD_W + BoardArrange.SATELLITE_GAP, at.get(d)[0], "every product in one column");
            assertTrue(at.get(d)[1] > y, "in port order, top down");
            y = at.get(d)[1];
        }
        final int stackTop = at.get(products.get(0))[1], stackBottom = at.get(products.get(10))[1] + DRAWER_H;
        assertEquals(sep[1] + 150, (stackTop + stackBottom) / 2, 1, "centred on the card");
        final int[] re = at.get(reactor);
        for (final UUID d : supplies) {
            assertEquals(re[0] - BoardArrange.SATELLITE_GAP - DRAWER_W, at.get(d)[0], "supplies down the left");
        }
    }

    @Test
    void theNextCardSitsClearOfTheDrawersNotUnderThem() {
        final Map<UUID, int[]> at = BoardArrange.arrange(boxes, links);
        final int productsRight = at.get(products.get(0))[0] + DRAWER_W;
        final int suppliesLeft = at.get(supplies.get(0))[0];
        assertTrue(suppliesLeft > productsRight, "the reactor's block starts right of the separator's drawers");
    }

    @Test
    void nothingOverlaps() {
        final Map<UUID, int[]> at = BoardArrange.arrange(boxes, links);
        for (int i = 0; i < boxes.size(); i++) {
            for (int j = i + 1; j < boxes.size(); j++) {
                final Box a = boxes.get(i), b = boxes.get(j);
                final int[] p = at.get(
                    a.node()
                        .id()),
                    q = at.get(
                        b.node()
                            .id());
                final boolean overlap = p[0] < q[0] + b.node()
                    .worldWidth() && q[0] < p[0]
                        + a.node()
                            .worldWidth()
                    && p[1] < q[1] + b.node()
                        .worldHeight()
                    && q[1] < p[1] + a.node()
                        .worldHeight();
                assertFalse(
                    overlap,
                    a.node()
                        .machineName() + " overlaps "
                        + b.node()
                            .machineName());
            }
        }
    }

    @Test
    void unwiredThingsGoOnAShelfBelow() {
        final UUID loose = drawer("loose", false);
        final Map<UUID, int[]> at = BoardArrange.arrange(boxes, links);
        int bottom = Integer.MIN_VALUE;
        for (final Box b : boxes) {
            if (b.node()
                .id()
                .equals(loose)) continue;
            bottom = Math.max(
                bottom,
                at.get(
                    b.node()
                        .id())[1]
                    + b.node()
                        .worldHeight());
        }
        assertTrue(at.get(loose)[1] >= bottom + BoardArrange.SHELF_GAP - BoardArrange.GRID, "below everything wired");
    }

    @Test
    void aDrawerSharedByTwoCardsStaysInTheColumns() {
        final UUID shared = drawer("shared", true);
        wire(shared, 0, separator, 0);
        wire(shared, 0, reactor, 3);
        final Map<UUID, int[]> at = BoardArrange.arrange(boxes, links);
        final int[] sep = at.get(separator);
        assertTrue(at.get(shared)[0] + DRAWER_W <= sep[0], "left of the first card it feeds");
        assertFalse(
            at.get(shared)[0] == sep[0] - BoardArrange.SATELLITE_GAP - DRAWER_W && at.get(shared)[1] == sep[1],
            "not pinned to either card");
    }
}

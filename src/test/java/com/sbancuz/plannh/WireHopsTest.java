package com.sbancuz.plannh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.sbancuz.plannh.layout.WireHops;
import com.sbancuz.plannh.layout.WireHops.Bump;
import com.sbancuz.plannh.layout.WireHops.Crossed;
import com.sbancuz.plannh.layout.WireHops.Hopped;

/** Wire hops, ported from Factory Flow's src/components/flow/wire-hops.test.ts. */
class WireHopsTest {

    private static final float EPS = 0.01f;

    /** A horizontal wire at {@code y}, drawn behind, 16 thick unless said. */
    private static Crossed across(final float y, final float width) {
        return new Crossed(0, y, 300, y, width);
    }

    private static Crossed across(final float y) {
        return across(y, 16);
    }

    private static List<int[]> path(final int... xy) {
        final java.util.ArrayList<int[]> out = new java.util.ArrayList<>();
        for (int i = 0; i < xy.length; i += 2) out.add(new int[] { xy[i], xy[i + 1] });
        return out;
    }

    @Test
    void drawsAWireThatCrossesNothingAsItIs() {
        final Hopped h = WireHops.build(path(0, 0, 50, 0, 50, 40), List.of(across(200)), 5);
        assertTrue(h.plain());
        assertEquals(
            1,
            h.straights()
                .size());
        assertEquals(
            3,
            h.straights()
                .get(0)
                .size());
        assertTrue(
            h.spans()
                .isEmpty());
    }

    @Test
    void liftsASquareCrossingInAHalfCircleCentredOnIt() {
        final float radius = WireHops.radiusFor(5, 16);
        final Hopped h = WireHops.build(path(150, 200, 150, 0), List.of(across(100)), 5);
        assertEquals(
            1,
            h.bumps()
                .size());
        final Bump b = h.bumps()
            .get(0);
        assertEquals(100 + radius, b.footY(), EPS);
        assertEquals(100 - radius, b.landY(), EPS);
        assertEquals(radius, b.chord() / 2, EPS);
        assertEquals(radius, b.rise(), EPS);
        // A vertical run bumps to its right.
        assertEquals(150 + radius, b.apexX(), EPS);
        assertEquals(
            1,
            h.spans()
                .size());
        assertEquals(
            100 - radius,
            h.spans()
                .get(0)[0],
            EPS);
        assertEquals(
            100 + radius,
            h.spans()
                .get(0)[1],
            EPS);
    }

    @Test
    void carriesAHopRoundABendNextToTheCrossing() {
        // Jack's broken hop (Factory Flow, 2026-09-23): up, then bending up-right 8 short of the crossed wire, so the
        // crossing is on the diagonal within a bump's reach of the corner.
        final float radius = WireHops.radiusFor(5, 16);
        final Hopped h = WireHops.build(path(100, 200, 100, 108, 192, 16), List.of(across(100)), 5);
        assertEquals(
            1,
            h.bumps()
                .size());
        final Bump b = h.bumps()
            .get(0);
        // One foot on each run, both clear of the crossed wire.
        assertEquals(100, b.footX(), EPS);
        assertEquals(100 + radius, b.footY(), EPS);
        assertEquals(100 - radius, b.landY(), EPS);
        // The landing is on the diagonal.
        assertEquals(108 - b.landY(), b.landX() - 100, EPS);
        // The bend is inside the bump, not left behind as a kink after it.
        assertTrue(
            h.spans()
                .get(0)[0] < 92);
        assertTrue(
            h.spans()
                .get(0)[1] > 92);
        // It bulges round the outside of the corner, clear of the crossed wire.
        final float cornerSide = side(b, 100, 108), apexSide = side(b, b.apexX(), b.apexY());
        assertEquals(Math.signum(cornerSide), Math.signum(apexSide));
        assertTrue(b.apexY() < 100);
    }

    private static float side(final Bump b, final float x, final float y) {
        return (b.landX() - b.footX()) * (y - b.footY()) - (b.landY() - b.footY()) * (x - b.footX());
    }

    @Test
    void hopsABendThatSitsExactlyOnTheCrossedWire() {
        final Hopped h = WireHops.build(path(100, 200, 100, 100, 200, 0), List.of(across(100)), 5);
        assertEquals(
            1,
            h.bumps()
                .size());
    }

    @Test
    void keepsA45DegreeHopsFeetAsFarOffTheCrossedWireAsASquareOnes() {
        final float radius = WireHops.radiusFor(5, 16);
        final Bump b = WireHops.build(path(50, 200, 250, 0), List.of(across(100)), 5)
            .bumps()
            .get(0);
        assertEquals(100 + radius, b.footY(), EPS);
        assertEquals(100 - radius, b.landY(), EPS);
        assertEquals(radius, b.rise(), EPS);
    }

    @Test
    void clearsARibbonOfWiresInOneBump() {
        final Hopped h = WireHops.build(path(150, 200, 150, 0), List.of(across(94, 8), across(106, 8)), 5);
        assertEquals(
            1,
            h.bumps()
                .size());
        final float radius = WireHops.radiusFor(5, 8);
        assertEquals(
            94 - radius,
            h.spans()
                .get(0)[0],
            EPS);
        assertEquals(
            106 + radius,
            h.spans()
                .get(0)[1],
            EPS);
    }

    @Test
    void runsBumpsAFewPixelsApartIntoOne() {
        // Factory Flow's oil berry board (2026-09-23): bends on one wire, crosses the next a cell up on its diagonal.
        final Hopped h = WireHops.build(
            path(780, -2000, 780, -2400, 860, -2480),
            List.of(new Crossed(880, -2400, 680, -2400, 6), new Crossed(680, -2420, 880, -2420, 7)),
            5);
        assertEquals(
            1,
            h.bumps()
                .size());
    }

    @Test
    void doesNotHopAWireThatOnlyTouchesTheLine() {
        // Ends on the line (a port), and turns back off it on the side it came.
        assertTrue(
            WireHops.build(path(100, 200, 100, 100), List.of(across(100)), 5)
                .spans()
                .isEmpty());
        assertTrue(
            WireHops.build(path(60, 200, 100, 100, 140, 200), List.of(across(100)), 5)
                .spans()
                .isEmpty());
    }

    @Test
    void doesNotHopALineThatStopsShortOfOvershootingTheWire() {
        assertTrue(
            WireHops.build(path(150, 200, 150, 0), List.of(new Crossed(0, 100, 152, 100, 16)), 5)
                .spans()
                .isEmpty());
    }

    @Test
    void bumpPointsRunFromFootToLandingThroughTheApex() {
        final Bump b = WireHops.build(path(150, 200, 150, 0), List.of(across(100)), 5)
            .bumps()
            .get(0);
        final List<float[]> pts = b.points(14);
        assertEquals(15, pts.size());
        assertEquals(b.footX(), pts.get(0)[0], EPS);
        assertEquals(b.footY(), pts.get(0)[1], EPS);
        assertEquals(b.landY(), pts.get(14)[1], EPS);
        assertEquals(b.apexX(), pts.get(7)[0], EPS);
        assertEquals(b.apexY(), pts.get(7)[1], EPS);
    }
}

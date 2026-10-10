package com.gtnhplanner.machines;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.machines.BacterialVat.Material;
import com.gtnhplanner.machines.BacterialVat.Needs;
import com.gtnhplanner.machines.BacterialVat.Setup;

/**
 * The Bacterial Vat against GT5U 5.09.54.205's MTEBioVat and MTERadioHatch (javap), with the website's
 * bacterial-vat.test.ts golden values: the two must agree.
 */
class BacterialVatTest {

    private static final List<Material> MATERIALS = BacterialVat.sorted(
        List.of(
            new Material("bartworks:gt.bwmetageneratedstick@30", "Thorium 232 Rod", 90, 1),
            new Material("gregtech:gt.metaitem.01@23098", "Uranium 238 Rod", 92, 1),
            new Material("miscutils:uranium232", "Uranium 232 Rod", 92, 1),
            new Material("gregtech:gt.metaitem.01@23100", "Plutonium 239 Rod", 94, 1),
            new Material("gregtech:gt.metaitem.01@23324", "Naquadah Rod", 130, 1),
            new Material("gregtech:gt.metaitem.01@23326", "Enriched Naquadah Rod", 140, 1)));

    @Test
    void readsTheOutputHatchsFillAsTheGameDoes() {
        assertEquals(1001, BacterialVat.multiplierAt(128_000, 256_000));
        assertEquals(1001, BacterialVat.multiplierAt(123_953, 256_000));
        assertEquals(1001, BacterialVat.multiplierAt(132_047, 256_000));
        assertEquals(1000, BacterialVat.multiplierAt(123_952, 256_000));
        assertEquals(1000, BacterialVat.multiplierAt(132_048, 256_000));
        assertEquals(2, BacterialVat.multiplierAt(1, 256_000));
        assertEquals(2, BacterialVat.multiplierAt(256_000, 256_000));
        assertEquals(1, BacterialVat.multiplierAt(0, 256_000));
        assertEquals(1, BacterialVat.multiplierAt(0, 0));
    }

    @Test
    void needsTheRunToFit() {
        final Needs biomass = new Needs(3, 0, false);
        final Setup hv = BacterialVat
            .setup(Map.of("bioVatOutputHatch", "hv", "bioVatFill", "50"), biomass, 100, MATERIALS);
        final BacterialVat.Fill fill = BacterialVat.fill(hv, 100);
        assertEquals(1001, fill.multiplier());
        assertFalse(fill.fits());
        assertEquals(0, fill.keptOut());
        final Setup voiding = BacterialVat
            .setup(Map.of("bioVatOutputHatch", "hv", "bioVatVoid", "void"), biomass, 100, MATERIALS);
        assertEquals(
            32_000,
            BacterialVat.fill(voiding, 100)
                .keptOut());
        assertTrue(
            BacterialVat.gate(biomass, hv, 100)
                .contains("does not fit"));
        // By default the smallest hatch that holds a 1001x run at half full: IV.
        assertEquals(
            "iv",
            BacterialVat.setup(Map.of(), biomass, 100, MATERIALS)
                .hatch()
                .key());
    }

    @Test
    void gatesGlassAndRadiation() {
        final Needs exact = new Needs(6, 94, true);
        assertTrue(
            BacterialVat.gate(exact, BacterialVat.setup(Map.of("bioVatGlass", "5"), exact, 2, MATERIALS), 2)
                .contains("LuV glass"));
        assertNull(BacterialVat.gate(exact, BacterialVat.setup(Map.of(), exact, 2, MATERIALS), 2));
        assertEquals(70, BacterialVat.effectiveSievert(140, 50));
        assertEquals(93, BacterialVat.effectiveSievert(94, 1));
        final Map<String, String> shut = new HashMap<>();
        shut.put("bioVatRadio", "gregtech:gt.metaitem.01@23100");
        shut.put("bioVatShutter", "1");
        assertTrue(
            BacterialVat.gate(exact, BacterialVat.setup(shut, exact, 2, MATERIALS), 2)
                .contains("exactly 94"));
        // GregTech's Uranium 238 before another mod's 232 at the same sieverts.
        assertEquals(
            "Uranium 238 Rod",
            BacterialVat.setup(Map.of(), new Needs(6, 92, false), 1, MATERIALS)
                .radio()
                .name());
        // 135 exactly: Enriched Naquadah (140) with the shutter at 3% (140 - ceil(4.2) = 135).
        final Setup tuned = BacterialVat.setup(Map.of(), new Needs(6, 135, true), 1, MATERIALS);
        assertEquals(
            140,
            tuned.radio()
                .sievert());
        assertEquals(135, BacterialVat.effectiveSievert(140, tuned.shutter()));
    }

    @Test
    void burnsRadioMaterialAtTheDecayRate() {
        assertEquals(3976, BacterialVat.decayTicks(83));
        assertEquals(1617, BacterialVat.decayTicks(92));
        assertEquals(1324, BacterialVat.decayTicks(94));
        assertEquals(288, BacterialVat.decayTicks(130));
        assertEquals(213, BacterialVat.decayTicks(140));
        assertEquals(157, BacterialVat.decayTicks(150));
        assertEquals(20.0 / 1324, BacterialVat.burnPerSecond(MATERIALS.get(3)), 1e-12);
    }

    @Test
    void showsItsWorking() {
        final Needs biomass = new Needs(3, 0, false);
        final Setup setup = BacterialVat.setup(Map.of("bioVatOutputHatch", "iv"), biomass, 100, MATERIALS);
        final Map<String, String> text = new HashMap<>();
        for (final FormulaLine line : BacterialVat.formulas(biomass, setup, 100, 100, 150, 2, 37, 32, 2))
            text.put(line.label(), line.mathText() + " = " + line.result());
        assertEquals("⌈1000·(1−(2·0.5−1)^2)⌉+1 = 1,001x", text.get("fill"));
        assertEquals("100 L·1,001 = 100,100 L", text.get("out"));
        assertEquals("100,100 ≤ 256,000−128,000 = fits", text.get("room"));
        assertEquals("150t ÷ 2^2 = 37t", text.get("time"));
        assertEquals("100,100 L ÷ 1.85s = 54,108.1 L/s", text.get("rate"));
        assertEquals("2·4^2 = 32 EU/t", text.get("power"));
    }
}

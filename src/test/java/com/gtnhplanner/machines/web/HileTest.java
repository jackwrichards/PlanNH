package com.gtnhplanner.machines.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * hile.test.ts: the laser source ladder and its migration, and the table side of the cases that go through the solver
 * (parallels, the overclock ceiling, the over-tier gate), which the golden fixture covers end to end.
 */
class HileTest {

    private static final String HILE = "Hyper-Intensity Laser Engraver";

    private static int position(final String key) {
        for (int i = 0; i < Hile.SOURCES.size(); i++) if (Hile.SOURCES.get(i)
            .key()
            .equals(key)) return i;
        return -1;
    }

    /** The solver's context at a laser source: its position on the ladder, its amps as the count behind it. */
    private static MachineTable.Context at(final String sourceKey, final Integer recipeVoltageTier) {
        final int position = position(sourceKey);
        final double amps = Hile.SOURCES.get(position)
            .amps();
        return new MachineTable.Context(
            id -> id.equals("laserSource") ? position : 0,
            id -> id.equals("laserSource") ? amps : 0,
            5,
            recipeVoltageTier,
            null,
            null,
            null,
            null);
    }

    private static List<Long> ampsAt(final String tier) {
        final List<Long> out = new ArrayList<>();
        for (final Hile.Source s : Hile.SOURCES) if (s.tier()
            .equals(tier)) out.add(s.amps());
        return out;
    }

    private static Map<String, String> settings(final String... kv) {
        final Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    @Test
    void offersOneSourceSelectorWithAll46RegisteredHatchCombinations() {
        final List<Web.Control> controls = MachineTable.controls(HILE, null);
        assertEquals(1, controls.size());
        assertEquals("laserSource", controls.get(0).id);
        assertEquals(46, controls.get(0).tiers.size());
        assertEquals("UHV · 65,536A", controls.get(0).tiers.get(position("uhv-65536")).label);
        assertEquals(List.of(256L), ampsAt("IV"));
        assertEquals(List.of(256L, 1024L, 4096L, 16384L, 65536L), ampsAt("UHV"));
        final Hile.Source last = Hile.SOURCES.get(Hile.SOURCES.size() - 1);
        assertEquals("UXV", last.tier());
        assertEquals(536_870_912L, last.amps());
        assertEquals(List.of("laserAmperage"), MachineTable.hiddenControlIds(HILE));
    }

    @ParameterizedTest(name = "gives {0}A a structural cap of {1} parallels")
    @CsvSource({ "256, 6", "1024, 10", "4096, 16", "16384, 25", "65536, 40", "262144, 64", "1048576, 101",
        "4194304, 161", "16777216, 256", "536870912, 812" })
    void givesEachAmperageItsStructuralCap(final long amps, final int parallels) {
        // Table side of getMachineStructuralParallels: floor(cbrt(amps)) from the source's count.
        final MachineTable.Behaviour hile = MachineTable.behaviour(HILE);
        int sources = 0;
        for (final Hile.Source source : Hile.SOURCES) if (source.amps() == amps) {
            assertEquals(parallels, Math.floor(MachineTable.resolve(hile.parallels, at(source.key(), 1), 1)));
            sources++;
        }
        assertTrue(sources > 0);
    }

    @Test
    void capsOverclocksIndependentlyAtLaserSourceTierPlusOne() {
        // Table side: an LV recipe's normal overclocks stop at the source tier + 1, whatever the hatches could pay.
        final MachineTable.Behaviour hile = MachineTable.behaviour(HILE);
        assertEquals(5, ((MachineTable.Rule) MachineTable.overclockSpec(hile, at("iv-256", 1))).maxNormal());
        assertEquals(6, ((MachineTable.Rule) MachineTable.overclockSpec(hile, at("luv-256", 1))).maxNormal());
        assertEquals(8, ((MachineTable.Rule) MachineTable.overclockSpec(hile, at("uv-256", 1))).maxNormal());
    }

    @Test
    void preservesTheNamedLegacyHatchInsteadOfTheConflictingDummyCount() {
        final MachineTable.Behaviour hile = MachineTable.behaviour(HILE);
        assertEquals(
            "uhv-65536",
            hile.normalizeConfig.apply(settings("laserSource", "a65536", "laserAmperage", "64"))
                .get("laserSource"));
        assertEquals(40, Math.floor(MachineTable.resolve(hile.parallels, at("uhv-65536", 1), 1)));
        assertEquals(
            "zpm-4096",
            hile.normalizeConfig.apply(settings("laserAmperage", "4096"))
                .get("laserSource"));
        assertEquals(
            "iv-256",
            hile.normalizeConfig.apply(settings())
                .get("laserSource"));
    }

    @Test
    void stallsAnOverTierRecipeDespiteAmplePowerAndResumesWithAHigherSource() {
        // Table side: a 122,880 EU/t recipe is ZPM; an IV source permits up to LuV, a LuV source up to ZPM.
        final MachineTable.Behaviour hile = MachineTable.behaviour(HILE);
        final int zpm = Tiers.index(Tiers.forEuT(122880));
        assertTrue(
            hile.recipeGate.apply(at("iv-256", zpm))
                .contains("Laser source tier too low"));
        assertNull(hile.recipeGate.apply(at("luv-256", zpm)));
    }
}

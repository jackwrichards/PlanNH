package com.gtnhplanner.power;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.power.PowerSearch.Hit;
import com.gtnhplanner.power.PowerSearch.Op;
import com.gtnhplanner.power.PowerSearch.StencilClause;
import com.gtnhplanner.power.PowerSearch.StencilHit;

/**
 * The website's power.test.ts "power search" and "stencil search" blocks. Some cases name sources from other ports
 * (boilers, the large turbines); those assertions come last in their test and are skipped, not failed, until that
 * port is in the registry.
 */
class PowerSearchTest {

    /** Skips the rest of a test until these sources are in the registry (each source file is ported separately). */
    private static void assumePorted(final String... ids) {
        for (final String id : ids) assumeTrue(PowerRegistry.get(id) != null, id + " is not ported yet");
    }

    /** Skips the rest of a test until every picker column has its sources. */
    private static void assumeAllGroupsPorted() {
        for (final PowerGroup group : PowerGroup.values()) {
            assumeTrue(
                !PowerRegistry.inGroup(group)
                    .isEmpty(),
                group.id + " sources are not ported yet");
        }
    }

    private static List<String> ids(final List<Hit> hits) {
        final List<String> ids = new ArrayList<>();
        for (final Hit hit : hits) ids.add(
            hit.source()
                .id());
        return ids;
    }

    @Nullable
    private static Hit find(final List<Hit> hits, final String sourceId) {
        for (final Hit hit : hits) {
            if (hit.source()
                .id()
                .equals(sourceId)) return hit;
        }
        return null;
    }

    private static List<StencilHit> stencilHits(final List<StencilHit> hits, final String sourceId) {
        final List<StencilHit> found = new ArrayList<>();
        for (final StencilHit hit : hits) {
            if (hit.source()
                .id()
                .equals(sourceId)) found.add(hit);
        }
        return found;
    }

    @Nullable
    private static StencilHit stencilHit(final List<StencilHit> hits, final String sourceId) {
        final List<StencilHit> found = stencilHits(hits, sourceId);
        return found.isEmpty() ? null : found.get(0);
    }

    private static StencilClause clause(final boolean takes, final String resourceName) {
        final PowerResources.Ref resource = PowerResources.resolve(resourceName);
        assertNotNull(resource, resourceName);
        return new StencilClause(takes, resource.kind, resource.id);
    }

    // ---- power search

    @Test
    void findsEveryMachineThatBurnsBenzeneWithTheFuelDialedIn() {
        final List<Hit> hits = PowerSearch.search("benzene");
        final List<String> ids = ids(hits);
        assertTrue(ids.contains("gas-turbine"));
        assertTrue(ids.contains("solid-oxide-fuel-cell-1"));
        final Hit gasTurbine = find(hits, "gas-turbine");
        assertNotNull(gasTurbine);
        assertEquals(
            "takes",
            gasTurbine.via()
                .direction());
        // Benzene is the gas turbine's default fuel, so no dial is needed.
        assertEquals(Map.of(), gasTurbine.settings());
        assumePorted("large-gas-turbine", "large-bronze-boiler");
        assertTrue(ids.contains("large-gas-turbine"));
        final Hit boiler = find(hits, "large-bronze-boiler");
        assertNotNull(boiler);
        assertNotNull(boiler.via());
    }

    @Test
    void dialsANonDefaultFuelIntoThePlacementSettings() {
        final List<Hit> hits = PowerSearch.search("nitrobenzene");
        final Hit turbine = find(hits, "gas-turbine");
        assertNotNull(turbine);
        assertEquals(
            "takes",
            turbine.via()
                .direction());
        assertEquals(Map.of("fuel", "Nitrobenzene"), turbine.settings());
    }

    @Test
    void findsMakersOfSuperheatedSteamAndPrefersMakesOverTakes() {
        final List<Hit> hits = PowerSearch.search("superheated steam");
        final Hit sofc2 = find(hits, "solid-oxide-fuel-cell-2");
        assertNotNull(sofc2);
        assertEquals(
            "makes",
            sofc2.via()
                .direction());
        assumePorted("large-titanium-boiler", "large-hp-steam-turbine");
        final Hit boiler = find(hits, "large-titanium-boiler");
        assertNotNull(boiler);
        assertEquals(
            "makes",
            boiler.via()
                .direction());
        final Hit hpTurbine = find(hits, "large-hp-steam-turbine");
        assertNotNull(hpTurbine);
        assertEquals(
            "takes",
            hpTurbine.via()
                .direction());
    }

    @Test
    void matchesMachineNamesFirstAndReturnsTheWholeCatalogWhenEmpty() {
        assertEquals(
            PowerRegistry.sources()
                .size(),
            PowerSearch.search("")
                .size());
        final List<Hit> hits = PowerSearch.search("turbine");
        assertNull(
            hits.get(0)
                .via());
        assertTrue(ids(hits).contains("steam-turbine"));
    }

    // ---- stencil search (the recipe book's view of the generators)

    @Test
    void answersATakesClauseByResourceIdDialingTheFuelWhenNeeded() {
        final List<StencilHit> hits = PowerSearch
            .searchForStencil(List.of(clause(true, "Nitrobenzene")), Op.ALL, Op.ALL, "");
        final StencilHit turbine = stencilHit(hits, "gas-turbine");
        assertNotNull(turbine);
        assertEquals(Map.of("fuel", "Nitrobenzene"), turbine.settings());
        // Benzene is the default: same machine, no dial.
        final List<StencilHit> defaults = PowerSearch
            .searchForStencil(List.of(clause(true, "Benzene")), Op.ALL, Op.ALL, "");
        final StencilHit plain = stencilHit(defaults, "gas-turbine");
        assertTrue(plain == null || plain.settings() == null);
    }

    @Test
    void answersTheMakesPowerPseudoClauseWithEveryGenerator() {
        final StencilClause power = new StencilClause(false, "fluid", PowerSearch.POWER_EU_CLAUSE_ID);
        final List<StencilHit> hits = PowerSearch.searchForStencil(List.of(power), Op.ALL, Op.ALL, "");
        assertFalse(stencilHits(hits, "large-combustion-engine").isEmpty());
        // A name query narrows the shelf like it narrows the recipes.
        final List<StencilHit> narrowed = PowerSearch.searchForStencil(List.of(power), Op.ALL, Op.ALL, "combustion");
        for (final StencilHit hit : narrowed) {
            assertTrue(
                hit.source()
                    .name()
                    .toLowerCase()
                    .contains("combustion"));
        }
        assertTrue(narrowed.size() > 0);
        assumeAllGroupsPorted();
        assertTrue(hits.size() > 20);
    }

    @Test
    void intersectsBothSidesTakesSteamAndMakesPowerIsTheSteamTurbines() {
        final List<StencilHit> hits = PowerSearch.searchForStencil(
            List.of(clause(true, "Steam"), new StencilClause(false, "fluid", PowerSearch.POWER_EU_CLAUSE_ID)),
            Op.ALL,
            Op.ALL,
            "");
        assertFalse(stencilHits(hits, "steam-turbine").isEmpty());
        assertTrue(stencilHits(hits, "gas-turbine").isEmpty());
        assumePorted("large-steam-turbine");
        assertFalse(stencilHits(hits, "large-steam-turbine").isEmpty());
    }

    @Test
    void expandsAnUnpinnedFuelKnobIntoOneCardPerFuelNeiStyle() {
        // A clause that PINS the fuel keeps exactly one card for it.
        final List<StencilHit> pinned = PowerSearch
            .searchForStencil(List.of(clause(true, "Benzene")), Op.ALL, Op.ALL, "");
        assertEquals(1, stencilHits(pinned, "gas-turbine").size());
        assumePorted("large-titanium-boiler");
        final List<StencilHit> hits = PowerSearch
            .searchForStencil(List.of(clause(false, "SH Steam")), Op.ALL, Op.ALL, "");
        // The titanium boiler makes SH steam on ANY of its fuels: one card each.
        final List<StencilHit> titanium = stencilHits(hits, "large-titanium-boiler");
        assertTrue(titanium.size() > 3);
        final Set<String> fuels = new HashSet<>();
        for (final StencilHit hit : titanium) fuels.add(
            hit.settings() == null ? null
                : hit.settings()
                    .get("liquidFuel"));
        assertEquals(titanium.size(), fuels.size());
    }

    @Test
    void dropsASourceWhenTwoClausesNeedTheSameKnobAtDifferentPositions() {
        final List<StencilClause> both = List.of(clause(true, "Nitrobenzene"), clause(true, "Naphtha"));
        final List<StencilHit> hits = PowerSearch.searchForStencil(both, Op.ALL, Op.ALL, "");
        assertNull(stencilHit(hits, "gas-turbine"));
        // ANY keeps it: one of the fuels is enough.
        final List<StencilHit> anyHits = PowerSearch.searchForStencil(both, Op.ANY, Op.ALL, "");
        assertNotNull(stencilHit(anyHits, "gas-turbine"));
    }
}

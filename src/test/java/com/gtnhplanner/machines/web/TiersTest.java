package com.gtnhplanner.machines.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** tiers.test.ts, the parts the machine maths read. */
class TiersTest {

    @Test
    void voltageTierForEuT() {
        assertEquals("ULV", Tiers.forEuT(0));
        assertEquals("ULV", Tiers.forEuT(8));
        assertEquals("LV", Tiers.forEuT(9));
        assertEquals("IV", Tiers.forEuT(8192));
        assertEquals("MAX", Tiers.forEuT(3e9));
        assertEquals("EV", Tiers.withinEuT(6000));
        assertEquals("ULV", Tiers.withinEuT(1));
    }

    @Test
    void runTierSnapsToRegisteredMachines() {
        final Web.Recipe r = new Web.Recipe();
        r.eut = 30;
        r.minimumTier = "LV";
        r.availableTiers = java.util.List.of("LV", "MV", "HV", "EV");
        assertEquals("EV", Tiers.runTier(r, "IV"));
        assertEquals("LV", Tiers.runTier(r, "ULV"));
        r.availableTiers = null;
        r.maximumTier = "HV";
        assertEquals("HV", Tiers.runTier(r, "IV"));
        assertEquals("UXV", Tiers.resolve("OpV", "LV"));
    }
}

package com.gtnhplanner.power;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.power.sources.Helpers;

/** The workbook tables load whole, and the website's number formats come out the same. */
class PowerDataTest {

    @Test
    void tablesLoad() {
        final PowerData data = PowerData.get();
        assertEquals(6, data.steamGrades.size());
        assertEquals(0.5, data.steamGrades.get(0).euPerLiter);
        assertEquals(166, data.rotors.size());
        assertEquals(12, data.singleblockTiers.size());
        assertEquals(8, data.boilerFuels.size());
        assertNull(data.singleblockEfficiency.get("steamTurbine")[3]);
        assertEquals(0.85, data.singleblockEfficiency.get("steamTurbine")[0]);
        assertEquals("Thermal Boiler", data.heatExchangers.get(0).name);
        assertEquals(
            "Lava",
            data.heatExchangers.get(0).fluids.keySet()
                .iterator()
                .next());
        assertTrue(data.eohStars.get(0).euInput > 1e16);
    }

    @Test
    void resourcesResolve() {
        final PowerResources.Ref cell = PowerResources.resolve("10k Coolant Cell");
        assertNotNull(cell);
        assertEquals("ic2:reactorcoolantsimple@1", cell.id);
        assertEquals("item", cell.kind);
        assertNull(PowerResources.resolve("no such thing"));
        assertEquals("gregtech:gt.blockmachines@1120", PowerResources.machineIcon("steam-turbine").id);
    }

    @Test
    void numbersFormatLikeIntl() {
        assertEquals("1,234.57", Helpers.number(1234.567));
        assertEquals("1", Helpers.number(1));
        assertEquals("0.5", Helpers.number(0.5));
        assertEquals("-1,597", Helpers.number(-1597.0001));
        // ICU rounds the shortest round-trip decimal, not the binary value (1.005 is stored as 1.00499999...).
        assertEquals("1.01", Helpers.number(1.005));
        assertEquals("2.49", Helpers.number(1 + 1.5 * 0.99));
        assertEquals("-2.49", Helpers.number(-2.485));
        assertEquals("1.5M", Helpers.formatAmount(1_500_000));
        assertEquals("2G", Helpers.formatAmount(2e9));
        assertEquals("-", Helpers.formatAmount(Double.NaN));
        assertEquals("85%", Helpers.percent(0.85));
        assertEquals("150h", Helpers.lifespanHours(150 * 3600));
        assertEquals("1.5h", Helpers.lifespanHours(5400));
    }

    @Test
    void familyTiersFollowTheLadder() {
        final Helpers.FamilyTiers tiers = Helpers.familyTierOptions("steamTurbine");
        assertEquals(
            3,
            tiers.options()
                .size());
        assertEquals(
            "LV",
            tiers.options()
                .get(0)
                .key());
        assertEquals(0.75, tiers.efficiencyFor("MV"));
        assertEquals(1, tiers.efficiencyFor("UV"));
    }
}

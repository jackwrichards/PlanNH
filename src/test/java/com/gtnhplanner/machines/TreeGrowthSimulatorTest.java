package com.gtnhplanner.machines;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.machines.TreeGrowthSimulator.Forestry;
import com.gtnhplanner.machines.TreeGrowthSimulator.Mode;
import com.gtnhplanner.machines.TreeGrowthSimulator.Setup;
import com.gtnhplanner.machines.TreeGrowthSimulator.Tree;

/**
 * The Tree Growth Simulator against GT5U 5.09.54.205's MTETreeFarm and ForestryMC 4.11.39 (javap), with the website's
 * tree-growth-simulator.test.ts golden values: the two must agree.
 */
class TreeGrowthSimulatorTest {

    private static final Tree OAK = new Tree(
        List.of(Mode.LOG, Mode.SAPLING, Mode.LEAVES, Mode.FRUIT),
        List.of(5, 5, 2, 1),
        null);

    private static Forestry sequoia() {
        final Map<Mode, Integer> base = new EnumMap<>(Mode.class);
        base.put(Mode.LOG, 1);
        base.put(Mode.SAPLING, 1);
        base.put(Mode.LEAVES, 1);
        return new Forestry("forestry.treeSequioa", 1.75f, 3, 0.025f, 0.025f, base);
    }

    @Test
    void multipliesByTheTierAndDrawsVp() {
        final int[] mults = new int[14];
        for (int t = 1; t <= 14; t++) mults[t - 1] = TreeGrowthSimulator.tierMultiplier(t);
        assertEquals(
            List.of(5, 9, 17, 29, 45, 65, 89, 117, 149, 185, 225, 269, 317, 369),
            java.util.Arrays.stream(mults)
                .boxed()
                .collect(Collectors.toList()));
        assertEquals(30, TreeGrowthSimulator.euPerTick(1));
        assertEquals(7680, TreeGrowthSimulator.euPerTick(5));
        assertEquals(491520, TreeGrowthSimulator.euPerTick(8));
    }

    @Test
    void readsTFromTheHatchesRoundedUp() {
        assertEquals(1, TreeGrowthSimulator.tier(32));
        assertEquals(1, TreeGrowthSimulator.tier(8));
        assertEquals(2, TreeGrowthSimulator.tier(32 * 4));
        assertEquals(3, TreeGrowthSimulator.tier(32 * 6));
        assertEquals(5, TreeGrowthSimulator.tier(8192));
    }

    @Test
    void scalesOakByTierAndTool() {
        final Setup basic = TreeGrowthSimulator.setup(Map.of(), OAK);
        // LV, basic tools: x5 a run.
        assertEquals(5.0, TreeGrowthSimulator.outputMultiplier(OAK, 0, basic, 1), 1e-9);
        // IV, chainsaw: 5 x 45 x 4 = 900 logs a run.
        final Setup chainsaw = TreeGrowthSimulator.setup(Map.of("tgsLogTool", "log:chainsaw"), OAK);
        assertEquals(900.0, TreeGrowthSimulator.outputMultiplier(OAK, 0, chainsaw, 5) * 5, 1e-9);
        final Setup wire = TreeGrowthSimulator.setup(Map.of("tgsLeavesTool", "leaves:wire_cutter"), OAK);
        assertEquals(20.0, TreeGrowthSimulator.outputMultiplier(OAK, 2, wire, 1) * 2, 1e-9);
        assertEquals(
            0.0,
            TreeGrowthSimulator
                .outputMultiplier(OAK, 3, TreeGrowthSimulator.setup(Map.of("tgsFruitTool", "none"), OAK), 8));
        assertEquals(
            5.0,
            TreeGrowthSimulator
                .outputMultiplier(OAK, 3, TreeGrowthSimulator.setup(Map.of("tgsFruitTool", "fruit:multitool"), OAK), 1),
            1e-9);
    }

    @Test
    void scalesAForestrySaplingByItsGenesInJavaFloats() {
        final Forestry f = sequoia();
        final Tree tree = new Tree(List.of(Mode.LOG, Mode.SAPLING, Mode.LEAVES), List.of(45, 5, 2), f);
        final Setup setup = TreeGrowthSimulator.setup(Map.of(), tree);
        // Height 3 x (1.75 - 1) + 1 = 3.25, x girth 3 = 9.75 -> 9 (NEI shows 45).
        assertEquals(9, TreeGrowthSimulator.forestryProduct(f, Mode.LOG, setup));
        assertEquals(1, TreeGrowthSimulator.forestryProduct(f, Mode.SAPLING, setup));
        final Setup gigantic = TreeGrowthSimulator.setup(Map.of("tgsHeight", "gigantic"), tree);
        assertEquals(12, TreeGrowthSimulator.forestryProduct(f, Mode.LOG, gigantic));
        final Setup fertile = TreeGrowthSimulator.setup(Map.of("tgsSaplings", "highest"), tree);
        assertEquals(3, TreeGrowthSimulator.forestryProduct(f, Mode.SAPLING, fertile));
        assertEquals(45, TreeGrowthSimulator.neiAmount(tree, 0, setup));
    }

    @Test
    void showsItsWorking() {
        final Map<String, String> settings = new HashMap<>();
        settings.put("tgsLogTool", "log:chainsaw");
        final Map<String, String> text = new HashMap<>();
        for (final FormulaLine line : TreeGrowthSimulator.formulas(OAK, settings, 8192, 1))
            text.put(line.label(), line.mathText() + " = " + line.result());
        assertEquals("8,192 V · 1 A = IV, t = 5", text.get("tier"));
        assertEquals("2·5^2−2·5+5 = 45x", text.get("mult"));
        assertEquals("5·45·4 ÷ 5s = 180/s", text.get("logs"));
        assertEquals("1·45·1 ÷ 5s = 9/s", text.get("fruit"));
        assertEquals("8,192·30/32 = 7,680 EU/t", text.get("power"));
    }
}

package com.gtnhplanner.machines.web;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

/**
 * The energy hatch families a multiblock can drink through (src/lib/machines/energy-hatches.ts), from GT5U's
 * MachineLoader and MTEHatchEnergy. A regular hatch carries 2 A of its tier (one alone is clamped to 1 A); the TecTech
 * families are exotic: one hatch, its whole rating, and its amps never raise the summed-voltage ordinal.
 */
public final class EnergyHatches {

    private EnergyHatches() {}

    /** A family: its stored id, its item's name, the chip shown for the count, its amps, and its lowest tier. */
    public record Type(String id, String label, String chip, double amps, boolean exotic, String minTier,
        String resourceId) {}

    public static final String STANDARD = "standard";

    public static final List<Type> TYPES = List.of(
        new Type(STANDARD, "Energy Hatch", "", 2, false, "ULV", "gregtech:gt.blockmachines@41"),
        new Type("amp4", "4A Energy Hatch", "4A", 4, true, "EV", "gregtech:gt.blockmachines@15109"),
        new Type("amp16", "16A Energy Hatch", "16A", 16, true, "EV", "gregtech:gt.blockmachines@15119"),
        new Type("amp64", "64A Energy Hatch", "64A", 64, true, "EV", "gregtech:gt.blockmachines@15129"),
        new Type("laser256", "256A Laser Target Hatch", "256A", 256, true, "IV", "gregtech:gt.blockmachines@15130"),
        new Type("laser1k", "1,024A Laser Target Hatch", "1kA", 1024, true, "LuV", "gregtech:gt.blockmachines@15141"),
        new Type("laser4k", "4,096A Laser Target Hatch", "4kA", 4096, true, "ZPM", "gregtech:gt.blockmachines@15152"),
        new Type(
            "laser16k",
            "16,384A Laser Target Hatch",
            "16kA",
            16384,
            true,
            "UV",
            "gregtech:gt.blockmachines@15163"),
        new Type(
            "laser65k",
            "65,536A Laser Target Hatch",
            "65kA",
            65536,
            true,
            "UHV",
            "gregtech:gt.blockmachines@15174"),
        new Type(
            "laser262k",
            "262,144A Laser Target Hatch",
            "262kA",
            262144,
            true,
            "UEV",
            "gregtech:gt.blockmachines@15185"),
        new Type(
            "laser1m",
            "1,048,576A Laser Target Hatch",
            "1MA",
            1048576,
            true,
            "UIV",
            "gregtech:gt.blockmachines@15196"),
        new Type(
            "laser4m",
            "4,194,304A Laser Target Hatch",
            "4MA",
            4194304,
            true,
            "UMV",
            "gregtech:gt.blockmachines@16023"),
        new Type(
            "laser16m",
            "16,777,216A Laser Target Hatch",
            "16MA",
            16777216,
            true,
            "UXV",
            "gregtech:gt.blockmachines@16025"));

    /** getEnergyHatchType: unknown or absent is the plain pair. */
    public static Type type(@Nullable final String id) {
        if (id != null && !id.isEmpty()) for (final Type t : TYPES) if (t.id.equals(id)) return t;
        return TYPES.get(0);
    }

    /** energyHatchTypesForTier: the families that exist at this hatch tier. */
    public static List<Type> typesForTier(final String tier) {
        final List<Type> out = new ArrayList<>();
        for (final Type t : TYPES) if (Tiers.index(t.minTier) <= Tiers.index(tier)) out.add(t);
        return out;
    }

    /** energyHatchTypeExistsAtTier. */
    public static boolean existsAtTier(@Nullable final String id, final String tier) {
        return Tiers.index(type(id).minTier) <= Tiers.index(tier);
    }
}

package com.gtnhplanner.machines.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Precise Auto-Assembler MT-3662's settings (precise-assembler.ts, after MTEPreciseAssembler): unit casings
 * (casingTier -1..3 is Imprecise and Mk-I..IV) for its normal Assembler mode and its precise mode, and the machine
 * casing that caps its working voltage. Pure: no game classes.
 */
public final class PreciseAssembler {

    private PreciseAssembler() {}

    private static final String[] UNIT_CASINGS = { "Imprecise", "Mk-I", "Mk-II", "Mk-III", "Mk-IV" };

    /** A unit casing rung: key {@code mk<index>}, its label, and its icon with this tooltip. */
    private static Web.TierOption unitCasing(final int index, final String tooltip) {
        final String label = UNIT_CASINGS[index];
        final Web.TierOption tier = new Web.TierOption();
        tier.key = "mk" + index;
        tier.label = label;
        tier.resource = MachineControls.item(
            "factoryflow:machine_config/preciseCasing_mk" + index,
            label + " Unit Casing",
            List.of(tooltip),
            false);
        return tier;
    }

    /** PRASS_NORMAL_CASING: normal Assembler mode, 16 x 2^n parallels at twice the speed. */
    public static final Web.Control NORMAL_CASING;

    static {
        final Web.Control control = new Web.Control();
        control.id = "preciseCasing";
        control.label = "Unit casing";
        control.minimumKey = "mk0";
        control.defaultKey = "mk0";
        final List<Web.TierOption> tiers = new ArrayList<>();
        for (int index = 0; index < UNIT_CASINGS.length; index++) {
            tiers.add(
                unitCasing(
                    index,
                    "Normal Assembler mode: " + 16 * (long) Math.pow(2, index)
                        + " maximum parallels at 2x speed. Energy supply limits usable parallels."));
        }
        control.tiers = tiers;
        NORMAL_CASING = control;
    }

    /** PRASS_PRECISE_CASING: precise mode, where special value 1 means Mk-I, so the ladder begins at Mk-I. */
    public static final Web.Control PRECISE_CASING;

    static {
        final Web.Control control = new Web.Control();
        control.id = "preciseCasing";
        control.label = "Unit casing";
        control.minimumKey = "mk1";
        control.defaultKey = "mk1";
        control.minimumFromSpecialValue = true;
        final List<Web.TierOption> tiers = new ArrayList<>();
        for (int index = 0; index < UNIT_CASINGS.length - 1; index++) {
            tiers.add(
                unitCasing(
                    index + 1,
                    "Precise mode: unlocks recipes requiring casing tier " + (index + 1)
                        + " or lower. One parallel, with no speed bonus."));
        }
        control.tiers = tiers;
        PRECISE_CASING = control;
    }

    /** PRASS_MACHINE_CASING: ULV through UHV machine casings; UHV removes the voltage cap. */
    public static final Web.Control MACHINE_CASING;

    static {
        final Web.Control control = new Web.Control();
        control.id = "prassMachineCasing";
        control.label = "Machine casing";
        control.minimumKey = "ulv";
        control.defaultKey = "uhv";
        final List<Web.TierOption> tiers = new ArrayList<>();
        for (int ordinal = 0; ordinal < 10; ordinal++) {
            final String tier = Tiers.NAMES[ordinal];
            final Web.TierOption option = new Web.TierOption();
            option.key = tier.toLowerCase(Locale.ROOT);
            option.label = tier;
            option.resource = MachineControls.item(
                "gregtech:gt.blockcasings1" + (ordinal != 0 ? "@" + ordinal : ""),
                tier + " Machine Casing",
                List.of(
                    ordinal == 9
                        ? "UHV machine casings remove the casing voltage limit. Energy hatches still determine supplied voltage and amps."
                        : "Limits usable voltage to " + tier
                            + ", even with higher-tier energy hatches. Amps can buy overclocks but cannot unlock recipes above this voltage."),
                false);
            tiers.add(option);
        }
        control.tiers = tiers;
        MACHINE_CASING = control;
    }

    /**
     * prassInputVoltageLimit: the machine casing's voltage ordinal, or infinity for UHV and for a plan with no stored
     * casing (which assumes a sufficient one).
     */
    public static double inputVoltageLimit(final Map<String, String> settings) {
        final String saved = settings.get("prassMachineCasing");
        int index = -1;
        for (int i = 0; i < MACHINE_CASING.tiers.size(); i++) if (MACHINE_CASING.tiers.get(i).key.equals(saved)) {
            index = i;
            break;
        }
        return index < 0 || index == 9 ? Double.POSITIVE_INFINITY : index;
    }
}

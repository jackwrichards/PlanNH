package com.gtnhplanner.power.sources;

import static com.gtnhplanner.power.sources.Helpers.*;

import java.util.ArrayList;
import java.util.List;

import com.gtnhplanner.power.PowerData;
import com.gtnhplanner.power.PowerData.Fuel;
import com.gtnhplanner.power.PowerGroup;
import com.gtnhplanner.power.PowerModel;
import com.gtnhplanner.power.PowerSetting;
import com.gtnhplanner.power.PowerSource;

/**
 * Solid-Oxide Fuel Cells (the website's sources/sofc.ts; GT++): direct EU plus a steam byproduct worth about as much
 * again once turbined. Mk II only rewards fuels over 1,000 EU/L (its efficiency is fuelEU/1000, floored at 1).
 */
public final class Sofc {

    private record Spec(String id, String name, String unlock, double output, double oxygenPerSecond,
        double steamPerSecond, String steamGrade, boolean scalesWithFuel) {}

    private static final List<Spec> SPECS = List.of(
        new Spec(
            "solid-oxide-fuel-cell-1",
            "Solid-Oxide Fuel Cell Mk I",
            // Kekztech Crafting: HV hull, HV pumps, HV circuits.
            "HV",
            2048,
            100,
            20_000,
            "Steam",
            false),
        new Spec(
            "solid-oxide-fuel-cell-2",
            "Solid-Oxide Fuel Cell Mk II",
            // Kekztech Crafting: IV hull and pumps, LuV circuits.
            "LuV",
            24_576,
            2000,
            96_000,
            "SH Steam",
            true));

    private static PowerSource build(final Spec spec) {
        final PowerData data = PowerData.get();
        return new PowerSource(
            spec.id(),
            spec.name(),
            PowerGroup.ENGINES,
            spec.unlock(),
            formatAmount(spec.output()) + " EU/t plus " + formatAmount(spec.steamPerSecond()) + " L/s steam.",
            List.of(new PowerSetting.Select("fuel", "Fuel", PowerData.fuelOptions(data.gasFuels), "Benzene")),
            read -> {
                final Fuel fuel = PowerData.findFuel(data.gasFuels, read.select("fuel"));
                final double euPerLiter = fuel.euPerLiter != null ? fuel.euPerLiter : 0;
                final double efficiency = spec.scalesWithFuel() && euPerLiter > 1000 ? euPerLiter / 1000 : 1;
                final double fuelPerSecond = euPerLiter > 0
                    ? Math.floor((20 * spec.output()) / (efficiency * euPerLiter))
                    : 0;
                return new PowerModel(
                    spec.output(),
                    List.of(liters(fuel.name, fuelPerSecond), liters("Oxygen", spec.oxygenPerSecond())),
                    List.of(liters(spec.steamGrade(), spec.steamPerSecond())),
                    List.of(stat("Efficiency", percent(efficiency))));
            });
    }

    private static List<PowerSource> sources;

    public static synchronized List<PowerSource> sources() {
        if (sources == null) {
            final List<PowerSource> all = new ArrayList<>();
            for (final Spec spec : SPECS) all.add(build(spec));
            sources = List.copyOf(all);
        }
        return sources;
    }

    private Sofc() {}
}

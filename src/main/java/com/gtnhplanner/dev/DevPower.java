package com.gtnhplanner.dev;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import com.gtnhplanner.power.PowerModel;
import com.gtnhplanner.power.PowerRegistry;
import com.gtnhplanner.power.PowerResources;
import com.gtnhplanner.power.PowerSetting;
import com.gtnhplanner.power.PowerSource;
import com.gtnhplanner.power.game.PowerPorts;

/**
 * Checks the power sources against this game: every flow any source makes or eats under any single setting choice,
 * and whether it becomes a port here (the resource map knows it, the game has it, a fluid has its icon); and every
 * source's machine item. Client thread only.
 */
final class DevPower {

    private DevPower() {}

    static Map<String, Object> check() {
        final Set<String> names = new LinkedHashSet<>();
        for (final PowerSource source : PowerRegistry.sources()) {
            collect(source, Map.of(), names);
            for (final PowerSetting setting : source.settings()) {
                if (!(setting instanceof final PowerSetting.Select select)) continue;
                for (final PowerSetting.Option option : select.options())
                    collect(source, Map.of(select.id(), option.key()), names);
            }
        }
        final List<String> unmapped = new ArrayList<>(), missing = new ArrayList<>(), noIcon = new ArrayList<>();
        final Map<String, String> fluids = new LinkedHashMap<>();
        for (final String name : names) {
            final PowerResources.Ref ref = PowerResources.resolve(name);
            if (ref == null) {
                unmapped.add(name);
                continue;
            }
            final Object value = PowerPorts.resolve(name);
            if (value == null) missing.add(name + " (" + ref.kind + " " + ref.id + ")");
            else if (value instanceof final FluidStack fluid) {
                fluids.put(name, fluid.getFluid()
                    .getName() + " / "
                    + fluid.getLocalizedName()
                    + " / icon "
                    + (fluid.getFluid()
                        .getIcon() != null));
                if (fluid.getFluid()
                    .getIcon() == null) noIcon.add(name);
            }
        }
        final List<String> noMachine = new ArrayList<>();
        for (final PowerSource source : PowerRegistry.sources()) {
            final ItemStack stack = PowerPorts.machineStack(source.id());
            if (stack == null) noMachine.add(source.id());
        }
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("sources", PowerRegistry.sources()
            .size());
        m.put("flows", names.size());
        m.put("unmapped", unmapped);
        m.put("missingInGame", missing);
        m.put("fluidsWithoutIcon", noIcon);
        m.put("noMachineItem", noMachine);
        m.put("fluids", fluids);
        return m;
    }

    private static void collect(final PowerSource source, final Map<String, String> settings, final Set<String> into) {
        final PowerModel model;
        try {
            model = source.compute(settings);
        } catch (final RuntimeException e) {
            into.add("!" + source.id() + ": " + e);
            return;
        }
        for (final PowerModel.Flow flow : model.inputs()) into.add(flow.name());
        for (final PowerModel.Flow flow : model.outputs()) into.add(flow.name());
    }
}

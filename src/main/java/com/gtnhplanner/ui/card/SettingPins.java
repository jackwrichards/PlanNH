package com.gtnhplanner.ui.card;

import java.util.ArrayList;
import java.util.List;

import com.gtnhplanner.api.PlanAPI;
import com.gtnhplanner.data.flowchart.Graph;

/**
 * Which settings a card shows on itself, as chips: pinned from the settings sheet, per machine (every Electric Blast
 * Furnace in the plan shows the same ones), kept in the plan ({@link Graph#settingPins}) so it looks the same wherever
 * it is opened. A machine never pinned in the plan shows its defaults: the coil on a heat recipe.
 */
public final class SettingPins {

    /** Counts every change, so cards and the world views know to lay themselves out again. */
    private static int version;

    private SettingPins() {}

    /** The key a card's pins are kept under: its machine, or its power source. */
    public static String machine(final CardModel m) {
        return m.isPower() ? "power:" + m.node.powerSource : "machine:" + m.machineName;
    }

    public static boolean pinned(final Graph plan, final CardModel m, final SettingControls.Control c) {
        final List<String> kept = plan == null ? null : plan.settingPins.get(machine(m));
        return kept == null ? c.pinDefault() : kept.contains(c.key());
    }

    /** Pins a setting on every card of the machine in the plan, or unpins it; the plan is saved. */
    public static void toggle(final Graph plan, final CardModel m, final SettingControls.Control c) {
        if (plan == null) return;
        final List<String> keys = new ArrayList<>();
        for (final SettingControls.Control each : SettingControls.of(m)) {
            final boolean on = each.key()
                .equals(c.key()) ? !pinned(plan, m, each) : pinned(plan, m, each);
            if (on) keys.add(each.key());
        }
        plan.settingPins.put(machine(m), keys);
        plan.touchLayout();
        version++;
        PlanAPI.save();
    }

    public static int version() {
        return version;
    }
}

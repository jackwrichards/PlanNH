package com.gtnhplanner.machines.web;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Hyper-Intensity Laser Engraver's laser source (hile.ts): every registered laser source hatch (tectech's
 * MachineLoader: IV starts at 256A, each tier adds one four-times-larger hatch, through UXV's 16,777,216A, plus the
 * Legendary UXV source at 536,870,912A), the one setting that picks among them, and the migration of older settings.
 * Pure: no game classes.
 */
public final class Hile {

    private Hile() {}

    /** A laser source hatch: its key, voltage tier name and ordinal (ULV = 0), and amps. */
    public record Source(String key, String tier, int ordinal, long amps) {}

    private static final long LEGENDARY_AMPS = 536_870_912L;

    /** HILE_SOURCES: the 46 registered hatches, IV 256A first, the Legendary source last. */
    public static final List<Source> SOURCES;

    static {
        final List<Source> sources = new ArrayList<>();
        for (int index = 0; index < 9; index++) {
            final String tier = Tiers.NAMES[5 + index];
            for (int ampIndex = 0; ampIndex < index + 1; ampIndex++) {
                final long amps = 256 * (long) Math.pow(4, ampIndex);
                sources.add(new Source(tier.toLowerCase(Locale.ROOT) + "-" + amps, tier, index + 5, amps));
            }
        }
        sources.add(new Source("uxv-536870912", "UXV", 13, LEGENDARY_AMPS));
        SOURCES = Collections.unmodifiableList(sources);
    }

    /** HILE_SOURCE_CONTROL: one source selector, keyed by tier and amps. */
    public static final Web.Control SOURCE_CONTROL;

    static {
        final Web.Control control = new Web.Control();
        control.id = "laserSource";
        control.label = "Laser source";
        control.minimumKey = SOURCES.get(0)
            .key();
        control.defaultKey = SOURCES.get(0)
            .key();
        final List<Web.TierOption> tiers = new ArrayList<>();
        for (final Source source : SOURCES) {
            final String amps = String.format(Locale.US, "%,d", source.amps());
            final boolean legendary = source.amps() == LEGENDARY_AMPS;
            final Web.TierOption tier = new Web.TierOption();
            tier.key = source.key();
            tier.label = source.tier() + " · " + amps + "A" + (legendary ? " (Legendary)" : "");
            final List<String> tooltip = new ArrayList<>();
            tooltip.add(
                (long) Math.floor(Math.cbrt(source.amps())) + " maximum parallels; recipe and overclock ceiling: "
                    + Tiers.NAMES[source.ordinal() + 1]
                    + ".");
            tooltip.add(
                "Requires " + source.tier()
                    + " or higher glass. The laser source supplies no operating power; set energy supply separately.");
            if (source.ordinal() >= 10)
                tooltip.add("Allows one multi-amp energy hatch instead of regular energy hatches.");
            tier.resource = MachineControls.item(
                "factoryflow:machine_config/laser_source_" + source.key(),
                legendary ? "Legendary Laser Source Hatch" : source.tier() + " " + amps + "A Laser Source Hatch",
                tooltip,
                false);
            tiers.add(tier);
        }
        control.tiers = tiers;
        SOURCE_CONTROL = control;
    }

    /** hileSourceAt: the source at a position on the full ladder; the first one past either end. */
    public static Source sourceAt(final int index) {
        return index >= 0 && index < SOURCES.size() ? SOURCES.get(index) : SOURCES.get(0);
    }

    private static final Pattern LEGACY_AMPS = Pattern.compile("a(\\d+)");

    /**
     * normalizeHileSettings: migrates an amperage-only source ({@code a<amps>}) or the separate {@code laserAmperage}
     * count to the lowest registered hatch with at least those amps. A saved named source always wins.
     */
    public static Map<String, String> normalizeSettings(final Map<String, String> settings) {
        final String saved = settings.get("laserSource");
        for (final Source source : SOURCES) if (source.key()
            .equals(saved)) return settings;
        final Matcher old = LEGACY_AMPS.matcher(saved != null ? saved : "");
        final String legacyAmperage = settings.get("laserAmperage");
        final double amps = Js.number(old.matches() ? old.group(1) : legacyAmperage != null ? legacyAmperage : "256");
        Source found = null;
        for (final Source entry : SOURCES) if (entry.amps() >= amps) {
            found = entry;
            break;
        }
        final Source source = found != null ? found : SOURCES.get(0);
        return MachineControls.with(settings, "laserSource", source.key());
    }
}

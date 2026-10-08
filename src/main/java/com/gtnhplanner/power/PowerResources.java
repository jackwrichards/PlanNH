package com.gtnhplanner.power;

import java.util.Map;

import javax.annotation.Nullable;

/**
 * Workbook names to game resources ({@code resource-map.json}) and each source's machine item
 * ({@code machine-icons.json}), copied from the website. Ids are the website's: items {@code mod:name@meta} (no
 * {@code @} for meta 0), fluids their lowercase registry name.
 */
public final class PowerResources {

    public static final String RESOURCE_MAP = "/assets/gtnhplanner/power/resource-map.json";
    public static final String MACHINE_ICONS = "/assets/gtnhplanner/power/machine-icons.json";

    /** {@code kind} is "item" or "fluid". {@code dominantColor} is "#rrggbb" or null. */
    public static class Ref {

        public String kind;
        public String id;
        public String displayName;
        public String dominantColor;

        public boolean fluid() {
            return "fluid".equals(kind);
        }

        /** The dominant colour as 0xRRGGBB, or -1. */
        public int rgb() {
            if (dominantColor == null || !dominantColor.startsWith("#") || dominantColor.length() != 7) return -1;
            try {
                return Integer.parseInt(dominantColor.substring(1), 16);
            } catch (final NumberFormatException e) {
                return -1;
            }
        }
    }

    private static class ResourceFile {

        Map<String, Ref> resources;
    }

    private static class MachineFile {

        Map<String, Ref> machines;
    }

    private static Map<String, Ref> resources, machines;

    private static synchronized Map<String, Ref> resources() {
        if (resources == null) resources = PowerData.read(RESOURCE_MAP, ResourceFile.class).resources;
        return resources;
    }

    private static synchronized Map<String, Ref> machines() {
        if (machines == null) machines = PowerData.read(MACHINE_ICONS, MachineFile.class).machines;
        return machines;
    }

    /** The game resource a workbook flow name stands for; null when the map does not know it (shown as a stat). */
    @Nullable
    public static Ref resolve(final String name) {
        return resources().get(name);
    }

    /** The machine item a source is drawn as; null for none. Its {@code kind} is unset. */
    @Nullable
    public static Ref machineIcon(final String sourceId) {
        return machines().get(sourceId);
    }

    private PowerResources() {}
}

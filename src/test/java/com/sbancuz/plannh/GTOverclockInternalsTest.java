package com.sbancuz.plannh;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.lang.reflect.Field;

import org.junit.jupiter.api.Test;

/**
 * The machine probe resolves GregTech's overclock internals by name, so a rename upstream would turn
 * the probe off and every probed machine into a fallback without anything failing. This fails the
 * build instead: one assertion that the resolver succeeded against the compiled GregTech, which is
 * the only tripwire that matters. The per-field inventory is OverclockInternals' own business and is
 * deliberately not mirrored here - mirroring it would force anyone touching the probe's member list
 * to also edit this test, for zero additional signal.
 *
 * <p>
 * {@code GregTechAPI.METATILEENTITIES} is empty outside a client, so the probe itself cannot run
 * here - only that it resolved is checkable. A resolved probe that reads wrong numbers is caught by
 * the disagreement log and the machine table, not here.
 */
class GTOverclockInternalsTest {

    /**
     * The one assertion that asks the probe itself. Catches a failure for any reason - renamed
     * field, renamed method, missing class - with a single message saying what it means for charts.
     */
    @Test
    void theProbeResolvedItsGregTechInternals() throws ReflectiveOperationException {
        final Field resolved = probeFields().getDeclaredField("RESOLVED");
        resolved.setAccessible(true);

        assertNotNull(
            resolved.get(null),
            "OverclockInternals gave up on GregTech, so the probe is off and every machine falls back");
    }

    private static Class<?> probeFields() throws ClassNotFoundException {
        return Class.forName(
            "com.sbancuz.plannh.data.provider.gregtech.probe.OverclockInternals",
            true,
            GTOverclockInternalsTest.class.getClassLoader());
    }
}

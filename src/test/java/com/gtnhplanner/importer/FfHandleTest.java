package com.gtnhplanner.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** FF's port handles: {@code [r<n>:]side:kind:encodedId[:slot]}. */
class FfHandleTest {

    @Test
    void readsAPlainHandle() {
        final FfHandle h = FfHandle.parse("output:fluid:hydrogen");
        assertNotNull(h);
        assertTrue(h.output());
        assertEquals("fluid", h.kind());
        assertEquals("hydrogen", h.resourceId());
        assertEquals(0, h.section());
        assertEquals(-1, h.slot());
    }

    @Test
    void decodesTheIdAndKeepsTheSlotApart() {
        final FfHandle h = FfHandle.parse("input:item:gregtech%3Agt.metaitem.01%402377:1");
        assertNotNull(h);
        assertFalse(h.output());
        assertEquals("gregtech:gt.metaitem.01@2377", h.resourceId());
        assertEquals(1, h.slot());
    }

    @Test
    void readsASharedMachineSection() {
        final FfHandle h = FfHandle.parse("r1:output:item:gregtech%3Agt.metaitem.01%402032");
        assertNotNull(h);
        assertEquals(1, h.section());
        assertEquals("gregtech:gt.metaitem.01@2032", h.resourceId());
        final FfHandle twelve = FfHandle.parse("r12:input:fluid:water:3");
        assertNotNull(twelve);
        assertEquals(12, twelve.section());
        assertEquals(3, twelve.slot());
        assertEquals(12, FfHandle.sectionOf("r12:input:fluid:water:3"));
        assertEquals(0, FfHandle.sectionOf("input:fluid:water"));
        assertEquals(0, FfHandle.sectionOf(null));
    }

    @Test
    void readsALegacyUnencodedId() {
        final FfHandle h = FfHandle.parse("output:item:minecraft:iron_ingot");
        assertNotNull(h);
        assertEquals("minecraft:iron_ingot", h.resourceId());
        assertEquals(-1, h.slot());
    }

    @Test
    void decodesLikeDecodeUriComponent() {
        assertEquals("a+b c", FfHandle.decode("a+b%20c"));
        assertEquals("été", FfHandle.decode("%C3%A9t%C3%A9"));
        assertEquals("50%", FfHandle.decode("50%"), "a stray percent stays");
    }

    @Test
    void refusesWhatIsNotAPortHandle() {
        assertNull(FfHandle.parse(null));
        assertNull(FfHandle.parse(""));
        assertNull(FfHandle.parse("garbage"));
        assertNull(FfHandle.parse("storage:item:x"));
        assertNull(FfHandle.parse("output:item:"));
    }
}

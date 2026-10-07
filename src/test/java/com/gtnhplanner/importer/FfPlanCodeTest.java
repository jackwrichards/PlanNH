package com.gtnhplanner.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.Deflater;

import org.junit.jupiter.api.Test;

/** Factory Flow's plan codes and links (FF's plan-code.ts). */
class FfPlanCodeTest {

    /** A code the way FF packs one: deflate-raw, URL-safe base64, no padding. */
    static String encode(final String json) {
        final Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION, true);
        deflater.setInput(json.getBytes(StandardCharsets.UTF_8));
        deflater.finish();
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buffer = new byte[4096];
        while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer));
        deflater.end();
        return FfPlanCode.PREFIX + Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(out.toByteArray());
    }

    @Test
    void roundTripsAFixtureThroughItsCode() {
        final String json = FakeGame.fixture("pa-cell-loop-plan.json");
        final String code = encode(json);
        assertTrue(code.length() * 3 < json.length(), "a code is much shorter than its JSON");
        assertEquals(json, FfPlanCode.decode(code));
        assertEquals(
            6,
            FfImport.read(code)
                .nodes()
                .size());
    }

    @Test
    void readsALinkEvenBrokenOverLinesByAChatWindow() {
        final String link = "https://gtnhplanner.com/#p=" + encode(FakeGame.fixture("high-amperage-reactor.json"));
        final String wrapped = "  " + link
            .substring(0, 40) + "\n" + link.substring(40, 90) + "\r\n" + link.substring(90) + "  ";
        assertEquals(
            "Benzene",
            FfImport.read(wrapped)
                .name());
    }

    @Test
    void readsALinkFactoryFlowItselfMade() {
        // Packed by Node's zlib (deflateRawSync), the same deflate-raw as the browser's CompressionStream.
        final String link = FakeGame.fixture("high-amperage-reactor.link.txt");
        assertTrue(link.startsWith("https://gtnhplanner.com/#p=gtnh1."));
        final FfPlan plan = FfImport.read(link);
        assertEquals("Benzene", plan.name());
        assertEquals(
            3,
            plan.storages()
                .size());
    }

    @Test
    void passesJsonThrough() {
        final String json = FakeGame.fixture("high-amperage-reactor.json");
        assertEquals(json.trim(), FfPlanCode.toJson("\n " + json));
    }

    @Test
    void refusesAnythingThatIsNotAWholeCode() {
        final FfImportException notACode = assertThrows(FfImportException.class, () -> FfPlanCode.decode("hello"));
        assertTrue(
            notACode.getMessage()
                .contains("not a Factory Flow plan"));
        final String code = encode(FakeGame.fixture("high-amperage-reactor.json"));
        final FfImportException cut = assertThrows(
            FfImportException.class,
            () -> FfPlanCode.decode(code.substring(0, code.length() / 2)));
        assertTrue(
            cut.getMessage()
                .contains("cut short"));
        assertThrows(FfImportException.class, () -> FfPlanCode.decode("gtnh1.!!!"));
        assertThrows(FfImportException.class, () -> FfPlanCode.decode("gtnh1.A"));
        final FfImportException post = assertThrows(
            FfImportException.class,
            () -> FfPlanCode.decode("https://gtnhplanner.com/?plan=abc123"));
        assertTrue(
            post.getMessage()
                .contains("shared post"));
    }
}

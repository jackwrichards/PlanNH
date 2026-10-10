package com.gtnhplanner.importer;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import javax.annotation.Nullable;

/**
 * Factory Flow's plan codes (FF's plan-code.ts): {@code gtnh1.} then the plan JSON, deflated raw and in URL-safe
 * base64 without padding. Shared as a link, {@code https://gtnhplanner.com/#p=<code>}, often broken over lines by a
 * chat window.
 */
public final class FfPlanCode {

    public static final String PREFIX = "gtnh1.";

    /** A decoded plan bigger than this is not a plan; stops a hostile code from inflating without end. */
    private static final int MAX_JSON_BYTES = 64 << 20;

    private FfPlanCode() {}

    /**
     * The plan JSON in pasted text: the JSON itself, a plan code, or a link carrying one, with any whitespace a
     * chat window added.
     */
    public static String toJson(final String text) {
        final String trimmed = text.trim();
        if (trimmed.startsWith("{")) return trimmed;
        return decode(trimmed);
    }

    /** The JSON in a plan code or a link carrying one ({@code ...#p=<code>}). */
    public static String decode(final String codeOrLink) {
        final String compact = codeOrLink.replaceAll("\\s+", "");
        String code = compact;
        final int hash = compact.indexOf('#');
        if (hash >= 0 && compact.startsWith("p=", hash + 1)) code = compact.substring(hash + 3);
        if (!code.startsWith(PREFIX)) {
            if (compact.contains("?plan="))
                throw new FfImportException("That is a link to a shared post, not a plan link.");
            throw new FfImportException("That is not a plan link or code.");
        }
        final String body = code.substring(PREFIX.length());
        if (!body.matches("[A-Za-z0-9_-]*")) throw damaged(null);
        final byte[] packed;
        try {
            packed = Base64.getUrlDecoder()
                .decode(body);
        } catch (final IllegalArgumentException e) {
            throw damaged(e);
        }
        return new String(inflate(packed), StandardCharsets.UTF_8);
    }

    private static byte[] inflate(final byte[] packed) {
        final Inflater inflater = new Inflater(true);
        try {
            // nowrap streams want one spare byte after the data (Inflater's javadoc); harmless when unneeded.
            final byte[] input = new byte[packed.length + 1];
            System.arraycopy(packed, 0, input, 0, packed.length);
            inflater.setInput(input);
            final ByteArrayOutputStream out = new ByteArrayOutputStream(packed.length * 4);
            final byte[] buffer = new byte[8192];
            while (!inflater.finished()) {
                final int n = inflater.inflate(buffer);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) throw damaged(null);
                out.write(buffer, 0, n);
                if (out.size() > MAX_JSON_BYTES) throw new FfImportException("That plan is too big to import.");
            }
            return out.toByteArray();
        } catch (final DataFormatException e) {
            throw damaged(e);
        } finally {
            inflater.end();
        }
    }

    private static FfImportException damaged(@Nullable final Throwable cause) {
        return new FfImportException("That copied plan is cut short or damaged. Copy it again, all of it.", cause);
    }
}

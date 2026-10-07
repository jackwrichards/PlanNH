package com.sbancuz.plannh.importer;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

/**
 * A port handle on an FF wire: {@code side:kind:id[:slot]}, the id URL-encoded ({@code encodeURIComponent}), with an
 * {@code r<n>:} prefix when the port belongs to section n of a shared machine. FF ports are named by resource; the
 * slot index is a leftover some wires carry and nothing should rely on.
 *
 * @param section 0 for a card's own recipe, n for its n-th extra recipe
 * @param slot    the trailing slot index, or -1
 */
public record FfHandle(int section, boolean output, String kind, String resourceId, int slot) {

    private static final Pattern SECTION = Pattern.compile("^r(\\d+):(.*)$");

    /** The handle, or null when there is none or it does not name a port this way. */
    @Nullable
    public static FfHandle parse(@Nullable final String handle) {
        if (handle == null || handle.isEmpty()) return null;
        int section = 0;
        String bare = handle;
        final Matcher m = SECTION.matcher(handle);
        if (m.matches()) {
            try {
                section = Integer.parseInt(m.group(1));
            } catch (final NumberFormatException e) {
                return null;
            }
            bare = m.group(2);
        }
        final String[] parts = bare.split(":", -1);
        if (parts.length < 3) return null;
        final boolean output;
        if ("output".equals(parts[0])) output = true;
        else if ("input".equals(parts[0])) output = false;
        else return null;
        final String kind = parts[1];
        if (kind.isEmpty()) return null;
        // Encoded ids never hold a colon; a legacy unencoded one does, so everything but a trailing number is the id.
        int last = parts.length;
        int slot = -1;
        if (parts.length > 3 && isNumber(parts[parts.length - 1])) {
            slot = Integer.parseInt(parts[parts.length - 1]);
            last--;
        }
        final StringBuilder id = new StringBuilder(parts[2]);
        for (int i = 3; i < last; i++) id.append(':')
            .append(parts[i]);
        final String decoded = decode(id.toString());
        if (decoded.isEmpty()) return null;
        return new FfHandle(section, output, kind, decoded, slot);
    }

    /** The section a handle names, 0 when it has no prefix or does not parse. */
    public static int sectionOf(@Nullable final String handle) {
        final FfHandle h = parse(handle);
        return h == null ? 0 : h.section();
    }

    private static boolean isNumber(final String s) {
        if (s.isEmpty() || s.length() > 6) return false;
        for (int i = 0; i < s.length(); i++) if (!Character.isDigit(s.charAt(i))) return false;
        return true;
    }

    /** {@code decodeURIComponent}: percent escapes as UTF-8, and a '+' stays a '+'. */
    static String decode(final String s) {
        if (s.indexOf('%') < 0) return s;
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < s.length();) {
            if (s.charAt(i) == '%' && i + 2 < s.length()) {
                final int hi = Character.digit(s.charAt(i + 1), 16), lo = Character.digit(s.charAt(i + 2), 16);
                if (hi >= 0 && lo >= 0) {
                    out.write(hi * 16 + lo);
                    i += 3;
                    continue;
                }
            }
            final int cp = s.codePointAt(i);
            final byte[] bytes = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8);
            out.write(bytes, 0, bytes.length);
            i += Character.charCount(cp);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}

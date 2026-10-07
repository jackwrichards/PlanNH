package com.sbancuz.plannh.importer;

import java.util.Collection;
import java.util.Locale;

import javax.annotation.Nullable;

/**
 * Resource ids the Factory Flow way: an item is its lowercase registry name, with {@code @meta} only when the meta
 * is not 0 ({@code gregtech:gt.metaitem.01@2377}, {@code ic2:itemcellempty}); a fluid is its fluid name; ore
 * dictionary slots are {@code oredict:<name>}. Meta 32767 is the any-damage wildcard. Ids are compared ignoring case.
 */
public final class FfIds {

    /** Forge's {@code OreDictionary.WILDCARD_VALUE}: any damage. */
    public static final int WILDCARD = 32767;

    private static final String CIRCUIT = "gregtech:gt.integrated_circuit";

    private FfIds() {}

    /** Lowercased, trimmed, with a {@code @0} meta dropped (FF never writes it). */
    public static String normalize(final String id) {
        String n = id.trim()
            .toLowerCase(Locale.ROOT);
        if (n.endsWith("@0")) n = n.substring(0, n.length() - 2);
        return n;
    }

    /** An item id from a registry name and a damage value. */
    public static String itemId(final String registryName, final int meta) {
        final String name = registryName.toLowerCase(Locale.ROOT);
        return meta == 0 ? name : name + "@" + meta;
    }

    /** Whether two ids name the same resource: equal ignoring case, or the same item where one side is wildcard. */
    public static boolean same(final String a, final String b) {
        // Fast paths: the matcher calls this a great many times, and ids are mostly normalized already.
        if (a.equalsIgnoreCase(b)) return true;
        if (a.indexOf('@') < 0 && b.indexOf('@') < 0) return a.trim()
            .equalsIgnoreCase(b.trim());
        final String x = normalize(a), y = normalize(b);
        if (x.equals(y)) return true;
        final int ax = metaAt(x), ay = metaAt(y);
        final String nx = ax < 0 ? x : x.substring(0, ax), ny = ay < 0 ? y : y.substring(0, ay);
        if (!nx.equals(ny)) return false;
        return meta(x) == WILDCARD || meta(y) == WILDCARD;
    }

    /** Whether any id of one list names the same resource as any of the other. */
    public static boolean anySame(final Collection<String> a, final Collection<String> b) {
        for (final String x : a) for (final String y : b) if (same(x, y)) return true;
        return false;
    }

    /** The {@code @meta} of an item id, 0 when it has none. */
    public static int meta(final String id) {
        final int at = metaAt(id);
        return at < 0 ? 0 : Integer.parseInt(id.substring(at + 1));
    }

    /** Index of a numeric {@code @meta} suffix's '@', or -1. */
    private static int metaAt(final String id) {
        final int at = id.lastIndexOf('@');
        if (at <= 0 || at == id.length() - 1 || id.length() - at - 1 > 9) return -1;
        for (int i = at + 1; i < id.length(); i++) if (!Character.isDigit(id.charAt(i))) return -1;
        return at;
    }

    /** The setting of a programmed circuit item id ({@code gregtech:gt.integrated_circuit@22}), else null. */
    @Nullable
    public static Integer circuitOf(final String id) {
        final String n = normalize(id);
        if (n.equals(CIRCUIT)) return 0;
        if (!n.startsWith(CIRCUIT + "@")) return null;
        try {
            return Integer.parseInt(n.substring(CIRCUIT.length() + 1));
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    /**
     * A best guess at PlanNH's resource key ({@code item:<registry name>:<meta>} or {@code fluid:<name>}) for an FF
     * id. FF lowercases registry names and the game does not, so a key read off a real port is better whenever there
     * is one.
     */
    public static String toKey(final String kind, final String id) {
        final String n = normalize(id);
        if ("fluid".equals(kind)) return "fluid:" + n;
        final int at = metaAt(n);
        return at < 0 ? "item:" + n + ":0" : "item:" + n.substring(0, at) + ":" + n.substring(at + 1);
    }

    /** FF's slug: lowercase, every run of other characters one dash, no dash at either end. */
    public static String slug(final String value) {
        final String s = value.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-");
        int start = 0, end = s.length();
        if (start < end && s.charAt(start) == '-') start++;
        if (end > start && s.charAt(end - 1) == '-') end--;
        return s.substring(start, end);
    }
}

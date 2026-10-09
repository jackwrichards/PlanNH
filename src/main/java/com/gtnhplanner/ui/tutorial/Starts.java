package com.gtnhplanner.ui.tutorial;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gtnhplanner.GtnhPlanner;
import com.gtnhplanner.data.flowchart.Serializer;

/**
 * The tour's plans as they stand at the start of its beats, shipped with the mod: the dev harness starts any beat at
 * once from these instead of hurrying through every beat before it. Captured from a run of the tour in the dev game
 * ({@code call 'tutorial?export=1'}); a start whose recipes this pack does not have reads short and is not used (the
 * tour then hurries there as before).
 */
final class Starts {

    private Starts() {}

    private static final String RESOURCE = "/assets/gtnhplanner/tutorial/starts.json";

    /** A beat's start: the tour's plans, as saved, and the screen it starts on. */
    record Entry(String plans, Tour.Scene scene) {}

    /** Beat to {plans, how many cards they hold, its screen}. */
    private record Start(String plans, int cards, Tour.Scene scene) {}

    private static Map<Integer, Start> starts;

    private static Map<Integer, Start> load() {
        if (starts != null) return starts;
        starts = new HashMap<>();
        try (InputStream in = Starts.class.getResourceAsStream(RESOURCE)) {
            if (in == null) return starts;
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                final JsonObject root = new JsonParser().parse(r)
                    .getAsJsonObject();
                for (final Map.Entry<String, JsonElement> e : root.getAsJsonObject("starts")
                    .entrySet()) {
                    final JsonObject s = e.getValue()
                        .getAsJsonObject();
                    starts.put(
                        Integer.parseInt(e.getKey()),
                        new Start(
                            s.get("plans")
                                .getAsString(),
                            s.get("cards")
                                .getAsInt(),
                            Tour.Scene.valueOf(
                                s.get("scene")
                                    .getAsString())));
                }
            }
        } catch (final IOException | RuntimeException e) {
            GtnhPlanner.LOG.warn("[tutorial] could not read the beats' starts", e);
        }
        return starts;
    }

    /** The shipped beat nearest at or before {@code beat} and after {@code after}; -1 for none. */
    static int nearest(final int beat, final int after) {
        for (int b = beat; b > after; b--) if (load().containsKey(b)) return b;
        return -1;
    }

    /** A beat's shipped plans, when they read whole in this pack; else null. */
    static String plans(final int beat) {
        final Start s = load().get(beat);
        if (s == null) return null;
        try {
            return cards(s.plans()) == s.cards() ? s.plans() : null;
        } catch (final RuntimeException e) {
            return null;
        }
    }

    static Tour.Scene scene(final int beat) {
        final Start s = load().get(beat);
        return s == null ? Script.START : s.scene();
    }

    private static int cards(final String plans) {
        int cards = 0;
        for (final com.gtnhplanner.data.flowchart.Graph g : Serializer.decodePlan(plans)
            .getGraphs()) cards += g.nodes.size();
        return cards;
    }

    /** Writes beats' starts for shipping. */
    static void write(final File file, final Map<Integer, Entry> entries) throws IOException {
        final JsonObject out = new JsonObject();
        out.addProperty("about", "The tour's plans at the start of each beat; written by call 'tutorial?export=1'.");
        final JsonObject all = new JsonObject();
        for (final Map.Entry<Integer, Entry> e : new TreeMap<>(entries).entrySet()) {
            final JsonObject s = new JsonObject();
            s.addProperty(
                "scene",
                e.getValue()
                    .scene()
                    .name());
            s.addProperty(
                "cards",
                cards(
                    e.getValue()
                        .plans()));
            s.addProperty(
                "plans",
                e.getValue()
                    .plans());
            all.add(Integer.toString(e.getKey()), s);
        }
        out.add("starts", all);
        file.getParentFile()
            .mkdirs();
        final Gson gson = new GsonBuilder().setPrettyPrinting()
            .create();
        Files.write(
            file.toPath(),
            gson.toJson(out)
                .getBytes(StandardCharsets.UTF_8));
        starts = null;
    }
}

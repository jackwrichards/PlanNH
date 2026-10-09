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
import com.gtnhplanner.data.flowchart.Plan;
import com.gtnhplanner.data.flowchart.Serializer;

/**
 * The tour's plans as they stand at the start of each chapter, shipped with the mod: picking a chapter not watched
 * yet starts there at once instead of hurrying through every chapter before it. Captured from a run of the tour in
 * the dev game ({@code call 'tutorial?export=1'}); a start whose recipes this pack does not have reads short and is
 * not used (the tour then hurries there as before).
 */
final class Starts {

    private Starts() {}

    private static final String RESOURCE = "/assets/gtnhplanner/tutorial/starts.json";

    /** Chapter to {plans, how many cards they hold}. */
    private record Start(String plans, int cards) {}

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
                                .getAsInt()));
                }
            }
        } catch (final IOException | RuntimeException e) {
            GtnhPlanner.LOG.warn("[tutorial] could not read the chapters' starts", e);
        }
        return starts;
    }

    /** The shipped chapter nearest at or before {@code chapter} and after {@code after}; -1 for none. */
    static int nearest(final int chapter, final int after) {
        for (int c = chapter; c > after; c--) if (load().containsKey(c)) return c;
        return -1;
    }

    /** A chapter's shipped plans, when they read whole in this pack; else null. */
    static String plans(final int chapter) {
        final Start s = load().get(chapter);
        if (s == null) return null;
        try {
            final Plan plan = Serializer.decodePlan(s.plans());
            int cards = 0;
            for (final com.gtnhplanner.data.flowchart.Graph g : plan.getGraphs()) cards += g.nodes.size();
            return cards == s.cards() ? s.plans() : null;
        } catch (final RuntimeException e) {
            return null;
        }
    }

    /** Writes chapter starts (their plans, as saved) for shipping. */
    static void write(final File file, final Map<Integer, String> plans) throws IOException {
        final JsonObject out = new JsonObject();
        out.addProperty("about", "The tour's plans at the start of each chapter; written by call 'tutorial?export=1'.");
        final JsonObject all = new JsonObject();
        for (final Map.Entry<Integer, String> e : new TreeMap<>(plans).entrySet()) {
            final Plan plan = Serializer.decodePlan(e.getValue());
            int cards = 0;
            for (final com.gtnhplanner.data.flowchart.Graph g : plan.getGraphs()) cards += g.nodes.size();
            final JsonObject s = new JsonObject();
            s.addProperty("cards", cards);
            s.addProperty("plans", e.getValue());
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

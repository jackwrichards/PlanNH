package com.gtnhplanner.library;

import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;
import javax.imageio.ImageIO;

import net.minecraft.client.Minecraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.gtnhplanner.GtnhPlanner;
import com.gtnhplanner.Tags;
import com.gtnhplanner.data.flowchart.Graph;

/**
 * Reports from the Feedback box: a bug, an idea or a call for help, with how the player feels, their words, the plans
 * they pick (as the library's plan JSON), a picture of the open plan, and if they agree the end of the game log and a
 * crash report. The site opens each as a public issue on the planner's GitHub repo and answers its link, so no one
 * needs a GitHub account. Names, the home folder and addresses are taken out of the log and the crash report first.
 */
public final class Reports {

    /** What a report is, as the site files it. */
    public enum Kind {

        BUG("bug"),
        IDEA("idea"),
        HELP("help");

        final String id;

        Kind(final String id) {
            this.id = id;
        }
    }

    /** How the planner is treating the player. */
    public enum Mood {

        HAPPY("happy"),
        OKAY("okay"),
        SAD("sad");

        final String id;

        Mood(final String id) {
            this.id = id;
        }
    }

    /** What goes: the words, the plans and the extras ({@code machines} counts the open plan's cards). */
    public record Report(Kind kind, @Nullable Mood mood, String title, String description, List<Graph> plans,
        Function<UUID, Double> machines, @Nullable BufferedImage picture, boolean log, @Nullable String crash) {}

    /** The widest picture sent: enough to see the plan, small enough to send. */
    private static final int PICTURE_MAX_W = 1600;
    /** How much of the log goes: its last lines, at most this many characters. */
    private static final int LOG_LINES = 200, TEXT_MAX = 50_000;

    private static final ExecutorService NET = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "GTNH Planner reports");
        t.setDaemon(true);
        return t;
    });

    private Reports() {}

    /** Sends the report in the background; {@code done} gets its issue, or {@code failed} why not, on the client. */
    public static void send(final Report report, final Consumer<CommunityApi.Sent> done,
        final Consumer<String> failed) {
        final JsonObject body;
        try {
            // The plans are read here, on the client thread, where the game's items are safe to touch.
            body = json(report);
        } catch (final RuntimeException e) {
            GtnhPlanner.LOG.warn("Could not write the report out", e);
            failed.accept("Couldn't write the report out: " + e.getMessage());
            return;
        }
        final BufferedImage picture = report.picture();
        NET.execute(() -> {
            try {
                if (picture != null) body.addProperty("picture", png(picture));
                final CommunityApi.Sent sent = CommunityApi.report(body);
                onClient(() -> done.accept(sent));
            } catch (final Exception e) {
                GtnhPlanner.LOG.info("Report: could not send", e);
                onClient(() -> failed.accept(e.getMessage() == null ? e.toString() : e.getMessage()));
            }
        });
    }

    /** The report as the site takes it (all but the picture, which is added on the way). */
    static JsonObject json(final Report r) {
        final JsonObject o = new JsonObject();
        o.addProperty("kind", r.kind().id);
        if (r.mood() != null) o.addProperty("mood", r.mood().id);
        o.addProperty(
            "title",
            r.title()
                .trim());
        o.addProperty(
            "description",
            r.description()
                .trim());
        o.addProperty("deviceId", Account.deviceId());
        o.add("versions", versions());
        final JsonArray plans = new JsonArray();
        for (final Graph g : r.plans()) {
            final JsonObject p = new JsonObject();
            p.addProperty("name", g.getName());
            p.add("plan", Posting.write(g, g.getName(), r.machines()));
            plans.add(p);
        }
        o.add("plans", plans);
        if (r.log()) {
            final String log = logTail();
            if (!log.isEmpty()) o.addProperty("log", log);
        }
        if (r.crash() != null) o.addProperty("crash", cut(redact(r.crash())));
        return o;
    }

    /** The versions that matter to a fix: the planner's, the pack's, Java's and the system's. */
    static JsonObject versions() {
        final JsonObject v = new JsonObject();
        v.addProperty("mod", Tags.VERSION);
        v.addProperty("pack", Posting.packVersion());
        v.addProperty("java", System.getProperty("java.version", ""));
        v.addProperty("os", System.getProperty("os.name", "") + " " + System.getProperty("os.version", ""));
        return v;
    }

    /** The end of the game's log, names and addresses taken out. */
    static String logTail() {
        final File logs = new File(Minecraft.getMinecraft().mcDataDir, "logs");
        File f = new File(logs, "latest.log");
        if (!f.isFile()) f = new File(logs, "fml-client-latest.log");
        if (!f.isFile()) return "";
        try {
            return cut(redact(tail(f, LOG_LINES)));
        } catch (final IOException e) {
            return "";
        }
    }

    /** The last {@code lines} lines of a file, read from its end. */
    static String tail(final File f, final int lines) throws IOException {
        try (RandomAccessFile in = new RandomAccessFile(f, "r")) {
            final long length = in.length();
            final int chunk = (int) Math.min(length, 256 * 1024);
            final byte[] bytes = new byte[chunk];
            in.seek(length - chunk);
            in.readFully(bytes);
            final String text = new String(bytes, StandardCharsets.UTF_8);
            final List<String> all = new ArrayList<>(
                List.of(
                    text.replace("\r", "")
                        .split("\n")));
            if (chunk < length && !all.isEmpty()) all.remove(0);
            return String.join("\n", all.subList(Math.max(0, all.size() - lines), all.size()));
        }
    }

    private static final Pattern ADDRESS = Pattern.compile("\\b\\d{1,3}(?:\\.\\d{1,3}){3}:\\d{2,5}\\b");
    private static final Pattern CONNECTING = Pattern.compile("(Connecting to )\\S+", Pattern.CASE_INSENSITIVE);
    private static final Pattern TOKEN = Pattern
        .compile("((?:accessToken|session|token)[\"'=:, ]+)[A-Za-z0-9._:\\-]{8,}", Pattern.CASE_INSENSITIVE);
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+");

    /**
     * Text with what could say who the player is taken out: their home folder, their system and game names, server
     * addresses, session tokens and e-mail addresses.
     */
    public static String redact(final String text) {
        return redact(text, System.getProperty("user.home", ""), System.getProperty("user.name", ""), player());
    }

    static String redact(final String text, final String home, final String user, final String player) {
        String out = text;
        if (home.length() > 3) {
            out = out.replace(home, "~")
                .replace(home.replace('\\', '/'), "~");
        }
        out = TOKEN.matcher(out)
            .replaceAll("$1<hidden>");
        out = CONNECTING.matcher(out)
            .replaceAll("$1<address>");
        out = ADDRESS.matcher(out)
            .replaceAll("<address>");
        out = EMAIL.matcher(out)
            .replaceAll("<email>");
        for (final String[] name : new String[][] { { user, "<user>" }, { player, "<player>" } }) {
            if (name[0] == null || name[0].length() < 3) continue;
            out = Pattern.compile("(?<![\\w])" + Pattern.quote(name[0]) + "(?![\\w])", Pattern.CASE_INSENSITIVE)
                .matcher(out)
                .replaceAll(Matcher.quoteReplacement(name[1]));
        }
        return out;
    }

    private static String player() {
        try {
            return Minecraft.getMinecraft()
                .getSession()
                .getUsername();
        } catch (final RuntimeException e) {
            return "";
        }
    }

    /** Text cut to what the site takes, keeping its end (where the error is). */
    static String cut(final String text) {
        return text.length() <= TEXT_MAX ? text : "...\n" + text.substring(text.length() - TEXT_MAX + 4);
    }

    /** The picture as PNG, in base64, no wider than the site needs. */
    static String png(final BufferedImage image) throws IOException {
        BufferedImage at = image;
        if (at.getWidth() > PICTURE_MAX_W) {
            final int h = Math.max(1, Math.round(at.getHeight() * PICTURE_MAX_W / (float) at.getWidth()));
            while (at.getWidth() / 2 >= PICTURE_MAX_W) {
                at = scaled(at, at.getWidth() / 2, at.getHeight() / 2);
            }
            at = scaled(at, PICTURE_MAX_W, h);
        }
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(at, "png", out);
        return Base64.getEncoder()
            .encodeToString(out.toByteArray());
    }

    private static BufferedImage scaled(final BufferedImage from, final int w, final int h) {
        final BufferedImage to = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        final java.awt.Graphics2D g = to.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(from, 0, 0, w, h, null);
        g.dispose();
        return to;
    }

    private static void onClient(final Runnable r) {
        Minecraft.getMinecraft()
            .func_152344_a(r);
    }
}

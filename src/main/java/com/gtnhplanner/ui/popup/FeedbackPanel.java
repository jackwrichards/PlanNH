package com.gtnhplanner.ui.popup;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;

import org.lwjgl.opengl.GL11;

import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.widget.ParentWidget;
import com.gtnhplanner.client.CrashReports;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.library.CommunityApi;
import com.gtnhplanner.library.Reports;
import com.gtnhplanner.ui.BoardSession;
import com.gtnhplanner.ui.canvas.BoardCanvas;
import com.gtnhplanner.ui.canvas.PlanPicture;
import com.gtnhplanner.ui.card.CardPaint;
import com.gtnhplanner.ui.sound.Sfx;
import com.gtnhplanner.ui.theme.Hyb;
import com.gtnhplanner.ui.theme.Icons;

/**
 * The top bar's Feedback, small and friendly: three big faces (how it's going, if you like), what it is (a bug, an
 * idea, or a call for help, which points to the Discord first), one box to write in, and a line saying what goes with
 * it: the open plan and its picture, the end of the game log, and the crash report when there is one. Send makes it an
 * issue on the planner's GitHub repo (the site opens it; no account needed) and gives its link. The issue's title is
 * the first line written.
 */
public final class FeedbackPanel extends ParentWidget<FeedbackPanel> implements Interactable {

    /** GTNH Planner's thread on the GT New Horizons Discord. */
    public static final String DISCORD_URL = "https://discord.com/channels/181078474394566657/1531402304530682036";

    private static final int W = 320, TITLE = 22, PAD = 10;
    private static final int FACE = 14, FACE_SCALE = 2, CHIP_H = 18, KEY_H = 18;
    private static final int FACES_Y = TITLE + 10, KINDS_Y = FACES_Y + FACE * FACE_SCALE + 10,
        PROMPT_Y = KINDS_Y + CHIP_H + 9, AREA_Y = PROMPT_Y + 12, AREA_H = 66, SENDS_Y = AREA_Y + AREA_H + 8,
        KEY_Y = SENDS_Y + 24, H = KEY_Y + KEY_H + PAD;
    private static final int CONTROL = 0xFF2A2C31, CONTROL_HOVER = 0xFF33363C, CYAN = 0xFF22D3EE, WELL = 0xFF16171A,
        DISCORD = 0xFF8EA1FF;
    private static final int[] MOOD_INK = { 0xFF5EE9B5, 0xFFE7C35A, 0xFFE57373 };
    private static final String[] KINDS = { "Bug", "Idea", "Help" };

    private final BoardSession session;
    private final BoardCanvas canvas;
    private final TextArea words;
    @Nullable
    private final CrashReports.Crash crash;

    private Reports.Kind kind = Reports.Kind.BUG;
    @Nullable
    private Reports.Mood mood;
    private boolean focusedOnce;

    private boolean sending;
    @Nullable
    private String error;
    @Nullable
    private CommunityApi.Sent sent;

    private FeedbackPanel(final BoardSession session, final BoardCanvas canvas,
        @Nullable final CrashReports.Crash crash) {
        this.session = session;
        this.canvas = canvas;
        this.crash = crash;
        size(W, H);
        words = new TextArea("");
        child(
            words.pos(PAD + 1, AREA_Y + 1)
                .size(W - 2 * PAD - 2, AREA_H - 2));
    }

    /** Opens the box in the middle of the screen; with a crash report to send when {@code crash} is one. */
    public static void open(final ModularPanel parent, final BoardSession session, final BoardCanvas canvas,
        @Nullable final CrashReports.Crash crash) {
        final Popup popup = new Popup("gtnhplanner_feedback", W, H) {

            @Override
            public void drawBackground(final ModularGuiContext context, final WidgetThemeEntry<?> widgetTheme) {
                Hyb.dropShadow(0, 0, W, H);
                CardPaint.surface(W, H);
            }

            /** What was written must never be lost to a stray click beside the box. */
            @Override
            public boolean closeOnOutOfBoundsClick() {
                return false;
            }
        };
        popup.child(new FeedbackPanel(session, canvas, crash).pos(0, 0));
        final Minecraft mc = Minecraft.getMinecraft();
        final ScaledResolution sr = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
        Popup.open(parent, popup, (sr.getScaledWidth() - W) / 2, (sr.getScaledHeight() - H) / 2);
    }

    @Override
    public void onUpdate() {
        super.onUpdate();
        // Straight into writing.
        if (!focusedOnce && isValid()) {
            focusedOnce = true;
            getContext().focus(words);
        }
    }

    /** The open plan, when it has anything on it to send. */
    @Nullable
    private Graph plan() {
        final Graph g = session.graph();
        return g.getNodes()
            .isEmpty() ? null : g;
    }

    // region Where things are (widget-local)

    private int[] closeRect() {
        return new int[] { W - 18, (TITLE - 14) / 2, 14, 14 };
    }

    private int[] faceRect(final int i) {
        final int size = FACE * FACE_SCALE, gap = 22, x0 = (W - 3 * size - 2 * gap) / 2;
        return new int[] { x0 + i * (size + gap), FACES_Y, size, size };
    }

    private static int chipW(final String label) {
        return 10 + 5 + Hyb.width(label) + 14;
    }

    private int[] kindRect(final int i) {
        int total = 0;
        for (final String k : KINDS) total += chipW(k);
        total += 2 * 8;
        int x = (W - total) / 2;
        for (int k = 0; k < i; k++) x += chipW(KINDS[k]) + 8;
        return new int[] { x, KINDS_Y, chipW(KINDS[i]), CHIP_H };
    }

    private int[] discordRect() {
        final String s = "Open Discord";
        return new int[] { W - PAD - Hyb.width(s), PROMPT_Y, Hyb.width(s), 9 };
    }

    private int[] sendRect() {
        return new int[] { W - PAD - 64, KEY_Y, 64, KEY_H };
    }

    private static boolean in(final int[] r, final int x, final int y) {
        return x >= r[0] && x < r[0] + r[2] && y >= r[1] && y < r[1] + r[3];
    }

    // endregion

    // region Drawing

    private int mouseX() {
        return getContext().getAbsMouseX() - getArea().x;
    }

    private int mouseY() {
        return getContext().getAbsMouseY() - getArea().y;
    }

    @Override
    public boolean canHover() {
        return true;
    }

    @Override
    public void draw(final ModularGuiContext context, final WidgetThemeEntry<?> widgetTheme) {
        final boolean hovering = isHovering();
        final int mx = hovering ? mouseX() : -1, my = hovering ? mouseY() : -1;
        Hyb.text("Send feedback", PAD, (TITLE - 8) / 2f + 1, Hyb.INK);
        final int[] close = closeRect();
        final boolean closeHot = in(close, mx, my);
        if (closeHot) Hyb.rect(close[0], close[1], close[2], close[3], CONTROL_HOVER);
        cross(close[0] + 7f, close[1] + 7f, closeHot ? Hyb.INK : Hyb.MUTED);
        Hyb.rect(1, TITLE, W - 2, 1, CardPaint.HAIR);
        if (sent != null) {
            drawSent(mx, my);
            return;
        }

        // How it's going: three big faces, one to pick or none.
        for (int i = 0; i < 3; i++) {
            final int[] r = faceRect(i);
            face(i, r[0], r[1], mood != null && mood.ordinal() == i, !sending && in(r, mx, my), FACE_SCALE);
        }
        // What it is.
        for (int i = 0; i < KINDS.length; i++) {
            final int[] r = kindRect(i);
            final boolean picked = kind.ordinal() == i, hot = !sending && in(r, mx, my);
            // Each kind in its own colour, a little softer until it is picked or hovered.
            final int colour = kindColour(i);
            chip(r, colour, picked, hot);
            kindIcon(i, r[0] + 7, r[1] + 4, picked || hot ? colour : Hyb.mix(colour, CONTROL, 0.75f));
            Hyb.text(KINDS[i], r[0] + 7 + 10 + 5, r[1] + 5, picked || hot ? Hyb.INK : Hyb.MUTED);
        }
        // What to write, and for help, the Discord first.
        final String prompt = switch (kind) {
            case BUG -> crash != null ? "What were you doing when it crashed?"
                : "What happened, and what did you expect?";
            case IDEA -> "What would make the planner better?";
            case HELP -> "Quick questions do well on the Discord.";
        };
        Hyb.text(prompt, PAD, PROMPT_Y, Hyb.MUTED);
        if (kind == Reports.Kind.HELP) {
            final int[] d = discordRect();
            Hyb.text("Open Discord", d[0], d[1], in(d, mx, my) ? 0xFFFFFFFF : DISCORD);
        }
        Hyb.rect(PAD, AREA_Y, W - 2 * PAD, AREA_H, CardPaint.EDGE);
        Hyb.rect(PAD + 1, AREA_Y + 1, W - 2 * PAD - 2, AREA_H - 2, WELL);
        // What goes with it, in words.
        final List<String> lines = wrap(sends(), W - 2 * PAD);
        for (int i = 0; i < Math.min(2, lines.size()); i++) Hyb.text(lines.get(i), PAD, SENDS_Y + i * 10, 0xFFB8BAC2);
        // Where it goes, and the key.
        Hyb.text(
            Hyb.fit(error != null ? error : "It becomes a public issue on GitHub.", W - 2 * PAD - 72),
            PAD,
            KEY_Y + 5,
            error != null ? Hyb.AMBER_INK : 0xFF6A6C74);
        final int[] s = sendRect();
        final boolean hot = !sending && in(s, mx, my);
        Hyb.rect(s[0], s[1], s[2], s[3], sending ? 0xFF1E6B78 : hot ? 0xFF38E1F5 : CYAN);
        Hyb.rect(s[0], s[1], s[2], 1, 0x66FFFFFF);
        final String label = sending ? "Sending..." : "Send";
        com.cleanroommc.modularui.drawable.GuiDraw
            .drawText(label, s[0] + (s[2] - Hyb.width(label)) / 2f, s[1] + 5, 1f, 0xFF0B1A1E, false);
    }

    /** What goes with the words, as a sentence: the open plan by name, the game log, the crash report. */
    private String sends() {
        final List<String> parts = new ArrayList<>();
        final Graph g = plan();
        if (g != null) parts.add("the open plan '" + g.getName() + "'");
        parts.add("the game log");
        if (crash != null) parts.add("the crash report");
        final String last = parts.remove(parts.size() - 1);
        return "Sent with it: " + (parts.isEmpty() ? last : String.join(", ", parts) + " and " + last) + ".";
    }

    private static List<String> wrap(final String text, final int width) {
        final List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (final String word : text.split(" ")) {
            final String next = line.length() == 0 ? word : line + " " + word;
            if (Hyb.width(next) <= width || line.length() == 0) line = new StringBuilder(next);
            else {
                lines.add(line.toString());
                line = new StringBuilder(word);
            }
        }
        lines.add(line.toString());
        if (lines.size() > 2)
            lines.set(1, Hyb.fit(lines.get(1) + " " + String.join(" ", lines.subList(2, lines.size())), width));
        return lines;
    }

    /** Sent: a happy face, its issue, and keys to open it or copy its link. */
    private void drawSent(final int mx, final int my) {
        final int size = FACE * 3;
        face(0, (W - size) / 2, TITLE + 20, true, false, 3);
        Hyb.textCentered("Thanks! It's sent.", W / 2f, TITLE + 20 + size + 14, Hyb.INK);
        Hyb.textCentered("Issue #" + sent.number() + " on GitHub", W / 2f, TITLE + 20 + size + 28, Hyb.MUTED);
        final String[] labels = { "Open it", "Copy link", "Done" };
        for (int i = 0; i < 3; i++) {
            final int[] k = sentKey(i);
            final boolean hot = in(k, mx, my);
            if (i == 0) {
                Hyb.rect(k[0], k[1], k[2], k[3], hot ? 0xFF38E1F5 : CYAN);
                com.cleanroommc.modularui.drawable.GuiDraw
                    .drawText(labels[i], k[0] + (k[2] - Hyb.width(labels[i])) / 2f, k[1] + 5, 1f, 0xFF0B1A1E, false);
            } else {
                Hyb.bevel(k[0], k[1], k[2], k[3], hot ? Hyb.KEY_HOVER : Hyb.KEY, Hyb.KEY_HI, Hyb.KEY_LO, 0, 1);
                Hyb.textCentered(labels[i], k[0] + k[2] / 2f, k[1] + 5, Hyb.INK);
            }
        }
    }

    private int[] sentKey(final int i) {
        final int w = 70, gap = 8, x0 = (W - 3 * w - 2 * gap) / 2;
        return new int[] { x0 + i * (w + gap), H - PAD - KEY_H - 4, w, KEY_H };
    }

    /** A rounded chip: picked, an edge in its kind's colour on a face tinted with it. */
    private static void chip(final int[] r, final int colour, final boolean picked, final boolean hot) {
        final int edge = picked ? colour : hot ? 0xFF5A5D66 : 0xFF3A3C42;
        final int fill = picked ? Hyb.mix(colour, CardPaint.SURFACE, 0.16f) : hot ? CONTROL_HOVER : CONTROL;
        pill(r[0], r[1], r[2], r[3], edge);
        pill(r[0] + 1, r[1] + 1, r[2] - 2, r[3] - 2, fill);
    }

    /** A kind's picture, the top bar's own: a bug, a light bulb, a question mark. */
    private static void kindIcon(final int kind, final int x, final int y, final int c) {
        switch (kind) {
            case 0 -> Icons.bug(x, y, c);
            case 1 -> Icons.bulb(x, y, c);
            default -> Icons.help(x, y, c);
        }
    }

    /** A kind's own colour: a red bug, an amber bulb, a blue question. */
    private static int kindColour(final int kind) {
        return switch (kind) {
            case 0 -> 0xFFE5484D;
            case 1 -> 0xFFFCD34D;
            default -> 0xFF60A5FA;
        };
    }

    /**
     * A face for a mood (happy, okay, sad), {@code scale} times 14 pixels, always in its colour: a shine at its top
     * left, and a touch of its own (a happy face's pink cheeks, a sad one's tear). Softer until it is hovered or
     * picked,
     * a white ring once picked.
     */
    private static void face(final int mood, final int x, final int y, final boolean picked, final boolean hot,
        final int scale) {
        final int ink = MOOD_INK[mood], dark = 0xFF15171A;
        final boolean soft = !picked && !hot;
        final int fill = soft ? Hyb.mix(ink, CardPaint.SURFACE, 0.7f) : ink;
        final int feature = soft ? Hyb.mix(dark, fill, 0.85f) : dark;
        GL11.glPushMatrix();
        GL11.glTranslatef(x, y, 0);
        GL11.glScalef(scale, scale, 1);
        if (picked) {
            // A ring a pixel out.
            Hyb.rect(2, -1, FACE - 4, FACE + 2, 0xFFFFFFFF);
            Hyb.rect(0, 0, FACE, FACE, 0xFFFFFFFF);
            Hyb.rect(-1, 2, FACE + 2, FACE - 4, 0xFFFFFFFF);
        }
        // A disc: the corners cut.
        Hyb.rect(3, 0, FACE - 6, FACE, fill);
        Hyb.rect(1, 1, FACE - 2, FACE - 2, fill);
        Hyb.rect(0, 3, FACE, FACE - 6, fill);
        final int shine = Hyb.mix(0xFFFFFFFF, fill, 0.45f);
        Hyb.rect(3, 2, 1, 1, shine);
        Hyb.rect(2, 3, 1, 1, shine);
        Hyb.rect(4, 4, 2, 2, feature);
        Hyb.rect(8, 4, 2, 2, feature);
        switch (mood) {
            case 0 -> {
                Hyb.rect(3, 8, 1, 1, feature);
                Hyb.rect(4, 9, 6, 1, feature);
                Hyb.rect(10, 8, 1, 1, feature);
                final int cheek = Hyb.mix(0xFFFF6F9C, fill, soft ? 0.55f : 0.8f);
                Hyb.rect(2, 7, 1, 1, cheek);
                Hyb.rect(11, 7, 1, 1, cheek);
            }
            case 1 -> Hyb.rect(4, 9, 6, 1, feature);
            default -> {
                Hyb.rect(3, 10, 1, 1, feature);
                Hyb.rect(4, 9, 6, 1, feature);
                Hyb.rect(10, 10, 1, 1, feature);
                Hyb.rect(4, 6, 1, 2, soft ? Hyb.mix(0xFF7CC4FF, fill, 0.7f) : 0xFF7CC4FF);
            }
        }
        GL11.glPopMatrix();
    }

    private static void pill(final int x, final int y, final int w, final int h, final int colour) {
        Hyb.rect(x + 1, y, w - 2, h, colour);
        Hyb.rect(x, y + 1, 1, h - 2, colour);
        Hyb.rect(x + w - 1, y + 1, 1, h - 2, colour);
    }

    private static void cross(final float cx, final float cy, final int colour) {
        for (int d = -3; d <= 3; d++) {
            Hyb.rect(cx + d - 0.5f, cy + d - 0.5f, 1, 1, colour);
            Hyb.rect(cx + d - 0.5f, cy - d - 0.5f, 1, 1, colour);
        }
    }

    // endregion

    // region Input

    @Override
    public Result onMousePressed(final int mouseButton) {
        if (mouseButton != 0) return Result.ACCEPT;
        final int mx = mouseX(), my = mouseY();
        if (in(closeRect(), mx, my)) {
            close();
            return Result.SUCCESS;
        }
        if (sent != null) {
            if (in(sentKey(0), mx, my)) {
                Hyb.click();
                browse(sent.url());
            } else if (in(sentKey(1), mx, my)) {
                Hyb.click();
                GuiScreen.setClipboardString(sent.url());
            } else if (in(sentKey(2), mx, my)) close();
            return Result.SUCCESS;
        }
        if (sending) return Result.SUCCESS;
        if (in(sendRect(), mx, my)) {
            Hyb.click();
            send();
            return Result.SUCCESS;
        }
        if (kind == Reports.Kind.HELP && in(discordRect(), mx, my)) {
            Hyb.click();
            browse(DISCORD_URL);
            return Result.SUCCESS;
        }
        for (int i = 0; i < 3; i++) if (in(faceRect(i), mx, my)) {
            mood = mood != null && mood.ordinal() == i ? null : Reports.Mood.values()[i];
            (mood != null ? Sfx.TOGGLE_ON : Sfx.TOGGLE_OFF).play();
            return Result.SUCCESS;
        }
        for (int i = 0; i < KINDS.length; i++) if (in(kindRect(i), mx, my)) {
            if (kind.ordinal() != i) {
                kind = Reports.Kind.values()[i];
                Sfx.TICK.play(1f + 0.1f * i);
            }
            return Result.SUCCESS;
        }
        return Result.ACCEPT;
    }

    private void close() {
        if (getPanel() != null) getPanel().closeIfOpen();
        Sfx.CLOSE.play();
    }

    /** The issue's title: the first line written, cut at a word; a crash's own when nothing is written. */
    private String title() {
        final String text = words.text()
            .trim();
        String first = text.isEmpty() ? "" : text.split("\n", 2)[0].trim();
        if (first.isEmpty() && crash != null) first = "The game crashed: " + crash.summary();
        if (first.length() > 100) {
            final int cut = first.lastIndexOf(' ', 97);
            first = first.substring(0, cut > 40 ? cut : 97) + "...";
        }
        return first;
    }

    private void send() {
        if (title().isEmpty()) {
            error = "Write a line about it first";
            return;
        }
        error = null;
        sending = true;
        words.lock();
        if (plan() != null) {
            PlanPicture.take(
                canvas,
                new PlanPicture.Options(PlanPicture.Detail.SIMPLE, false, false),
                this::sendWith,
                why -> sendWith(null));
        } else sendWith(null);
    }

    private void sendWith(@Nullable final BufferedImage image) {
        final Graph g = plan();
        final Reports.Report report = new Reports.Report(
            kind,
            mood,
            title(),
            words.text(),
            g == null ? List.of() : List.of(g),
            id -> {
                final com.gtnhplanner.ui.card.CardModel m = session.model(id);
                return m == null ? null : m.machines;
            },
            image,
            true,
            crash == null ? null : crash.text());
        Reports.send(report, issue -> {
            sending = false;
            sent = issue;
            words.setEnabled(false);
            if (crash != null) CrashReports.dealtWith();
            Sfx.NOTE_STICK.play();
        }, why -> {
            sending = false;
            words.unlock();
            error = "Couldn't send it: " + why;
        });
    }

    static void browse(final String url) {
        try {
            java.awt.Desktop.getDesktop()
                .browse(java.net.URI.create(url));
        } catch (final Exception | LinkageError e) {
            org.lwjgl.Sys.openURL(url);
        }
    }

    // endregion
}

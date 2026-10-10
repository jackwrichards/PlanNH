package com.gtnhplanner.ui.popup;

import java.awt.image.BufferedImage;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.ResourceLocation;

import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.widget.Widget;
import com.gtnhplanner.GtnhPlanner;
import com.gtnhplanner.data.flowchart.balancer.Severity;
import com.gtnhplanner.ui.BoardSession;
import com.gtnhplanner.ui.canvas.BoardCanvas;
import com.gtnhplanner.ui.canvas.PlanPicture;
import com.gtnhplanner.ui.card.CardPaint;
import com.gtnhplanner.ui.sound.Sfx;
import com.gtnhplanner.ui.theme.Hyb;

/**
 * The Share key's Screenshot: a picture of the open plan, shown as it will come out. On the left the picture; on the
 * right how much the cards show (detailed, or simple: the zoomed-out look), whether a footer gives the plan's name and
 * what goes in and out, its size, and Copy and Save (the screenshots folder). By default it is the plan and nothing
 * else, with the small GTNH Planner mark every picture carries. A box in the middle of the screen, in the settings'
 * look.
 */
public final class ScreenshotPanel extends Widget<ScreenshotPanel> implements Interactable {

    private static final int TITLE = 22, PAD = 8, ROW = 22, CLOSE = 14;
    /** Room left round the box on screen, the right column's width, and the largest the box gets. */
    private static final int MARGIN = 20, COL_W = 160, MAX_W = 1200, MAX_H = 720;
    private static final int SWITCH_W = 22, SWITCH_H = 12, CHOICE_H = 16, KEY_H = 18, SAVE_W = 58, COPY_W = 46;

    /**
     * The box as big as the screen allows, nearly all of it the picture's well; the right column (the choices, the
     * size, the keys) from {@code col} to {@code right}.
     */
    private final int boxW, boxH, wellW, wellH, col, right;

    private static final int WELL = 0xFF0E0F11, CONTROL = 0xFF2A2C31, CONTROL_HOVER = 0xFF33363C,
        CONTROL_PICKED = 0xFF4A4D55, TRACK = 0xFF3A3C42, SWITCH_ON = 0xFF3E8F72, CYAN = 0xFF22D3EE;

    private static final String[] DETAILS = { "Detailed", "Simple" };

    /** What was chosen last, kept while the game runs. */
    private static PlanPicture.Options chosen = new PlanPicture.Options(PlanPicture.Detail.DETAILED, false, false);

    /** The preview: one texture, kept between openings and replaced as pictures come in. */
    private static final ResourceLocation PREVIEW = new ResourceLocation(GtnhPlanner.MODID, "plan_picture_preview");
    @Nullable
    private static DynamicTexture previewTexture;

    private final BoardSession session;
    private final BoardCanvas canvas;
    /** What the picture shows, and what is being taken now. */
    @Nullable
    private PlanPicture.Options taken, taking;
    @Nullable
    private BufferedImage picture;
    /** The preview's size in GUI units, once this box has one. */
    private float previewW, previewH;
    /** Why there is no picture; what Save or Copy did (good in green, else amber). */
    @Nullable
    private String problem, status;
    private boolean statusGood, keeping;

    private ScreenshotPanel(final BoardSession session, final BoardCanvas canvas) {
        this.session = session;
        this.canvas = canvas;
        final ScaledResolution sr = resolution();
        boxW = Math.min(MAX_W, sr.getScaledWidth() - 2 * MARGIN);
        boxH = Math.min(MAX_H, sr.getScaledHeight() - 2 * MARGIN);
        wellW = boxW - 2 * PAD - 10 - COL_W;
        wellH = boxH - TITLE - 2 * PAD;
        col = PAD + wellW + 10;
        right = boxW - PAD;
        size(boxW, boxH);
    }

    /** Opens the box in the middle of the screen, the picture on its way. */
    public static void open(final ModularPanel parent, final BoardSession session, final BoardCanvas canvas) {
        final ScreenshotPanel shot = new ScreenshotPanel(session, canvas);
        final int w = shot.boxW, h = shot.boxH;
        final Popup popup = new Popup("gtnhplanner_screenshot", w, h) {

            @Override
            public void drawBackground(final ModularGuiContext context, final WidgetThemeEntry<?> widgetTheme) {
                final ScaledResolution sr = resolution();
                Hyb.rect(-getArea().x, -getArea().y, sr.getScaledWidth(), sr.getScaledHeight(), 0x60000000);
                Hyb.dropShadow(0, 0, w, h);
                CardPaint.surface(w, h);
            }
        };
        popup.child(shot.pos(0, 0));
        final ScaledResolution sr = resolution();
        Popup.open(parent, popup, (sr.getScaledWidth() - w) / 2, (sr.getScaledHeight() - h) / 2);
    }

    private static ScaledResolution resolution() {
        final Minecraft mc = Minecraft.getMinecraft();
        return new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
    }

    // region The picture

    @Override
    public void onUpdate() {
        super.onUpdate();
        // A new choice takes the picture again, once the one on its way is in.
        if (taking == null && !chosen.equals(taken)) take();
    }

    private void take() {
        final PlanPicture.Options o = chosen;
        taking = o;
        PlanPicture.take(canvas, o, image -> {
            final int scale = resolution().getScaleFactor();
            PlanPicture.preview(image, (wellW - 8) * scale, (wellH - 8) * scale, small -> {
                show(small, scale);
                picture = image;
                taken = o;
                taking = null;
                problem = null;
            });
        }, why -> {
            picture = null;
            taken = o;
            taking = null;
            problem = why;
        });
    }

    /** The preview, a pixel of it to a pixel of the screen. */
    private void show(final BufferedImage small, final int scale) {
        if (previewTexture != null) previewTexture.deleteGlTexture();
        previewTexture = new DynamicTexture(small);
        Minecraft.getMinecraft()
            .getTextureManager()
            .loadTexture(PREVIEW, previewTexture);
        previewW = small.getWidth() / (float) scale;
        previewH = small.getHeight() / (float) scale;
    }

    private boolean ready() {
        return picture != null && taking == null && !keeping;
    }

    private void save() {
        if (!ready()) return;
        keeping = true;
        status = null;
        PlanPicture.save(
            picture,
            session.graph()
                .getName(),
            file -> {
                keeping = false;
                said("Saved as " + file.getName(), true);
                session.flash(Severity.INFO, "Saved the picture in screenshots: " + file.getName());
            },
            why -> {
                keeping = false;
                said("Couldn't save it: " + why, false);
            });
    }

    private void copy() {
        if (!ready()) return;
        keeping = true;
        status = null;
        PlanPicture.copy(picture, () -> {
            keeping = false;
            said("Copied: paste it anywhere", true);
        }, why -> {
            keeping = false;
            said("Couldn't copy it: " + why, false);
        });
    }

    private void said(final String text, final boolean good) {
        status = text;
        statusGood = good;
    }

    // endregion

    // region Where things are (widget-local)

    private static int rowY(final int i) {
        return TITLE + PAD + i * ROW;
    }

    private int[] closeRect() {
        return new int[] { boxW - 4 - CLOSE, (TITLE - CLOSE) / 2, CLOSE, CLOSE };
    }

    private int[] switchRect(final int i) {
        return new int[] { right - SWITCH_W, rowY(i) + (ROW - SWITCH_H) / 2, SWITCH_W, SWITCH_H };
    }

    private static int choiceWidth(final String name) {
        return Hyb.width(name) + 12;
    }

    /** One of the detail's choices. */
    private int[] detailRect(final int i) {
        int w = 1;
        for (final String n : DETAILS) w += choiceWidth(n) + 1;
        int x = right - w + 1;
        for (int k = 0; k < i; k++) x += choiceWidth(DETAILS[k]) + 1;
        return new int[] { x, rowY(0) + (ROW - CHOICE_H) / 2 + 1, choiceWidth(DETAILS[i]), CHOICE_H - 2 };
    }

    private int[] saveRect() {
        return new int[] { right - SAVE_W, TITLE + PAD + wellH - KEY_H, SAVE_W, KEY_H };
    }

    private int[] copyRect() {
        return new int[] { right - SAVE_W - 6 - COPY_W, TITLE + PAD + wellH - KEY_H, COPY_W, KEY_H };
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

        Hyb.text("Screenshot", 10, (TITLE - 8) / 2f + 1, Hyb.INK);
        final int[] close = closeRect();
        final boolean closeHot = in(close, mx, my);
        if (closeHot) Hyb.rect(close[0], close[1], close[2], close[3], CONTROL_HOVER);
        cross(close[0] + close[2] / 2f, close[1] + close[3] / 2f, closeHot ? Hyb.INK : Hyb.MUTED);
        Hyb.rect(1, TITLE, boxW - 2, 1, CardPaint.HAIR);

        drawPreview();

        // The choices.
        final PlanPicture.Options o = chosen;
        label(0, "Cards");
        Hyb.rect(detailRect(0)[0] - 1, detailRect(0)[1] - 1, right - detailRect(0)[0] + 1, CHOICE_H, CardPaint.EDGE);
        for (int i = 0; i < DETAILS.length; i++) {
            final int[] r = detailRect(i);
            final boolean picked = o.detail()
                .ordinal() == i, hot = in(r, mx, my);
            Hyb.rect(r[0], r[1], r[2], r[3], picked ? CONTROL_PICKED : hot ? CONTROL_HOVER : CONTROL);
            Hyb.textCentered(
                DETAILS[i],
                r[0] + r[2] / 2f,
                r[1] + (r[3] - 8) / 2f + 1,
                picked || hot ? Hyb.INK : Hyb.MUTED);
        }
        drawSwitch(1, "Plan name", o.name(), mx, my);
        drawSwitch(2, "Inputs and outputs", o.flows(), mx, my);

        // How big it comes out, and what Save or Copy did.
        final int infoY = rowY(3) + 6;
        Hyb.rect(col, rowY(3), right - col, 1, CardPaint.HAIR);
        if (picture != null && taking == null)
            Hyb.text(String.format("%,d x %,d pixels", picture.getWidth(), picture.getHeight()), col, infoY, Hyb.MUTED);
        if (status != null)
            Hyb.text(Hyb.fit(status, right - col), col, infoY + 12, statusGood ? Hyb.PRODUCT_INK : Hyb.AMBER_INK);

        // Copy, and Save in cyan.
        final boolean on = ready();
        final int[] copy = copyRect(), save = saveRect();
        final boolean copyHot = on && in(copy, mx, my), saveHot = on && in(save, mx, my);
        Hyb.bevel(copy[0], copy[1], copy[2], copy[3], copyHot ? Hyb.KEY_HOVER : Hyb.KEY, Hyb.KEY_HI, Hyb.KEY_LO, 0, 1);
        Hyb.textCentered("Copy", copy[0] + copy[2] / 2f, copy[1] + 5, on ? Hyb.INK : 0xFF5A5C65);
        Hyb.rect(save[0], save[1], save[2], save[3], !on ? 0xFF1E6B78 : saveHot ? 0xFF38E1F5 : CYAN);
        Hyb.rect(save[0], save[1], save[2], 1, 0x66FFFFFF);
        final String saveLabel = keeping ? "..." : "Save";
        com.cleanroommc.modularui.drawable.GuiDraw
            .drawText(saveLabel, save[0] + (save[2] - Hyb.width(saveLabel)) / 2f, save[1] + 5, 1f, 0xFF0B1A1E, false);
    }

    /** The picture in its well, fitted and centred; a word while it is taken, or why there is none. */
    private void drawPreview() {
        final int x = PAD, y = TITLE + PAD;
        Hyb.rect(x, y, wellW, wellH, CardPaint.EDGE);
        Hyb.rect(x + 1, y + 1, wellW - 2, wellH - 2, WELL);
        final float cx = x + wellW / 2f, cy = y + wellH / 2f;
        if (previewTexture != null && previewW > 0 && problem == null) {
            // As big as the well allows: a small picture is drawn larger, so it can be seen.
            final float f = Math.min((wellW - 8) / previewW, (wellH - 8) / previewH);
            final float pw = previewW * f, ph = previewH * f;
            Hyb.texture(PREVIEW, cx - pw / 2f, cy - ph / 2f, pw, ph);
            if (taking != null) Hyb.rect(x + 1, y + 1, wellW - 2, wellH - 2, 0x99000000);
        }
        if (taking != null) Hyb.textCentered("Taking the picture...", cx, cy - 4, Hyb.INK);
        else if (problem != null) {
            Hyb.textCentered("No picture", cx, cy - 10, Hyb.AMBER_INK);
            Hyb.textCentered(Hyb.fit(problem, wellW - 12), cx, cy + 2, Hyb.MUTED);
        }
    }

    private void label(final int i, final String text) {
        Hyb.text(text, col, rowY(i) + (ROW - 8) / 2f + 1, Hyb.INK);
    }

    private void drawSwitch(final int i, final String text, final boolean on, final int mx, final int my) {
        label(i, text);
        final boolean hot = mx >= col && mx < right && my >= rowY(i) && my < rowY(i) + ROW;
        final int[] c = switchRect(i);
        pill(c[0], c[1], c[2], c[3], on ? SWITCH_ON : hot ? 0xFF44464D : TRACK);
        final int k = c[3] - 4;
        pill(on ? c[0] + c[2] - 2 - k : c[0] + 2, c[1] + 2, k, k, on ? Hyb.INK : 0xFF9A9CA4);
    }

    /** A box with its corners off, a pixel's rounding. */
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
        if (mouseButton != 0) return Result.SUCCESS;
        final int mx = mouseX(), my = mouseY();
        final PlanPicture.Options o = chosen;
        if (in(closeRect(), mx, my)) {
            if (getPanel() != null) getPanel().closeIfOpen();
            Sfx.CLOSE.play();
        } else if (in(saveRect(), mx, my) && ready()) {
            Hyb.click();
            save();
        } else if (in(copyRect(), mx, my) && ready()) {
            Hyb.click();
            copy();
        } else if (mx >= col && mx < right && my >= rowY(0) && my < rowY(3)) {
            final int row = (my - rowY(0)) / ROW;
            if (row == 0) {
                for (int i = 0; i < DETAILS.length; i++) if (in(detailRect(i), mx, my) && i != o.detail()
                    .ordinal()) {
                        chosen = new PlanPicture.Options(PlanPicture.Detail.values()[i], o.name(), o.flows());
                        Sfx.TICK.play(i > 0 ? 1.12f : 0.9f);
                    }
            } else if (row == 1) {
                chosen = new PlanPicture.Options(o.detail(), !o.name(), o.flows());
                (chosen.name() ? Sfx.TOGGLE_ON : Sfx.TOGGLE_OFF).play();
            } else {
                chosen = new PlanPicture.Options(o.detail(), o.name(), !o.flows());
                (chosen.flows() ? Sfx.TOGGLE_ON : Sfx.TOGGLE_OFF).play();
            }
            status = null;
        }
        return Result.SUCCESS;
    }

    // endregion
}

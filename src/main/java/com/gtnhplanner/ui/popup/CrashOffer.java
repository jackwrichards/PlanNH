package com.gtnhplanner.ui.popup;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;

import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.widget.Widget;
import com.gtnhplanner.client.CrashReports;
import com.gtnhplanner.ui.BoardSession;
import com.gtnhplanner.ui.canvas.BoardCanvas;
import com.gtnhplanner.ui.card.CardPaint;
import com.gtnhplanner.ui.sound.Sfx;
import com.gtnhplanner.ui.theme.Hyb;

/**
 * After a crash the planner had a hand in, the first time the board opens: what happened in a line, and whether to
 * send the crash report (the Feedback box, filled in) or let it be. Either answer puts that crash away.
 */
public final class CrashOffer extends Widget<CrashOffer> implements Interactable {

    private static final int W = 300, H = 104, PAD = 10, KEY_H = 18;

    private final ModularPanel board;
    private final BoardSession session;
    private final BoardCanvas canvas;
    private final CrashReports.Crash crash;

    private CrashOffer(final ModularPanel board, final BoardSession session, final BoardCanvas canvas,
        final CrashReports.Crash crash) {
        this.board = board;
        this.session = session;
        this.canvas = canvas;
        this.crash = crash;
        size(W, H);
    }

    /** Offers the waiting crash, if there is one. */
    public static void offer(final ModularPanel board, final BoardSession session, final BoardCanvas canvas) {
        final CrashReports.Crash crash = CrashReports.pending();
        if (crash == null) return;
        final Popup popup = new Popup("gtnhplanner_crash_offer", W, H) {

            @Override
            public void drawBackground(final ModularGuiContext context, final WidgetThemeEntry<?> widgetTheme) {
                Hyb.dropShadow(0, 0, W, H);
                CardPaint.surface(W, H);
                Hyb.rect(0, 0, 2, H, Hyb.AMBER_INK);
            }

            @Override
            public boolean closeOnOutOfBoundsClick() {
                return false;
            }
        };
        popup.child(new CrashOffer(board, session, canvas, crash).pos(0, 0));
        final Minecraft mc = Minecraft.getMinecraft();
        final ScaledResolution sr = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
        Popup.open(board, popup, (sr.getScaledWidth() - W) / 2, (sr.getScaledHeight() - H) / 3);
    }

    private int[] sendRect() {
        final int w = Hyb.width("Send a report") + 20;
        return new int[] { W - PAD - w, H - PAD - KEY_H, w, KEY_H };
    }

    private int[] laterRect() {
        final int w = Hyb.width("Not now") + 16;
        return new int[] { sendRect()[0] - 6 - w, H - PAD - KEY_H, w, KEY_H };
    }

    private static boolean in(final int[] r, final int x, final int y) {
        return x >= r[0] && x < r[0] + r[2] && y >= r[1] && y < r[1] + r[3];
    }

    @Override
    public void draw(final ModularGuiContext context, final WidgetThemeEntry<?> widgetTheme) {
        final int mx = isHovering() ? getContext().getAbsMouseX() - getArea().x : -1,
            my = isHovering() ? getContext().getAbsMouseY() - getArea().y : -1;
        Hyb.text("The game crashed last time", PAD, PAD, Hyb.INK);
        Hyb.text("GTNH Planner was part of it:", PAD, PAD + 14, Hyb.MUTED);
        Hyb.text(Hyb.fit(crash.summary(), W - 2 * PAD), PAD, PAD + 26, Hyb.AMBER_INK);
        Hyb.text(Hyb.fit("Send the crash report so it can be fixed?", W - 2 * PAD), PAD, PAD + 42, Hyb.INK);
        final int[] later = laterRect(), send = sendRect();
        final boolean laterHot = in(later, mx, my), sendHot = in(send, mx, my);
        Hyb.bevel(
            later[0],
            later[1],
            later[2],
            later[3],
            laterHot ? Hyb.KEY_HOVER : Hyb.KEY,
            Hyb.KEY_HI,
            Hyb.KEY_LO,
            0,
            1);
        Hyb.textCentered("Not now", later[0] + later[2] / 2f, later[1] + 5, Hyb.INK);
        Hyb.rect(send[0], send[1], send[2], send[3], sendHot ? 0xFF38E1F5 : 0xFF22D3EE);
        Hyb.rect(send[0], send[1], send[2], 1, 0x66FFFFFF);
        com.cleanroommc.modularui.drawable.GuiDraw.drawText(
            "Send a report",
            send[0] + (send[2] - Hyb.width("Send a report")) / 2f,
            send[1] + 5,
            1f,
            0xFF0B1A1E,
            false);
    }

    @Override
    public Result onMousePressed(final int mouseButton) {
        if (mouseButton != 0) return Result.ACCEPT;
        final int mx = getContext().getAbsMouseX() - getArea().x, my = getContext().getAbsMouseY() - getArea().y;
        if (in(sendRect(), mx, my)) {
            Hyb.click();
            if (getPanel() != null) getPanel().closeIfOpen();
            FeedbackPanel.open(board, session, canvas, crash);
        } else if (in(laterRect(), mx, my)) {
            CrashReports.dealtWith();
            if (getPanel() != null) getPanel().closeIfOpen();
            Sfx.CLOSE.play();
        }
        return Result.SUCCESS;
    }

    @Override
    public boolean canHover() {
        return true;
    }
}

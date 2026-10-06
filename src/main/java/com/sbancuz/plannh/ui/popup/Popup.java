package com.sbancuz.plannh.ui.popup;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;

import com.cleanroommc.modularui.api.IPanelHandler;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.sbancuz.plannh.ui.theme.Hyb;

/**
 * A floating panel in Factory Flow's menu style (bevelled #3c3e45 with a hard drop shadow) that closes on any click
 * outside it. Opened at a screen position, kept on screen.
 */
public class Popup extends ModularPanel {

    protected final int popupW, popupH;

    /**
     * ModularUI keeps one handler per panel name for the life of the screen and re-opens the old panel when a name
     * comes back, so every popup gets a name of its own.
     */
    private static int opened;

    public Popup(final String name, final int width, final int height) {
        super(name + "_" + ++opened);
        this.popupW = width;
        this.popupH = height;
        size(width, height);
    }

    /**
     * Opens {@code popup} over {@code parent} with its top-left at the given screen point, nudged onto the screen. It
     * opens on the next tick: opened inside a click handler, the click that opened it would reach it as a click
     * outside and close it again.
     */
    public static void open(final ModularPanel parent, final Popup popup, final int screenX, final int screenY) {
        final ScaledResolution sr = new ScaledResolution(
            Minecraft.getMinecraft(),
            Minecraft.getMinecraft().displayWidth,
            Minecraft.getMinecraft().displayHeight);
        final int w = popup.popupW, h = popup.popupH;
        final int x = Math.max(2, Math.min(screenX, sr.getScaledWidth() - w - 2));
        final int y = Math.max(2, Math.min(screenY, sr.getScaledHeight() - h - 2));
        popup.pos(x, y);
        PENDING.add(
            () -> {
                if (parent.isOpen()) IPanelHandler.simple(parent, (p, player) -> popup, true)
                    .openPanel();
            });
    }

    private static final List<Runnable> PENDING = new ArrayList<>();

    /** Opens the popups asked for since the last tick. The board calls this every tick. */
    public static void openPending() {
        if (PENDING.isEmpty()) return;
        final List<Runnable> now = new ArrayList<>(PENDING);
        PENDING.clear();
        now.forEach(Runnable::run);
    }

    @Override
    public boolean closeOnOutOfBoundsClick() {
        return true;
    }

    @Override
    public boolean isDraggable() {
        return false;
    }

    @Override
    public boolean shouldAnimate() {
        return false;
    }

    @Override
    public boolean disablePanelsBelow() {
        return false;
    }

    @Override
    public void drawBackground(final ModularGuiContext context, final WidgetThemeEntry<?> widgetTheme) {
        final int w = getArea().width, h = getArea().height;
        Hyb.rect(4, 4, w, h, 0x59000000);
        Hyb.rect(0, 0, w, h, Hyb.KEY_EDGE);
        Hyb.rect(1, 1, w - 2, h - 2, Hyb.MENU);
        Hyb.rect(1, 1, w - 2, 1, Hyb.HIGHLIGHT);
        Hyb.rect(1, 1, 1, h - 2, Hyb.HIGHLIGHT);
        Hyb.rect(1, h - 2, w - 2, 1, Hyb.SHADOW);
        Hyb.rect(w - 2, 1, 1, h - 2, Hyb.SHADOW);
    }
}

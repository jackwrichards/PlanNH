package com.gtnhplanner.ui.popup;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiControls;

import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.widget.Widget;
import com.gtnhplanner.ui.PlannerSettings;
import com.gtnhplanner.ui.theme.Hyb;

/**
 * The planner's settings (the top bar's gear): one row each, its name on the left and its value on the right. Click a
 * value for the next one, right-click for the one before; it saves at once and the popup stays open.
 */
public final class SettingsPanel extends Widget<SettingsPanel> implements Interactable {

    /** A heading ({@code value} null) or a setting: its name, what it is now, and how to step it (+1 or -1). */
    public record Row(String label, @Nullable Supplier<String> value, @Nullable IntConsumer step) {

        static Row heading(final String label) {
            return new Row(label, null, null);
        }
    }

    private static final int ROW = 16, W = 270;

    private final List<Row> rows;

    private SettingsPanel(final List<Row> rows) {
        this.rows = rows;
        size(W - 8, rows.size() * ROW);
    }

    /** Opens the settings at a screen point. */
    public static void open(final ModularPanel parent, final int x, final int y) {
        final SettingsPanel list = new SettingsPanel(rows());
        final Popup popup = new Popup("gtnhplanner_settings_panel", W, list.rows.size() * ROW + 8);
        popup.child(list.pos(4, 4));
        Popup.open(parent, popup, x, y);
    }

    private static String onOff(final boolean on) {
        return on ? "On" : "Off";
    }

    private static int cycle(final int i, final int step, final int n) {
        return ((i + step) % n + n) % n;
    }

    private static List<Row> rows() {
        final List<Row> rows = new ArrayList<>();
        rows.add(Row.heading("BOARD"));
        rows.add(
            new Row(
                "Names when zoomed out",
                () -> onOff(PlannerSettings.zoomedOutNames()),
                s -> PlannerSettings.setZoomedOutNames(!PlannerSettings.zoomedOutNames())));
        rows.add(
            new Row(
                "Circuit as an input",
                () -> onOff(PlannerSettings.circuitAsInput()),
                s -> PlannerSettings.setCircuitAsInput(!PlannerSettings.circuitAsInput())));
        rows.add(Row.heading("SOUND"));
        rows.add(
            new Row(
                "Sounds",
                () -> PlannerSettings.SOUND_NAMES[PlannerSettings.soundIndex()],
                s -> PlannerSettings.setSoundIndex(
                    Math.max(0, Math.min(PlannerSettings.SOUND_NAMES.length - 1, PlannerSettings.soundIndex() + s)))));
        rows.add(Row.heading("MINIMAP (WHILE PLAYING)"));
        rows.add(
            new Row(
                "Show the minimap",
                () -> onOff(PlannerSettings.minimap()),
                s -> PlannerSettings.setMinimap(!PlannerSettings.minimap())));
        rows.add(
            new Row(
                "Size",
                () -> PlannerSettings.MINIMAP_SIZE_NAMES[PlannerSettings.minimapSizeIndex()],
                s -> PlannerSettings.setMinimapSizeIndex(
                    cycle(PlannerSettings.minimapSizeIndex(), s, PlannerSettings.MINIMAP_SIZES.length))));
        rows.add(
            new Row(
                "Shape",
                () -> PlannerSettings.minimapCircle() ? "Circle" : "Square",
                s -> PlannerSettings.setMinimapCircle(!PlannerSettings.minimapCircle())));
        rows.add(
            new Row(
                "Position",
                () -> PlannerSettings.minimapCorner().label,
                s -> PlannerSettings.setMinimapCorner(
                    PlannerSettings.Corner.values()[cycle(
                        PlannerSettings.minimapCorner()
                            .ordinal(),
                        s,
                        PlannerSettings.Corner.values().length)])));
        rows.add(
            new Row(
                "Zoom",
                () -> Math.round(PlannerSettings.MINIMAP_ZOOMS[PlannerSettings.minimapZoomIndex()] * 100) + "%",
                s -> PlannerSettings.setMinimapZoomIndex(
                    Math.max(
                        0,
                        Math.min(PlannerSettings.MINIMAP_ZOOMS.length - 1, PlannerSettings.minimapZoomIndex() + s)))));
        rows.add(
            new Row(
                "Centre on the machine you look at",
                () -> onOff(PlannerSettings.minimapFollows()),
                s -> PlannerSettings.setMinimapFollows(!PlannerSettings.minimapFollows())));
        rows.add(Row.heading("IN THE WORLD"));
        rows.add(
            new Row(
                "Show the plan over the world (Shift+Y)",
                () -> onOff(PlannerSettings.arLens()),
                s -> PlannerSettings.setArLens(!PlannerSettings.arLens())));
        rows.add(
            new Row(
                "Cards only where you look (Shift+U)",
                () -> onOff(PlannerSettings.arFocus()),
                s -> PlannerSettings.setArFocus(!PlannerSettings.arFocus())));
        rows.add(
            new Row(
                "How far to show it",
                () -> PlannerSettings.arRange() + " blocks",
                s -> PlannerSettings
                    .setArRangeIndex(cycle(PlannerSettings.arRangeIndex(), s, PlannerSettings.AR_RANGES.length))));
        rows.add(
            new Row(
                "Outline a placed block you look at",
                () -> onOff(PlannerSettings.worldHighlight()),
                s -> PlannerSettings.setWorldHighlight(!PlannerSettings.worldHighlight())));
        rows.add(Row.heading("KEYS"));
        rows.add(new Row("Set them in Controls", () -> "Open", s -> {
            final Minecraft mc = Minecraft.getMinecraft();
            mc.displayGuiScreen(new GuiControls(mc.currentScreen, mc.gameSettings));
        }));
        return rows;
    }

    /** The GUI rectangle {x, y, w, h} of the first setting whose name contains {@code label}; null for none. */
    public int[] rowRect(final String label) {
        for (int i = 0; i < rows.size(); i++) {
            final Row r = rows.get(i);
            if (r.value() == null || !r.label()
                .contains(label)) continue;
            final com.cleanroommc.modularui.widget.sizer.Area a = getArea();
            return new int[] { a.x, a.y + i * ROW, a.width, ROW };
        }
        return null;
    }

    /** What a setting shows now, by its name; null for none. */
    public String value(final String label) {
        for (final Row r : rows) if (r.value() != null && r.label()
            .contains(label))
            return r.value()
                .get();
        return null;
    }

    private int rowAtMouse() {
        if (!isHovering()) return -1;
        final int y = getContext().getAbsMouseY() - getArea().y;
        final int i = y / ROW;
        return i >= 0 && i < rows.size()
            && rows.get(i)
                .value() != null ? i : -1;
    }

    @Override
    public boolean canHover() {
        return true;
    }

    @Override
    public void draw(final ModularGuiContext context, final WidgetThemeEntry<?> widgetTheme) {
        final int w = getArea().width, hot = rowAtMouse();
        for (int i = 0; i < rows.size(); i++) {
            final Row row = rows.get(i);
            final int y = i * ROW;
            if (row.value() == null) {
                Hyb.text(row.label(), 4, y + 6, Hyb.MUTED);
                continue;
            }
            if (i == hot) Hyb.rect(0, y, w, ROW, Hyb.MENU_HOVER);
            Hyb.text(row.label(), 8, y + 4, Hyb.INK);
            final String value = row.value()
                .get();
            final int vw = Hyb.width(value) + 12;
            Hyb.bevel(w - vw - 2, y + 2, vw, ROW - 4, Hyb.KEY, Hyb.KEY_HI, Hyb.KEY_LO, 0, 1);
            Hyb.textCentered(value, w - vw / 2f - 1.5f, y + 4, i == hot ? 0xFFFFFFFF : Hyb.INK);
        }
    }

    @Override
    public Result onMousePressed(final int mouseButton) {
        final int i = rowAtMouse();
        if (i < 0 || mouseButton > 1) return Result.IGNORE;
        final Row row = rows.get(i);
        row.step()
            .accept(mouseButton == 1 ? -1 : 1);
        // A switch sounds as it lands (and the Sounds row at its new loudness).
        final String now = row.value() == null ? ""
            : row.value()
                .get();
        if ("On".equals(now)) com.gtnhplanner.ui.sound.Sfx.TOGGLE_ON.play();
        else if ("Off".equals(now)) com.gtnhplanner.ui.sound.Sfx.TOGGLE_OFF.play();
        else Hyb.click();
        return Result.SUCCESS;
    }
}

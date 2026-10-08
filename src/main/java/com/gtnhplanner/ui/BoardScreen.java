package com.gtnhplanner.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;

import com.cleanroommc.modularui.api.drawable.IDrawable;
import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.ModularScreen;
import com.cleanroommc.modularui.screen.UISettings;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.layout.Flow;
import com.gtnhplanner.GtnhPlanner;
import com.gtnhplanner.nei.NEIPlanConfig;
import com.gtnhplanner.ui.canvas.BoardCanvas;
import com.gtnhplanner.ui.card.CardModel;
import com.gtnhplanner.ui.card.PortSlot;
import com.gtnhplanner.ui.card.RecipeCard;
import com.gtnhplanner.ui.drawer.DrawerCard;
import com.gtnhplanner.ui.library.LibraryView;
import com.gtnhplanner.ui.popup.Popup;
import com.gtnhplanner.ui.popup.Tip;
import com.gtnhplanner.ui.power.PowerPicker;
import com.gtnhplanner.ui.theme.Fmt;
import com.gtnhplanner.ui.theme.Hyb;

import codechicken.nei.LayoutManager;
import codechicken.nei.NEIClientConfig;
import codechicken.nei.guihook.GuiContainerManager;

/**
 * The planner: a slim top bar over the board, with NEI's item list kept on the right. Solve mode only.
 */
public final class BoardScreen extends ModularScreen {

    private static final int TOP_BAR = 20;

    private final BoardSession session;
    private final BoardCanvas canvas;
    private final LibraryDoor library;
    private final PowerPicker picker;

    private BoardScreen(final ModularPanel panel, final BoardSession session, final BoardCanvas canvas,
        final LibraryDoor library, final PowerPicker picker) {
        super(GtnhPlanner.MODID, panel);
        this.session = session;
        this.canvas = canvas;
        this.library = library;
        this.picker = picker;
        getContext().setSettings(new UISettings());
        getContext().getUISettings()
            .getRecipeViewerSettings()
            .enable();
    }

    public static BoardScreen create() {
        final BoardSession session = new BoardSession();
        final ModularPanel panel = ModularPanel.defaultPanel("gtnhplanner_board")
            .fullScreenInvisible()
            .left(0)
            // Clear of NEI's search box along the bottom.
            .marginBottom(22)
            .widthRelOffset(
                () -> (double) Math.max(120, LayoutManager.itemPanel.x - 4)
                    / Minecraft.getMinecraft().currentScreen.width,
                0);
        final BoardCanvas canvas = new BoardCanvas(session, panel);
        final OverviewRail rail = new OverviewRail(session, canvas);
        final LibraryDoor library = new LibraryDoor();
        library.view = new LibraryView(session, () -> library.set(false), () -> {
            library.set(false);
            canvas.frameAllWhenBuilt();
        });
        session.setLibraryOpener(() -> {
            library.view.showPublic();
            library.set(true);
        });
        session.setMyPlansOpener(() -> {
            library.view.showMine();
            library.set(true);
        });
        session.setLibrarySearch((key, label) -> {
            library.view.showMaking(key, label);
            library.set(true);
        });

        final Flow topBar = Flow.row()
            .widthRel(1f)
            .height(TOP_BAR)
            .padding(4, 2)
            .childPadding(3)
            .background((IDrawable) (ctx, x, y, w, h, theme) -> {
                Hyb.rect(x, y, w, h, 0xFF0E0F12);
                Hyb.rect(x, y + h - 1, w, 1, 0xFF2A2C31);
            });
        // The tabs take all the room the keys leave; the keys come in groups, a gap between each: editing, the view,
        // the library, then help.
        topBar.child(
            new PlanTabs(session).expanded()
                .height(16));
        // Over the board (and the library): the non-recipe machines, opened from their key.
        final PickerDoor pickerDoor = new PickerDoor();
        final PowerPicker picker = new PowerPicker(session, () -> pickerDoor.picker.setEnabled(false));
        pickerDoor.picker = picker;
        picker.setEnabled(false);
        final Runnable openPicker = () -> {
            library.set(false);
            picker.opened();
            picker.setEnabled(true);
        };
        session.setPowerPickerOpener(openPicker);
        topBar.child(boltKey("Non-recipe machines: generators, turbines, boilers, reactors", () -> {
            // The key opens the picker and closes it again.
            if (picker.isEnabled()) picker.setEnabled(false);
            else openPicker.run();
        }).marginLeft(GROUP_GAP));
        topBar.child(iconKey(Arrow.UNDO, session::canUndo, "Undo (Ctrl+Z)", session::undo).marginLeft(GROUP_GAP));
        topBar.child(iconKey(Arrow.REDO, session::canRedo, "Redo (Ctrl+Shift+Z or Ctrl+Y)", session::redo));
        topBar.child(
            key(() -> "Arrange", () -> true, "Auto-arrange the plan", fit("Arrange"), canvas::arrange)
                .marginLeft(GROUP_GAP));
        topBar.child(key(() -> "Fit", () -> true, "Zoom to fit the whole plan", fit("Fit"), canvas::frameAll));
        topBar.child(
            key(
                () -> session.rateUnit().suffix,
                () -> true,
                "Rate unit: per tick, second, minute or hour",
                fit("/t", "/s", "/min", "/hr"),
                () -> session.setRateUnit(
                    session.rateUnit()
                        .next())).marginLeft(GROUP_GAP));
        topBar.child(
            key(
                () -> session.powerKey() == BoardSession.PowerKey.EU ? "EU/t" : "Amps",
                () -> true,
                "Show power as EU/t, or as amps at each machine's tier",
                fit("EU/t", "Amps"),
                session::togglePowerKey));
        topBar.child(
            key(
                () -> session.peakPower() ? "Peak" : "Avg",
                () -> true,
                "Average or peak power use (peak: every machine running at once)",
                fit("Peak", "Avg"),
                session::togglePeakPower));
        topBar.child(
            key(
                () -> library.open ? "Board" : "Library",
                () -> true,
                "Library: your plans, and public plans from gtnhplanner.com",
                fit("Library", "Board"),
                () -> library.set(!library.open)).marginLeft(GROUP_GAP));
        topBar.child(
            iconKey(
                Arrow.FEEDBACK,
                () -> true,
                "Feedback and bug reports: a thread on the GT New Horizons Discord (join the server to see it)",
                BoardScreen::openFeedback).marginLeft(GROUP_GAP));
        topBar.child(key(() -> "?", () -> true, "Help: controls and shortcuts", 16, () -> showHelp(panel)));

        final Flow column = Flow.column()
            .widthRel(1f)
            .heightRel(1f);
        column.child(topBar);
        // The overview rail is part of the layout, left of the board, and folds away to give the board its width.
        final Flow body = Flow.row()
            .widthRel(1f)
            .expanded()
            .collapseDisabledChild();
        body.child(rail.heightRel(1f));
        body.child(
            canvas.heightRel(1f)
                .expanded());
        library.view.heightRel(1f)
            .expanded()
            .setEnabled(false);
        body.child(library.view);
        column.child(body);
        panel.child(column);
        final NoticeBar notices = new NoticeBar(session, canvas)
            .left(() -> rail.currentWidth() + 6, com.cleanroommc.modularui.widget.sizer.Unit.Measure.PIXEL)
            .right(6)
            .top(TOP_BAR + 4);
        panel.child(notices);
        final SelectionBar selectionBar = new SelectionBar(session, canvas, notices);
        panel.child(selectionBar);
        picker.left(0)
            .right(0)
            .top(TOP_BAR)
            .bottom(0);
        panel.child(picker);
        library.body = body;
        library.board = List.of(rail, canvas, notices, selectionBar);
        return new BoardScreen(panel, session, canvas, library, picker);
    }

    /** Swaps the board (the overview, the canvas and the bars over it) for the library, and back. */
    private static final class LibraryDoor {

        LibraryView view;
        Flow body;
        List<com.cleanroommc.modularui.widget.Widget<?>> board = List.of();
        boolean open;

        void set(final boolean open) {
            if (open == this.open) return;
            this.open = open;
            view.setEnabled(open);
            for (final com.cleanroommc.modularui.widget.Widget<?> w : board) w.setEnabled(!open);
            if (open) view.opened();
            body.scheduleResize();
        }
    }

    /** Every gesture on the board, in one list. */
    private static void showHelp(final ModularPanel panel) {
        final List<com.gtnhplanner.ui.popup.PickList.Entry> rows = new ArrayList<>();
        final String[][] tips = { { "R or U over an item", "Show its recipes or uses in NEI; + adds a recipe" },
            { "Drag an item out of NEI", "Click the board to add a drawer for it" },
            { "Click an item in NEI's list", "Its recipes (left-click) or uses (right-click)" },
            { "Click a port", "Its recipes (left) or uses (right) in NEI; adding one wires it to the port" },
            { "Drag a port", "Drop on a card to wire it, or on empty board to add a drawer" },
            { "Right-click a wire", "Add a drawer on it, or delete it" }, { "Middle-click a rate", "Clear the rate" },
            { "Click a card or drawer", "Select it (Shift-click adds to the selection)" },
            { "Drag the board", "Pan (Shift-drag selects a box)" }, { "Drag a selected card", "Move the selection" },
            { "Ctrl+A", "Select everything" }, { "Delete", "Delete the selection (Esc deselects)" },
            { "Wheel", "Zoom; over a value, change it" }, { "WASD or arrows", "Pan (+/- or Page Up/Down zoom)" },
            { "Ctrl+C, Ctrl+X, Ctrl+V", "Copy, cut, paste the selection and its wires" },
            { "Ctrl+Z, Ctrl+Shift+Z", "Undo, redo" },
            { "Double-click an overview row", "Show the cards that make or use it" } };
        for (final String[] tip : tips)
            rows.add(new com.gtnhplanner.ui.popup.PickList.Entry(null, tip[0], tip[1], Hyb.INK, false, () -> {}));
        com.cleanroommc.modularui.screen.ModularPanel p = com.gtnhplanner.ui.popup.PickList
            .popup("gtnhplanner_help", "HOW THE BOARD WORKS", rows, false, 300, rows.size());
        com.gtnhplanner.ui.popup.Popup.open(panel, (com.gtnhplanner.ui.popup.Popup) p, 300, 24);
    }

    /** The top-bar keys' tooltips, drawn as board tips (ModularUI's own would be the game's purple ones). */
    private static final java.util.Map<IWidget, String> KEY_TIPS = new java.util.WeakHashMap<>();

    /** Where bugs and development talk go: GTNH Planner's thread on the GT New Horizons Discord. */
    private static final String FEEDBACK_URL = "https://discord.com/channels/181078474394566657/1531402304530682036";

    /** Opens the feedback thread in the browser, as vanilla opens chat links. */
    private static void openFeedback() {
        try {
            java.awt.Desktop.getDesktop()
                .browse(java.net.URI.create(FEEDBACK_URL));
        } catch (final Exception | LinkageError e) {
            org.lwjgl.Sys.openURL(FEEDBACK_URL);
        }
    }

    /** A top-bar key in the card's key style: a label, a tooltip, greyed when it can do nothing. */
    private static ButtonWidget<?> key(final Supplier<String> label, final BooleanSupplier enabled,
        final String tooltip, final int width, final Runnable action) {
        final ButtonWidget<?> button = new ButtonWidget<>();
        button.size(width, 16)
            .background(
                (IDrawable) (ctx, x, y, w, h, theme) -> {
                    Hyb.bevel(x, y, w, h, Hyb.KEY, Hyb.KEY_HI, Hyb.KEY_LO, 0, 1);
                })
            .hoverBackground(
                (IDrawable) (ctx, x, y, w, h, theme) -> {
                    Hyb.bevel(x, y, w, h, Hyb.KEY_HOVER, Hyb.KEY_HI, Hyb.KEY_LO, 0, 1);
                })
            .overlay(
                (IDrawable) (ctx, x, y, w, h, theme) -> Hyb.textCentered(
                    label.get(),
                    x + w / 2f,
                    y + (h - 8) / 2f,
                    enabled.getAsBoolean() ? Hyb.INK : 0xFF5A5C65))
            .onMousePressed(b -> {
                // True makes ModularUI play its click: only when the key did something.
                if (b != 0 || !enabled.getAsBoolean()) return false;
                action.run();
                return true;
            });
        KEY_TIPS.put(button, tooltip);
        return button;
    }

    /** The picker, reachable from its own close callback (made before it). */
    private static final class PickerDoor {

        PowerPicker picker;
    }

    /** The non-recipe machines key: the power wing's amber bolt, then its name. */
    private static ButtonWidget<?> boltKey(final String tooltip, final Runnable action) {
        final String label = "Non-recipe";
        final ButtonWidget<?> button = key(() -> "", () -> true, tooltip, Hyb.width(label) + 22, action);
        button.overlay((IDrawable) (ctx, x, y, w, h, theme) -> {
            PowerPicker.bolt(x + 6, y + (h - 11) / 2f, 0xFFFCD34D);
            Hyb.text(label, x + 16, y + (h - 8) / 2f, Hyb.INK);
        });
        return button;
    }

    /** Between groups of keys in the top bar. */
    private static final int GROUP_GAP = 6;

    /** A key's width for the widest label it shows: the same padding either side of every label. */
    private static int fit(final String... labels) {
        int w = 0;
        for (final String l : labels) w = Math.max(w, Hyb.width(l));
        return w + 12;
    }

    /** The pictures drawn on the small keys. */
    private enum Arrow {
        UNDO,
        REDO,
        FEEDBACK
    }

    /** Discord's blurple. */
    private static final int DISCORD = 0xFF5865F2;

    /** Discord's face, 11 by 8 in pixels: ears, two eyes, two feet. */
    private static void discord(final int x, final int y) {
        // {dx, dy, length} runs of the face; the eyes are left out of rows 3 and 4.
        final int[][] runs = { { 2, 0, 2 }, { 7, 0, 2 }, { 1, 1, 9 }, { 0, 2, 11 }, { 0, 3, 3 }, { 5, 3, 1 },
            { 8, 3, 3 }, { 0, 4, 3 }, { 5, 4, 1 }, { 8, 4, 3 }, { 0, 5, 11 }, { 1, 6, 3 }, { 7, 6, 3 }, { 2, 7, 2 },
            { 7, 7, 2 } };
        for (final int[] r : runs) Hyb.rect(x + r[0], y + r[1], r[2], 1, DISCORD);
    }

    /** A small key with an arrow drawn on it (the game's font has none), for undo and redo. */
    private static ButtonWidget<?> iconKey(final Arrow arrow, final BooleanSupplier enabled, final String tooltip,
        final Runnable action) {
        final ButtonWidget<?> button = key(() -> "", enabled, tooltip, 18, action);
        button.overlay(
            (IDrawable) (ctx, x, y, w, h, theme) -> drawArrow(
                arrow,
                x + (w - 9) / 2,
                y + (h - 7) / 2,
                enabled.getAsBoolean() ? Hyb.INK : 0xFF5A5C65));
        return button;
    }

    /**
     * 9 by 7: a hooked arrow, its head pointing left for undo and right for redo, its tail curling under; for feedback,
     * a speech bubble.
     */
    private static void drawArrow(final Arrow arrow, final int x, final int y, final int c) {
        if (arrow == Arrow.FEEDBACK) {
            discord(x - 1, y - 1);
            return;
        }
        final boolean undo = arrow == Arrow.UNDO;
        // Pixel runs {dx, dy, length} for undo; redo is the mirror.
        final int[][] runs = { { 2, 0, 1 }, { 1, 1, 2 }, { 0, 2, 7 }, { 1, 3, 2 }, { 7, 3, 1 }, { 2, 4, 1 },
            { 8, 4, 1 }, { 8, 5, 1 }, { 4, 6, 4 } };
        for (final int[] r : runs) {
            final int dx = undo ? r[0] : 9 - r[0] - r[2];
            Hyb.rect(x + dx, y + r[1], r[2], 1, c);
        }
    }

    public BoardSession session() {
        return session;
    }

    public BoardCanvas canvas() {
        return canvas;
    }

    /**
     * The board's keys live on the screen, not the canvas: the panel only offers keys to the hovered widget. ModularUI
     * offers each key event twice (char and key paths), so the ones that must act once remember the last event. Undo
     * and
     * redo are NEI-configurable (Ctrl+Z, Ctrl+Shift+Z, Ctrl+Y); a focused text field keeps its own keys.
     */
    private long lastKeyEvent;

    /**
     * Pastes the last copy with its top-left at the mouse when it is over the board, else in the middle of the view.
     */
    private void paste() {
        final int mx = getContext().getAbsMouseX(), my = getContext().getAbsMouseY();
        final com.cleanroommc.modularui.widget.sizer.Area a = canvas.getArea();
        final boolean over = a.isInside(mx, my);
        final int sx = over ? mx : a.x + a.width / 2, sy = over ? my : a.y + a.height / 2;
        canvas.revealWhenBuilt(session.paste(Math.round(canvas.worldX(sx)), Math.round(canvas.worldY(sy))));
    }

    /**
     * Whether a widget (a text field) has the keyboard. The context returns an empty holder, never null, and keeps the
     * focus on a field whose popup has closed until something else takes it: that field is not typing.
     */
    public static boolean textFocused(final ModularScreen screen) {
        final com.cleanroommc.modularui.screen.viewport.LocatedWidget focused = screen.getContext()
            .getFocusedWidget();
        final IWidget w = focused == null ? null : focused.getElement();
        return w != null && w.isValid()
            && w.getPanel()
                .isOpen();
    }

    @Override
    public boolean onKeyPressed(final char typedChar, final int keyCode) {
        // Esc closes the open popup (a menu, a number box), not the whole planner.
        if (keyCode == org.lwjgl.input.Keyboard.KEY_ESCAPE
            && getPanelManager().getTopMostPanel() instanceof Popup popup) {
            getContext().removeFocus();
            popup.closeIfOpen();
            return true;
        }
        // The non-recipe machines: Esc closes them, even from the search; the board's keys wait meanwhile.
        if (picker.isEnabled()) {
            if (keyCode == org.lwjgl.input.Keyboard.KEY_ESCAPE) {
                getContext().removeFocus();
                picker.setEnabled(false);
                return true;
            }
            return super.onKeyPressed(typedChar, keyCode);
        }
        // The library: Esc closes its pane, then the library; the board's keys wait until it is closed.
        if (library.open) {
            if (keyCode == org.lwjgl.input.Keyboard.KEY_ESCAPE && !textFocused(this)) {
                if (!library.view.escape()) library.set(false);
                return true;
            }
            return super.onKeyPressed(typedChar, keyCode);
        }
        if (!textFocused(this)) {
            // Delete or Backspace removes the selection; Esc clears it (and only closes the planner when nothing is
            // selected).
            if ((keyCode == org.lwjgl.input.Keyboard.KEY_DELETE || keyCode == org.lwjgl.input.Keyboard.KEY_BACK)
                && session.hasSelection()) {
                session.deleteSelected();
                return true;
            }
            if (keyCode == org.lwjgl.input.Keyboard.KEY_A && net.minecraft.client.gui.GuiScreen.isCtrlKeyDown()) {
                session.selectAll();
                return true;
            }
            if (keyCode == org.lwjgl.input.Keyboard.KEY_ESCAPE && session.hasSelection()) {
                session.clearSelection();
                return true;
            }
            // +, -, Page Up and Page Down step the zoom, as on the website (the pan keys are read by the canvas).
            final int zoom = switch (keyCode) {
                case org.lwjgl.input.Keyboard.KEY_EQUALS, org.lwjgl.input.Keyboard.KEY_ADD, org.lwjgl.input.Keyboard.KEY_PRIOR -> 1;
                case org.lwjgl.input.Keyboard.KEY_MINUS, org.lwjgl.input.Keyboard.KEY_SUBTRACT, org.lwjgl.input.Keyboard.KEY_NEXT -> -1;
                default -> 0;
            };
            if (zoom != 0 && codechicken.nei.LayoutManager.getInputFocused() == null) {
                final long event = org.lwjgl.input.Keyboard.getEventNanoseconds();
                if (event != lastKeyEvent) {
                    lastKeyEvent = event;
                    canvas.zoomKey(zoom);
                }
                return true;
            }
            // Ctrl+C, Ctrl+X, Ctrl+V: the selection with its wires, pasted at the mouse (or the middle of the view).
            if (net.minecraft.client.gui.GuiScreen.isCtrlKeyDown()
                && (keyCode == org.lwjgl.input.Keyboard.KEY_C || keyCode == org.lwjgl.input.Keyboard.KEY_X
                    || keyCode == org.lwjgl.input.Keyboard.KEY_V)) {
                final long event = org.lwjgl.input.Keyboard.getEventNanoseconds();
                if (event == lastKeyEvent) return true;
                lastKeyEvent = event;
                if (keyCode == org.lwjgl.input.Keyboard.KEY_V) paste();
                else if (session.copySelection() && keyCode == org.lwjgl.input.Keyboard.KEY_X) session.deleteSelected();
                return true;
            }
            final boolean undo = NEIClientConfig.isKeyHashDown(NEIPlanConfig.ConfigUndoKey.KEY);
            if (undo || NEIClientConfig.isKeyHashDown(NEIPlanConfig.ConfigRedoKey.KEY)
                || NEIClientConfig.isKeyHashDown(NEIPlanConfig.ConfigRedoAltKey.KEY)) {
                // ModularUI offers each key event twice (char and key paths); act once per event.
                final long event = org.lwjgl.input.Keyboard.getEventNanoseconds();
                if (event != lastKeyEvent) {
                    lastKeyEvent = event;
                    if (undo) session.undo();
                    else session.redo();
                }
                return true;
            }
        }
        return super.onKeyPressed(typedChar, keyCode);
    }

    /**
     * An item dragged out of NEI's list is carried until the next click, which NEI turns into a drop through its GUI
     * handlers. On a ModularUI screen the board takes that click first and NEI never sees it, so the board drops the
     * item itself: on the canvas it offers a drawer or its recipes there; anywhere else in the planner the carry just
     * ends, as it does off a container.
     */
    @Override
    public boolean onMousePressed(final int mouseButton) {
        final ItemStack carried = codechicken.nei.ItemPanels.itemPanel.draggedStack;
        final int mx = getContext().getAbsMouseX(), my = getContext().getAbsMouseY();
        // Any other press ends a middle pan whose release went missing.
        if (mouseButton != 2) canvas.endMiddlePan();
        // The middle button pans from anywhere on the board, cards included.
        if (mouseButton == 2 && carried == null
            && canvas.getArea()
                .isInside(mx, my)
            && !(getPanelManager().getTopMostPanel() instanceof Popup)) {
            canvas.beginMiddlePan();
            return true;
        }
        if (carried == null || !getMainPanel().getArea()
            .isInside(mx, my)) return super.onMousePressed(mouseButton);
        codechicken.nei.ItemPanels.itemPanel.draggedStack = null;
        if (mouseButton == 0 && canvas.getArea()
            .isInside(mx, my) && !(getPanelManager().getTopMostPanel() instanceof Popup)) {
            final ItemStack one = carried.copy();
            one.stackSize = 1;
            canvas.dropNeiItem(one, mx, my);
        }
        return true;
    }

    @Override
    public boolean onMouseRelease(final int mouseButton) {
        if (mouseButton == 2) canvas.endMiddlePan();
        return super.onMouseRelease(mouseButton);
    }

    @Override
    public void onOpen() {
        super.onOpen();
        session.reopen();
        session.disarmPending();
    }

    @Override
    public void onClose() {
        session.close();
        super.onClose();
    }

    @Override
    public void drawForeground() {
        super.drawForeground();
        drawPortTooltip();
    }

    /**
     * The board's tooltips, in Factory Flow's panel: NEI skips its own on ModularUI screens whenever a widget is
     * hovered, so the board draws them in the foreground pass. A multiblock's power chips answer with the power panel,
     * and a card far out with its reveal.
     */
    /** What the mouse was over last frame, and since when: tips that wait a moment need it. */
    private Object tipTarget;
    private long tipSince;

    private void drawPortTooltip() {
        final Object hovered = getContext().getHovered();
        // While a popup is open, only it explains itself: a tip from the board would cover it.
        final ModularPanel top = getPanelManager().getTopMostPanel();
        if (hovered != tipTarget) {
            tipTarget = hovered;
            tipSince = System.currentTimeMillis();
        }
        if (top instanceof Popup && !(hovered instanceof final IWidget w && w.getPanel() == top)) return;
        final int mx = getContext().getAbsMouseX(), my = getContext().getAbsMouseY();
        // NEI's item list draws over anything past the planner's right edge: tips stay left of it.
        final int right = getMainPanel().getArea().x + getMainPanel().getArea().width;
        final int bottom = getMainPanel().getArea().y + getMainPanel().getArea().height;
        final Tip tip;
        if (hovered instanceof final RecipeCard card) {
            if (card.drawPower(right, bottom)) return;
            // Zoomed out, a card answers with its own panel: name, numbers, ins and outs.
            if (card.drawReveal(mx, my, right, bottom)) return;
            tip = card.tip();
        } else if (hovered instanceof final DrawerCard drawer) {
            tip = drawer.tip();
        } else if (hovered instanceof final PlanTabs tabs) {
            tip = Tip.ofLines(tabs.hoverLines());
        } else if (hovered instanceof final OverviewRail overview) {
            final Tip railTip = Tip.ofLines(overview.hoverLines());
            if (railTip == null) return;
            // Beside the rail, so the tip never covers the row it is about.
            railTip.draw(
                overview.getArea().x + overview.getArea().width + 4,
                Math.max(2, Math.min(my - 12, bottom - railTip.height())));
            return;
        } else if (hovered == null || hovered == canvas) {
            tip = Tip.ofLines(canvas.wireLines());
        } else if (hovered instanceof SelectionBar) {
            tip = Tip.of("Combine into one shared machine")
                .muted("The selected recipes run on one machine and share its settings.");
        } else if (hovered instanceof final PortSlot slot) {
            tip = portTip(slot);
        } else if (hovered instanceof final IWidget w && KEY_TIPS.containsKey(w)) {
            // The keys wait a moment, so passing over the bar does not flash tips.
            if (System.currentTimeMillis() - tipSince < 350) return;
            tip = Tip.ofLines(java.util.Arrays.asList(KEY_TIPS.get(w).split("\n")));
        } else {
            return;
        }
        if (tip != null) tip.drawNear(mx, my, right, bottom);
    }

    /** A port: what it is, its rate, NEI's own lines about the stack, and what the mouse and keys do with it. */
    private Tip portTip(final PortSlot slot) {
        final CardModel.PortView view = slot.view();
        if (view == null) return null;
        final ItemStack stack = slot.stack();
        final boolean chanced = view.output() && view.chance() < 0.9999f;
        final Tip tip = Tip.of(view.name())
            .sub(view.output() ? "Output" : "Input")
            .row(
                view.output() ? chanced ? "Average output" : "Produced" : "Consumed",
                Fmt.rate(view.perSecond(), session.rateUnit(), view.isFluid()));
        if (chanced) tip.row("Chance", Fmt.compact(view.chance() * 100) + "%");
        if (stack != null) {
            // NEI's lines past the name: a formula, a material's notes, the mod. A few at most.
            final List<String> nei = GuiContainerManager.itemDisplayNameMultiline(stack, null, true);
            for (int i = 1; i < Math.min(nei.size(), 5); i++) tip.note(nei.get(i), Tip.SUBTLE);
        }
        if (!view.wired()) {
            tip.note("Unconnected", Tip.WARN);
            tip.muted(
                view.output() ? "Wire it to a card, or drag it onto the board to add a drawer."
                    : "Wire it to a card that makes it, or drag it onto the board to add a drawer.");
        }
        return tip.action(Tip.Input.LEFT, "Recipes: what makes it")
            .action(Tip.Input.RIGHT, "Uses: what uses it")
            .action(Tip.Input.DRAG, "Connect")
            .action(Tip.Input.KEY, "R, U");
    }
}

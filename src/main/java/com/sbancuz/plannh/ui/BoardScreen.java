package com.sbancuz.plannh.ui;

import static codechicken.lib.gui.GuiDraw.drawMultilineTip;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;

import org.lwjgl.opengl.GL11;

import com.cleanroommc.modularui.api.drawable.IDrawable;
import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.ModularScreen;
import com.cleanroommc.modularui.screen.UISettings;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.TextWidget;
import com.cleanroommc.modularui.widgets.layout.Flow;
import com.sbancuz.plannh.PlanNH;
import com.sbancuz.plannh.nei.NEIPlanConfig;
import com.sbancuz.plannh.ui.canvas.BoardCanvas;
import com.sbancuz.plannh.ui.card.CardModel;
import com.sbancuz.plannh.ui.card.PortSlot;
import com.sbancuz.plannh.ui.card.RecipeCard;
import com.sbancuz.plannh.ui.drawer.DrawerCard;
import com.sbancuz.plannh.ui.popup.Popup;
import com.sbancuz.plannh.ui.popup.RecipePicker;
import com.sbancuz.plannh.ui.theme.Fmt;
import com.sbancuz.plannh.ui.theme.Hyb;

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

    private BoardScreen(final ModularPanel panel, final BoardSession session, final BoardCanvas canvas) {
        super(PlanNH.MODID, panel);
        this.session = session;
        this.canvas = canvas;
        getContext().setSettings(new UISettings());
        getContext().getUISettings()
            .getRecipeViewerSettings()
            .enable();
    }

    public static BoardScreen create() {
        final BoardSession session = new BoardSession();
        final ModularPanel panel = ModularPanel.defaultPanel("plannh_board")
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

        final Flow topBar = Flow.row()
            .widthRel(1f)
            .height(TOP_BAR)
            .padding(4, 2)
            .childPadding(3)
            .background((IDrawable) (ctx, x, y, w, h, theme) -> {
                Hyb.rect(x, y, w, h, 0xFF0E0F12);
                Hyb.rect(x, y + h - 1, w, 1, 0xFF2A2C31);
            });
        topBar.child(
            new PlanTabs(session).width(320)
                .height(16));
        topBar.child(key(() -> "Undo", session::canUndo, "Undo (Ctrl+Z)", 30, session::undo));
        topBar.child(key(() -> "Redo", session::canRedo, "Redo (Ctrl+Shift+Z)", 30, session::redo));
        topBar.child(
            new TextWidget<>(
                IKey.dynamic(
                    () -> session.solvingVisibly()
                        ? "Solving" + ".".repeat((int) (System.currentTimeMillis() / 400 % 4))
                        : "")).color(Hyb.MUTED)
                            .shadow(true)
                            .heightRel(1f)
                            .expanded());
        topBar.child(
            key(
                () -> session.rateUnit().suffix,
                () -> true,
                "Rate unit",
                30,
                () -> session.setRateUnit(
                    session.rateUnit()
                        .next())));
        topBar.child(
            key(
                () -> session.powerKey() == BoardSession.PowerKey.EU ? "EU/t" : "Amps",
                () -> true,
                "Power: EU/t, or amps at each card's tier",
                30,
                session::togglePowerKey));
        topBar.child(
            key(
                () -> session.peakPower() ? "Peak" : "Avg",
                () -> true,
                "Power: average (solved machines) or peak (every machine running)",
                28,
                session::togglePeakPower));
        topBar
            .child(key(() -> "Arrange", () -> true, "Lay the plan out left to right (undoable)", 42, canvas::arrange));
        topBar.child(key(() -> "Fit", () -> true, "Fit the whole plan in view", 24, canvas::frameAll));
        topBar.child(key(() -> "?", () -> true, "How the board works", 16, () -> showHelp(panel)));

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
        column.child(body);
        panel.child(column);
        panel.child(
            new NoticeBar(session, canvas)
                .left(() -> rail.currentWidth() + 6, com.cleanroommc.modularui.widget.sizer.Unit.Measure.PIXEL)
                .right(6)
                .top(TOP_BAR + 4));
        return new BoardScreen(panel, session, canvas);
    }

    /** Every gesture on the board, in one list. */
    private static void showHelp(final ModularPanel panel) {
        final List<com.sbancuz.plannh.ui.popup.PickList.Entry> rows = new ArrayList<>();
        final String[][] tips = { { "P over any item", "what makes it (Shift+P: what uses it)" },
            { "Click a port", "what makes this input, or uses this output" },
            { "Drag a port", "onto a card: wire it; onto the board: a drawer" },
            { "Right-click a wire", "a drawer on it, or delete it" },
            { "Click a card or drawer", "select it (Shift: add to the selection)" },
            { "Drag the board", "pan (Shift: select everything in the box)" },
            { "Drag a selected card", "move the whole selection" }, { "Ctrl+A", "select everything" },
            { "Delete", "remove the selection (Esc: clear it)" }, { "Wheel", "zoom; over a control: change it" },
            { "Ctrl+Z, Ctrl+Shift+Z", "undo, redo" },
            { "Overview: double-click", "fly to the cards that use a resource" } };
        for (final String[] tip : tips)
            rows.add(new com.sbancuz.plannh.ui.popup.PickList.Entry(null, tip[0], tip[1], Hyb.INK, false, () -> {}));
        com.cleanroommc.modularui.screen.ModularPanel p = com.sbancuz.plannh.ui.popup.PickList
            .popup("plannh_help", "HOW THE BOARD WORKS", rows, false, 300, rows.size());
        com.sbancuz.plannh.ui.popup.Popup.open(panel, (com.sbancuz.plannh.ui.popup.Popup) p, 300, 24);
    }

    /** A top-bar key in the card's key style: a label, a tooltip, greyed when it can do nothing. */
    private static ButtonWidget<?> key(final Supplier<String> label, final BooleanSupplier enabled,
        final String tooltip, final int width, final Runnable action) {
        return new ButtonWidget<>().size(width, 16)
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
            .addTooltipLine(tooltip)
            .onMousePressed(b -> {
                if (b == 0 && enabled.getAsBoolean()) action.run();
                return true;
            });
    }

    public BoardSession session() {
        return session;
    }

    public BoardCanvas canvas() {
        return canvas;
    }

    /**
     * Undo and redo on the screen, not the canvas: the panel only offers keys to the hovered widget. The keys are
     * NEI-configurable (Ctrl+Z, Ctrl+Shift+Z, Ctrl+Y). A focused text field keeps its own Ctrl+Z.
     */
    private long lastUndoKeyEvent;

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
        // Esc closes the open popup (a menu, a number box, the picker), not the whole planner.
        if (keyCode == org.lwjgl.input.Keyboard.KEY_ESCAPE
            && getPanelManager().getTopMostPanel() instanceof Popup popup) {
            getContext().removeFocus();
            popup.closeIfOpen();
            return true;
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
            final boolean undo = NEIClientConfig.isKeyHashDown(NEIPlanConfig.ConfigUndoKey.KEY);
            if (undo || NEIClientConfig.isKeyHashDown(NEIPlanConfig.ConfigRedoKey.KEY)
                || NEIClientConfig.isKeyHashDown(NEIPlanConfig.ConfigRedoAltKey.KEY)) {
                // ModularUI offers each key event twice (char and key paths); act once per event.
                final long event = org.lwjgl.input.Keyboard.getEventNanoseconds();
                if (event != lastUndoKeyEvent) {
                    lastUndoKeyEvent = event;
                    if (undo) session.undo();
                    else session.redo();
                }
                return true;
            }
        }
        return super.onKeyPressed(typedChar, keyCode);
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
     * NEI's item tooltip plus the port's rate. NEI skips its own tooltip on ModularUI screens whenever a widget is
     * hovered, so the board draws it in the foreground pass.
     */
    private void drawPortTooltip() {
        final Object hovered = getContext().getHovered();
        // While a popup is open, only it explains itself: a tip from the board would cover it.
        final ModularPanel top = getPanelManager().getTopMostPanel();
        if (top instanceof Popup && !(hovered instanceof final IWidget w && w.getPanel() == top)) return;
        final List<String> lines;
        int tipX = getContext().getAbsMouseX() + 12;
        if (hovered instanceof final RecipeCard card) {
            lines = card.hoverLines();
            if (lines == null) return;
        } else if (hovered instanceof final DrawerCard drawer) {
            lines = drawer.hoverLines();
            if (lines == null) return;
        } else if (hovered instanceof final RecipePicker picker) {
            lines = picker.hoverLines();
            if (lines == null) return;
        } else if (hovered instanceof final PlanTabs tabs) {
            lines = tabs.hoverLines();
            if (lines == null) return;
        } else if (hovered instanceof final OverviewRail overview) {
            lines = overview.hoverLines();
            if (lines == null) return;
            // Beside the rail, so the tip never covers the row it is about.
            tipX = overview.getArea().x + overview.getArea().width + 4;
        } else if (hovered == null || hovered == canvas) {
            lines = canvas.wireLines();
            if (lines == null) return;
        } else if (hovered instanceof final PortSlot slot) {
            final ItemStack stack = slot.stack();
            final CardModel.PortView view = slot.view();
            if (stack == null || view == null) return;
            lines = new ArrayList<>(GuiContainerManager.itemDisplayNameMultiline(stack, null, true));
            if (lines.isEmpty()) lines.add(view.name());
            lines.add("§7" + Fmt.rate(view.perSecond(), session.rateUnit(), view.isFluid()));
        } else {
            return;
        }
        // NEI's item list draws over anything past the planner's right edge: flip the tip to the cursor's left.
        int width = 0;
        for (final String line : lines) width = Math.max(
            width,
            net.minecraft.client.Minecraft.getMinecraft().fontRenderer.getStringWidth(line));
        final int right = getMainPanel().getArea().x + getMainPanel().getArea().width;
        if (tipX + width + 4 > right) tipX = Math.max(4, getContext().getAbsMouseX() - 16 - width);
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        drawMultilineTip(tipX, getContext().getAbsMouseY() - 12, lines);
        GL11.glPopAttrib();
    }
}

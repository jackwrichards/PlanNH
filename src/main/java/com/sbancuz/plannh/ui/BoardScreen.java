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
                    () -> session.solving() ? "Solving" + ".".repeat((int) (System.currentTimeMillis() / 400 % 4))
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
        topBar.child(
            key(
                () -> rail.isOpen() ? "Hide overview" : "Overview",
                () -> true,
                "The overview rail: what the plan takes in and gives out, its power, the machines to build",
                66,
                rail::toggle));

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

    /** Whether a widget (a text field) has the keyboard. The context returns an empty holder, never null. */
    public static boolean textFocused(final ModularScreen screen) {
        final com.cleanroommc.modularui.screen.viewport.LocatedWidget focused = screen.getContext()
            .getFocusedWidget();
        return focused != null && focused.getElement() != null;
    }

    @Override
    public boolean onKeyPressed(final char typedChar, final int keyCode) {
        if (!textFocused(this)) {
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
        final List<String> lines;
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
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        drawMultilineTip(getContext().getAbsMouseX() + 12, getContext().getAbsMouseY() - 12, lines);
        GL11.glPopAttrib();
    }
}

package com.sbancuz.plannh.ui;

import static codechicken.lib.gui.GuiDraw.drawMultilineTip;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;

import org.lwjgl.opengl.GL11;

import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.ModularScreen;
import com.cleanroommc.modularui.screen.UISettings;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.TextWidget;
import com.cleanroommc.modularui.widgets.layout.Flow;
import com.sbancuz.plannh.PlanNH;
import com.sbancuz.plannh.ui.canvas.BoardCanvas;
import com.sbancuz.plannh.ui.card.CardModel;
import com.sbancuz.plannh.ui.card.PortSlot;
import com.sbancuz.plannh.ui.card.RecipeCard;
import com.sbancuz.plannh.ui.theme.Fmt;
import com.sbancuz.plannh.ui.theme.Hyb;

import codechicken.nei.LayoutManager;
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

        final Flow topBar = Flow.row()
            .widthRel(1f)
            .height(TOP_BAR)
            .padding(4, 2)
            .childPadding(4)
            .background((com.cleanroommc.modularui.api.drawable.IDrawable) (ctx, x, y, w, h, theme) -> {
                Hyb.rect(x, y, w, h, 0xFF0E0F12);
                Hyb.rect(x, y + h - 1, w, 1, 0xFF2A2C31);
            });
        topBar.child(
            new TextWidget<>(
                IKey.dynamic(
                    () -> session.graph()
                        .getName())).color(Hyb.INK)
                            .shadow(true)
                            .heightRel(1f));
        topBar.child(
            new ButtonWidget<>().size(34, 16)
                .overlay(IKey.dynamic(() -> session.rateUnit().suffix))
                .addTooltipLine("Rate unit")
                .onMousePressed(b -> {
                    session.setRateUnit(
                        session.rateUnit()
                            .next());
                    return true;
                }));

        final Flow column = Flow.column()
            .widthRel(1f)
            .heightRel(1f);
        column.child(topBar);
        column.child(
            canvas.widthRel(1f)
                .expanded());
        panel.child(column);
        return new BoardScreen(panel, session, canvas);
    }

    public BoardSession session() {
        return session;
    }

    public BoardCanvas canvas() {
        return canvas;
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

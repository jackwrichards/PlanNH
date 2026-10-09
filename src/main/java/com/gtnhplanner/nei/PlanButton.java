package com.gtnhplanner.nei;

import java.awt.Point;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;

import com.gtnhplanner.ui.card.MachineChoices;

import codechicken.lib.gui.GuiDraw;
import codechicken.nei.recipe.GuiRecipeButton;
import codechicken.nei.recipe.RecipeHandlerRef;

/**
 * The plan button, above NEI's + and star on every recipe the planner can read: puts the recipe in a plan, with the
 * planner open or not, and shows it there. Click: asks which plan (when there are several) and which machine (when the
 * recipe's tab has several). Shift-click: the open plan, on the machine picked last time. {@link PlanMenu} does the
 * work.
 */
public final class PlanButton extends GuiRecipeButton {

    /** Where it was last drawn, in screen pixels: its menus open beside it. */
    int screenX, screenY;

    PlanButton(final RecipeHandlerRef ref, final int x, final int y) {
        super(ref, x, y, 0, "");
    }

    private List<MachineChoices.Choice> choices;

    /**
     * What the recipe can run on; worked out once, as reading a recipe is not free and the tooltip asks every frame.
     */
    List<MachineChoices.Choice> choices() {
        if (choices == null) choices = MachineChoices.of(handlerRef.handler, handlerRef.recipeIndex);
        return choices;
    }

    /** The buttons drawn lately, and when each was: the tour finds a recipe's button among them. */
    private static final java.util.Map<PlanButton, Long> SHOWN = new java.util.WeakHashMap<>();

    /** The button on a recipe of NEI's open page, when it was drawn in the last moment; else null. */
    @javax.annotation.Nullable
    public static PlanButton shownFor(final codechicken.nei.recipe.IRecipeHandler handler, final int recipe) {
        final long now = System.currentTimeMillis();
        for (final java.util.Map.Entry<PlanButton, Long> e : SHOWN.entrySet()) {
            final PlanButton b = e.getKey();
            if (now - e.getValue() < 300 && b.handlerRef.handler == handler && b.handlerRef.recipeIndex == recipe)
                return b;
        }
        return null;
    }

    public int screenX() {
        return screenX;
    }

    public int screenY() {
        return screenY;
    }

    @Override
    public void drawButton(final Minecraft mc, final int mouseX, final int mouseY) {
        if (!visible) return;
        SHOWN.put(this, System.currentTimeMillis());
        // NEI hands buttons the mouse in whatever space it draws them in; the real mouse gives the offset to the
        // screen.
        final Point mouse = GuiDraw.getMousePosition();
        screenX = xPosition + mouse.x - mouseX;
        screenY = yPosition + mouse.y - mouseY;
        super.drawButton(mc, mouseX, mouseY);
    }

    /** The planner's mark, 8 pixels square: a card wired round a corner to another. */
    @Override
    protected void drawContent(final Minecraft mc, final int y, final int x, final boolean mouseOver) {
        final int c = 0xFF000000 | getTextColour(mouseOver);
        final int ox = xPosition + 2, oy = yPosition + 2;
        Gui.drawRect(ox, oy, ox + 3, oy + 3, c);
        Gui.drawRect(ox + 3, oy + 1, ox + 6, oy + 2, c);
        Gui.drawRect(ox + 5, oy + 2, ox + 6, oy + 5, c);
        Gui.drawRect(ox + 4, oy + 5, ox + 7, oy + 8, c);
    }

    @Override
    public void mouseReleased(final int mouseX, final int mouseY) {
        PlanMenu.INSTANCE.click(this);
    }

    @Override
    public List<String> handleTooltip(final List<String> tooltip) {
        tooltip.addAll(PlanMenu.INSTANCE.tooltip(this));
        return tooltip;
    }

    @Override
    public Map<String, String> handleHotkeys(final int mouseX, final int mouseY, final Map<String, String> hotkeys) {
        return hotkeys;
    }

    @Override
    public void lastKeyTyped(final char keyChar, final int keyID) {}

    @Override
    public void drawItemOverlay() {}
}

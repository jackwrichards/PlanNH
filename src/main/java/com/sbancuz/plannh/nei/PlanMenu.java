package com.sbancuz.plannh.nei;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;

import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import com.sbancuz.plannh.data.flowchart.Graph;
import com.sbancuz.plannh.data.flowchart.Node;
import com.sbancuz.plannh.data.flowchart.Plan;
import com.sbancuz.plannh.ui.BoardScreen;
import com.sbancuz.plannh.ui.Planner;
import com.sbancuz.plannh.ui.card.MachineChoices;
import com.sbancuz.plannh.ui.card.MachinePicks;
import com.sbancuz.plannh.ui.popup.Tip;
import com.sbancuz.plannh.ui.theme.Hyb;

import codechicken.nei.guihook.IContainerDrawHandler;
import codechicken.nei.guihook.IContainerInputHandler;
import codechicken.nei.guihook.IContainerObjectHandler;
import codechicken.nei.recipe.GuiRecipe;
import codechicken.nei.recipe.RecipeHandlerRef;

/**
 * What the {@link PlanButton} does: the menus it opens on NEI's recipe page (which plan, which machine) and the recipe
 * going into the plan, on the board, in view. The menus are drawn over everything NEI draws, and are first in line for
 * clicks and keys while open.
 */
public final class PlanMenu implements IContainerDrawHandler, IContainerInputHandler, IContainerObjectHandler {

    public static final PlanMenu INSTANCE = new PlanMenu();

    private static final int ROW = 20, TITLE = 16;

    private record Row(@Nullable ItemStack icon, String label, String detail, boolean current, int ink,
        Runnable action) {

        Row(@Nullable final ItemStack icon, final String label, final String detail, final boolean current,
            final Runnable action) {
            this(icon, label, detail, current, Hyb.INK, action);
        }
    }

    /** A new plan's row: the board's cyan, as its other adding keys. */
    private static final int NEW_INK = 0xFF22D3EE;

    private PlanMenu() {}

    // The open menu: the screen it is on, where, its title and rows.
    @Nullable
    private GuiContainer menuOn;
    private int mx, my, mw, mh;
    private String title = "";
    private List<Row> rows = List.of();

    // region What the button does

    /**
     * A click: which plan (a new one, or one of the plans, the most recently open first), then which machine, when the
     * recipe's tab has several. Shift skips both: the open plan, on the machine picked last time.
     */
    void click(final PlanButton button) {
        final Plan plan = Plan.getInstance();
        if (GuiScreen.isShiftKeyDown()) add(button, plan.getActiveIndex(), null);
        else offerPlans(button);
    }

    private void offerPlans(final PlanButton button) {
        final Plan plan = Plan.getInstance();
        final List<Row> list = new ArrayList<>();
        list.add(new Row(null, "+ New plan", "", false, NEW_INK, () -> offerMachines(button, -1)));
        for (final int i : plan.byRecency()) {
            final boolean open = i == plan.getActiveIndex();
            list.add(new Row(null, planName(i), open ? "current" : "", open, () -> offerMachines(button, i)));
        }
        open(button, "Which plan to add to?", list);
    }

    private void offerMachines(final PlanButton button, final int plan) {
        final RecipeHandlerRef ref = button.handlerRef;
        final List<MachineChoices.Choice> choices = button.choices();
        if (choices.size() <= 1) {
            add(button, plan, null);
            return;
        }
        final MachineChoices.Choice last = lastPick(button);
        final List<Row> list = new ArrayList<>();
        for (final MachineChoices.Choice c : choices) {
            list.add(new Row(c.machine(), c.label(), c.detail(), c == last, () -> {
                MachinePicks.put(ref.handler, c.key());
                add(button, plan, c);
            }));
        }
        open(button, "Which machine to use?", list);
    }

    /** The machine the tab was last added on, else the first. */
    private static MachineChoices.Choice lastPick(final PlanButton button) {
        final List<MachineChoices.Choice> choices = button.choices();
        final MachineChoices.Choice last = MachineChoices.find(choices, MachinePicks.get(button.handlerRef.handler));
        return last != null ? last : choices.get(0);
    }

    private static String planName(final int plan) {
        final List<Graph> plans = Plan.getInstance()
            .getGraphs();
        return plan >= 0 && plan < plans.size() ? plans.get(plan)
            .getName() : "Plan " + (plans.size() + 1);
    }

    /**
     * Puts the recipe in {@code plan} (-1: a new one) on {@code choice}, or on the tab's remembered machine, and shows
     * it: the board opens on that plan (or the page closes back to it) with the new card centred and selected. It
     * lands as + would land it, beside what it wires to.
     */
    private void add(final PlanButton button, int plan, @Nullable final MachineChoices.Choice choice) {
        final RecipeHandlerRef ref = button.handlerRef;
        final Node node = MachineChoices.newNode(ref.handler, ref.recipeIndex, choice);
        final Plan p = Plan.getInstance();
        if (plan < 0 || plan >= p.getGraphs()
            .size()) {
            p.getGraphs()
                .add(new Graph(planName(-1)));
            plan = p.getGraphs()
                .size() - 1;
        }
        final Minecraft mc = Minecraft.getMinecraft();
        final GuiContainer first = mc.currentScreen instanceof final GuiRecipe<?> recipes ? recipes.firstGui : null;
        final BoardScreen board;
        if (Planner.screenOf(first) instanceof final BoardScreen open) {
            open.session()
                .switchSlot(plan);
            mc.displayGuiScreen(first);
            board = open;
        } else {
            p.setActiveIndex(plan);
            board = Planner.open();
        }
        board.session()
            .addAndFocus(node);
    }

    /** The button's tooltip: where a click puts the recipe, and what Shift-click does. */
    List<String> tooltip(final PlanButton button) {
        final List<String> lines = new ArrayList<>();
        // Its menu says it all.
        if (menuOn != null) return lines;
        final Plan p = Plan.getInstance();
        final boolean machines = button.choices()
            .size() > 1;
        lines.add("Add to a plan");
        lines.add("§7Click: choose the plan (or a new one)" + (machines ? " and the machine" : ""));
        String quick = "add to " + planName(p.getActiveIndex());
        if (machines) {
            final MachineChoices.Choice last = lastPick(button);
            quick += ", using " + last.label()
                + (last.detail()
                    .isEmpty() ? "" : " (" + last.detail() + ")");
        }
        lines.add("§7Shift-click: " + quick);
        return lines;
    }

    // endregion

    // region The menu

    private void open(final PlanButton button, final String heading, final List<Row> list) {
        int width = Hyb.width(heading) + 12;
        for (final Row r : list) {
            final int detail = r.detail.isEmpty() ? 0 : Hyb.width(r.detail) + 12;
            width = Math.max(width, (r.icon == null ? 8 : 26) + Hyb.width(r.label) + detail + 10);
        }
        final GuiScreen screen = Minecraft.getMinecraft().currentScreen;
        if (!(screen instanceof final GuiContainer gui)) return;
        menuOn = gui;
        title = heading;
        rows = list;
        mw = Math.min(width, 260);
        mh = TITLE + list.size() * ROW + 4;
        // Left of the button, its top level with the button's; on the right when there is no room, and on screen.
        mx = button.screenX - mw - 3;
        if (mx < 2) mx = button.screenX + button.width + 3;
        mx = Math.max(2, Math.min(mx, gui.width - mw - 2));
        my = Math.max(2, Math.min(button.screenY - 2, gui.height - mh - 2));
    }

    private void close() {
        menuOn = null;
        rows = List.of();
    }

    private boolean menuOpenOn(final GuiContainer gui) {
        return menuOn != null && menuOn == gui;
    }

    private boolean overMenu(final int x, final int y) {
        return x >= mx && x < mx + mw && y >= my && y < my + mh;
    }

    private int rowAt(final int x, final int y) {
        if (!overMenu(x, y) || x < mx + 2 || x >= mx + mw - 2) return -1;
        final int r = (y - my - TITLE) / ROW;
        return y >= my + TITLE && r >= 0 && r < rows.size() ? r : -1;
    }

    private void drawMenu(final int mouseX, final int mouseY) {
        // Popup chrome, as the board's menus wear it.
        Hyb.rect(mx + 4, my + 4, mw, mh, 0x59000000);
        Hyb.rect(mx, my, mw, mh, Hyb.KEY_EDGE);
        Hyb.rect(mx + 1, my + 1, mw - 2, mh - 2, Hyb.MENU);
        Hyb.rect(mx + 1, my + 1, mw - 2, 1, Hyb.HIGHLIGHT);
        Hyb.rect(mx + 1, my + 1, 1, mh - 2, Hyb.HIGHLIGHT);
        Hyb.rect(mx + 1, my + mh - 2, mw - 2, 1, Hyb.SHADOW);
        Hyb.rect(mx + mw - 2, my + 1, 1, mh - 2, Hyb.SHADOW);
        Hyb.text(Hyb.fit(title, mw - 12), mx + 6, my + 5, Hyb.MUTED);
        final int hovered = rowAt(mouseX, mouseY);
        for (int i = 0; i < rows.size(); i++) {
            final Row r = rows.get(i);
            final int y = my + TITLE + i * ROW;
            if (i == hovered) Hyb.rect(mx + 2, y, mw - 4, ROW, Hyb.MENU_HOVER);
            else if (r.current) Hyb.rect(mx + 2, y, mw - 4, ROW, Hyb.TILE);
            int x = mx + 6;
            if (r.icon != null) {
                GL11.glEnable(GL11.GL_DEPTH_TEST);
                Hyb.item(r.icon, x, y + 2, 16, 0);
                GL11.glDisable(GL11.GL_DEPTH_TEST);
                GL11.glDisable(GL11.GL_LIGHTING);
                x += 20;
            }
            final int detailW = r.detail.isEmpty() ? 0 : Hyb.width(r.detail) + 8;
            Hyb.text(Hyb.fit(r.label, mx + mw - 6 - detailW - x), x, y + 6, r.ink);
            if (!r.detail.isEmpty()) Hyb.textRight(r.detail, mx + mw - 6, y + 6, Hyb.MUTED);
        }
    }

    // endregion

    // region NEI's draw hooks

    @Override
    public void onPreDraw(final GuiContainer gui) {
        // The page went away under the menu.
        if (menuOn != null && Minecraft.getMinecraft().currentScreen != menuOn) close();
    }

    @Override
    public void renderObjects(final GuiContainer gui, final int mouseX, final int mouseY) {}

    @Override
    public void postRenderObjects(final GuiContainer gui, final int mouseX, final int mouseY) {
        if (!menuOpenOn(gui)) return;
        Tip.beginPanel();
        drawMenu(mouseX, mouseY);
        Tip.endPanel();
    }

    @Override
    public void renderSlotUnderlay(final GuiContainer gui, final Slot slot) {}

    @Override
    public void renderSlotOverlay(final GuiContainer gui, final Slot slot) {}

    // endregion

    // region NEI's input hooks

    @Override
    public boolean mouseClicked(final GuiContainer gui, final int mouseX, final int mouseY, final int button) {
        if (!menuOpenOn(gui)) return false;
        final int row = rowAt(mouseX, mouseY);
        final Runnable action = row >= 0 && button == 0 ? rows.get(row).action : null;
        // Any click closes it; one outside does nothing else.
        close();
        if (action != null) {
            Hyb.click();
            action.run();
        }
        return true;
    }

    @Override
    public boolean keyTyped(final GuiContainer gui, final char keyChar, final int keyCode) {
        if (!menuOpenOn(gui)) return false;
        if (keyCode == Keyboard.KEY_ESCAPE) close();
        return true;
    }

    @Override
    public boolean mouseScrolled(final GuiContainer gui, final int mouseX, final int mouseY, final int scrolled) {
        return menuOpenOn(gui);
    }

    @Override
    public void onKeyTyped(final GuiContainer gui, final char keyChar, final int keyID) {}

    @Override
    public boolean lastKeyTyped(final GuiContainer gui, final char keyChar, final int keyID) {
        return false;
    }

    @Override
    public void onMouseClicked(final GuiContainer gui, final int mouseX, final int mouseY, final int button) {}

    @Override
    public void onMouseUp(final GuiContainer gui, final int mouseX, final int mouseY, final int button) {}

    @Override
    public void onMouseScrolled(final GuiContainer gui, final int mouseX, final int mouseY, final int scrolled) {}

    @Override
    public void onMouseDragged(final GuiContainer gui, final int mouseX, final int mouseY, final int button,
        final long heldTime) {}

    // endregion

    // region NEI's object hooks: nothing under the menu is hovered or tipped

    @Override
    public void guiTick(final GuiContainer gui) {}

    @Override
    public void refresh(final GuiContainer gui) {}

    @Override
    public void load(final GuiContainer gui) {}

    @Override
    public ItemStack getStackUnderMouse(final GuiContainer gui, final int mouseX, final int mouseY) {
        return null;
    }

    @Override
    public boolean objectUnderMouse(final GuiContainer gui, final int mouseX, final int mouseY) {
        return menuOpenOn(gui) && overMenu(mouseX, mouseY);
    }

    @Override
    public boolean shouldShowTooltip(final GuiContainer gui) {
        if (!menuOpenOn(gui)) return true;
        final java.awt.Point mouse = codechicken.lib.gui.GuiDraw.getMousePosition();
        return !overMenu(mouse.x, mouse.y);
    }

    // endregion
}

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
import com.sbancuz.plannh.ui.NewCards;
import com.sbancuz.plannh.ui.Planner;
import com.sbancuz.plannh.ui.card.CardDefaults;
import com.sbancuz.plannh.ui.card.CardModel;
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
 * What the {@link PlanButton} does: the menus it opens on NEI's recipe page (which machine, which plan), the recipe
 * going into the plan, and a short note when it went in with the planner closed. Drawn over everything NEI draws,
 * and first in line for clicks and keys while a menu is open.
 */
public final class PlanMenu implements IContainerDrawHandler, IContainerInputHandler, IContainerObjectHandler {

    public static final PlanMenu INSTANCE = new PlanMenu();

    private static final int ROW = 20, TITLE = 16, TOAST_MS = 2600;

    private record Row(@Nullable ItemStack icon, String label, String detail, boolean current, Runnable action) {}

    private PlanMenu() {}

    /** Plan buttons drawn this frame, for right clicks. */
    private final List<PlanButton> shown = new ArrayList<>();

    // The open menu: the screen it is on, where, its title and rows.
    @Nullable
    private GuiContainer menuOn;
    private int mx, my, mw, mh;
    private String title = "";
    private List<Row> rows = List.of();

    // The note after an add.
    @Nullable
    private GuiContainer toastOn;
    private String toast = "", toastDetail = "";
    private int tx, ty;
    private long toastUntil;

    void drawn(final PlanButton button) {
        shown.add(button);
    }

    // region What the button does

    /** A click: straight in when there is nothing to ask (or Shift is down), else the machine menu. */
    void click(final PlanButton button) {
        offerMachines(
            button,
            Plan.getInstance()
                .getActiveIndex());
    }

    private void offerMachines(final PlanButton button, final int plan) {
        final RecipeHandlerRef ref = button.handlerRef;
        final List<MachineChoices.Choice> choices = button.choices();
        if (choices.size() <= 1 || GuiScreen.isShiftKeyDown()) {
            add(button, plan, null);
            return;
        }
        final MachineChoices.Choice remembered = MachineChoices.find(choices, MachinePicks.get(ref.handler));
        final List<Row> list = new ArrayList<>();
        for (final MachineChoices.Choice c : choices) {
            final boolean current = remembered != null ? c == remembered : c == choices.get(0);
            list.add(new Row(c.machine(), c.label(), c.detail(), current, () -> {
                MachinePicks.put(ref.handler, c.key());
                add(button, plan, c);
            }));
        }
        open(button, "Add to " + planName(plan) + " on", list);
    }

    /** A right click: which plan, then on as a click. */
    private void offerPlans(final PlanButton button) {
        final List<Graph> plans = Plan.getInstance()
            .getGraphs();
        final int active = Plan.getInstance()
            .getActiveIndex();
        final List<Row> list = new ArrayList<>();
        for (int i = 0; i < plans.size(); i++) {
            final int plan = i;
            list.add(
                new Row(
                    null,
                    plans.get(i)
                        .getName(),
                    i == active ? "open" : "",
                    i == active,
                    () -> offerMachines(button, plan)));
        }
        list.add(new Row(null, "New plan", "", false, () -> offerMachines(button, -1)));
        open(button, "Add to which plan?", list);
    }

    private static String planName(final int plan) {
        final List<Graph> plans = Plan.getInstance()
            .getGraphs();
        return plan >= 0 && plan < plans.size() ? plans.get(plan)
            .getName() : "Plan " + (plans.size() + 1);
    }

    /**
     * Puts the recipe in {@code plan} (-1: a new one) on {@code choice}, or on the tab's remembered machine. With the
     * planner under the recipe page it goes back to the board and lands as + would land it; otherwise it goes into the
     * plan where the board would put it, and that plan becomes the open one.
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
        if (Planner.screenOf(first) instanceof final BoardScreen board) {
            board.session()
                .switchSlot(plan);
            mc.displayGuiScreen(first);
            board.session()
                .addRecipe(node);
            return;
        }
        p.setActiveIndex(plan);
        final Graph graph = p.getGraphs()
            .get(plan);
        NewCards.addTo(graph, node);
        toast(button, "Added to " + graph.getName(), machineName(node));
    }

    /** The name of the machine a new card runs on, for the note. */
    private static String machineName(final Node node) {
        for (final ItemStack s : CardModel.catalystsOf(node)) {
            if (CardDefaults.matches(s, node.machineName)) return s.getDisplayName();
        }
        return node.machineName;
    }

    /** The button's tooltip: where a click puts the recipe, and the other ways in. */
    List<String> tooltip(final PlanButton button) {
        final List<String> lines = new ArrayList<>();
        // Its menu says it all.
        if (menuOn != null) return lines;
        final RecipeHandlerRef ref = button.handlerRef;
        final GuiContainer first = Minecraft.getMinecraft().currentScreen instanceof final GuiRecipe<?> recipes
            ? recipes.firstGui
            : null;
        final String plan = planName(
            Plan.getInstance()
                .getActiveIndex());
        lines.add(Planner.isPlanner(first) ? "Add to the board" : "Add to " + plan);
        final List<MachineChoices.Choice> choices = button.choices();
        if (choices.size() > 1) {
            MachineChoices.Choice last = MachineChoices.find(choices, MachinePicks.get(ref.handler));
            if (last == null) last = choices.get(0);
            lines.add("§7Click: pick the machine");
            lines.add("§7Shift-click: on " + last.label() + (last.detail().isEmpty() ? "" : " (" + last.detail() + ")"));
        }
        lines.add("§7Right-click: pick the plan");
        return lines;
    }

    // endregion

    // region The menu and the note

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

    private void toast(final PlanButton button, final String text, final String detail) {
        toastOn = Minecraft.getMinecraft().currentScreen instanceof final GuiContainer gui ? gui : null;
        toast = text;
        toastDetail = detail;
        final int w = Math.max(Hyb.width(text), Hyb.width(detail)) + 12;
        tx = Math.max(2, button.screenX - w - 3);
        ty = Math.max(2, button.screenY - 6);
        toastUntil = System.currentTimeMillis() + TOAST_MS;
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
            Hyb.text(Hyb.fit(r.label, mx + mw - 6 - detailW - x), x, y + 6, Hyb.INK);
            if (!r.detail.isEmpty()) Hyb.textRight(r.detail, mx + mw - 6, y + 6, Hyb.MUTED);
        }
    }

    private void drawToast() {
        final long left = toastUntil - System.currentTimeMillis();
        final int w = Math.max(Hyb.width(toast), Hyb.width(toastDetail)) + 12, h = toastDetail.isEmpty() ? 16 : 27;
        // Fades over its last half second.
        GL11.glColor4f(1, 1, 1, 1);
        Tip.chrome(tx, ty, w, h);
        Hyb.text(toast, tx + 6, ty + 4, left > 500 ? Hyb.INK : fade(Hyb.INK, left / 500f));
        if (!toastDetail.isEmpty()) {
            Hyb.text(toastDetail, tx + 6, ty + 15, left > 500 ? Hyb.MUTED : fade(Hyb.MUTED, left / 500f));
        }
    }

    private static int fade(final int argb, final float f) {
        return Math.max(4, (int) ((argb >>> 24) * f)) << 24 | argb & 0x00FFFFFF;
    }

    // endregion

    // region NEI's draw hooks

    @Override
    public void onPreDraw(final GuiContainer gui) {
        shown.clear();
        // The page went away under the menu.
        if (menuOn != null && Minecraft.getMinecraft().currentScreen != menuOn) close();
    }

    @Override
    public void renderObjects(final GuiContainer gui, final int mouseX, final int mouseY) {}

    @Override
    public void postRenderObjects(final GuiContainer gui, final int mouseX, final int mouseY) {
        final boolean menu = menuOpenOn(gui);
        final boolean note = toastOn == gui && System.currentTimeMillis() < toastUntil;
        if (!menu && !note) return;
        Tip.beginPanel();
        if (note && !menu) drawToast();
        if (menu) drawMenu(mouseX, mouseY);
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
        if (menuOpenOn(gui)) {
            final int row = rowAt(mouseX, mouseY);
            final Runnable action = row >= 0 && button == 0 ? rows.get(row).action : null;
            // Any click closes it; one outside does nothing else.
            close();
            if (action != null) action.run();
            return true;
        }
        if (button == 1) {
            for (final PlanButton b : shown) {
                if (mouseX >= b.screenX && mouseX < b.screenX + b.width
                    && mouseY >= b.screenY
                    && mouseY < b.screenY + b.height) {
                    offerPlans(b);
                    return true;
                }
            }
        }
        return false;
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

package com.gtnhplanner.ui.tutorial;

import java.util.List;
import java.util.function.Predicate;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.item.ItemStack;

import org.jetbrains.annotations.Nullable;

import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.widget.sizer.Area;
import com.gtnhplanner.data.flowchart.Drawer;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Note;
import com.gtnhplanner.nei.OpenFlowchartButton;
import com.gtnhplanner.nei.PlanButton;
import com.gtnhplanner.nei.PlanMenu;
import com.gtnhplanner.ui.BoardScreen;
import com.gtnhplanner.ui.Planner;
import com.gtnhplanner.ui.canvas.BoardCanvas;
import com.gtnhplanner.ui.card.CardLayout;
import com.gtnhplanner.ui.card.CardModel;
import com.gtnhplanner.ui.card.RecipeCard;
import com.gtnhplanner.ui.drawer.DrawerCard;
import com.gtnhplanner.ui.drawer.DrawerModel;
import com.gtnhplanner.ui.note.NoteCard;
import com.gtnhplanner.ui.popup.PickList;
import com.gtnhplanner.ui.popup.Popup;

import codechicken.nei.ItemPanels;
import codechicken.nei.LayoutManager;
import codechicken.nei.recipe.GuiRecipe;

/**
 * What the tour points at, by name rather than by pixel: looked up afresh every frame, so a target that moves (the
 * board panning, a menu opening) is followed, and one that is not there yet (a menu a tick from opening) is waited
 * for. Every rectangle is in GUI pixels.
 */
public final class Targets {

    private Targets() {}

    /** Something on screen, or null while it is not. */
    @FunctionalInterface
    public interface Target {

        @Nullable
        Rect rect();
    }

    private static Minecraft mc() {
        return Minecraft.getMinecraft();
    }

    static ScaledResolution resolution() {
        return new ScaledResolution(mc(), mc().displayWidth, mc().displayHeight);
    }

    /** A fixed rectangle. */
    public static Target at(final float x, final float y, final float w, final float h) {
        final Rect r = new Rect(x, y, w, h);
        return () -> r;
    }

    /** Part of another target: the fraction {@code fx, fy} across it, as a point. */
    public static Target point(final Target t, final float fx, final float fy) {
        return () -> {
            final Rect r = t.rect();
            return r == null ? null : new Rect(r.x() + r.w() * fx, r.y() + r.h() * fy, 0, 0);
        };
    }

    /** Several targets as one, round them all; null while any is missing. */
    public static Target around(final Target... ts) {
        return () -> {
            Rect out = null;
            quiet++;
            try {
                for (final Target t : ts) {
                    final Rect r = t.rect();
                    if (r == null) return null;
                    out = out == null ? r : out.union(r);
                }
            } finally {
                quiet--;
            }
            return out;
        };
    }

    // region NEI

    /** The planner's key on NEI's bar, bottom left of an inventory. */
    public static Target plannerButton() {
        return () -> {
            final OpenFlowchartButton b = OpenFlowchartButton.shown();
            return b == null || !(mc().currentScreen instanceof net.minecraft.client.gui.inventory.GuiContainer) ? null
                : new Rect(b.x, b.y, b.w, b.h);
        };
    }

    /** NEI's search field. */
    public static Target neiSearch() {
        return () -> {
            final codechicken.nei.SearchField f = LayoutManager.searchField;
            return f == null || f.w <= 0 || !f.isVisible() ? null : new Rect(f.x, f.y, f.w, f.h);
        };
    }

    /**
     * The first item in NEI's list that the first of {@code inOrder} accepts (else the second's, and so on), turning
     * its page to it.
     */
    @SafeVarargs
    public static Target neiItem(final Predicate<ItemStack>... inOrder) {
        return () -> {
            if (ItemPanels.itemPanel == null) return null;
            final codechicken.nei.ItemsGrid<?, ?> grid = ItemPanels.itemPanel.getGrid();
            final int i = neiIndex(grid.getItems(), inOrder);
            if (i < 0) return null;
            final int perPage = Math.max(1, grid.getPerPage());
            if (grid.getPage() != i / perPage) grid.setPage(i / perPage);
            final codechicken.lib.vec.Rectangle4i r = grid.getItemRect(i);
            return r == null || r.w <= 0 ? null : new Rect(r.x, r.y, r.w, r.h);
        };
    }

    /** Where the first item the first of {@code inOrder} accepts is in {@code items} (else the second's...); -1. */
    @SafeVarargs
    static int neiIndex(final List<ItemStack> items, final Predicate<ItemStack>... inOrder) {
        for (final Predicate<ItemStack> which : inOrder)
            for (int i = 0; i < items.size(); i++) if (which.test(items.get(i))) return i;
        return -1;
    }

    /** The open NEI recipe page, if one is. */
    @Nullable
    static GuiRecipe<?> recipePage() {
        return mc().currentScreen instanceof final GuiRecipe<?> g ? g : null;
    }

    /** The tab on NEI's recipe page for a handler, when it shows on the tab strip. */
    public static Target recipeTab(final Predicate<codechicken.nei.recipe.IRecipeHandler> which) {
        return () -> {
            final GuiRecipe<?> page = recipePage();
            if (page == null) return null;
            try {
                final java.lang.reflect.Field tabsField = GuiRecipe.class.getDeclaredField("recipeTabs");
                tabsField.setAccessible(true);
                final Object tabs = tabsField.get(page);
                // Declared on GuiRecipeTabs: the strip on the page can be a subclass of it.
                final java.lang.reflect.Field list = codechicken.nei.recipe.GuiRecipeTabs.class
                    .getDeclaredField("tabs");
                list.setAccessible(true);
                final java.lang.reflect.Field handler = codechicken.nei.recipe.GuiRecipeTab.class
                    .getDeclaredField("handler");
                handler.setAccessible(true);
                for (final Object o : (java.util.List<?>) list.get(tabs)) {
                    final codechicken.nei.recipe.GuiRecipeTab tab = (codechicken.nei.recipe.GuiRecipeTab) o;
                    if (which.test((codechicken.nei.recipe.IRecipeHandler) handler.get(tab)))
                        return new Rect(tab.x, tab.y, tab.w, tab.h);
                }
            } catch (final ReflectiveOperationException | RuntimeException e) {
                return null;
            }
            return null;
        };
    }

    /** The plan button on a recipe on NEI's page. */
    public static Target planButton(final codechicken.nei.recipe.IRecipeHandler handler, final int recipe) {
        return () -> {
            final PlanButton b = PlanButton.shownFor(handler, recipe);
            return b == null ? null : new Rect(b.screenX(), b.screenY(), b.width, b.height);
        };
    }

    /** A row of the plan button's menu, by its label. */
    public static Target planMenuRow(final String label) {
        return () -> Rect.of(PlanMenu.INSTANCE.rowRect(label));
    }

    // endregion

    // region The board

    @Nullable
    static BoardScreen board() {
        return Planner.screenOf(mc().currentScreen) instanceof final BoardScreen b ? b : null;
    }

    @Nullable
    private static BoardCanvas canvas() {
        final BoardScreen b = board();
        return b == null ? null : b.canvas();
    }

    @Nullable
    private static Rect area(final IWidget w) {
        if (w == null || !w.isValid() || !w.isEnabled()) return null;
        final Area a = w.getArea();
        return a.width <= 0 ? null : new Rect(a.x, a.y, a.width, a.height);
    }

    /** A key on the board's top bar, by its id (see {@link BoardScreen#key}). */
    public static Target topKey(final String id) {
        return () -> {
            final BoardScreen b = board();
            return b == null ? null : area(b.key(id));
        };
    }

    /** The board itself, the area cards sit in. */
    public static Target canvasArea() {
        return () -> area(canvas());
    }

    /** A point on the board, in world coordinates. */
    public static Target world(final float x, final float y) {
        return () -> {
            final BoardCanvas c = canvas();
            return c == null ? null : new Rect(c.screenX(x), c.screenY(y), 0, 0);
        };
    }

    /** While above zero, targets looked up do not pan the board to themselves (several looked up as one). */
    private static int quiet;

    /**
     * A world-space rectangle on the board, when it is in view: one off the board's visible part (or under the tour's
     * bar along its bottom) pans the board to it, and reads as not there yet, so a step waits for it.
     */
    @Nullable
    static Rect worldRect(final BoardCanvas c, final float x, final float y, final float w, final float h) {
        final int x0 = c.screenX(x), y0 = c.screenY(y);
        final Rect r = new Rect(x0, y0, c.screenX(x + w) - x0, c.screenY(y + h) - y0);
        if (quiet > 0 || inView(c, r)) return r;
        c.bringIntoView(Math.round(x) - 30, Math.round(y) - 30, Math.round(x + w) + 30, Math.round(y + h) + 30);
        return null;
    }

    /** Whether a rectangle sits on the visible board, clear of its edges. */
    private static boolean inView(final BoardCanvas c, final Rect r) {
        final Area a = c.getArea();
        return r.x() >= a.x + 4 && r.y() >= a.y + 4
            && r.right() <= a.x + a.width - 4
            && r.bottom() <= a.y + a.height - 4;
    }

    /**
     * What a callout should keep off, as screen rectangles: the board's cards, drawers and notes, its open popups, and
     * the overview.
     */
    static List<Rect> content() {
        final List<Rect> out = new java.util.ArrayList<>();
        final BoardCanvas c = canvas();
        if (c == null) return out;
        for (final RecipeCard card : c.cards()
            .values()) {
            if (card.model() == null || card.layout() == null) continue;
            final int[] r = card.partRect(RecipeCard.Part.BODY);
            if (r != null) out.add(onScreen(c, card.model().node.x + r[0], card.model().node.y + r[1], r[2], r[3]));
        }
        for (final DrawerCard d : c.drawers()
            .values()) {
            if (d.model() == null) continue;
            final int[] r = d.partRect(DrawerCard.Part.BODY);
            final Drawer dr = d.model().drawer;
            if (r != null) out.add(onScreen(c, dr.getX() + r[0], dr.getY() + r[1], r[2], r[3]));
        }
        for (final NoteCard n : c.notes()
            .values()) {
            final Note note = n.note();
            if (note == null) continue;
            final int[] r = n.partRect(NoteCard.Part.BODY);
            out.add(onScreen(c, note.getX() + r[0], note.getY() + r[1], r[2], r[3]));
        }
        for (final ModularPanel p : popups()) {
            final Rect r = area(p);
            if (r != null) out.add(r);
        }
        final Rect rail = boardPart("rail:all").rect();
        if (rail != null) out.add(rail);
        return out;
    }

    private static Rect onScreen(final BoardCanvas c, final float x, final float y, final float w, final float h) {
        final int x0 = c.screenX(x), y0 = c.screenY(y);
        return new Rect(x0, y0, c.screenX(x + w) - x0, c.screenY(y + h) - y0);
    }

    /** The first card whose node {@code which} accepts. */
    @Nullable
    static RecipeCard card(final Predicate<Node> which) {
        final BoardCanvas c = canvas();
        if (c == null) return null;
        for (final RecipeCard card : c.cards()
            .values()) {
            if (card.model() != null && which.test(card.model().node)) return card;
        }
        return null;
    }

    /** A card, whole. */
    public static Target card(final Predicate<Node> which, final RecipeCard.Part part) {
        return () -> {
            final BoardCanvas c = canvas();
            final RecipeCard card = card(which);
            if (c == null || card == null || card.layout() == null) return null;
            final int[] r = card.partRect(part);
            if (r == null) return null;
            final Node n = card.model().node;
            return worldRect(c, n.x + r[0], n.y + r[1], r[2], r[3]);
        };
    }

    /** A port's icon on a card: an input or an output, by its resource's name. */
    public static Target port(final Predicate<Node> which, final boolean output, final String name) {
        return () -> {
            final BoardCanvas c = canvas();
            final RecipeCard card = card(which);
            if (c == null || card == null || card.layout() == null) return null;
            final CardModel m = card.model();
            for (final CardModel.PortView p : output ? m.outputs : m.inputs) {
                if (!p.name()
                    .toLowerCase(java.util.Locale.ROOT)
                    .contains(name.toLowerCase(java.util.Locale.ROOT))) continue;
                final int lx = CardLayout.iconX(output), ly = card.layout()
                    .rowY(output, p.index()) + CardLayout.ICON_Y;
                return worldRect(c, m.node.x + lx, m.node.y + ly, CardLayout.ICON, CardLayout.ICON);
            }
            return null;
        };
    }

    /** A point beside a card, in board units from its top-left corner. */
    public static Target nearCard(final Predicate<Node> which, final float dx, final float dy) {
        return () -> {
            final BoardCanvas c = canvas();
            final RecipeCard card = card(which);
            if (c == null || card == null) return null;
            final Node n = card.model().node;
            return new Rect(c.screenX(n.x + dx), c.screenY(n.y + dy), 0, 0);
        };
    }

    /** The middle of a target moved by board units (scaled by the board's zoom), as a point. */
    public static Target shift(final Target t, final float dx, final float dy) {
        return () -> {
            final Rect r = t.rect();
            final BoardScreen b = board();
            if (r == null) return null;
            final float z = b == null ? 1
                : b.session()
                    .graph()
                    .getZoom();
            final Rect at = new Rect(r.cx() + dx * z, r.cy() + dy * z, 0, 0);
            final BoardCanvas c = canvas();
            if (c == null || quiet > 0 || inView(c, at.grow(12))) return at;
            // A point off the visible board: the board pans to it first.
            final float wx = c.worldX(Math.round(at.x())), wy = c.worldY(Math.round(at.y()));
            c.bringIntoView(Math.round(wx) - 40, Math.round(wy) - 40, Math.round(wx) + 40, Math.round(wy) + 40);
            return null;
        };
    }

    /**
     * A point on empty board with room for a {@code w} by {@code h} board-unit box below and right of it, as near the
     * middle of the view as there is: where a note or a dropped item goes without landing on anything.
     */
    public static Target emptyBoard(final float w, final float h) {
        return emptyBoard(w, h, -1, -1);
    }

    /**
     * As {@link #emptyBoard(float, float)}, but the point {@code fx, fy} of the way across the free box (to drop
     * something held by that point of itself, so it lands in the box); negative for its top-left, a little in.
     */
    public static Target emptyBoard(final float w, final float h, final float fx, final float fy) {
        return () -> {
            final BoardScreen b = board();
            final BoardCanvas c = canvas();
            if (b == null || c == null) return null;
            final com.gtnhplanner.data.flowchart.Graph g = b.session()
                .graph();
            final Area a = c.getArea();
            final List<float[]> boxes = new java.util.ArrayList<>();
            for (final RecipeCard card : c.cards()
                .values()) {
                if (card.model() == null || card.layout() == null) continue;
                final Node n = card.model().node;
                boxes.add(new float[] { n.x, n.y, CardLayout.W, card.layout().height });
            }
            for (final Drawer d : g.getDrawers())
                boxes.add(new float[] { d.getX(), d.getY(), DrawerCard.W, DrawerCard.H });
            for (final Note n : g.getNotes())
                boxes.add(new float[] { n.getX(), n.getY(), n.getWidth(), n.getHeight() });
            final float x0 = c.worldX(a.x + 16), y0 = c.worldY(a.y + 50);
            final float x1 = c.worldX(a.x + a.width - 16) - w, y1 = c.worldY(a.y + a.height - 70) - h;
            final float mx = c.worldX(a.x + a.width / 2), my = c.worldY(a.y + a.height / 2);
            float bestX = 0, bestY = 0, best = Float.MAX_VALUE;
            for (float x = x0; x <= x1; x += 20) {
                for (float y = y0; y <= y1; y += 20) {
                    boolean clear = true;
                    for (final float[] o : boxes) {
                        if (x - 20 < o[0] + o[2] && o[0] < x + w + 20 && y - 20 < o[1] + o[3] && o[1] < y + h + 20) {
                            clear = false;
                            break;
                        }
                    }
                    if (!clear) continue;
                    final float dist = (float) Math.hypot(x + w / 2 - mx, y + h / 2 - my);
                    if (dist < best) {
                        best = dist;
                        bestX = x;
                        bestY = y;
                    }
                }
            }
            if (best == Float.MAX_VALUE) return null;
            if (fx < 0) return new Rect(c.screenX(bestX + 10), c.screenY(bestY + 10), 0, 0);
            return new Rect(c.screenX(bestX + fx * w), c.screenY(bestY + fy * h), 0, 0);
        };
    }

    /**
     * Where to drop a port so its new drawer lands on empty board as near as there is to {@code dx, dy} board units
     * from the port: an output's drawer goes right of the drop point, an input's left of it.
     */
    public static Target freeNear(final Target port, final boolean output, final float dx, final float dy) {
        return () -> {
            final BoardScreen b = board();
            final BoardCanvas c = canvas();
            final Rect p = port.rect();
            if (b == null || c == null || p == null) return null;
            final com.gtnhplanner.data.flowchart.Graph g = b.session()
                .graph();
            final List<float[]> boxes = new java.util.ArrayList<>();
            for (final RecipeCard card : c.cards()
                .values()) {
                if (card.model() == null || card.layout() == null) continue;
                final Node n = card.model().node;
                boxes.add(new float[] { n.x, n.y, CardLayout.W, card.layout().height });
            }
            for (final Drawer d : g.getDrawers())
                boxes.add(new float[] { d.getX(), d.getY(), DrawerCard.W, DrawerCard.H });
            for (final Note n : g.getNotes())
                boxes.add(new float[] { n.getX(), n.getY(), n.getWidth(), n.getHeight() });
            final float wx = c.worldX(Math.round(p.cx())) + dx, wy = c.worldY(Math.round(p.cy())) + dy;
            float bestX = 0, bestY = 0, best = Float.MAX_VALUE;
            for (float x = wx - 400; x <= wx + 400; x += 10) {
                for (float y = wy - 300; y <= wy + 300; y += 10) {
                    final float left = output ? x : x - DrawerCard.W, top = y - DrawerCard.ANCHOR_Y;
                    boolean clear = true;
                    for (final float[] o : boxes) {
                        if (left - 16 < o[0] + o[2] && o[0] < left + DrawerCard.W + 16
                            && top - 16 < o[1] + o[3]
                            && o[1] < top + DrawerCard.H + 16) {
                            clear = false;
                            break;
                        }
                    }
                    if (!clear) continue;
                    final float dist = (float) Math.hypot(x - wx, y - wy);
                    if (dist < best) {
                        best = dist;
                        bestX = x;
                        bestY = y;
                    }
                }
            }
            if (best == Float.MAX_VALUE) return null;
            final Rect at = new Rect(c.screenX(bestX), c.screenY(bestY), 0, 0);
            if (quiet > 0 || inView(c, at.grow(12))) return at;
            c.bringIntoView(
                Math.round(bestX) - 60,
                Math.round(bestY) - 40,
                Math.round(bestX) + 60,
                Math.round(bestY) + 40);
            return null;
        };
    }

    /** A drawer, by its resource's label, and a part of it. */
    public static Target drawer(final String label, final DrawerCard.Part part) {
        return () -> {
            final BoardCanvas c = canvas();
            if (c == null) return null;
            for (final DrawerCard d : c.drawers()
                .values()) {
                final DrawerModel m = d.model();
                if (m == null || !m.label.toLowerCase(java.util.Locale.ROOT)
                    .contains(label.toLowerCase(java.util.Locale.ROOT))) continue;
                final int[] r = d.partRect(part);
                if (r == null) return null;
                final Drawer dr = m.drawer;
                return worldRect(c, dr.getX() + r[0], dr.getY() + r[1], r[2], r[3]);
            }
            return null;
        };
    }

    /** A sticky note (the first whose text has {@code text}, or any for null), and a part of it. */
    public static Target note(@Nullable final String text, final NoteCard.Part part) {
        return () -> {
            final BoardCanvas c = canvas();
            if (c == null) return null;
            for (final NoteCard card : c.notes()
                .values()) {
                final Note n = card.note();
                if (n == null || text != null && !n.joined()
                    .contains(text)) continue;
                final int[] r = card.partRect(part);
                return worldRect(c, n.getX() + r[0], n.getY() + r[1], r[2], r[3]);
            }
            return null;
        };
    }

    /** The widget the board's screen offers for an id (rail rows, tabs, settings rows...). */
    public static Target boardPart(final String id) {
        return () -> {
            final BoardScreen b = board();
            return b == null ? null : Rect.of(b.partRect(id));
        };
    }

    // endregion

    // region Popups

    /** A row of an open popup list, by its label (the first that contains it). */
    public static Target popupRow(final String label) {
        return () -> {
            final BoardScreen b = board();
            if (b == null) return null;
            for (final ModularPanel p : b.getPanelManager()
                .getOpenPanels()) {
                if (!(p instanceof Popup)) continue;
                for (final IWidget w : p.getChildren()) {
                    if (w instanceof final PickList list) {
                        final int[] r = list.rowRect(label);
                        if (r != null) return Rect.of(r);
                    }
                    if (w instanceof final com.gtnhplanner.ui.popup.SettingsPanel settings) {
                        final int[] r = settings.rowRect(label);
                        if (r != null) return Rect.of(r);
                    }
                }
            }
            return null;
        };
    }

    /** The newest open popup, whole. */
    public static Target popup() {
        return () -> {
            final List<ModularPanel> open = popups();
            return open.isEmpty() ? null : area(open.get(open.size() - 1));
        };
    }

    /** The board's open popups, oldest first. */
    static List<ModularPanel> popups() {
        final List<ModularPanel> out = new java.util.ArrayList<>();
        final BoardScreen b = board();
        if (b == null) return out;
        for (final ModularPanel p : b.getPanelManager()
            .getOpenPanels()) if (p instanceof Popup && p.isOpen()) out.add(p);
        return out;
    }

    // endregion

    /** What a row of the open settings shows now; null when the settings are shut. */
    @Nullable
    static String setting(final String label) {
        final BoardScreen b = board();
        if (b == null) return null;
        for (final ModularPanel p : b.getPanelManager()
            .getOpenPanels()) {
            if (!(p instanceof Popup)) continue;
            for (final IWidget w : p.getChildren())
                if (w instanceof final com.gtnhplanner.ui.popup.SettingsPanel settings) return settings.value(label);
        }
        return null;
    }

    /** The minimap on the screen, while it shows. */
    public static Target minimap() {
        return () -> Rect.of(com.gtnhplanner.ui.world.Minimap.bounds(resolution()));
    }

    /** The whole screen. */
    public static Target screen() {
        return () -> {
            final ScaledResolution sr = resolution();
            return new Rect(0, 0, sr.getScaledWidth(), sr.getScaledHeight());
        };
    }

    /** Whether the open screen is {@code type}. */
    static boolean showing(final Class<? extends GuiScreen> type) {
        return type.isInstance(mc().currentScreen);
    }
}

package com.sbancuz.plannh.ui.canvas;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.item.ItemStack;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.cleanroommc.modularui.api.UpOrDown;
import com.cleanroommc.modularui.api.layout.IViewport;
import com.cleanroommc.modularui.api.layout.IViewportStack;
import com.cleanroommc.modularui.api.widget.IDraggable;
import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.drawable.Stencil;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.widget.ParentWidget;
import com.cleanroommc.modularui.widget.sizer.Area;
import com.sbancuz.plannh.data.flowchart.Drawer;
import com.sbancuz.plannh.data.flowchart.Edge;
import com.sbancuz.plannh.data.flowchart.Graph;
import com.sbancuz.plannh.data.flowchart.Node;
import com.sbancuz.plannh.nei.NodeLookupContext;
import com.sbancuz.plannh.ui.BoardSession;
import com.sbancuz.plannh.ui.card.CardLayout;
import com.sbancuz.plannh.ui.card.CardModel;
import com.sbancuz.plannh.ui.card.RecipeCard;
import com.sbancuz.plannh.ui.drawer.DrawerCard;
import com.sbancuz.plannh.ui.drawer.DrawerModel;
import com.sbancuz.plannh.ui.popup.PickList;
import com.sbancuz.plannh.ui.popup.Popup;
import com.sbancuz.plannh.ui.popup.RecipePicker;
import com.sbancuz.plannh.ui.theme.Hyb;

/**
 * The board: pans by dragging empty space (left or middle button), zooms with the wheel around the cursor, and holds
 * one {@link RecipeCard} per recipe in world space. Zoom moves in whole steps so the game font stays sharp.
 */
public final class BoardCanvas extends ParentWidget<BoardCanvas> implements Interactable, IViewport, IDraggable {

    /** Steps where the font is sharp at GUI scale 2 (1 font pixel lands on whole screen pixels), plus a far view. */
    private static final float[] ZOOMS = { 0.25f, 0.5f, 1f, 1.5f, 2f };
    private static final int GRID = 20;

    private final BoardSession session;
    private final ModularPanel panel;
    private final Map<UUID, RecipeCard> cards = new HashMap<>();
    private final Map<UUID, DrawerCard> drawers = new HashMap<>();
    private final WireLayer wires;
    private int builtStructure = -1;

    private float panStartX, panStartY;
    private int panMouseX, panMouseY;
    private boolean panning;

    public BoardCanvas(final BoardSession session, final ModularPanel panel) {
        this.session = session;
        this.panel = panel;
        this.wires = new WireLayer(session);
    }

    private Graph graph() {
        return session.graph();
    }

    @Override
    public void onUpdate() {
        super.onUpdate();
        session.tick();
        Popup.openPending();
        boolean rebuild = builtStructure != session.structure();
        for (final RecipeCard card : cards.values()) rebuild |= card.shapeChanged();
        if (rebuild) rebuildCards();
        if (reveal != null && cards.containsKey(reveal)) {
            reveal(cards.get(reveal));
            reveal = null;
        }
    }

    private void rebuildCards() {
        builtStructure = session.structure();
        removeAll();
        cards.clear();
        drawers.clear();
        // Drawers first so cards draw over them where they overlap.
        for (final DrawerModel model : session.drawerModels()
            .values()) {
            final DrawerCard drawer = new DrawerCard(session, model.drawer.getId());
            drawers.put(model.drawer.getId(), drawer);
            child(drawer);
        }
        for (final CardModel model : session.models()
            .values()) {
            final RecipeCard card = new RecipeCard(session, model.node.id);
            cards.put(model.node.id, card);
            child(card);
        }
        scheduleResize();
    }

    public Map<UUID, RecipeCard> cards() {
        return cards;
    }

    public Map<UUID, DrawerCard> drawers() {
        return drawers;
    }

    /** Screen x of a board (world) x, through pan and zoom. */
    public int screenX(final float worldX) {
        return Math.round(getArea().x + graph().getPanX() + worldX * graph().getZoom());
    }

    public int screenY(final float worldY) {
        return Math.round(getArea().y + graph().getPanY() + worldY * graph().getZoom());
    }

    /** Board (world) x under a screen x. */
    public float worldX(final int screenX) {
        return (screenX - getArea().x - graph().getPanX()) / graph().getZoom();
    }

    public float worldY(final int screenY) {
        return (screenY - getArea().y - graph().getPanY()) / graph().getZoom();
    }

    // region Drawing

    @Override
    public void draw(final ModularGuiContext context, final WidgetThemeEntry<?> widgetTheme) {
        final Area a = getArea();
        Hyb.rect(0, 0, a.width, a.height, Hyb.CANVAS);
        final float zoom = graph().getZoom();
        final float step = GRID * zoom;
        if (step >= 6) {
            final float ox = mod(graph().getPanX(), step), oy = mod(graph().getPanY(), step);
            final float dot = zoom >= 1 ? 2 : 1;
            for (float x = ox; x < a.width; x += step) {
                for (float y = oy; y < a.height; y += step) Hyb.rect(x, y, dot, dot, Hyb.CANVAS_DOT);
            }
        }
    }

    private static float mod(final float v, final float m) {
        final float r = v % m;
        return r < 0 ? r + m : r;
    }

    @Override
    public void transformChildren(final IViewportStack stack) {
        stack.translate(graph().getPanX(), graph().getPanY());
        stack.scale(graph().getZoom(), graph().getZoom());
    }

    @Override
    public void preDraw(final ModularGuiContext context, final boolean transformed) {
        if (!transformed) {
            Stencil.applyAtZero(getArea(), context);
            return;
        }
        // World space, under the cards.
        wires.draw(wires.wires(cards, drawers));
        if (portDrag != null) drawPortDrag();
    }

    @Override
    public void postDraw(final ModularGuiContext context, final boolean transformed) {
        if (!transformed) Stencil.remove();
    }

    // endregion

    // region Wiring by dragging a port

    /** A wire being dragged out of a port. */
    private record PortDrag(UUID nodeId, boolean output, int port, int startX, int startY) {}

    private PortDrag portDrag;

    /** Called by a port slot when the mouse goes down on it and starts to drag. */
    public void beginPortDrag(final UUID nodeId, final boolean output, final int port) {
        portDrag = new PortDrag(nodeId, output, port, getContext().getAbsMouseX(), getContext().getAbsMouseY());
    }

    /**
     * Ends a port drag where the mouse is: on another card it wires the matching port, on a drawer it links, on empty
     * board it makes a drawer. A drag that never left the port does nothing.
     */
    public void endPortDrag(final boolean successful) {
        final PortDrag drag = portDrag;
        portDrag = null;
        if (drag == null) return;
        final int mx = getContext().getAbsMouseX(), my = getContext().getAbsMouseY();
        if (Math.abs(mx - drag.startX()) + Math.abs(my - drag.startY()) < 4) {
            // A click, not a drag: what makes this input, or what uses this output.
            openPortPicker(drag);
            return;
        }
        if (!successful) return;
        final float wx = worldX(mx), wy = worldY(my);
        for (final RecipeCard card : cards.values()) {
            if (card.model() == null
                || !inside(card.model().node.x, card.model().node.y, CardLayout.W, card.layout().height, wx, wy))
                continue;
            if (!card.nodeId.equals(drag.nodeId()))
                session.dropPortOnCard(drag.nodeId(), drag.output(), drag.port(), card.nodeId);
            return;
        }
        for (final DrawerCard drawer : drawers.values()) {
            if (drawer.model() == null) continue;
            final Drawer d = drawer.model().drawer;
            if (!inside(d.getX(), d.getY(), DrawerCard.W, DrawerCard.H, wx, wy)) continue;
            session.dropPortOnDrawer(drag.nodeId(), drag.output(), drag.port(), d);
            return;
        }
        session.dropPortOnBoard(drag.nodeId(), drag.output(), drag.port(), Math.round(wx), Math.round(wy));
    }

    /** A click on a port: "What makes this?" for an input, "What uses this?" for an output. */
    public void clickPort(final UUID nodeId, final boolean output, final int port) {
        openPortPicker(new PortDrag(nodeId, output, port, 0, 0));
    }

    private void openPortPicker(final PortDrag drag) {
        final RecipeCard card = cards.get(drag.nodeId());
        if (card == null || card.model() == null) return;
        final List<CardModel.PortView> ports = drag.output() ? card.model().outputs : card.model().inputs;
        if (drag.port() >= ports.size()) return;
        final ItemStack stack = ports.get(drag.port())
            .lookupStack();
        if (stack == null) return;
        openRecipePicker(stack.copy(), drag.output(), new NodeLookupContext(drag.nodeId(), drag.output(), drag.port()));
    }

    /**
     * Opens "What makes this?" ({@code uses} false) or "What uses this?" for an item. With an origin port, the recipe
     * picked lands beside that card and wires only that resource into it; without one it is auto-wired.
     */
    public void openRecipePicker(final ItemStack stack, final boolean uses, @Nullable final NodeLookupContext origin) {
        final Popup picker = RecipePicker.create(stack, uses, (handler, index) -> {
            if (origin != null) session.armLookup(origin.nodeId(), origin.output(), origin.portIndex());
            reveal = session.addRecipe(handler, index).id;
        });
        final int x, y;
        if (origin != null && cards.get(origin.nodeId()) != null) {
            final Node n = cards.get(origin.nodeId())
                .model().node;
            x = screenX(n.x + CardLayout.anchorX(origin.output())) + (origin.output() ? 4 : -RecipePicker.ROW * 16);
            y = screenY(n.y + CardLayout.anchorY(origin.portIndex()));
        } else {
            x = getArea().x + getArea().width / 2 - 180;
            y = getArea().y + 30;
        }
        if (picker != null) {
            Popup.open(getPanel(), picker, x, y);
            return;
        }
        final String name = stack.getDisplayName();
        Popup.open(
            getPanel(),
            PickList.popup(
                "plannh_none",
                null,
                List.of(
                    new PickList.Entry(
                        stack,
                        (uses ? "Nothing uses " : "Nothing makes ") + name,
                        "",
                        Hyb.MUTED,
                        false,
                        () -> {})),
                false,
                160),
            x,
            y);
    }

    private static boolean inside(final int x, final int y, final int w, final int h, final float px, final float py) {
        return px >= x && py >= y && px < x + w && py < y + h;
    }

    private void drawPortDrag() {
        final RecipeCard card = cards.get(portDrag.nodeId());
        if (card == null || card.model() == null) return;
        final Node n = card.model().node;
        final int sx = n.x + CardLayout.anchorX(portDrag.output());
        final int sy = n.y + CardLayout.anchorY(portDrag.port());
        final int mx = Math.round(worldX(getContext().getAbsMouseX()));
        final int my = Math.round(worldY(getContext().getAbsMouseY()));
        final List<int[]> path = portDrag.output()
            ? List.of(
                new int[] { sx, sy },
                new int[] { (sx + mx) / 2, sy },
                new int[] { (sx + mx) / 2, my },
                new int[] { mx, my })
            : List.of(
                new int[] { mx, my },
                new int[] { (sx + mx) / 2, my },
                new int[] { (sx + mx) / 2, sy },
                new int[] { sx, sy });
        WireLayer.drawWire(path, 0xFFE8E9EE, 2, true);
    }

    // endregion

    /**
     * An item dragged in from NEI's list and dropped on the board: offers it as a drawer there (source or
     * product), or its recipes.
     */
    public void dropNeiItem(final ItemStack stack, final int screenX, final int screenY) {
        final int wx = Math.round(worldX(screenX)), wy = Math.round(worldY(screenY));
        final String key = com.sbancuz.plannh.ui.Resources.keyOf(stack);
        final String label = stack.getDisplayName();
        final List<PickList.Entry> rows = new ArrayList<>();
        rows.add(
            new PickList.Entry(
                stack,
                "Add as a product",
                "",
                Hyb.PRODUCT_INK,
                false,
                () -> session.addDrawer(Drawer.Kind.PRODUCT, key, label, wx, wy - DrawerCard.ANCHOR_Y)));
        rows.add(
            new PickList.Entry(
                stack,
                "Add as a source",
                "",
                Hyb.SOURCE_INK,
                false,
                () -> session.addDrawer(Drawer.Kind.SOURCE, key, label, wx - DrawerCard.W, wy - DrawerCard.ANCHOR_Y)));
        rows.add(PickList.Entry.of("What makes this?", () -> openRecipePicker(stack, false, null)));
        rows.add(PickList.Entry.of("What uses this?", () -> openRecipePicker(stack, true, null)));
        Popup.open(getPanel(), PickList.popup("plannh_drop", null, rows, false, 150), screenX, screenY);
    }

    // region Show me

    /** A card just added from the picker; brought into view once its widget exists. */
    private UUID reveal;

    /** Pans the least needed to show a whole card (with a margin), keeping the zoom. */
    private void reveal(final RecipeCard card) {
        if (card.model() == null || card.layout() == null) return;
        final Node n = card.model().node;
        final Graph g = graph();
        final float z = g.getZoom(), m = 16;
        final float left = g.getPanX() + n.x * z, right = g.getPanX() + (n.x + CardLayout.W) * z;
        final float top = g.getPanY() + n.y * z, bottom = g.getPanY() + (n.y + card.layout().height) * z;
        final int w = getArea().width, h = getArea().height;
        if (left < m) g.setPanX(g.getPanX() + m - left);
        else if (right > w - m) g.setPanX(g.getPanX() - Math.min(right - (w - m), left - m));
        if (top < m) g.setPanY(g.getPanY() + m - top);
        else if (bottom > h - m) g.setPanY(g.getPanY() - Math.min(bottom - (h - m), top - m));
    }

    /** Frames every card and drawer on the board. */
    public void frameAll() {
        final List<UUID> ids = new ArrayList<>(cards.keySet());
        ids.addAll(drawers.keySet());
        frame(ids);
    }

    /** Pans and zooms so the given cards and drawers fill the view, at the largest whole zoom step that fits. */
    public void frame(final List<UUID> ids) {
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE;
        for (final UUID id : ids) {
            final RecipeCard card = cards.get(id);
            if (card != null && card.model() != null) {
                final Node n = card.model().node;
                x0 = Math.min(x0, n.x);
                y0 = Math.min(y0, n.y);
                x1 = Math.max(x1, n.x + CardLayout.W);
                y1 = Math.max(y1, n.y + card.layout().height);
            }
            final DrawerCard drawer = drawers.get(id);
            if (drawer != null && drawer.model() != null) {
                final Drawer d = drawer.model().drawer;
                x0 = Math.min(x0, d.getX());
                y0 = Math.min(y0, d.getY());
                x1 = Math.max(x1, d.getX() + DrawerCard.W);
                y1 = Math.max(y1, d.getY() + DrawerCard.H);
            }
        }
        if (x0 == Integer.MAX_VALUE) return;
        final int margin = 24;
        final float fitW = getArea().width / (float) (x1 - x0 + 2 * margin);
        final float fitH = getArea().height / (float) (y1 - y0 + 2 * margin);
        float zoom = ZOOMS[0];
        for (final float z : ZOOMS) if (z <= Math.min(Math.min(fitW, fitH), 1.5f)) zoom = z;
        final Graph g = graph();
        g.setZoom(zoom);
        g.setPanX(getArea().width / 2f - (x0 + x1) / 2f * zoom);
        g.setPanY(getArea().height / 2f - (y0 + y1) / 2f * zoom);
    }

    // endregion

    // region Right-click on a wire

    private boolean openWireMenu() {
        final float wx = worldX(getContext().getAbsMouseX()), wy = worldY(getContext().getAbsMouseY());
        final WireLayer.Wire wire = wires.hit(wx, wy);
        if (wire == null) return false;
        final List<PickList.Entry> rows = new ArrayList<>();
        final int px = Math.round(wx), py = Math.round(wy);
        if (wire.kind() == WireLayer.Kind.EDGE) {
            final Edge edge = wire.edge();
            rows.add(
                PickList.Entry.of(
                    "Add a product here (takes the surplus)",
                    () -> session.addDrawerOnEdge(edge, Drawer.Kind.PRODUCT, px, py)));
            rows.add(
                PickList.Entry.of(
                    "Add a source here (brings in more)",
                    () -> session.addDrawerOnEdge(edge, Drawer.Kind.SOURCE, px, py)));
            rows.add(
                new PickList.Entry(null, "Delete wire", "", Hyb.RED_INK, false, () -> session.deleteEdge(edge.id)));
        } else {
            final Drawer drawer = wire.drawer();
            final Drawer.Link link = wire.link();
            rows.add(
                new PickList.Entry(
                    null,
                    "Delete wire",
                    "",
                    Hyb.RED_INK,
                    false,
                    () -> session.unlinkDrawer(drawer, link)));
        }
        Popup.open(
            getPanel(),
            PickList.popup("plannh_wire", null, rows, false, 220),
            getContext().getAbsMouseX(),
            getContext().getAbsMouseY());
        return true;
    }

    // endregion

    // region Pan and zoom

    @Override
    public Result onMousePressed(final int mouseButton) {
        if (mouseButton == 1) return openWireMenu() ? Result.SUCCESS : Result.IGNORE;
        return mouseButton == 0 || mouseButton == 2 ? Result.ACCEPT : Result.IGNORE;
    }

    @Override
    public boolean onMouseScroll(final UpOrDown direction, final int amount) {
        final Graph g = graph();
        final float old = g.getZoom();
        int i = nearestZoom(old);
        i = Math.max(0, Math.min(ZOOMS.length - 1, i + (direction == UpOrDown.UP ? 1 : -1)));
        final float next = ZOOMS[i];
        if (next == old) return true;
        final float ratio = next / old;
        final float mx = getContext().getAbsMouseX() - getArea().x;
        final float my = getContext().getAbsMouseY() - getArea().y;
        g.setZoom(next);
        g.setPanX(mx - (mx - g.getPanX()) * ratio);
        g.setPanY(my - (my - g.getPanY()) * ratio);
        return true;
    }

    private static int nearestZoom(final float zoom) {
        int best = 0;
        for (int i = 1; i < ZOOMS.length; i++) {
            if (Math.abs(ZOOMS[i] - zoom) < Math.abs(ZOOMS[best] - zoom)) best = i;
        }
        return best;
    }

    @Override
    public boolean onDragStart(final int button) {
        if (button != 0 && button != 2) return false;
        panStartX = graph().getPanX();
        panStartY = graph().getPanY();
        panMouseX = getContext().getAbsMouseX();
        panMouseY = getContext().getAbsMouseY();
        return true;
    }

    @Override
    public void onDrag(final int mouseButton, final long timeSinceLastClick) {
        graph().setPanX(panStartX + getContext().getAbsMouseX() - panMouseX);
        graph().setPanY(panStartY + getContext().getAbsMouseY() - panMouseY);
    }

    @Override
    public void onDragEnd(final boolean successful) {}

    @Override
    public void drawMovingState(final ModularGuiContext context, final float partialTicks) {}

    @Override
    public @Nullable Area getMovingArea() {
        return null;
    }

    @Override
    public boolean isMoving() {
        return panning;
    }

    @Override
    public void setMoving(final boolean moving) {
        panning = moving;
    }

    // endregion

    @Override
    public @NotNull ModularPanel getPanel() {
        return panel;
    }
}

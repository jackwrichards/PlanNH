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
import com.cleanroommc.modularui.api.widget.IWidget;
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
import com.sbancuz.plannh.layout.AutoLayout;
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
        stepCamera();
        stepGlide();
        session.setHoverKey(hoveredResource());
        final Area a = getArea();
        Hyb.rect(0, 0, a.width, a.height, Hyb.CANVAS);
        if (cards.isEmpty() && drawers.isEmpty()) {
            // An empty board says how to start.
            Hyb.textCentered("Nothing here yet.", a.width / 2f, a.height / 2f - 14, Hyb.MUTED);
            Hyb.textCentered("Press P over any item for its recipes,", a.width / 2f, a.height / 2f, 0xFF6A6C74);
            Hyb.textCentered("or + on an NEI recipe page.", a.width / 2f, a.height / 2f + 11, 0xFF6A6C74);
        }
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

    /** The resource under the mouse: a port, a drawer, or a wire. Every place it flows then glows. */
    private String hoveredResource() {
        final com.cleanroommc.modularui.api.widget.IWidget hovered = getContext().getHovered();
        if (hovered instanceof final com.sbancuz.plannh.ui.card.PortSlot slot) {
            return slot.view() == null ? null
                : slot.view()
                    .key();
        }
        if (hovered instanceof final DrawerCard drawer) {
            return drawer.model() == null ? null
                : drawer.model().drawer.getResourceKey();
        }
        if (hovered != null && hovered != this) return null;
        final WireLayer.Wire wire = wires.hit(worldX(getContext().getAbsMouseX()), worldY(getContext().getAbsMouseY()));
        return wire == null ? null : wire.resource();
    }

    private static float mod(final float v, final float m) {
        final float r = v % m;
        return r < 0 ? r + m : r;
    }

    /**
     * Cards and drawers are only under the mouse when the mouse is on the board: the board clips what it draws to its
     * area, but ModularUI would otherwise still find a card panned under the overview rail.
     */
    @Override
    public void getWidgetsAt(final IViewportStack stack,
        final com.cleanroommc.modularui.utils.HoveredWidgetList widgets, final int x, final int y) {
        final Area a = getArea();
        final int mx = getContext().getAbsMouseX(), my = getContext().getAbsMouseY();
        if (mx < a.x || my < a.y || mx >= a.x + a.width || my >= a.y + a.height) return;
        IViewport.super.getWidgetsAt(stack, widgets, x, y);
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
        wires.draw(wires.wires(cards, drawers), session.hoverKey());
    }

    @Override
    public void postDraw(final ModularGuiContext context, final boolean transformed) {
        if (transformed) {
            // Over the cards: the wire in hand and the selection box.
            if (portDrag != null) drawPortDrag();
            drawBox();
        } else Stencil.remove();
    }

    // endregion

    // region Moving cards and drawers, and box selection

    private java.util.Map<UUID, int[]> moveStart;
    private UUID moveAnchor;
    private int moveMouseX, moveMouseY, moveDX, moveDY;
    private String moveUndo;

    /**
     * A card or drawer body was pressed and is being dragged: it moves, with the rest of the selection when it is
     * selected. Offsets snap to the grid as a whole, so things keep their alignment to each other.
     */
    public void beginMove(final UUID id) {
        final java.util.List<UUID> ids = new ArrayList<>();
        if (session.isSelected(id)) {
            for (final UUID s : session.selection()) ids.add(s);
        } else ids.add(id);
        moveStart = new HashMap<>();
        for (final UUID m : ids) {
            final Node n = graph().nodes.get(m);
            if (n != null) moveStart.put(m, new int[] { n.x, n.y });
            final Drawer d = graph().getDrawer(m);
            if (d != null) moveStart.put(m, new int[] { d.getX(), d.getY() });
        }
        moveAnchor = id;
        moveMouseX = getContext().getAbsMouseX();
        moveMouseY = getContext().getAbsMouseY();
        moveDX = moveDY = 0;
        moveUndo = com.sbancuz.plannh.api.PlanAPI.undoHistory()
            .beginEdit(graph());
    }

    /** Whether a card or drawer is being carried by a move right now (it draws lifted). */
    public boolean isCarried(final UUID id) {
        return moveStart != null && moveStart.containsKey(id) && (moveDX != 0 || moveDY != 0);
    }

    /** Cards and drawers moved this session, most recent last: they draw over the rest, in that order. */
    private final java.util.LinkedHashSet<UUID> raised = new java.util.LinkedHashSet<>();

    /**
     * Children in draw and hit order: drawers, then cards, then whatever was moved last, then whatever a move is
     * carrying right now, so the thing in hand is always on top.
     */
    @Override
    public @NotNull List<IWidget> getChildren() {
        final List<IWidget> all = super.getChildren();
        final boolean carrying = moveStart != null && (moveDX != 0 || moveDY != 0);
        if (raised.isEmpty() && !carrying) return all;
        final List<IWidget> out = new ArrayList<>(all.size()), carried = new ArrayList<>();
        final Map<UUID, IWidget> lifted = new HashMap<>();
        for (final IWidget w : all) {
            final UUID id = w instanceof final RecipeCard card ? card.nodeId
                : w instanceof final DrawerCard drawer ? drawer.drawerId : null;
            if (id != null && carrying && isCarried(id)) carried.add(w);
            else if (id != null && raised.contains(id)) lifted.put(id, w);
            else out.add(w);
        }
        for (final UUID id : raised) {
            final IWidget w = lifted.get(id);
            if (w != null) out.add(w);
        }
        out.addAll(carried);
        return out;
    }

    public void dragMove() {
        if (moveStart == null) return;
        final float zoom = graph().getZoom();
        moveDX = BoardSession.snap((getContext().getAbsMouseX() - moveMouseX) / zoom);
        moveDY = BoardSession.snap((getContext().getAbsMouseY() - moveMouseY) / zoom);
        final java.util.Map<UUID, int[]> at = new HashMap<>();
        for (final java.util.Map.Entry<UUID, int[]> e : moveStart.entrySet())
            at.put(e.getKey(), new int[] { e.getValue()[0] + moveDX, e.getValue()[1] + moveDY });
        place(at);
    }

    /** Ends a move: one undoable step; a press that never moved is a click, which selects (Shift adds). */
    public void endMove(final boolean successful) {
        if (moveStart == null) return;
        if (moveDX == 0 && moveDY == 0) {
            session.select(moveAnchor, net.minecraft.client.gui.GuiScreen.isShiftKeyDown());
        } else if (successful) {
            for (final UUID id : moveStart.keySet()) {
                raised.remove(id);
                raised.add(id);
            }
            com.sbancuz.plannh.api.PlanAPI.undoHistory()
                .commitEdit(moveUndo, graph());
            graph().touchLayout();
            com.sbancuz.plannh.api.PlanAPI.save();
        } else place(moveStart);
        moveStart = null;
        moveUndo = null;
    }

    /** Shift-drag on empty board draws a box; what it touches becomes the selection. */
    private float boxX0, boxY0, boxX1, boxY1;
    private boolean boxing;

    private void drawBox() {
        if (!boxing) return;
        final float x = Math.min(boxX0, boxX1), y = Math.min(boxY0, boxY1);
        final float w = Math.abs(boxX1 - boxX0), h = Math.abs(boxY1 - boxY0);
        Hyb.rect(x, y, w, h, 0x2022D3EE);
        Hyb.ring(x, y, w, h, 1 / graph().getZoom(), Hyb.SELECTION);
    }

    private void selectBox() {
        final float x0 = Math.min(boxX0, boxX1), y0 = Math.min(boxY0, boxY1);
        final float x1 = Math.max(boxX0, boxX1), y1 = Math.max(boxY0, boxY1);
        if (!net.minecraft.client.gui.GuiScreen.isCtrlKeyDown()) session.clearSelection();
        for (final RecipeCard card : cards.values()) {
            if (card.model() == null || card.layout() == null) continue;
            final Node n = card.model().node;
            if (n.x < x1 && n.x + CardLayout.W > x0 && n.y < y1 && n.y + card.layout().height > y0)
                session.select(n.id, true);
        }
        for (final DrawerCard drawer : drawers.values()) {
            if (drawer.model() == null) continue;
            final Drawer d = drawer.model().drawer;
            if (d.getX() < x1 && d.getX() + DrawerCard.W > x0 && d.getY() < y1 && d.getY() + DrawerCard.H > y0)
                session.select(d.getId(), true);
        }
    }

    // endregion

    // region Wiring by dragging a port

    /** A wire being dragged out of a port. */
    private record PortDrag(UUID nodeId, boolean output, int port, int startX, int startY) {}

    private PortDrag portDrag;

    /** The resource a wire being dragged carries, or null when no port is being dragged. */
    public String dragResource() {
        if (portDrag == null) return null;
        final RecipeCard card = cards.get(portDrag.nodeId());
        if (card == null || card.model() == null) return null;
        final List<CardModel.PortView> ports = portDrag.output() ? card.model().outputs : card.model().inputs;
        return portDrag.port() < ports.size() ? ports.get(portDrag.port())
            .key() : null;
    }

    /** Whether a port on this card, on that side, would take the wire being dragged. */
    public boolean acceptsDrag(final UUID nodeId, final boolean output, final String key) {
        final String dragging = dragResource();
        return dragging != null && !dragging.isEmpty()
            && !nodeId.equals(portDrag.nodeId())
            && output != portDrag.output()
            && dragging.equals(key);
    }

    /** Whether a drawer would take the wire being dragged: same resource, on the matching side. */
    public boolean drawerAcceptsDrag(final Drawer drawer) {
        final String dragging = dragResource();
        return dragging != null && dragging.equals(drawer.getResourceKey())
            && drawer.getKind()
                .linksInputs() != portDrag.output();
    }

    /** Tooltip for the wire under the mouse: what it carries and how much. */
    public List<String> wireLines() {
        final WireLayer.Wire wire = wires.hit(worldX(getContext().getAbsMouseX()), worldY(getContext().getAbsMouseY()));
        if (wire == null) return null;
        final boolean fluid = com.sbancuz.plannh.ui.Resources.isFluid(wire.resource());
        return List.of(
            com.sbancuz.plannh.ui.Resources.name(wire.resource()),
            "§7" + (wire.perSecond() > 0
                ? com.sbancuz.plannh.ui.theme.Fmt.rate(wire.perSecond(), session.rateUnit(), fluid)
                : "nothing flows yet"),
            "§8Right click: add a drawer here, or delete it");
    }

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
            y = screenY(
                n.y + cards.get(origin.nodeId())
                    .layout()
                    .anchorY(origin.output(), origin.portIndex()));
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
        final int sy = n.y + card.layout()
            .anchorY(portDrag.output(), portDrag.port());
        final int mx = Math.round(worldX(getContext().getAbsMouseX()));
        final int my = Math.round(worldY(getContext().getAbsMouseY()));
        // The bend stays on the port's own side, so the wire in hand never cuts back across its card.
        final int bend = portDrag.output() ? Math.max(sx + 16, (sx + mx) / 2) : Math.min(sx - 16, (sx + mx) / 2);
        final List<int[]> path = portDrag.output()
            ? List.of(new int[] { sx, sy }, new int[] { bend, sy }, new int[] { bend, my }, new int[] { mx, my })
            : List.of(new int[] { mx, my }, new int[] { bend, my }, new int[] { bend, sy }, new int[] { sx, sy });
        final java.util.List<com.sbancuz.plannh.data.flowchart.Port<?>> ports = portDrag.output() ? n.outputs
            : n.inputs;
        final int color = portDrag.port() < ports.size() ? WireLayer.colorOf(ports.get(portDrag.port())) : 0xFFE8E9EE;
        WireLayer.drawWire(path, color, 3, true);
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
        float panX = g.getPanX(), panY = g.getPanY();
        if (left < m) panX += m - left;
        else if (right > w - m) panX -= Math.min(right - (w - m), left - m);
        if (top < m) panY += m - top;
        else if (bottom > h - m) panY -= Math.min(bottom - (h - m), top - m);
        if (panX == g.getPanX() && panY == g.getPanY()) return;
        moveCamera(z, (w / 2f - panX) / z, (h / 2f - panY) / z, w / 2f, h / 2f);
    }

    // region Camera

    private static final long CAMERA_MS = 160;
    private long cameraStart = -1;
    private float fromZoom, toZoom, fromWX, fromWY, toWX, toWY, cameraSX, cameraSY;

    /**
     * Eases the camera (160 ms, ease-out) so the world point (wx, wy) ends at the board point (sx, sy) at {@code zoom}.
     * Zoom moves in log space and the anchor point slides in a straight line, so zooming around the cursor keeps the
     * point under it still.
     */
    private void moveCamera(final float zoom, final float wx, final float wy, final float sx, final float sy) {
        final Graph g = graph();
        fromZoom = g.getZoom();
        fromWX = (sx - g.getPanX()) / fromZoom;
        fromWY = (sy - g.getPanY()) / fromZoom;
        toZoom = zoom;
        toWX = wx;
        toWY = wy;
        cameraSX = sx;
        cameraSY = sy;
        cameraStart = System.currentTimeMillis();
    }

    private void stepCamera() {
        if (cameraStart < 0) return;
        final float t = Math.min(1, (System.currentTimeMillis() - cameraStart) / (float) CAMERA_MS);
        final float e = 1 - (1 - t) * (1 - t) * (1 - t);
        final float z = (float) (fromZoom * Math.pow(toZoom / fromZoom, e));
        final float wx = fromWX + (toWX - fromWX) * e, wy = fromWY + (toWY - fromWY) * e;
        final Graph g = graph();
        g.setZoom(t >= 1 ? toZoom : z);
        g.setPanX(cameraSX - wx * g.getZoom());
        g.setPanY(cameraSY - wy * g.getZoom());
        if (t >= 1) cameraStart = -1;
    }

    // endregion

    /** A card or drawer as the layered layout sees it; drawers have one port, at their anchor. */
    private record LayoutItem(UUID id, String machineName, int worldWidth, int worldHeight, int inputCount,
        int outputCount, CardLayout layout) implements AutoLayout.LayoutNode {

        @Override
        public int portY(final boolean output, final int index) {
            return layout == null ? DrawerCard.ANCHOR_Y : layout.anchorY(output, index);
        }
    }

    /**
     * Lays the plan out left to right with the layered layout (sources, then the cards in flow order, then products),
     * keeps it where it was on the board, and frames it. One undoable step.
     */
    public void arrange() {
        final Graph g = graph();
        final List<LayoutItem> items = new ArrayList<>();
        final List<Edge> links = new ArrayList<>(g.getEdges());
        for (final RecipeCard card : cards.values()) {
            if (card.model() == null || card.layout() == null) continue;
            final CardModel m = card.model();
            items.add(
                new LayoutItem(
                    m.node.id,
                    m.machineName,
                    CardLayout.W,
                    card.layout().height,
                    m.inputs.size(),
                    m.outputs.size(),
                    card.layout()));
        }
        for (final Drawer d : g.getDrawers()) {
            final boolean source = d.getKind()
                .linksInputs();
            items.add(
                new LayoutItem(
                    d.getId(),
                    d.getResourceKey(),
                    DrawerCard.W,
                    DrawerCard.H,
                    source ? 0 : 1,
                    source ? 1 : 0,
                    null));
            for (final Drawer.Link link : d.getLinks()) {
                if (!cards.containsKey(link.nodeId())) continue;
                final UUID id = UUID.nameUUIDFromBytes(
                    (d.getId() + ":" + link.nodeId() + ":" + link.portIndex())
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                links.add(
                    source ? new Edge(id, d.getId(), link.nodeId(), 0, link.portIndex())
                        : new Edge(id, link.nodeId(), d.getId(), link.portIndex(), 0));
            }
        }
        if (items.isEmpty()) return;
        final java.util.Map<UUID, int[]> placed = AutoLayout.layout(items, links);
        if (placed.isEmpty()) return;
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, px0 = Integer.MAX_VALUE, py0 = Integer.MAX_VALUE;
        for (final Node n : g.getNodes()) {
            x0 = Math.min(x0, n.x);
            y0 = Math.min(y0, n.y);
        }
        for (final Drawer d : g.getDrawers()) {
            x0 = Math.min(x0, d.getX());
            y0 = Math.min(y0, d.getY());
        }
        for (final int[] p : placed.values()) {
            px0 = Math.min(px0, p[0]);
            py0 = Math.min(py0, p[1]);
        }
        final int dx = x0 - px0, dy = y0 - py0;
        // Where everything is now, and where it goes.
        final java.util.Map<UUID, int[]> from = new HashMap<>(), to = new HashMap<>();
        for (final Node n : g.getNodes()) {
            final int[] p = placed.get(n.id);
            if (p == null) continue;
            from.put(n.id, new int[] { n.x, n.y });
            to.put(n.id, new int[] { p[0] + dx, p[1] + dy });
        }
        for (final Drawer d : g.getDrawers()) {
            final int[] p = placed.get(d.getId());
            if (p == null) continue;
            from.put(d.getId(), new int[] { d.getX(), d.getY() });
            to.put(d.getId(), new int[] { p[0] + dx, p[1] + dy });
        }
        // One undoable step with the final places, then rewind and let everything glide there.
        session.editLayout(() -> place(to));
        place(from);
        glideFrom = from;
        glideTo = to;
        glideStart = System.currentTimeMillis();
        int bx0 = Integer.MAX_VALUE, by0 = Integer.MAX_VALUE, bx1 = Integer.MIN_VALUE, by1 = Integer.MIN_VALUE;
        for (final java.util.Map.Entry<UUID, int[]> e : to.entrySet()) {
            final RecipeCard card = cards.get(e.getKey());
            final int w = card != null ? CardLayout.W : DrawerCard.W;
            final int h = card != null && card.layout() != null ? card.layout().height : DrawerCard.H;
            bx0 = Math.min(bx0, e.getValue()[0]);
            by0 = Math.min(by0, e.getValue()[1]);
            bx1 = Math.max(bx1, e.getValue()[0] + w);
            by1 = Math.max(by1, e.getValue()[1] + h);
        }
        if (bx0 != Integer.MAX_VALUE) frameBox(bx0, by0, bx1, by1);
    }

    private static final long GLIDE_MS = 250;
    private java.util.Map<UUID, int[]> glideFrom, glideTo;
    private long glideStart = -1;

    /** Puts cards and drawers at the given places, widgets included. */
    private void place(final java.util.Map<UUID, int[]> at) {
        final Graph g = graph();
        for (final java.util.Map.Entry<UUID, int[]> e : at.entrySet()) {
            final Node n = g.nodes.get(e.getKey());
            if (n != null) {
                n.x = e.getValue()[0];
                n.y = e.getValue()[1];
                final RecipeCard card = cards.get(n.id);
                if (card != null) card.pos(n.x, n.y);
                continue;
            }
            final Drawer d = g.getDrawer(e.getKey());
            if (d == null) continue;
            d.setX(e.getValue()[0]);
            d.setY(e.getValue()[1]);
            final DrawerCard drawer = drawers.get(d.getId());
            if (drawer != null) drawer.pos(d.getX(), d.getY());
        }
    }

    /** One frame of the arrange glide; at the end everything sits exactly where it was arranged. */
    private void stepGlide() {
        if (glideStart < 0) return;
        final float t = Math.min(1, (System.currentTimeMillis() - glideStart) / (float) GLIDE_MS);
        final float e = 1 - (1 - t) * (1 - t) * (1 - t);
        final java.util.Map<UUID, int[]> at = new HashMap<>();
        for (final java.util.Map.Entry<UUID, int[]> f : glideFrom.entrySet()) {
            final int[] a = f.getValue(), b = glideTo.get(f.getKey());
            at.put(
                f.getKey(),
                new int[] { Math.round(a[0] + (b[0] - a[0]) * e), Math.round(a[1] + (b[1] - a[1]) * e) });
        }
        place(at);
        if (t >= 1) {
            glideStart = -1;
            graph().touchLayout();
            com.sbancuz.plannh.api.PlanAPI.save();
        }
    }

    /** Frames every card and drawer on the board. */
    public void frameAll() {
        final List<UUID> ids = new ArrayList<>(cards.keySet());
        ids.addAll(drawers.keySet());
        frame(ids);
    }

    /** Pans and zooms so the given cards and drawers fill the view, at the largest whole zoom step that fits. */
    public void frame(final List<UUID> ids) {
        final int[] box = box(ids);
        if (box != null) frameBox(box[0], box[1], box[2], box[3]);
    }

    /** Whether {@link #frame} would show these closer than the glance view, so their ports can be read. */
    public boolean framesReadably(final List<UUID> ids) {
        final int[] box = box(ids);
        return box != null && fitZoom(box[0], box[1], box[2], box[3]) > RecipeCard.GLANCE_ZOOM;
    }

    /** The world box around the given cards and drawers, as {x0, y0, x1, y1}; null when none is on the board. */
    @Nullable
    private int[] box(final List<UUID> ids) {
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
        return x0 == Integer.MAX_VALUE ? null : new int[] { x0, y0, x1, y1 };
    }

    /** Eases the camera onto a world box, at the largest whole zoom step that fits it (the cards' own size at most). */
    private void frameBox(final int x0, final int y0, final int x1, final int y1) {
        moveCamera(
            fitZoom(x0, y0, x1, y1),
            (x0 + x1) / 2f,
            (y0 + y1) / 2f,
            getArea().width / 2f,
            getArea().height / 2f);
    }

    /** The largest whole zoom step that fits a world box in the view with a margin, 1 at most. */
    private float fitZoom(final int x0, final int y0, final int x1, final int y1) {
        final int margin = 24;
        final float fitW = getArea().width / (float) (x1 - x0 + 2 * margin);
        final float fitH = getArea().height / (float) (y1 - y0 + 2 * margin);
        float zoom = ZOOMS[0];
        for (final float z : ZOOMS) if (z <= Math.min(Math.min(fitW, fitH), 1f)) zoom = z;
        return zoom;
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
        // Steps chain: a second notch during the ease continues from where the first one is heading.
        final float from = cameraStart >= 0 ? toZoom : g.getZoom();
        int i = nearestZoom(from);
        i = Math.max(0, Math.min(ZOOMS.length - 1, i + (direction == UpOrDown.UP ? 1 : -1)));
        final float next = ZOOMS[i];
        if (next == from) return true;
        final float mx = getContext().getAbsMouseX() - getArea().x;
        final float my = getContext().getAbsMouseY() - getArea().y;
        moveCamera(next, (mx - g.getPanX()) / g.getZoom(), (my - g.getPanY()) / g.getZoom(), mx, my);
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
        cameraStart = -1;
        boxing = button == 0 && net.minecraft.client.gui.GuiScreen.isShiftKeyDown();
        if (boxing) {
            boxX0 = boxX1 = worldX(getContext().getAbsMouseX());
            boxY0 = boxY1 = worldY(getContext().getAbsMouseY());
            return true;
        }
        panStartX = graph().getPanX();
        panStartY = graph().getPanY();
        panMouseX = getContext().getAbsMouseX();
        panMouseY = getContext().getAbsMouseY();
        return true;
    }

    @Override
    public void onDrag(final int mouseButton, final long timeSinceLastClick) {
        if (boxing) {
            boxX1 = worldX(getContext().getAbsMouseX());
            boxY1 = worldY(getContext().getAbsMouseY());
            return;
        }
        graph().setPanX(panStartX + getContext().getAbsMouseX() - panMouseX);
        graph().setPanY(panStartY + getContext().getAbsMouseY() - panMouseY);
    }

    @Override
    public void onDragEnd(final boolean successful) {
        if (boxing) {
            boxing = false;
            selectBox();
            return;
        }
        // A click on empty board (no pan) clears the selection.
        if (graph().getPanX() == panStartX && graph().getPanY() == panStartY) session.clearSelection();
    }

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

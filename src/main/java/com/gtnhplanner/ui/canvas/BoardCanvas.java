package com.gtnhplanner.ui.canvas;

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
import com.gtnhplanner.data.flowchart.Drawer;
import com.gtnhplanner.data.flowchart.Edge;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.ui.BoardSession;
import com.gtnhplanner.ui.card.CardLayout;
import com.gtnhplanner.ui.card.CardModel;
import com.gtnhplanner.ui.card.RecipeCard;
import com.gtnhplanner.ui.drawer.DrawerCard;
import com.gtnhplanner.ui.drawer.DrawerModel;
import com.gtnhplanner.ui.popup.PickList;
import com.gtnhplanner.ui.popup.Popup;
import com.gtnhplanner.ui.sound.Sfx;
import com.gtnhplanner.ui.theme.Hyb;

/**
 * The board: pans by dragging empty space (left or middle button), zooms with the wheel around the cursor, and holds
 * one {@link RecipeCard} per recipe in world space. Zoom moves in whole steps so the game font stays sharp.
 */
public final class BoardCanvas extends ParentWidget<BoardCanvas> implements Interactable, IViewport, IDraggable {

    /**
     * Wheel steps, as on the website: a fifth of a doubling each (2^0.2, about 15%), from a sixteenth (so a big plan
     * still fits the board whole) to twice.
     */
    private static final float[] ZOOMS = new float[26];

    static {
        for (int i = 0; i < ZOOMS.length; i++) ZOOMS[i] = (float) (0.0625 * Math.pow(2, i / 5.0));
        // The powers of two exactly, where the game's font is crisp.
        for (int i = 0; i < ZOOMS.length; i += 5) ZOOMS[i] = (float) (0.0625 * (1 << i / 5));
    }
    private static final int GRID = 20;

    private final BoardSession session;
    private final ModularPanel panel;
    private final Map<UUID, RecipeCard> cards = new HashMap<>();
    private final Map<UUID, DrawerCard> drawers = new HashMap<>();
    private final Map<UUID, com.gtnhplanner.ui.note.NoteCard> notes = new HashMap<>();
    private final WireLayer wires;
    private int builtStructure = -1;
    /** When the widgets were last built afresh: the screen finds what is under the mouse again a frame later. */
    private long builtAt;

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
        if (editNote != null && notes.containsKey(editNote)) {
            notes.get(editNote)
                .startEditing();
            editNote = null;
        }
        if (frameAllPending && (!cards.isEmpty() || !drawers.isEmpty())) {
            frameAllPending = false;
            frameAll();
        }
        final UUID added = session.takeJustAdded();
        final boolean focus = session.takeFocusAdded();
        if (added != null) {
            reveal = List.of(added);
            revealFocus = focus;
        }
        if (!reveal.isEmpty() && reveal.stream()
            .allMatch(id -> cards.containsKey(id) || drawers.containsKey(id))) {
            final int[] box = box(reveal);
            if (box != null && revealFocus) focusBox(reveal.get(0), box);
            else if (box != null) revealBox(box[0], box[1], box[2], box[3]);
            reveal = List.of();
            revealFocus = false;
        }
    }

    /** Brings cards and drawers just added into view once their widgets exist. */
    public void revealWhenBuilt(final List<UUID> ids) {
        reveal = List.copyOf(ids);
    }

    private void rebuildCards() {
        builtStructure = session.structure();
        builtAt = System.currentTimeMillis();
        removeAll();
        cards.clear();
        drawers.clear();
        notes.clear();
        // Notes first, then drawers, so cards draw over them where they overlap.
        for (final UUID id : session.graph().notes.keySet()) {
            final com.gtnhplanner.ui.note.NoteCard note = new com.gtnhplanner.ui.note.NoteCard(session, id);
            notes.put(id, note);
            child(note);
        }
        for (final DrawerModel model : session.drawerModels()
            .values()) {
            final DrawerCard drawer = new DrawerCard(session, model.drawer.getId());
            drawers.put(model.drawer.getId(), drawer);
            child(drawer);
        }
        for (final CardModel model : session.models()
            .values()) {
            // A shared machine is one card: its first recipe makes it, and every recipe on it finds it.
            final UUID host = session.hostOf(model.node.id);
            if (!host.equals(model.node.id)) continue;
            final RecipeCard card = new RecipeCard(session, host);
            for (final UUID section : session.sectionsOf(host)) cards.put(section, card);
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

    public Map<UUID, com.gtnhplanner.ui.note.NoteCard> notes() {
        return notes;
    }

    /** A note just added, to start writing on once its widget exists. */
    private UUID editNote;

    /** Adds a sticky note at a board point and starts writing on it. */
    public void addNoteAt(final int worldX, final int worldY) {
        editNote = session.addNote(worldX, worldY)
            .getId();
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
        if (!com.gtnhplanner.dev.DevPerf.on()) {
            drawCanvas(context, widgetTheme);
            return;
        }
        final long started = System.nanoTime();
        drawCanvas(context, widgetTheme);
        com.gtnhplanner.dev.DevPerf.time("canvas", System.nanoTime() - started);
    }

    private void drawCanvas(final ModularGuiContext context, final WidgetThemeEntry<?> widgetTheme) {
        stepCamera();
        stepMiddlePan();
        stepPanGlide();
        if (panning && !boxing || middlePan) samplePan();
        collectArrange();
        stepGlide();
        stepMoveGlide();
        session.setHover(hoveredScope());
        final Area a = getArea();
        Hyb.rect(0, 0, a.width, a.height, Hyb.seeThrough(Hyb.CANVAS));
        if (cards.isEmpty() && drawers.isEmpty() && notes.isEmpty()) {
            // An empty board says how to start.
            Hyb.textCentered("Nothing here yet.", a.width / 2f, a.height / 2f - 14, Hyb.MUTED);
            Hyb.textCentered(
                "Look an item up in NEI (R or U) and press + on a recipe,",
                a.width / 2f,
                a.height / 2f,
                0xFF6A6C74);
            Hyb.textCentered(
                "or drag an item out of NEI's list and click here.",
                a.width / 2f,
                a.height / 2f + 11,
                0xFF6A6C74);
        }
        final float zoom = graph().getZoom();
        final float step = GRID * zoom;
        if (step >= 6) {
            final float ox = mod(graph().getPanX(), step), oy = mod(graph().getPanY(), step);
            final float dot = zoom >= 1 ? 2 : 1;
            final int dotColor = Hyb.seeThrough(Hyb.CANVAS_DOT);
            for (float x = ox; x < a.width; x += step) {
                for (float y = oy; y < a.height; y += step) Hyb.rect(x, y, dot, dot, dotColor);
            }
        }
    }

    /**
     * What glows for what the mouse is on, as on the website: a port, its wires and their far ends; a drawer, its wires
     * and the ports they reach; a wire and its two ends. Nothing while a wire is in hand.
     */
    private com.gtnhplanner.ui.HoverScope hoveredScope() {
        if (portDrag != null || moveStarted) return com.gtnhplanner.ui.HoverScope.NONE;
        final com.cleanroommc.modularui.api.widget.IWidget hovered = getContext().getHovered();
        if (hovered instanceof final com.gtnhplanner.ui.card.PortSlot slot) {
            final CardModel.PortView view = slot.view();
            return view == null ? com.gtnhplanner.ui.HoverScope.NONE
                : com.gtnhplanner.ui.HoverScope.ofPort(graph(), slot.node(), slot.output, view.index());
        }
        if (hovered instanceof final DrawerCard drawer) {
            return drawer.model() == null ? com.gtnhplanner.ui.HoverScope.NONE
                : com.gtnhplanner.ui.HoverScope.ofDrawer(drawer.model().drawer);
        }
        if (hovered != null && hovered != this) return com.gtnhplanner.ui.HoverScope.NONE;
        final WireLayer.Wire wire = wires.hit(worldX(getContext().getAbsMouseX()), worldY(getContext().getAbsMouseY()));
        if (wire == null) return com.gtnhplanner.ui.HoverScope.NONE;
        if (wire.edge() != null) return com.gtnhplanner.ui.HoverScope.ofEdge(wire.edge());
        return wire.drawer() != null && wire.link() != null
            ? com.gtnhplanner.ui.HoverScope.ofLink(wire.drawer(), wire.link())
            : com.gtnhplanner.ui.HoverScope.NONE;
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
        labels.clear();
        final boolean timed = com.gtnhplanner.dev.DevPerf.on();
        final long started = timed ? System.nanoTime() : 0;
        final List<WireLayer.Wire> routed = wires.wires(cards, drawers, moveStart != null || glideStart >= 0);
        final long drawing = timed ? System.nanoTime() : 0;
        wires.draw(routed, session.lit());
        publishSnapshot(routed);
        if (timed) {
            com.gtnhplanner.dev.DevPerf.time("wires.route", drawing - started);
            com.gtnhplanner.dev.DevPerf.time("wires.draw", System.nanoTime() - drawing);
        }
    }

    @Override
    public void postDraw(final ModularGuiContext context, final boolean transformed) {
        if (transformed) {
            // Over the cards: the names when zoomed out, the wire in hand and the selection box.
            if (com.gtnhplanner.ui.PlannerSettings.zoomedOutNames()) labels.draw(graph().getZoom());
            if (portDrag != null) drawPortDrag();
            drawBox();
        } else Stencil.remove();
    }

    // endregion

    /** What the last snapshot was taken from: a change in any of them takes a new one. */
    private Object snapshotWires, snapshotModels, snapshotDrawers;
    private long snapshotVersion = Long.MIN_VALUE;
    private int snapshotPins = -1;

    /** Publishes the board as drawn for the minimap and the world views, when the plan, its answer or wires changed. */
    private void publishSnapshot(final List<WireLayer.Wire> routed) {
        final Area view = getArea();
        com.gtnhplanner.ui.world.PlanSnapshot
            .setView(worldX(view.x + view.width / 2), worldY(view.y + view.height / 2));
        final Object models = session.models(), drawerModels = session.drawerModels();
        final long version = graph().version();
        if (routed == snapshotWires && models == snapshotModels
            && drawerModels == snapshotDrawers
            && version == snapshotVersion
            && com.gtnhplanner.ui.card.SettingPins.version() == snapshotPins) return;
        snapshotWires = routed;
        snapshotModels = models;
        snapshotDrawers = drawerModels;
        snapshotVersion = version;
        snapshotPins = com.gtnhplanner.ui.card.SettingPins.version();
        final Area a = getArea();
        com.gtnhplanner.ui.world.PlanSnapshot.publish(
            SnapshotMaker.make(session, cards, drawers, routed, worldX(a.x + a.width / 2), worldY(a.y + a.height / 2)));
    }

    /** The names over cards and drawers zoomed far out, gathered as they draw and placed over everything. */
    private final ZoomedOutLabels labels = new ZoomedOutLabels();

    /** A card's name for the zoomed-out view (world coordinates). */
    public void labelCard(final String name, final float x, final float y, final float w, final float h) {
        labels.card(name, x, y, w, h);
    }

    /** A drawer's name for the zoomed-out view: a source's on its left, a product's on its right. */
    public void labelDrawer(final String name, final float x, final float y, final float w, final float h,
        final boolean source, final double rate) {
        labels.drawer(name, x, y, w, h, source, rate);
    }

    // region Moving cards and drawers, and box selection

    private java.util.Map<UUID, int[]> moveStart;
    private UUID moveAnchor;
    private int moveMouseX, moveMouseY, moveDX, moveDY;
    private String moveUndo;
    /**
     * Whether the press has gone far enough to be a move ({@value #DRAG_SLOP} GUI pixels); until then it is a click.
     */
    private boolean moveStarted;
    /** How far the mouse goes, in GUI pixels, before a press on a card, drawer or note moves it. */
    private static final int DRAG_SLOP = 3;
    /** The grid moves snap to, in board pixels. */
    private static final int MOVE_STEP = 10;
    /** How far the clear spot is looked for around the mouse, in grid steps, before giving up and overlapping. */
    private static final int MOVE_SEARCH = 40;
    /** Where the carried things are drawn, gliding to the grid spot the move puts them on, and when it last stepped. */
    private float glideDX, glideDY;
    private int placedDX, placedDY;
    private long glideNanos;
    /** How quickly the glide closes in on its spot: about 120 ms to get there, as the website's ease. */
    private static final float MOVE_EASE_SECONDS = 0.035f;

    /**
     * A card or drawer body was pressed and is being dragged: it moves, with the rest of the selection when it is
     * selected. Offsets snap to the grid as a whole, so things keep their alignment to each other.
     */
    public void beginMove(final UUID id) {
        stopPanGlide();
        final java.util.List<UUID> ids = new ArrayList<>();
        if (session.isSelected(id)) {
            for (final UUID s : session.selection()) ids.add(s);
        } else ids.add(id);
        // A shared machine moves whole: every recipe on its card.
        for (final UUID s : new ArrayList<>(ids))
            for (final UUID section : session.sectionsOf(s)) if (!ids.contains(section)) ids.add(section);
        moveStart = new HashMap<>();
        for (final UUID m : ids) {
            final Node n = graph().nodes.get(m);
            if (n != null) moveStart.put(m, new int[] { n.x, n.y });
            final Drawer d = graph().getDrawer(m);
            if (d != null) moveStart.put(m, new int[] { d.getX(), d.getY() });
            final com.gtnhplanner.data.flowchart.Note note = graph().notes.get(m);
            if (note != null) moveStart.put(m, new int[] { note.getX(), note.getY() });
        }
        moveAnchor = id;
        moveMouseX = getContext().getAbsMouseX();
        moveMouseY = getContext().getAbsMouseY();
        moveDX = moveDY = 0;
        moveStarted = false;
        glideDX = glideDY = 0;
        placedDX = placedDY = 0;
        glideNanos = System.nanoTime();
        carried = obstacles = null;
        moveUndo = com.gtnhplanner.api.PlanAPI.undoHistory()
            .beginEdit(graph());
    }

    /** Whether a card or drawer is being carried by a move right now (it draws lifted). */
    public boolean isCarried(final UUID id) {
        return moveStart != null && moveStarted && moveStart.containsKey(id);
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
        final boolean carrying = moveStart != null && moveStarted;
        if (raised.isEmpty() && !carrying) return all;
        final List<IWidget> out = new ArrayList<>(all.size()), carried = new ArrayList<>();
        final Map<UUID, IWidget> lifted = new HashMap<>();
        for (final IWidget w : all) {
            final UUID id = w instanceof final RecipeCard card ? card.nodeId
                : w instanceof final DrawerCard drawer ? drawer.drawerId
                    : w instanceof final com.gtnhplanner.ui.note.NoteCard note ? note.noteId : null;
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
        final int rawX = getContext().getAbsMouseX() - moveMouseX, rawY = getContext().getAbsMouseY() - moveMouseY;
        if (!moveStarted) {
            // A press that has not gone anywhere yet is still a click.
            if (Math.max(Math.abs(rawX), Math.abs(rawY)) < DRAG_SLOP) return;
            moveStarted = true;
            Sfx.LIFT.play();
        }
        final float zoom = graph().getZoom();
        // Cards and drawers never land on each other, as on the website: they go to the clear spot nearest the mouse,
        // so they hug the near side of what is in the way and come out the far side once the mouse is past its middle.
        final int[] to = nearestClear(BoardSession.snap(rawX / zoom), BoardSession.snap(rawY / zoom));
        moveDX = to[0];
        moveDY = to[1];
        stepMoveGlide();
    }

    /**
     * The clear offset nearest {@code wantX, wantY}: it if clear, else the first clear grid spot on rings of growing
     * size
     * round it (nearest first in each ring), as the website's search; none within reach, it itself.
     */
    private int[] nearestClear(final int wantX, final int wantY) {
        if (clearAt(wantX, wantY)) return new int[] { wantX, wantY };
        for (int r = 1; r <= MOVE_SEARCH; r++) for (final int[] o : ring(r)) {
            final int x = wantX + o[0] * MOVE_STEP, y = wantY + o[1] * MOVE_STEP;
            if (clearAt(x, y)) return new int[] { x, y };
        }
        return new int[] { wantX, wantY };
    }

    private static final java.util.Map<Integer, List<int[]>> RINGS = new HashMap<>();

    /** The grid steps {dx, dy} at ring {@code r} (the larger of |dx| and |dy| is r), nearest first, then top left. */
    private static List<int[]> ring(final int r) {
        return RINGS.computeIfAbsent(r, k -> {
            final List<int[]> out = new ArrayList<>();
            for (int dy = -k; dy <= k; dy++) for (int dx = -k; dx <= k; dx++)
                if (Math.max(Math.abs(dx), Math.abs(dy)) == k) out.add(new int[] { dx, dy });
            out.sort((a, b) -> {
                final int d = a[0] * a[0] + a[1] * a[1] - (b[0] * b[0] + b[1] * b[1]);
                return d != 0 ? d : a[1] != b[1] ? a[1] - b[1] : a[0] - b[0];
            });
            return out;
        });
    }

    /**
     * Moves what is carried a step of its glide toward the spot the move puts it on, every frame while it is not there:
     * smooth between grid spots, landing exactly on one.
     */
    private void stepMoveGlide() {
        if (moveStart == null || !moveStarted) return;
        final long now = System.nanoTime();
        final float dt = Math.min(0.05f, (now - glideNanos) / 1e9f);
        glideNanos = now;
        final float k = 1 - (float) Math.exp(-dt / MOVE_EASE_SECONDS);
        glideDX += (moveDX - glideDX) * k;
        glideDY += (moveDY - glideDY) * k;
        if (Math.abs(moveDX - glideDX) < 0.5f) glideDX = moveDX;
        if (Math.abs(moveDY - glideDY) < 0.5f) glideDY = moveDY;
        final int dx = Math.round(glideDX), dy = Math.round(glideDY);
        if (dx == placedDX && dy == placedDY) return;
        placedDX = dx;
        placedDY = dy;
        place(offset(dx, dy));
    }

    /** Where everything carried is, moved {@code dx, dy} from where the move began. */
    private java.util.Map<UUID, int[]> offset(final int dx, final int dy) {
        final java.util.Map<UUID, int[]> at = new HashMap<>();
        for (final java.util.Map.Entry<UUID, int[]> e : moveStart.entrySet())
            at.put(e.getKey(), new int[] { e.getValue()[0] + dx, e.getValue()[1] + dy });
        return at;
    }

    /**
     * What the move carries ({x, y, w, h} at its start) and what it must not land on: the cards and drawers it does not
     * carry. Notes take no part (they lie under the cards). Something already under a carried thing when the move
     * began is let be, so things stacked before can be pulled apart.
     */
    private List<int[]> carried, obstacles;

    private void takeStock() {
        carried = new ArrayList<>();
        obstacles = new ArrayList<>();
        for (final RecipeCard card : cards.values()) {
            if (card.model() == null || card.layout() == null) continue;
            final int[] start = moveStart.get(card.nodeId);
            final Node n = card.model().node;
            if (start != null) carried.add(new int[] { start[0], start[1], CardLayout.W, card.layout().height });
            else obstacles.add(new int[] { n.x, n.y, CardLayout.W, card.layout().height });
        }
        for (final DrawerCard drawer : drawers.values()) {
            if (drawer.model() == null) continue;
            final Drawer d = drawer.model().drawer;
            final int[] start = moveStart.get(drawer.drawerId);
            if (start != null) carried.add(new int[] { start[0], start[1], DrawerCard.W, DrawerCard.H });
            else obstacles.add(new int[] { d.getX(), d.getY(), DrawerCard.W, DrawerCard.H });
        }
        obstacles.removeIf(
            o -> carried.stream()
                .anyMatch(c -> overlaps(c, 0, 0, o)));
    }

    /** Whether the carried things, moved by {@code dx, dy} from their start, touch nothing they must not. */
    private boolean clearAt(final int dx, final int dy) {
        if (carried == null) takeStock();
        for (final int[] c : carried) for (final int[] o : obstacles) if (overlaps(c, dx, dy, o)) return false;
        return true;
    }

    private static boolean overlaps(final int[] c, final int dx, final int dy, final int[] o) {
        return c[0] + dx < o[0] + o[2] && o[0] < c[0] + dx + c[2] && c[1] + dy < o[1] + o[3] && o[1] < c[1] + dy + c[3];
    }

    /**
     * Ends a move: one undoable step, landing exactly on its spot. A press that never went anywhere is a click instead:
     * true, and the thing pressed decides what the click does ({@link #clickSelect} on its body).
     */
    public boolean endMove(final boolean successful) {
        if (moveStart == null) return false;
        final boolean click = !moveStarted;
        moveStarted = false;
        if (click) {
            moveStart = null;
            moveUndo = null;
            return true;
        }
        if (moveDX == 0 && moveDY == 0) place(moveStart);
        else if (successful) {
            place(offset(moveDX, moveDY));
            for (final UUID id : moveStart.keySet()) {
                raised.remove(id);
                raised.add(id);
            }
            com.gtnhplanner.api.PlanAPI.undoHistory()
                .commitEdit(moveUndo, graph());
            graph().touchLayout();
            com.gtnhplanner.api.PlanAPI.save();
            Sfx.DROP.play();
        } else place(moveStart);
        moveStart = null;
        moveUndo = null;
        return false;
    }

    /** A click on a card's, drawer's or note's body: it is selected (Shift adds it). */
    public void clickSelect(final UUID id) {
        session.select(id, net.minecraft.client.gui.GuiScreen.isShiftKeyDown());
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
        for (final com.gtnhplanner.data.flowchart.Note n : graph().getNotes()) {
            if (n.getX() < x1 && n.getX() + n.getWidth() > x0 && n.getY() < y1 && n.getY() + n.getHeight() > y0)
                session.select(n.getId(), true);
        }
    }

    // endregion

    // region Wiring by dragging a port

    /**
     * A wire being dragged out of a port; {@code port} -1 for one of an empty custom rate card's sockets (the supply
     * socket as an output, the drain as an input), which carries no resource until it lands on a port.
     */
    private record PortDrag(UUID nodeId, boolean output, int port, int startX, int startY) {

        boolean socket() {
            return port < 0;
        }
    }

    private PortDrag portDrag;

    /** The model of the recipe a port is on: on a shared machine, its own section's, not the card's first. */
    private CardModel portModel(final UUID nodeId) {
        final RecipeCard card = cards.get(nodeId);
        return card == null ? null : card.modelOf(Math.max(0, card.sectionOf(nodeId)));
    }

    /** The resource a wire being dragged carries, or null when no port is being dragged. */
    public String dragResource() {
        if (portDrag == null || portDrag.socket()) return null;
        final CardModel m = portModel(portDrag.nodeId());
        if (m == null) return null;
        final List<CardModel.PortView> ports = portDrag.output() ? m.outputs : m.inputs;
        return portDrag.port() < ports.size() ? ports.get(portDrag.port())
            .key() : null;
    }

    /** Whether a port on this card, on that side, would take the wire being dragged. */
    public boolean acceptsDrag(final UUID nodeId, final boolean output, final String key) {
        // A custom rate card's socket takes any resource from a port on the other side, of any other card.
        if (portDrag != null && portDrag.socket()) return key != null && !key.isEmpty()
            && !nodeId.equals(portDrag.nodeId())
            && output != portDrag.output()
            && !com.gtnhplanner.power.CustomRate.is(session.graph().nodes.get(nodeId));
        final String dragging = dragResource();
        return dragging != null && !dragging.isEmpty()
            && !nodeId.equals(portDrag.nodeId())
            && output != portDrag.output()
            && dragging.equals(key);
    }

    /** Whether any recipe on a card would take the wire being dragged (the card is outlined green). */
    public boolean cardAcceptsDrag(final UUID cardId) {
        if (portDrag == null || portDrag.nodeId()
            .equals(cardId)) return false;
        for (final UUID section : session.sectionsOf(cardId)) {
            final CardModel m = portModel(section);
            if (m == null) continue;
            for (final CardModel.PortView p : portDrag.output() ? m.inputs : m.outputs)
                if (acceptsDrag(section, !portDrag.output(), p.key())) return true;
        }
        return false;
    }

    /** The card or drawer the wire in hand is over and would go to, or null: it is outlined brighter. */
    @Nullable
    public UUID dragSnappedTo() {
        return portDrag == null ? null : snappedTo;
    }

    /** Whether a drawer would take the wire being dragged: same resource, on the matching side. */
    public boolean drawerAcceptsDrag(final Drawer drawer) {
        if (portDrag != null && portDrag.socket()) return drawer.getKind()
            .linksInputs() != portDrag.output();
        final String dragging = dragResource();
        return dragging != null && dragging.equals(drawer.getResourceKey())
            && drawer.getKind()
                .linksInputs() != portDrag.output();
    }

    /** Tooltip for the wire under the mouse: what it carries and how much. */
    /** Every routed wire as {resource, its world points as "x,y" pairs}, for the dev harness. */
    public List<List<String>> wirePaths() {
        final List<List<String>> out = new ArrayList<>();
        for (final WireLayer.Wire w : wires.wires(cards, drawers, false)) {
            final List<String> row = new ArrayList<>();
            row.add(w.resource());
            // Which boxes it joins (a card by its first recipe, a drawer by its id), source first, and its width.
            final RecipeCard from = w.edge() != null ? cards.get(w.edge().sourceNodeId)
                : w.drawer() != null && !w.drawer()
                    .getKind()
                    .linksInputs() ? cards.get(
                        w.link()
                            .nodeId())
                        : null;
            final RecipeCard to = w.edge() != null ? cards.get(w.edge().targetNodeId)
                : w.drawer() != null && w.drawer()
                    .getKind()
                    .linksInputs() ? cards.get(
                        w.link()
                            .nodeId())
                        : null;
            row.add(
                "from=" + (from != null ? from.nodeId
                    : w.drawer() != null ? w.drawer()
                        .getId() : "?"));
            row.add(
                "to=" + (to != null ? to.nodeId
                    : w.drawer() != null ? w.drawer()
                        .getId() : "?"));
            row.add("width=" + w.width());
            row.add("key=" + w.key());
            for (final int[] p : w.path()) row.add(p[0] + "," + p[1]);
            out.add(row);
        }
        return out;
    }

    public List<String> wireLines() {
        final WireLayer.Wire wire = wires.hit(worldX(getContext().getAbsMouseX()), worldY(getContext().getAbsMouseY()));
        if (wire == null) return null;
        final boolean fluid = com.gtnhplanner.ui.Resources.isFluid(wire.resource());
        return List.of(
            com.gtnhplanner.ui.Resources.name(wire.resource()),
            "§7" + (wire.perSecond() > 0
                ? com.gtnhplanner.ui.theme.Fmt.rate(wire.perSecond(), session.rateUnit(), fluid)
                : "nothing flows yet"),
            "§8Right click: add a drawer here, or delete it");
    }

    /** Called by a port slot when the mouse goes down on it and starts to drag. */
    public void beginPortDrag(final UUID nodeId, final boolean output, final int port) {
        portDrag = new PortDrag(nodeId, output, port, getContext().getAbsMouseX(), getContext().getAbsMouseY());
        dragHeard = false;
        snappedTo = null;
    }

    /** Whether the wire in hand has made its pick-up sound, and what it last snapped over (for the snap sound). */
    private boolean dragHeard;
    private UUID snappedTo;

    /** The card or drawer under a world point that would take the wire in hand; null for none. */
    private UUID dragTargetAt(final int wx, final int wy) {
        for (final RecipeCard card : cards.values()) {
            final CardModel m = card.model();
            if (m == null || !inside(m.node.x, m.node.y, CardLayout.W, card.layout().height, wx, wy)) continue;
            if (card.nodeId.equals(portDrag.nodeId())) return null;
            for (final UUID section : session.sectionsOf(card.nodeId)) {
                final CardModel sm = portModel(section);
                if (sm == null) continue;
                for (final CardModel.PortView p : portDrag.output() ? sm.inputs : sm.outputs)
                    if (acceptsDrag(section, !portDrag.output(), p.key())) return card.nodeId;
            }
            return null;
        }
        for (final DrawerCard drawer : drawers.values()) {
            if (drawer.model() == null) continue;
            final Drawer d = drawer.model().drawer;
            if (inside(d.getX(), d.getY(), DrawerCard.W, DrawerCard.H, wx, wy))
                return drawerAcceptsDrag(d) ? d.getId() : null;
        }
        return null;
    }

    /** Called by an empty custom rate card's socket when a drag starts on it: its supply socket, or its drain. */
    public void beginSocketDrag(final UUID nodeId, final boolean supply) {
        portDrag = new PortDrag(nodeId, supply, -1, getContext().getAbsMouseX(), getContext().getAbsMouseY());
        dragHeard = false;
        snappedTo = null;
    }

    /** Whether a wire is in hand that a custom rate card would take: one dragged from a port of another card. */
    public boolean draggingFor(final UUID customRateCard) {
        return portDrag != null && !portDrag.socket()
            && !portDrag.nodeId()
                .equals(customRateCard);
    }

    /**
     * A socket's wire let go: on another card's port (an input for the supply socket, an output for the drain) the card
     * takes that port's resource and is wired to it; on a drawer on the matching side, the drawer's. On a card without
     * a
     * port under the mouse, its only port on that side, if it has just one.
     */
    private void endSocketDrag(final PortDrag drag, final float wx, final float wy) {
        final Node custom = session.graph().nodes.get(drag.nodeId());
        if (custom == null) return;
        final boolean supply = drag.output();
        for (final RecipeCard card : cards.values()) {
            if (card.model() == null || card.nodeId.equals(drag.nodeId())
                || !inside(card.model().node.x, card.model().node.y, CardLayout.W, card.layout().height, wx, wy))
                continue;
            final int[] hit = com.gtnhplanner.power.CustomRate.is(card.model().node) ? null
                : card.portAt(!supply, wy - card.model().node.y);
            final CardModel m = hit == null ? null : card.modelOf(hit[0]);
            final String key = m == null ? null
                : (supply ? m.inputs : m.outputs).get(hit[1])
                    .key();
            if (key == null || key.isEmpty()) {
                Sfx.DENY.play();
                return;
            }
            session.holdAndWire(custom, key, supply, card.sectionId(hit[0]), hit[1]);
            return;
        }
        for (final DrawerCard drawer : drawers.values()) {
            if (drawer.model() == null) continue;
            final Drawer d = drawer.model().drawer;
            if (!inside(d.getX(), d.getY(), DrawerCard.W, DrawerCard.H, wx, wy)) continue;
            if (d.getKind()
                .linksInputs() != supply) session.holdAndLink(custom, d);
            else Sfx.DENY.play();
            return;
        }
        Sfx.DENY.play();
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
            // A (left) click, not a drag: NEI's recipes for the port's resource (a socket has none).
            if (!drag.socket()) lookUpPort(drag, false);
            return;
        }
        if (!successful) return;
        final float wx = worldX(mx), wy = worldY(my);
        if (drag.socket()) {
            endSocketDrag(drag, wx, wy);
            return;
        }
        for (final RecipeCard card : cards.values()) {
            if (card.model() == null
                || !inside(card.model().node.x, card.model().node.y, CardLayout.W, card.layout().height, wx, wy))
                continue;
            if (card.nodeId.equals(drag.nodeId())
                || !session.dropPortOnCard(drag.nodeId(), drag.output(), drag.port(), card.nodeId)) Sfx.DENY.play();
            return;
        }
        for (final DrawerCard drawer : drawers.values()) {
            if (drawer.model() == null) continue;
            final Drawer d = drawer.model().drawer;
            if (!inside(d.getX(), d.getY(), DrawerCard.W, DrawerCard.H, wx, wy)) continue;
            if (!session.dropPortOnDrawer(drag.nodeId(), drag.output(), drag.port(), d)) Sfx.DENY.play();
            return;
        }
        if (!session.dropPortOnBoard(drag.nodeId(), drag.output(), drag.port(), Math.round(wx), Math.round(wy)))
            Sfx.DENY.play();
    }

    /** A click on a port: NEI's recipes that make an input, or that use an output. */
    /** A click on a port, as NEI's own: left its recipes (what makes it), right its uses. */
    public void clickPort(final UUID nodeId, final boolean output, final int port, final boolean uses) {
        lookUpPort(new PortDrag(nodeId, output, port, 0, 0), uses);
    }

    /**
     * Opens NEI's own page for a port's resource: what makes it, or with {@code uses} what uses it. The recipe added
     * from it with + lands beside this card, wired into this port where it fits. When NEI knows none, the port says so.
     */
    private void lookUpPort(final PortDrag drag, final boolean uses) {
        final RecipeCard card = cards.get(drag.nodeId());
        final CardModel model = portModel(drag.nodeId());
        if (card == null || model == null) return;
        final List<CardModel.PortView> ports = drag.output() ? model.outputs : model.inputs;
        if (drag.port() >= ports.size()) return;
        final ItemStack stack = ports.get(drag.port())
            .lookupStack();
        if (stack == null) return;
        session.armLookup(drag.nodeId(), drag.output(), drag.port());
        if (com.gtnhplanner.ui.Planner.lookUp(stack.copy(), uses)) return;
        session.disarmLookup();
        final Node n = card.model().node;
        final String none = (uses ? "Nothing uses " : "Nothing makes ") + stack.getDisplayName();
        Popup.open(
            getPanel(),
            PickList.popup(
                "gtnhplanner_none",
                null,
                List.of(new PickList.Entry(stack, none, "", Hyb.MUTED, false, () -> {})),
                false,
                160),
            screenX(n.x + CardLayout.anchorX(drag.output())) + (drag.output() ? 4 : -164),
            screenY(n.y + card.anchorY(drag.nodeId(), drag.output(), drag.port())));
    }

    private static boolean inside(final int x, final int y, final int w, final int h, final float px, final float py) {
        return px >= x && py >= y && px < x + w && py < y + h;
    }

    /** What letting go of the wire in hand would do, as the website tells it by the wire's colour. */
    private enum Verdict {
        /** Over a card or drawer that takes it: wired. */
        CONNECT,
        /** Over empty board: a drawer made for it. */
        SPAWN,
        /** Over empty board, the port already has its drawer: nothing. */
        DEAD,
        /** Over a card or drawer that does not take it: nothing. */
        REFUSE
    }

    private Verdict verdictAt(final int wx, final int wy) {
        if (dragTargetAt(wx, wy) != null) return Verdict.CONNECT;
        for (final RecipeCard card : cards.values()) if (card.model() != null
            && inside(card.model().node.x, card.model().node.y, CardLayout.W, card.layout().height, wx, wy))
            return Verdict.REFUSE;
        for (final DrawerCard drawer : drawers.values()) if (drawer.model() != null
            && inside(drawer.model().drawer.getX(), drawer.model().drawer.getY(), DrawerCard.W, DrawerCard.H, wx, wy))
            return Verdict.REFUSE;
        if (portDrag.socket()) return Verdict.DEAD;
        return session.portHasDrawer(portDrag.nodeId(), portDrag.output(), portDrag.port()) ? Verdict.DEAD
            : Verdict.SPAWN;
    }

    /**
     * Where the wire in hand ends when it is over something that takes it: the port it would be wired to on a card, a
     * drawer's side; null to follow the mouse.
     */
    @Nullable
    private int[] snapEnd(final UUID target) {
        final RecipeCard card = cards.get(target);
        if (card != null && card.model() != null && !portDrag.socket()) {
            final BoardSession.PortRef to = session
                .dropTarget(portDrag.nodeId(), portDrag.output(), portDrag.port(), target);
            if (to == null) return null;
            final Node n = card.model().node;
            return new int[] { n.x + CardLayout.anchorX(to.output()),
                n.y + card.anchorY(to.node(), to.output(), to.index()) };
        }
        final DrawerCard drawer = drawers.get(target);
        if (drawer == null || drawer.model() == null) return null;
        final Drawer d = drawer.model().drawer;
        // A source gives from its right side, the rest take on their left.
        return new int[] { d.getKind()
            .linksInputs() ? d.getX() + DrawerCard.W : d.getX(), d.getY() + DrawerCard.ANCHOR_Y };
    }

    /** The wire in hand's colours: green that it will go, red that it will not (Factory Flow's). */
    private static final int WIRE_GO = 0xFF22C55E, WIRE_NO = 0xFFEF4444;

    private void drawPortDrag() {
        final RecipeCard card = cards.get(portDrag.nodeId());
        if (card == null || card.model() == null) return;
        final Node n = card.model().node;
        final int sx = n.x + CardLayout.anchorX(portDrag.output());
        final int sy = n.y + (portDrag.socket() ? com.gtnhplanner.ui.card.SocketSlot.anchorY()
            : card.anchorY(portDrag.nodeId(), portDrag.output(), portDrag.port()));
        final int mouseX = Math.round(worldX(getContext().getAbsMouseX()));
        final int mouseY = Math.round(worldY(getContext().getAbsMouseY()));
        if (!dragHeard && Math.abs(getContext().getAbsMouseX() - portDrag.startX())
            + Math.abs(getContext().getAbsMouseY() - portDrag.startY()) >= 4) {
            dragHeard = true;
            Sfx.WIRE_GRAB.play();
        }
        final UUID over = dragTargetAt(mouseX, mouseY);
        if (over != null && !over.equals(snappedTo)) Sfx.WIRE_SNAP.play();
        snappedTo = over;
        final Verdict verdict = verdictAt(mouseX, mouseY);
        final int[] snap = over == null ? null : snapEnd(over);
        final int mx = snap != null ? snap[0] : mouseX, my = snap != null ? snap[1] : mouseY;
        // The bend stays on the port's own side, so the wire in hand never cuts back across its card.
        final int bend = portDrag.output() ? Math.max(sx + 16, (sx + mx) / 2) : Math.min(sx - 16, (sx + mx) / 2);
        final List<int[]> path = portDrag.output()
            ? List.of(new int[] { sx, sy }, new int[] { bend, sy }, new int[] { bend, my }, new int[] { mx, my })
            : List.of(new int[] { mx, my }, new int[] { bend, my }, new int[] { bend, sy }, new int[] { sx, sy });
        // Green that it will go (solid onto a port, dotted where it makes a drawer), red that it will not.
        final boolean go = verdict == Verdict.CONNECT || verdict == Verdict.SPAWN;
        final boolean solid = verdict == Verdict.CONNECT || verdict == Verdict.REFUSE;
        WireLayer.drawWire(path, go ? WIRE_GO : WIRE_NO, 3, solid);
        if (verdict == Verdict.DEAD && !portDrag.socket()) {
            // Said by the mouse, at the board's text size whatever the zoom.
            final float s = 1 / graph().getZoom();
            Hyb.text("Drawer already exists", mouseX + 8 * s, mouseY + 6 * s, s, WIRE_NO);
        }
    }

    // endregion

    /**
     * An item dragged in from NEI's list and dropped on the board: offers it as a drawer there, a product or a source.
     */
    public void dropNeiItem(final ItemStack stack, final int screenX, final int screenY) {
        final int wx = Math.round(worldX(screenX)), wy = Math.round(worldY(screenY));
        final String key = com.gtnhplanner.ui.Resources.keyOf(stack);
        // A cell dropped in is its fluid, and is named as the fluid.
        final net.minecraftforge.fluids.FluidStack fluid = com.gtnhplanner.ui.Resources.fluid(key);
        final String label = fluid != null ? fluid.getLocalizedName() : stack.getDisplayName();
        final List<PickList.Entry> rows = new ArrayList<>();
        rows.add(
            new PickList.Entry(
                stack,
                "Add as a product",
                "",
                Hyb.PRODUCT_INK,
                false,
                () -> session
                    .addDrawer(Drawer.Kind.PRODUCT, key, label, wx - DrawerCard.W / 2, wy - DrawerCard.ANCHOR_Y)));
        rows.add(
            new PickList.Entry(
                stack,
                "Add as a source",
                "",
                Hyb.SOURCE_INK,
                false,
                () -> session
                    .addDrawer(Drawer.Kind.SOURCE, key, label, wx - DrawerCard.W / 2, wy - DrawerCard.ANCHOR_Y)));
        Popup.open(getPanel(), PickList.popup("gtnhplanner_drop", null, rows, false, 150), screenX, screenY);
    }

    // region Show me

    /** Cards and drawers just added (from NEI, or pasted); brought into view once their widgets exist. */
    private List<UUID> reveal = List.of();
    /** Whether {@link #reveal} is the plan button's card, to centre and select rather than just bring into view. */
    private boolean revealFocus;

    /**
     * Pans the least needed to show a world box (with a margin), keeping the zoom; frames it instead when it is bigger
     * than the view.
     */
    /**
     * Centres a card and selects it, at the current zoom when its ports can be read there, else the closest step where
     * they can (never closer than fits it): what the plan button just added, from NEI.
     */
    private void focusBox(final UUID id, final int[] box) {
        session.select(id, false);
        float readable = ZOOMS[ZOOMS.length - 1];
        for (final float z : ZOOMS) {
            if (z > RecipeCard.GLANCE_ZOOM * 1.4f) {
                readable = z;
                break;
            }
        }
        final float zoom = Math.min(Math.max(graph().getZoom(), readable), fitZoom(box[0], box[1], box[2], box[3]));
        moveCamera(zoom, (box[0] + box[2]) / 2f, (box[1] + box[3]) / 2f, getArea().width / 2f, getArea().height / 2f);
    }

    /**
     * Brings a world box into view, panning the least it takes (framing it when it is bigger than the view), unless the
     * camera is already on its way somewhere. For the tour, which points only at what can be seen.
     */
    public void bringIntoView(final int x0, final int y0, final int x1, final int y1) {
        if (cameraStart < 0) revealBox(x0, y0, x1, y1);
    }

    private void revealBox(final int x0, final int y0, final int x1, final int y1) {
        final Graph g = graph();
        final float z = g.getZoom(), m = 16;
        final int w = getArea().width, h = getArea().height;
        if ((x1 - x0) * z > w - 2 * m || (y1 - y0) * z > h - 2 * m) {
            frameBox(x0, y0, x1, y1);
            return;
        }
        final float left = g.getPanX() + x0 * z, right = g.getPanX() + x1 * z;
        final float top = g.getPanY() + y0 * z, bottom = g.getPanY() + y1 * z;
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
        stopPanGlide();
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

    /**
     * The board is not at rest for a click: the camera moving on its own (easing to a zoom or a place, gliding out of
     * a fling), or its widgets about to be built afresh or just built (what is under the mouse is found a frame
     * later). For the tour, which clicks as fast as the board allows.
     */
    public boolean busy() {
        return cameraStart >= 0 || glideVX != 0
            || glideVY != 0
            || builtStructure != session.structure()
            || System.currentTimeMillis() - builtAt < 120;
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

    /**
     * Arranges the plan ({@link com.gtnhplanner.layout.arrange.Arrange}: the column pass, its optimiser and the free
     * placement, each routed by the board's router, the best two polished), keeping it where it was on the board. It
     * runs in the background, a second or two on most plans and longer on a big one; the key shows how far it has got
     * and a second press stops it. When it is done everything glides to its place and the plan is framed: one
     * undoable step.
     */
    public void arrange() {
        if (arrangeJob != null) {
            arrangeCancelled = true;
            return;
        }
        final Graph g = graph();
        // A shared machine is one box: every recipe on it answers to its card.
        final List<com.gtnhplanner.layout.arrange.ArrangeCard> boxes = new ArrayList<>();
        final java.util.Map<UUID, UUID> owner = new HashMap<>();
        for (final RecipeCard card : new java.util.LinkedHashSet<>(cards.values())) {
            final Node n = g.nodes.get(card.nodeId);
            if (n == null || card.model() == null || card.layout() == null) continue;
            for (int s = 0; s < card.layout()
                .sections(); s++) owner.put(card.sectionId(s), card.nodeId);
            boxes.add(
                new com.gtnhplanner.layout.arrange.ArrangeCard(
                    card.nodeId.toString(),
                    n.x,
                    n.y,
                    CardLayout.W,
                    card.layout().height,
                    false));
        }
        for (final Drawer d : g.getDrawers()) boxes.add(
            new com.gtnhplanner.layout.arrange.ArrangeCard(
                d.getId()
                    .toString(),
                d.getX(),
                d.getY(),
                DrawerCard.W,
                DrawerCard.H,
                true));
        if (boxes.isEmpty()) return;
        // The wires, with the ports they leave and enter at, weighed by the width they are drawn at.
        final java.util.Map<UUID, Double> width = new HashMap<>();
        for (final WireLayer.Wire w : wires.wires(cards, drawers, false)) width.put(w.key(), (double) w.width());
        final List<com.gtnhplanner.layout.arrange.ArrangeWire> links = new ArrayList<>();
        for (final Edge e : g.getEdges()) {
            final UUID from = owner.get(e.sourceNodeId), to = owner.get(e.targetNodeId);
            if (from == null || to == null) continue;
            links.add(
                new com.gtnhplanner.layout.arrange.ArrangeWire(
                    e.id.toString(),
                    from.toString(),
                    to.toString(),
                    (double) cards.get(e.sourceNodeId)
                        .anchorY(e.sourceNodeId, true, e.sourceOutputIndex),
                    (double) cards.get(e.targetNodeId)
                        .anchorY(e.targetNodeId, false, e.targetInputIndex),
                    null,
                    width.get(e.id)));
        }
        for (final Drawer d : g.getDrawers()) {
            final boolean source = d.getKind()
                .linksInputs();
            for (final Drawer.Link link : d.getLinks()) {
                final UUID card = owner.get(link.nodeId());
                if (card == null) continue;
                final UUID key = UUID.nameUUIDFromBytes(
                    (d.getId() + ":" + link.nodeId() + ":" + link.portIndex())
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                final double port = cards.get(link.nodeId())
                    .anchorY(link.nodeId(), !source, link.portIndex());
                final String drawer = d.getId()
                    .toString();
                links.add(
                    source
                        ? new com.gtnhplanner.layout.arrange.ArrangeWire(
                            key.toString(),
                            drawer,
                            card.toString(),
                            null,
                            port,
                            null,
                            width.get(key))
                        : new com.gtnhplanner.layout.arrange.ArrangeWire(
                            key.toString(),
                            card.toString(),
                            drawer,
                            port,
                            null,
                            null,
                            width.get(key)));
            }
        }
        arrangeCancelled = false;
        arrangeProgress = null;
        arrangeShown = 0;
        arrangeGraph = g;
        arrangeJob = ARRANGER.submit(
            () -> com.gtnhplanner.layout.arrange.Arrange.arrange(
                new com.gtnhplanner.layout.arrange.Arrange.Input(
                    boxes,
                    links,
                    null,
                    "compact",
                    new com.gtnhplanner.layout.arrange.RouterJudge(boxes, links),
                    null,
                    null,
                    progress -> arrangeProgress = progress,
                    () -> arrangeCancelled)));
    }

    /**
     * The arrange working in the background, if any, on which plan, where it has got to, and whether it was stopped.
     */
    @Nullable
    private java.util.concurrent.Future<com.gtnhplanner.layout.arrange.Arrange.Result> arrangeJob;
    @Nullable
    private Graph arrangeGraph;
    private volatile com.gtnhplanner.layout.arrange.Arrange.Progress arrangeProgress;
    private volatile boolean arrangeCancelled;
    /** How far the key says it has got (it never goes back, though the two polishes report in turn). */
    private float arrangeShown;

    private static final java.util.concurrent.ExecutorService ARRANGER = java.util.concurrent.Executors
        .newSingleThreadExecutor(r -> {
            final Thread t = new Thread(r, "GTNH Planner arrange");
            t.setDaemon(true);
            return t;
        });

    /** Whether an arrange is working. */
    public boolean arranging() {
        return arrangeJob != null;
    }

    /** The Arrange key's label: how far an arrange under way has got. */
    public String arrangeLabel() {
        if (arrangeJob == null) return "Arrange";
        final com.gtnhplanner.layout.arrange.Arrange.Progress p = arrangeProgress;
        if (p != null) {
            final float within = p.total() > 0 ? Math.min(1, p.done() / (float) p.total()) : 0;
            arrangeShown = Math.max(arrangeShown, (p.step() + within) / 6);
        }
        return arrangeCancelled ? "Stopping" : "Arranging " + Math.round(arrangeShown * 100) + "%";
    }

    /** Takes an arrange that has finished: everything glides to its place, as one undoable step. */
    private void collectArrange() {
        if (arrangeJob == null || !arrangeJob.isDone()) return;
        final java.util.concurrent.Future<com.gtnhplanner.layout.arrange.Arrange.Result> job = arrangeJob;
        arrangeJob = null;
        final com.gtnhplanner.layout.arrange.Arrange.Result result;
        try {
            result = job.get();
        } catch (final java.util.concurrent.ExecutionException e) {
            if (!(e.getCause() instanceof com.gtnhplanner.layout.arrange.Arrange.Cancelled))
                com.gtnhplanner.GtnhPlanner.LOG.warn("[arrange] failed", e.getCause());
            return;
        } catch (final InterruptedException e) {
            Thread.currentThread()
                .interrupt();
            return;
        }
        // Another plan came up meanwhile: this one's places are not for it.
        if (graph() != arrangeGraph) return;
        com.gtnhplanner.GtnhPlanner.LOG
            .info("[arrange] chose the {} layout, {} points", result.chosen(), Math.round(result.points()));
        final Graph g = graph();
        // Where everything is now, and where it goes (cards and drawers made since keep their places).
        final java.util.Map<UUID, int[]> from = new HashMap<>(), to = new HashMap<>();
        for (final java.util.Map.Entry<String, com.gtnhplanner.layout.arrange.Point> e : result.positions()
            .entrySet()) {
            final UUID id = UUID.fromString(e.getKey());
            final int[] at = { (int) Math.round(
                e.getValue()
                    .x()),
                (int) Math.round(
                    e.getValue()
                        .y()) };
            final Node n = g.nodes.get(id);
            final Drawer d = n == null ? g.getDrawer(id) : null;
            if (n == null && d == null) continue;
            from.put(id, n != null ? new int[] { n.x, n.y } : new int[] { d.getX(), d.getY() });
            to.put(id, at);
        }
        if (to.isEmpty()) return;
        // One undoable step with the final places, then rewind and let everything glide there.
        session.editLayout(() -> place(to));
        Sfx.SWEEP.play();
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
                // A shared machine's recipes all sit where its card does.
                for (final UUID s : session.sectionsOf(n.id)) {
                    final Node section = g.nodes.get(s);
                    if (section != null) {
                        section.x = n.x;
                        section.y = n.y;
                    }
                }
                continue;
            }
            final com.gtnhplanner.data.flowchart.Note note = g.notes.get(e.getKey());
            if (note != null) {
                note.setX(e.getValue()[0]);
                note.setY(e.getValue()[1]);
                final com.gtnhplanner.ui.note.NoteCard widget = notes.get(note.getId());
                if (widget != null) widget.sync();
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
            com.gtnhplanner.api.PlanAPI.save();
        }
    }

    /** Frames every card and drawer on the board. */
    /** Frames the whole plan once its cards exist: a plan just opened from the library. */
    public void frameAllWhenBuilt() {
        frameAllPending = true;
    }

    private boolean frameAllPending;

    public void frameAll() {
        final List<UUID> ids = new ArrayList<>(cards.keySet());
        ids.addAll(drawers.keySet());
        ids.addAll(notes.keySet());
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
            final com.gtnhplanner.data.flowchart.Note note = notes.containsKey(id) ? graph().notes.get(id) : null;
            if (note != null) {
                x0 = Math.min(x0, note.getX());
                y0 = Math.min(y0, note.getY());
                x1 = Math.max(x1, note.getX() + note.getWidth());
                y1 = Math.max(y1, note.getY() + note.getHeight());
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
            PickList.popup("gtnhplanner_wire", null, rows, false, 220),
            getContext().getAbsMouseX(),
            getContext().getAbsMouseY());
        return true;
    }

    // endregion

    // region Pan and zoom

    /** A right-click on empty board: what can be added there. */
    private void openBoardMenu() {
        final int sx = getContext().getAbsMouseX(), sy = getContext().getAbsMouseY();
        final int wx = Math.round(worldX(sx)), wy = Math.round(worldY(sy));
        final List<PickList.Entry> rows = new ArrayList<>();
        rows.add(PickList.Entry.of("Add a sticky note", () -> addNoteAt(wx, wy)));
        Popup.open(getPanel(), PickList.popup("gtnhplanner_board_menu", null, rows, false, 140), sx, sy);
    }

    @Override
    public Result onMousePressed(final int mouseButton) {
        if (mouseButton == 1) {
            if (!openWireMenu()) openBoardMenu();
            return Result.SUCCESS;
        }
        return mouseButton == 0 ? Result.ACCEPT : Result.IGNORE;
    }

    // region Pan glide

    /**
     * Let go of a pan while moving and the board drifts on briefly, as on the website: released motion bleeds off over
     * {@value #GLIDE_TAU_MS} ms; a slow or parked release does not glide.
     */
    private static final double GLIDE_TAU_MS = 100, GLIDE_STOP_PX_MS = 0.01, GLIDE_START_PX_MS = 0.04;
    private static final double GLIDE_STALE_MS = 90, GLIDE_MIN_SPAN_MS = 30;
    /** Recent pans as {time ms, panX, panY}, the last 120 ms at most. */
    private final java.util.ArrayDeque<double[]> panSamples = new java.util.ArrayDeque<>();
    private double glideVX, glideVY, glideLast;

    private void samplePan() {
        final double now = System.nanoTime() / 1e6;
        panSamples.addLast(new double[] { now, graph().getPanX(), graph().getPanY() });
        while (panSamples.size() > 8 || panSamples.size() > 2 && panSamples.peekFirst()[0] < now - 120)
            panSamples.removeFirst();
    }

    private void startPanGlide() {
        final double now = System.nanoTime() / 1e6;
        if (panSamples.size() >= 2) {
            final double[] oldest = panSamples.peekFirst(), newest = panSamples.peekLast();
            final double span = newest[0] - oldest[0];
            if (span >= GLIDE_MIN_SPAN_MS && now - newest[0] <= GLIDE_STALE_MS) {
                final double vx = (newest[1] - oldest[1]) / span, vy = (newest[2] - oldest[2]) / span;
                if (Math.hypot(vx, vy) >= GLIDE_START_PX_MS) {
                    glideVX = vx;
                    glideVY = vy;
                    glideLast = now;
                }
            }
        }
        panSamples.clear();
    }

    /** A hand on the board, or the camera moving on its own, stops a glide dead. */
    private void stopPanGlide() {
        glideVX = glideVY = 0;
        panSamples.clear();
    }

    /**
     * Held pan keys (WASD, arrows) ease up to this speed in GUI pixels per ms: the website's 1.05 screen pixels per ms
     * at GUI scale 2.
     */
    private static final double KEY_CRUISE_PX_MS = 0.55, KEY_ACCEL_TAU_MS = 70;

    private void stepPanGlide() {
        final double now = System.nanoTime() / 1e6, dt = Math.min(50, now - glideLast);
        glideLast = now;
        final int[] keys = heldPanKeys();
        if (keys != null) {
            // Held keys steer the same velocity a fling leaves: they ease up to cruise, and glide out when let go.
            final double len = Math.hypot(keys[0], keys[1]);
            final double approach = 1 - Math.exp(-dt / KEY_ACCEL_TAU_MS);
            glideVX += (KEY_CRUISE_PX_MS * keys[0] / len - glideVX) * approach;
            glideVY += (KEY_CRUISE_PX_MS * keys[1] / len - glideVY) * approach;
        } else if (glideVX == 0 && glideVY == 0) return;
        graph().setPanX((float) (graph().getPanX() + glideVX * dt));
        graph().setPanY((float) (graph().getPanY() + glideVY * dt));
        if (keys != null) return;
        final double decay = Math.exp(-dt / GLIDE_TAU_MS);
        glideVX *= decay;
        glideVY *= decay;
        if (Math.hypot(glideVX, glideVY) < GLIDE_STOP_PX_MS) glideVX = glideVY = 0;
    }

    /**
     * The direction the board moves for the pan keys held now (W/A/S/D or the arrows; the board moves opposite the
     * camera), or null when none is held or the keys belong to something else: a text field, NEI's search, a popup, or
     * a
     * Ctrl/Alt shortcut (Ctrl+A, Ctrl+S...).
     */
    @Nullable
    private int[] heldPanKeys() {
        if (net.minecraft.client.gui.GuiScreen.isCtrlKeyDown()
            || org.lwjgl.input.Keyboard.isKeyDown(org.lwjgl.input.Keyboard.KEY_LMENU)
            || org.lwjgl.input.Keyboard.isKeyDown(org.lwjgl.input.Keyboard.KEY_RMENU)) return null;
        final int x = (held(org.lwjgl.input.Keyboard.KEY_A, org.lwjgl.input.Keyboard.KEY_LEFT) ? 1 : 0)
            - (held(org.lwjgl.input.Keyboard.KEY_D, org.lwjgl.input.Keyboard.KEY_RIGHT) ? 1 : 0);
        final int y = (held(org.lwjgl.input.Keyboard.KEY_W, org.lwjgl.input.Keyboard.KEY_UP) ? 1 : 0)
            - (held(org.lwjgl.input.Keyboard.KEY_S, org.lwjgl.input.Keyboard.KEY_DOWN) ? 1 : 0);
        if (x == 0 && y == 0) return null;
        // Over NEI's list the keys are NEI's (A bookmarks the item under the mouse).
        if (!getPanel().getArea()
            .isInside(getContext().getAbsMouseX(), getContext().getAbsMouseY())) return null;
        if (com.gtnhplanner.ui.BoardScreen.textFocused(getScreen())
            || codechicken.nei.LayoutManager.getInputFocused() != null
            || getScreen().getPanelManager()
                .getTopMostPanel() instanceof Popup)
            return null;
        return new int[] { x, y };
    }

    private static boolean held(final int key, final int alt) {
        return org.lwjgl.input.Keyboard.isKeyDown(key) || org.lwjgl.input.Keyboard.isKeyDown(alt);
    }

    // endregion

    /** Whether the middle button is panning the board: from anywhere on it, over cards too, as on the website. */
    private boolean middlePan;

    /**
     * Starts a middle-button pan. ModularUI only drags with the left button, so the screen starts this on the press,
     * the pan follows the mouse frame by frame, and the screen ends it on the release.
     */
    public void beginMiddlePan() {
        stopPanGlide();
        cameraStart = -1;
        middlePan = true;
        panStartX = graph().getPanX();
        panStartY = graph().getPanY();
        panMouseX = getContext().getAbsMouseX();
        panMouseY = getContext().getAbsMouseY();
    }

    /**
     * A middle-click that did not pan: on a drawer's rule or rate it clears the drawer back to "rate?", as on the
     * website.
     */
    private void middleClick() {
        for (final DrawerCard d : drawers.values()) {
            final DrawerCard.Part part = d.partUnderMouse();
            if ((part == DrawerCard.Part.RULE || part == DrawerCard.Part.RATE) && d.model() != null) {
                session.setDrawerRate(d.model().drawer, 0);
                return;
            }
        }
    }

    /** Ends a middle-button pan on the release: a press that never moved is a middle-click. */
    public void endMiddlePan() {
        if (!middlePan) return;
        stepMiddlePan();
        middlePan = false;
        if (graph().getPanX() == panStartX && graph().getPanY() == panStartY) middleClick();
        else startPanGlide();
    }

    private void stepMiddlePan() {
        if (!middlePan) return;
        graph().setPanX(panStartX + getContext().getAbsMouseX() - panMouseX);
        graph().setPanY(panStartY + getContext().getAbsMouseY() - panMouseY);
    }

    @Override
    public boolean onMouseScroll(final UpOrDown direction, final int amount) {
        zoomStep(
            direction == UpOrDown.UP ? 1 : -1,
            getContext().getAbsMouseX() - getArea().x,
            getContext().getAbsMouseY() - getArea().y);
        return true;
    }

    /** A zoom key (+, -, Page Up, Page Down): one step, about the middle of the view. */
    public void zoomKey(final int steps) {
        zoomStep(steps, getArea().width / 2f, getArea().height / 2f);
    }

    /** Steps the zoom keeping the board point under (sx, sy) in place. */
    private void zoomStep(final int steps, final float sx, final float sy) {
        final Graph g = graph();
        // Steps chain: a second notch during the ease continues from where the first one is heading.
        final float from = cameraStart >= 0 ? toZoom : g.getZoom();
        final int i = Math.max(0, Math.min(ZOOMS.length - 1, nearestZoom(from) + steps));
        final float next = ZOOMS[i];
        if (next == from) return;
        moveCamera(next, (sx - g.getPanX()) / g.getZoom(), (sy - g.getPanY()) / g.getZoom(), sx, sy);
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
        if (button != 0) return false;
        stopPanGlide();
        cameraStart = -1;
        boxing = net.minecraft.client.gui.GuiScreen.isShiftKeyDown();
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
        // A click on empty board (no pan) clears the selection; a pan let go while moving glides on.
        if (graph().getPanX() == panStartX && graph().getPanY() == panStartY) session.clearSelection();
        else startPanGlide();
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

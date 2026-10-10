package com.gtnhplanner.ui.card;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.item.ItemStack;

import org.jetbrains.annotations.Nullable;

import com.cleanroommc.modularui.api.UpOrDown;
import com.cleanroommc.modularui.api.widget.IDraggable;
import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.widget.ParentWidget;
import com.cleanroommc.modularui.widget.sizer.Area;
import com.gtnhplanner.data.MachineConfig;
import com.gtnhplanner.data.SettingDef;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.provider.GTKeys;
import com.gtnhplanner.ui.BoardSession;
import com.gtnhplanner.ui.canvas.BoardCanvas;
import com.gtnhplanner.ui.gt.GtCoils;
import com.gtnhplanner.ui.gt.GtMachines;
import com.gtnhplanner.ui.gt.MultiblockPictures;
import com.gtnhplanner.ui.popup.NumberPopup;
import com.gtnhplanner.ui.popup.PickList;
import com.gtnhplanner.ui.popup.Popup;
import com.gtnhplanner.ui.popup.Tip;
import com.gtnhplanner.ui.theme.Fmt;
import com.gtnhplanner.ui.theme.Hyb;

/**
 * One recipe on the board, drawn as Factory Flow's recipe card in Solve mode: head (actions, machine, amps, tier),
 * rails of NEI slots with names and rates, the machine, settings, and a footer with power and the solved machine
 * count. Lives in world space inside the canvas; dragging its body moves the recipe, its controls edit it.
 */
public final class RecipeCard extends ParentWidget<RecipeCard> implements Interactable, IDraggable {

    /** The parts of a card that react to the mouse. */
    public enum Part {
        ACTIONS,
        /** The key that places the card on a block in the world. */
        PLACE,
        MACHINE,
        AMPS,
        TIER,
        /** The gear key, which opens the card's settings. */
        SETTINGS,
        MACHINES,
        POWER,
        CIRCUIT,
        /** A key on a shared machine's recipe rule: up, down or off. */
        SECTION,
        /** A setting pinned to the card, as a chip. */
        CHIP,
        BODY
    }

    private static final int CHIP_Y = CardLayout.HEAD_Y;
    private static final int CHIP_H = CardLayout.HEAD;
    private static final int KEY_X = CardLayout.HEAD_Y;
    private static final int KEY_W = CardLayout.HEAD;
    /** The chips stand as far from the card's right edge as from its top. */
    private static final int RIGHT = CardLayout.W - CardLayout.HEAD_Y;
    /** A multiblock's amps and tier chips, two apart; a single block's lone tier chip. */
    private static final int CHIP_W = 48;
    private static final int SINGLE_TIER_W = 42;
    private static final int AMPS_X = RIGHT - 2 * CHIP_W - 2;
    private static final int PLACE_X = KEY_X + KEY_W + 2;
    private static final int GEAR_X = PLACE_X + KEY_W + 2;
    /** Where the machine's name starts, after the keys. */
    private static final int BAR_X = GEAR_X + KEY_W + 8;

    private final BoardSession session;
    /** The card's own node: the recipe it was made for, the first on a shared machine. */
    public final UUID nodeId;
    private CardModel model;
    /** Every recipe on the card, top first, and their models: one, or several on a shared machine. */
    private List<UUID> sections = List.of();
    private List<CardModel> models = List.of();
    private CardLayout layout;

    private boolean moving;

    public RecipeCard(final BoardSession session, final UUID nodeId) {
        this.session = session;
        this.nodeId = nodeId;
        refresh();
        for (int s = 0; s < models.size(); s++) {
            final CardModel m = models.get(s);
            for (int i = 0; i < m.inputs.size(); i++) child(new PortSlot(this, s, false, i));
            for (int i = 0; i < m.outputs.size(); i++) child(new PortSlot(this, s, true, i));
        }
        if (emptyCustomRate()) {
            child(new SocketSlot(this, false));
            child(new SocketSlot(this, true));
        }
    }

    /** A custom rate card holding nothing yet: it shows its two sockets instead of ports. */
    public boolean emptyCustomRate() {
        return model != null && com.gtnhplanner.power.CustomRate.is(model.node)
            && model.inputs.isEmpty()
            && model.outputs.isEmpty();
    }

    /**
     * The port on one side at a card-local height, {section, index}: the row under it, else the side's only port when
     * it has just one; null for none.
     */
    public int[] portAt(final boolean output, final float y) {
        int count = 0, onlySection = -1, only = -1;
        for (int s = 0; s < models.size(); s++) {
            final List<CardModel.PortView> ports = output ? models.get(s).outputs : models.get(s).inputs;
            for (int i = 0; i < ports.size(); i++) {
                count++;
                onlySection = s;
                only = i;
                final int top = layout.rowY(s, output, i);
                if (y >= top && y < top + CardLayout.ROW) return new int[] { s, i };
            }
        }
        return count == 1 ? new int[] { onlySection, only } : null;
    }

    public CardModel model() {
        return model;
    }

    /** A recipe's model, by its place on the card. */
    public CardModel modelOf(final int section) {
        return section >= 0 && section < models.size() ? models.get(section) : null;
    }

    /** A recipe's node id, by its place on the card. */
    public UUID sectionId(final int section) {
        return section >= 0 && section < sections.size() ? sections.get(section) : nodeId;
    }

    /** Where a recipe is on the card; -1 when it is not on it. */
    public int sectionOf(final UUID id) {
        return sections.indexOf(id);
    }

    public boolean shared() {
        return models.size() > 1;
    }

    /** Where a wire meets the card for one recipe's port. */
    public int anchorY(final UUID node, final boolean output, final int port) {
        return layout.anchorY(Math.max(0, sectionOf(node)), output, port);
    }

    public BoardSession session() {
        return session;
    }

    public CardLayout layout() {
        return layout;
    }

    /** Re-reads the models after a solve; resizes when the card's shape changed. */
    public void refresh() {
        final List<UUID> ids = session.sectionsOf(nodeId);
        final List<CardModel> next = new ArrayList<>(ids.size());
        for (final UUID id : ids) {
            final CardModel m = session.model(id);
            if (m == null) return;
            next.add(m);
        }
        if (next.isEmpty()) return;
        sections = ids;
        models = next;
        model = next.get(0);
        final CardLayout nextLayout = new CardLayout(next, session.graph());
        if (layout == null || layout.height != nextLayout.height) {
            layout = nextLayout;
            size(CardLayout.W, layout.height);
            scheduleResize();
            for (final com.cleanroommc.modularui.api.widget.IWidget child : getChildren()) {
                if (child instanceof final PortSlot slot) slot.place(layout);
            }
        } else {
            layout = nextLayout;
        }
        pos(model.node.x, model.node.y);
    }

    /** True when the recipes or their port counts no longer match the slot widgets. */
    public boolean shapeChanged() {
        final List<UUID> ids = session.sectionsOf(nodeId);
        if (!ids.equals(sections)) return true;
        for (int s = 0; s < ids.size(); s++) {
            final CardModel next = session.model(ids.get(s)), was = models.get(s);
            if (next != null && (next.inputs.size() != was.inputs.size() || next.outputs.size() != was.outputs.size()))
                return true;
        }
        return false;
    }

    @Override
    public void onUpdate() {
        super.onUpdate();
        boolean changed = false;
        for (int s = 0; s < sections.size() && !changed; s++) changed = session.model(sections.get(s)) != models.get(s);
        if (changed || sections.isEmpty()) refresh();
        if (model != null) commitWheel();
        if (layout != null && layout.pinsVersion != SettingPins.version()) refresh();
    }

    /** The machines the card runs: all its recipes' together on a shared machine. */
    private double machinesTotal() {
        double total = 0;
        for (final CardModel m : models) total += m.machines;
        return total;
    }

    /** What the card draws, as the board's power switches say: all its recipes' together. */
    private double powerTotal() {
        double total = 0;
        for (final CardModel m : models) total += session.power(m);
        return total;
    }

    /** Whether the machine count is pinned: the node's, or a shared machine's own. */
    private boolean pinned() {
        final com.gtnhplanner.data.flowchart.MachineGroup g = session.sharedOf(nodeId);
        return g != null ? g.isPinned() : model.pinned;
    }

    /** Whether a coil too cold stops any of the card's recipes. */
    private boolean coilTooCold() {
        for (final CardModel m : models) if (SettingControls.coilTooCold(m)) return true;
        return false;
    }

    private boolean anyTierTooLow() {
        for (final CardModel m : models) if (m.tierTooLow()) return true;
        return false;
    }

    /** ModularUI only counts a parent widget as hovered when it has a background or tooltip; the card draws itself. */
    @Override
    public boolean canHover() {
        return true;
    }

    // region Geometry and hit testing

    /** The tier chip: beside the amps on a multiblock, alone on a single block, none outside GregTech. */
    private int tierW() {
        return model != null && model.multiblock ? CHIP_W : SINGLE_TIER_W;
    }

    private int tierX() {
        return RIGHT - tierW();
    }

    /** The power card's tier select, stepped by the head's tier chip; null when it has none or is a recipe card. */
    private com.gtnhplanner.power.PowerSetting.Select powerTier() {
        return model != null && model.isPower() ? PowerTiles.tierSetting(model.power.source()) : null;
    }

    /** Whether the head has a tier chip: a GregTech recipe's voltage, or a generator's tier. */
    private boolean hasTier() {
        return model != null && (model.gregtech || powerTier() != null);
    }

    private int barRight() {
        // A recipe outside GregTech has no voltage: no tier chip, and the name runs to the edge.
        if (model != null && !hasTier()) return RIGHT;
        return (model != null && model.multiblock ? AMPS_X : tierX()) - 8;
    }

    private BoardCanvas canvas() {
        return getParent() instanceof final BoardCanvas c ? c : null;
    }

    /** The mouse in card coordinates (GUI pixels at zoom 1), from the absolute mouse. */
    private float localX() {
        final BoardCanvas c = canvas();
        return c == null ? -1 : c.worldX(getContext().getAbsMouseX()) - model.node.x;
    }

    private float localY() {
        final BoardCanvas c = canvas();
        return c == null ? -1 : c.worldY(getContext().getAbsMouseY()) - model.node.y;
    }

    private static boolean in(final float x, final float y, final int rx, final int ry, final int rw, final int rh) {
        return x >= rx && y >= ry && x < rx + rw && y < ry + rh;
    }

    public Part partAt(final float x, final float y) {
        if (model == null) return null;
        // Far out the controls are not drawn, so nothing invisible answers a click or the wheel.
        if (glance()) return Part.BODY;
        if (sectionKeyAt(x, y) != null) return Part.SECTION;
        if (layout.chipAt(x, y) >= 0) return Part.CHIP;
        for (final Part part : Part.values()) {
            if (part == Part.BODY || part == Part.SECTION) continue;
            final int[] r = partRect(part);
            if (r != null && in(x, y, r[0], r[1], r[2], r[3])) return part;
        }
        return Part.BODY;
    }

    /** The part under the mouse, or null when the mouse is off the card. */
    public Part partUnderMouse() {
        if (model == null) return null;
        final float x = localX(), y = localY();
        return x < 0 || y < 0 || x >= CardLayout.W || y >= layout.height ? null : partAt(x, y);
    }

    /** Card-local rectangle {x, y, w, h} of a part, or null when this card has none (no amps on a single block). */
    public int[] partRect(final Part part) {
        if (model == null) return null;
        return switch (part) {
            case ACTIONS -> new int[] { KEY_X, CHIP_Y, KEY_W, CHIP_H };
            case PLACE -> new int[] { PLACE_X, CHIP_Y, KEY_W, CHIP_H };
            case TIER -> hasTier() ? new int[] { tierX(), CHIP_Y, tierW(), CHIP_H } : null;
            case AMPS -> model.multiblock ? new int[] { AMPS_X, CHIP_Y, CHIP_W, CHIP_H } : null;
            case MACHINE -> new int[] { BAR_X, CHIP_Y, barRight() - BAR_X, CHIP_H };
            case SETTINGS -> new int[] { GEAR_X, CHIP_Y, KEY_W, CHIP_H };
            case POWER -> layout.powerRowY >= 0
                ? new int[] { CardLayout.IN_RAIL_X, layout.powerRowY, CardLayout.RAIL_W, CardLayout.ROW }
                : null;
            case MACHINES -> CardPaint.countRect(countText(), layout.picture());
            case CIRCUIT -> model.circuit == null || shared() || model.isPower() ? null
                : layout.circuitRowY >= 0
                    ? new int[] { CardLayout.IN_RAIL_X, layout.circuitRowY, CardLayout.RAIL_W, CardLayout.ROW }
                    : new int[] { layout.picture()[0], layout.picture()[1] + CardLayout.PICTURE - 24, 24, 24 };
            case SECTION, CHIP -> null;
            case BODY -> new int[] { 0, 0, CardLayout.W, layout.height };
        };
    }

    /** The machine count as the card shows it: a wheeled count at once, before the solve catches up. */
    private String countText() {
        return "×" + Fmt.machines(pendingCount > 0 ? pendingCount : machinesTotal());
    }

    /** Screen point of a card-local point, for anchoring popups. */
    private int screenX(final int localX) {
        final BoardCanvas c = canvas();
        return c == null ? 0 : c.screenX(model.node.x + localX);
    }

    private int screenY(final int localY) {
        final BoardCanvas c = canvas();
        return c == null ? 0 : c.screenY(model.node.y + localY);
    }

    // endregion

    // region Drawing

    @Override
    public void draw(final ModularGuiContext context, final WidgetThemeEntry<?> widgetTheme) {
        if (!com.gtnhplanner.dev.DevPerf.on()) {
            drawCard(context, widgetTheme);
            drawLinkMode();
            return;
        }
        final long started = System.nanoTime();
        drawCard(context, widgetTheme);
        drawLinkMode();
        com.gtnhplanner.dev.DevPerf.time("cards", System.nanoTime() - started);
    }

    /**
     * Linking a machine from the world: a card whose recipe runs on it is ringed (gold when it is the card the machine
     * is on now), the rest are dimmed.
     */
    private void drawLinkMode() {
        if (!com.gtnhplanner.ui.world.LinkTarget.active() || model == null || layout == null) return;
        final int h = layout.height;
        final Node node = model.node;
        if (com.gtnhplanner.ui.world.LinkTarget.holds(node)) Hyb.ring(-3, -3, CardLayout.W + 6, h + 6, 2, Hyb.LIT);
        else if (com.gtnhplanner.ui.world.LinkTarget.fits(session.graph(), node))
            Hyb.ring(-4, -4, CardLayout.W + 8, h + 8, 3, Hyb.mix(Hyb.SELECTION, 0xFFFFFF, 0.3f + 0.7f * breathe()));
        else Hyb.rect(0, 0, CardLayout.W, h, 0xC0101114);
    }

    /** A click on the card while linking a machine from the world: this card is the pick. */
    void chooseForLink() {
        if (model != null) com.gtnhplanner.ui.world.LinkTarget.choose(session.graph(), model.node);
    }

    private void drawCard(final ModularGuiContext context, final WidgetThemeEntry<?> widgetTheme) {
        final CardModel m = model;
        if (m == null) return;
        final float z = context.getCurrentDrawingZ();
        if (session.graph()
            .getZoom() <= GLANCE_ZOOM) {
            drawGlance(m, z);
            return;
        }
        if (layout.circuitAsInput != com.gtnhplanner.ui.PlannerSettings.circuitAsInput()) refresh();
        final Part hover = isHovering() ? partAt(localX(), localY()) : null;
        final int w = CardLayout.W, h = layout.height;
        if (canvas() != null && canvas().isCarried(nodeId)) {
            // Lifted while it is carried: a soft shadow under it.
            Hyb.rect(5, 7, w, h, 0x50000000);
            Hyb.rect(3, 4, w, h, 0x40000000);
        } else Hyb.dropShadow(0, 0, w, h);
        boolean unwired = false;
        for (final CardModel each : models) unwired |= anyUnwired(each);
        if (unwired && !com.gtnhplanner.ui.canvas.PlanPicture.drawing()) drawUnwiredRing();
        if (session.showsSelected(nodeId)) Hyb.ring(-2, -2, w + 4, h + 4, 2, Hyb.SELECTION);
        // While a wire is in hand, a card that would take it is outlined green; brighter where it would go now.
        final BoardCanvas board = canvas();
        if (board != null && board.cardAcceptsDrag(nodeId)) {
            final boolean there = nodeId.equals(board.dragSnappedTo());
            Hyb.ring(0, 0, w, h, there ? 2 : 1, there ? CardPaint.ACCEPT : CardPaint.ACCEPT_FAINT);
        }
        CardPaint.surface(w, h);
        if (anyTierTooLow() || coilTooCold()) {
            // Can't run: the tier is below the recipe's (the power row says TIER!) or the coil is too cold (the gear
            // key
            // is red). A red ring outside the card.
            Hyb.rect(-2, -2, w + 4, 2, Hyb.RED_INK);
            Hyb.rect(-2, h, w + 4, 2, Hyb.RED_INK);
            Hyb.rect(-2, 0, 2, h, Hyb.RED_INK);
            Hyb.rect(w, 0, 2, h, Hyb.RED_INK);
        }
        drawHead(m, hover);
        CardPaint.hair(CardLayout.HEADER);
        drawPicture(m, z, hover);
        final int[] key = shared() ? sectionKeyAt(localX(), localY()) : null;
        for (int s = 0; s < models.size(); s++) {
            drawRail(s, models.get(s).inputs, false, z);
            drawRail(s, models.get(s).outputs, true, z);
            if (shared()) drawSectionRule(s, z, isHovering() && key != null && key[0] == s ? key[1] : -1);
        }
        if (layout.circuitRowY >= 0) {
            if (layout.circuitRowY > CardLayout.RAILS_Y) CardPaint.rowSeparator(false, layout.circuitRowY);
            CardPaint.circuitRow(m.circuit, layout.circuitRowY, z);
        }
        if (layout.powerRowY >= 0) drawPowerRow(m, hover == Part.POWER);
        if (emptyCustomRate()) drawSockets();
        drawStrip(m, z, hover);
    }

    /**
     * An empty custom rate card's two sockets, dashed: "Drain any" (takes an output) on the left, "Supply any" (feeds
     * an
     * input) on the right. They light gold while a wire is in hand that the card would take.
     */
    private void drawSockets() {
        final boolean inHand = canvas() != null && canvas().draggingFor(nodeId);
        for (final boolean supply : new boolean[] { false, true }) {
            final int[] r = SocketSlot.rect(supply);
            final boolean hot = isHovering() && localX() >= r[0]
                && localX() < r[0] + r[2]
                && localY() >= r[1]
                && localY() < r[1] + r[3];
            Hyb.rect(r[0], r[1], r[2], r[3], inHand ? 0x30FFD257 : hot ? 0x26FFFFFF : 0x14FFFFFF);
            Hyb.dashed(r[0], r[1], r[2], r[3], inHand || hot ? Hyb.GOLD : 0xFF6A6D78);
            final String label = supply ? "SUPPLY ANY" : "DRAIN ANY";
            Hyb.text(
                label,
                r[0] + (r[2] - Hyb.width(label)) / 2f,
                r[1] + (r[3] - 8) / 2f,
                inHand ? Hyb.GOLD : Hyb.MUTED);
            final String hint = supply ? "feeds an input" : "takes an output";
            Hyb.text(hint, r[0] + (r[2] - Hyb.width(hint)) / 2f, r[1] + r[3] + 6, 0xFF6A6D78);
        }
    }

    /** The custom rate card's own colour: the website paints it blue. */
    static final int CUSTOM_RATE_INK = 0xFFAEBCEB;

    /**
     * At this zoom and below the card shows the glance view instead: the first wheel step out from half size, past
     * which the card's own text is less than a screen pixel per font pixel and breaks up.
     */
    public static final float GLANCE_ZOOM = 0.45f;

    private boolean glance() {
        return session.graph()
            .getZoom() <= GLANCE_ZOOM;
    }

    /**
     * The zoomed-out card: the machine, its name and tier, and the two numbers that matter, in type large enough to
     * read at half zoom or less. Ports stay where they are so wires still meet the card.
     */
    private void drawGlance(final CardModel m, final float z) {
        final int w = CardLayout.W, h = layout.height;
        final float zoom = session.graph()
            .getZoom();
        // The clean card's surface, its edge holding two screen pixels however far out, and the machine large on it.
        Hyb.dropShadow(0, 0, w, h);
        final float rim = Math.max(2, 1 / zoom);
        Hyb.rect(0, 0, w, h, CardPaint.EDGE);
        Hyb.rect(rim, rim, w - 2 * rim, h - 2 * rim, CardPaint.SURFACE);
        if (m.tierTooLow()) Hyb.ring(-rim, -rim, w + 2 * rim, h + 2 * rim, rim, Hyb.RED_INK);
        if (session.showsSelected(nodeId))
            Hyb.ring(-3 * rim, -3 * rim, w + 6 * rim, h + 6 * rim, 2 * rim, Hyb.SELECTION);
        // The machine, big and centred: the whole structure where there is a picture of it.
        // As large as the card allows either way: a tall card (a shared machine) is no wider for it.
        final float side = Math.min(w, h) - 2 * rim - 12;
        if (com.gtnhplanner.power.CustomRate.is(m.node)) {
            // The custom rate card's dial, as big as its picture would be.
            final float scale = side / 9f;
            org.lwjgl.opengl.GL11.glPushMatrix();
            org.lwjgl.opengl.GL11.glTranslatef((w - 9 * scale) / 2f, (h - 8 * scale) / 2f, 0);
            org.lwjgl.opengl.GL11.glScalef(scale, scale, 1);
            com.gtnhplanner.ui.power.PowerPicker.gauge(0, 0, CUSTOM_RATE_INK);
            org.lwjgl.opengl.GL11.glPopMatrix();
        } else drawMachineArt(m, (w - side) / 2f, (h - side) / 2f, side, side, side, z, true);
        // How many, in white in the corner, at whole screen pixels per font pixel so it stays sharp.
        final String count = "×" + Fmt.machines(machinesTotal());
        // Two screen pixels per font pixel, or one far out: whole pixels at every wheel step, so it stays sharp.
        float cs = 1 / zoom;
        if (cs > 4) cs /= 2;
        while (cs > 1 && Hyb.width(count) * cs > w * 0.8f) cs /= 2;
        final float tw = Hyb.width(count) * cs, th = 8 * cs;
        Hyb.text(count, w - rim - 6 - tw, h - rim - 6 - th, cs, machinesTotal() <= 0 ? Hyb.MUTED : Hyb.INK);
        if (canvas() != null) canvas().labelCard(m.machineName, m.node.x, m.node.y, w, h);
    }

    /**
     * Zoomed out, hovering a card shows the card itself as it is zoomed in, the clean card at full size beside the
     * pointer (as the minimap and the plan over the world draw it, from the board's snapshot), in the screen's
     * foreground. False when the card is not in the glance view or not in the snapshot yet (the ordinary tooltip
     * applies).
     */
    public boolean drawReveal(final int mouseX, final int mouseY, final int right, final int bottom) {
        if (model == null || !glance() || !isHovering()) return false;
        final com.gtnhplanner.ui.world.PlanSnapshot snap = com.gtnhplanner.ui.world.PlanSnapshot.latest();
        final com.gtnhplanner.ui.world.PlanSnapshot.Card card = snap == null || snap.graph() != session.graph() ? null
            : snap.cardOf(nodeId);
        if (card == null) return false;
        final int w = CleanCardView.width(), h = CleanCardView.height(card);
        int x = mouseX + 14, y = mouseY + 14;
        if (x + w > right) x = Math.max(2, mouseX - 14 - w);
        if (y + h > bottom) y = Math.max(2, bottom - h);
        Tip.beginPanel();
        org.lwjgl.opengl.GL11.glPushMatrix();
        org.lwjgl.opengl.GL11.glTranslatef(x, y, 0);
        CleanCardView.draw(card, session.rateUnit(), false);
        org.lwjgl.opengl.GL11.glPopMatrix();
        Tip.endPanel();
        return true;
    }

    /** Rounds to the nearest half pixel: one screen pixel at the game's GUI scale 2, so text stays crisp. */
    public static float crisp(final float v) {
        return Math.round(v * 2) / 2f;
    }

    /** Factory Flow's breathing: 0 to 1 and back over 1.9 s, for what wants attention (an unwired port). */
    private static float breathe() {
        // A picture of the plan holds still.
        if (com.gtnhplanner.ui.canvas.PlanPicture.drawing()) return 0;
        final double t = (System.currentTimeMillis() % 1900L) / 1900.0;
        return (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * t));
    }

    private static int alpha(final int rgb, final float a) {
        return Math.round(Math.max(0, Math.min(1, a)) * 255) << 24 | rgb & 0xFFFFFF;
    }

    private static boolean anyUnwired(final CardModel m) {
        for (final CardModel.PortView p : m.inputs) if (!p.wired()) return true;
        for (final CardModel.PortView p : m.outputs) if (!p.wired()) return true;
        return false;
    }

    /** A card with a port still to wire breathes: a dim ring, and a lit one with a halo fading in and out. */
    private void drawUnwiredRing() {
        final int w = CardLayout.W, h = layout.height;
        final float b = breathe();
        Hyb.ring(0, 0, w, h, 1, 0x40C8D2E0);
        Hyb.ring(0, 0, w, h, 1, alpha(0xEEF2F8, 0.85f * b));
        for (int i = 1; i <= 4; i++)
            Hyb.ring(-i, -i, w + 2 * i, h + 2 * i, 1, alpha(0xDCE6F4, 0.3f * b * (5 - i) / 5f));
    }

    /**
     * The header: the actions key and the place key on the left, the machine's name large after them (a menu, with its
     * chevron), the amps and tier chips on the right.
     */
    private void drawHead(final CardModel m, final Part hover) {
        final int y = CHIP_Y;
        Hyb.bevel(
            KEY_X + 1,
            y + 1,
            KEY_W - 2,
            CHIP_H - 2,
            hover == Part.ACTIONS ? Hyb.KEY_HOVER : Hyb.KEY,
            Hyb.KEY_HI,
            Hyb.KEY_LO,
            Hyb.KEY_EDGE,
            1);
        for (int i = 0; i < 3; i++) Hyb.rect(KEY_X + 5, y + 5 + i * 4, 10, 2, Hyb.INK);
        // The place key: a map pin, gold once the card is on a block in the world.
        Hyb.bevel(
            PLACE_X + 1,
            y + 1,
            KEY_W - 2,
            CHIP_H - 2,
            hover == Part.PLACE ? Hyb.KEY_HOVER : Hyb.KEY,
            Hyb.KEY_HI,
            Hyb.KEY_LO,
            Hyb.KEY_EDGE,
            1);
        keyIcon(true, PLACE_X + KEY_W / 2f, y + CHIP_H / 2f, placed() ? Hyb.GOLD : Hyb.INK);
        // The settings key: a gear, red when a setting stops the recipe (a coil too cold).
        Hyb.bevel(
            GEAR_X + 1,
            y + 1,
            KEY_W - 2,
            CHIP_H - 2,
            hover == Part.SETTINGS ? Hyb.KEY_HOVER : Hyb.KEY,
            Hyb.KEY_HI,
            Hyb.KEY_LO,
            Hyb.KEY_EDGE,
            1);
        keyIcon(false, GEAR_X + KEY_W / 2f, y + CHIP_H / 2f, coilTooCold() ? Hyb.RED_INK : Hyb.INK);

        if (m.gregtech) {
            // Amps wear the tier's colours too: together they read as one figure, 16A UV.
            final Hyb.Tier tier = Hyb.tier(m.tier);
            if (m.multiblock) chip(
                AMPS_X,
                y,
                CHIP_W,
                CHIP_H,
                tier,
                Fmt.compact(pendingAmps > 0 ? pendingAmps : m.amps) + "A",
                false,
                hover == Part.AMPS,
                true);
            chip(tierX(), y, tierW(), CHIP_H, tier, tier.name(), tier.underline(), hover == Part.TIER, true);
        } else if (powerTier() != null) {
            final Hyb.Tier tier = Hyb.tier(powerTier().value(m.node.powerSettings.get("tier")));
            chip(tierX(), y, tierW(), CHIP_H, tier, tier.name(), tier.underline(), hover == Part.TIER, true);
        }

        // The name is the machine menu: a faint plate under it while the mouse is on it, and its chevron. A generator
        // has no other machine to pick: its name alone, in the power wing's amber.
        final boolean menu = !m.isPower();
        if (hover == Part.MACHINE && menu) Hyb.rect(BAR_X - 4, y, barRight() - BAR_X + 4, CHIP_H, 0x14FFFFFF);
        final float end = CardPaint.name(
            m.machineName,
            BAR_X,
            barRight() - (menu ? 12 : 0),
            com.gtnhplanner.power.CustomRate.is(m.node) ? CUSTOM_RATE_INK : m.isPower() ? 0xFFFEF3C7 : Hyb.INK);
        if (menu) chevron(Math.round(end + 5), y + 9, hover == Part.MACHINE ? Hyb.INK : Hyb.MUTED);
    }

    /**
     * A key's icon, the place key's map pin or the settings key's gear, centred on a point: drawn as a shape, not
     * pixels,
     * so it stays sharp at any zoom, over a shadow one screen pixel down and right, as the game shades its text.
     */
    private static void keyIcon(final boolean mapPin, final float cx, final float cy, final int color) {
        final float px = 0.5f;
        if (mapPin) {
            mapPin(cx + px, cy + px, 0xFF16181C);
            mapPin(cx, cy, color);
        } else {
            gear(cx + px, cy + px, 0xFF16181C);
            gear(cx, cy, color);
        }
    }

    /** A gear: a ring with a hole in it and eight tapered teeth. */
    private static void gear(final float cx, final float cy, final int color) {
        final float hole = 2.0f, body = 4.5f, root = 4.2f, tip = 6.6f;
        ring(cx, cy, hole, body, color);
        for (int k = 0; k < 8; k++) {
            final double a = k * Math.PI / 4;
            final float[] b0 = polar(cx, cy, root, a - 0.32), b1 = polar(cx, cy, root, a + 0.32),
                t0 = polar(cx, cy, tip, a - 0.21), t1 = polar(cx, cy, tip, a + 0.21);
            Hyb.triangle(b0[0], b0[1], t0[0], t0[1], t1[0], t1[1], color);
            Hyb.triangle(b0[0], b0[1], t1[0], t1[1], b1[0], b1[1], color);
        }
    }

    /** A map pin: a round head with a hole in it, on a point. */
    private static void mapPin(final float cx, final float cy, final int color) {
        final float hx = cx, hy = cy - 2.2f, head = 3.6f;
        ring(hx, hy, 1.3f, head, color);
        Hyb.triangle(hx - 3.1f, cy - 0.7f, hx + 3.1f, cy - 0.7f, hx, cy + 5.6f, color);
    }

    /** A filled ring between two radii. */
    private static void ring(final float cx, final float cy, final float inner, final float outer, final int color) {
        final int seg = 28;
        for (int i = 0; i < seg; i++) {
            final double a0 = 2 * Math.PI * i / seg, a1 = 2 * Math.PI * (i + 1) / seg;
            final float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0), c1 = (float) Math.cos(a1),
                s1 = (float) Math.sin(a1);
            Hyb.triangle(
                cx + inner * c0,
                cy + inner * s0,
                cx + outer * c0,
                cy + outer * s0,
                cx + outer * c1,
                cy + outer * s1,
                color);
            Hyb.triangle(
                cx + inner * c0,
                cy + inner * s0,
                cx + outer * c1,
                cy + outer * s1,
                cx + inner * c1,
                cy + inner * s1,
                color);
        }
    }

    private static float[] polar(final float cx, final float cy, final float r, final double a) {
        return new float[] { cx + r * (float) Math.cos(a), cy + r * (float) Math.sin(a) };
    }

    /** The power wing's amber (the website's text-amber-400). */
    private static final int POWER_AMBER = 0xFFFBBF24;

    /**
     * A chip in a voltage tier's colours, as Factory Flow draws its amps and tier: a raised face, the label large with
     * a
     * hard shadow in the tier's dark, underlined from UV up.
     */
    static void chip(final int x, final int y, final int w, final int h, final Hyb.Tier tier, final String label,
        final boolean underline, final boolean hover) {
        chip(x, y, w, h, tier, label, underline, hover, false);
    }

    /**
     * A chip; {@code snapped} sets its label on the screen's own pixels at a size where every font pixel is a whole
     * number of them (up to the usual 1.5 board pixels), so every stroke is as wide as every other at any zoom.
     */
    static void chip(final int x, final int y, final int w, final int h, final Hyb.Tier tier, final String label,
        final boolean underline, final boolean hover, final boolean snapped) {
        // Flat, in the tier's colours: a darker edge, the face, the label in black or white (whichever reads on it),
        // and no shadow to double it.
        Hyb.rect(x, y, w, h, tier.border());
        Hyb.rect(x + 1, y + 1, w - 2, h - 2, hover ? Hyb.mix(tier.bg(), 0xFFFFFF, 0.88f) : tier.bg());
        final int ink = Hyb.contrastInk(tier.bg());
        float s = 1.5f;
        float[] g = null;
        if (snapped) {
            g = screenGrid();
            // The nearest whole number of screen pixels a font pixel to the usual 1.5, short of overflowing the chip.
            final float perUnit = g[0] * g[4];
            final float most = Math.min((w - 6f) / Math.max(1, Hyb.width(label) - 1), (h - 4f) / 7f);
            s = Math.max(1, Math.min(Math.round(1.5f * perUnit), (int) Math.floor(most * perUnit + 1e-3f))) / perUnit;
        }
        final float tw = (Hyb.width(label) - 1) * s;
        float tx = crisp(x + (w - tw) / 2f), ty = crisp(y + (h - 7 * s) / 2f);
        if (g != null) {
            tx = snap(tx, g[0], g[1], g[4]);
            ty = snap(ty, g[2], g[3], g[4]);
        }
        Hyb.text(label, tx, ty, s, ink, false);
        if (underline) Hyb.rect(tx, ty + 8 * s, tw, 1, ink);
    }

    private static final java.nio.FloatBuffer MATRIX = org.lwjgl.BufferUtils.createFloatBuffer(16);

    /**
     * The current drawing against the screen's pixels: {x scale, x offset, y scale, y offset} in GUI units, then screen
     * pixels per GUI unit.
     */
    private static float[] screenGrid() {
        MATRIX.clear();
        org.lwjgl.opengl.GL11.glGetFloat(org.lwjgl.opengl.GL11.GL_MODELVIEW_MATRIX, MATRIX);
        final int gui = new net.minecraft.client.gui.ScaledResolution(
            net.minecraft.client.Minecraft.getMinecraft(),
            net.minecraft.client.Minecraft.getMinecraft().displayWidth,
            net.minecraft.client.Minecraft.getMinecraft().displayHeight).getScaleFactor();
        final float sx = Math.abs(MATRIX.get(0)), sy = Math.abs(MATRIX.get(5));
        return new float[] { sx > 0 ? sx : 1, MATRIX.get(12), sy > 0 ? sy : 1, MATRIX.get(13), gui };
    }

    /** A drawing coordinate moved onto the nearest screen pixel. */
    private static float snap(final float v, final float scale, final float offset, final float perUnit) {
        return (Math.round((v * scale + offset) * perUnit) / perUnit - offset) / scale;
    }

    private static void chevron(final int x, final int y, final int color) {
        Hyb.rect(x, y, 7, 1, color);
        Hyb.rect(x + 1, y + 1, 5, 1, color);
        Hyb.rect(x + 2, y + 2, 3, 1, color);
        Hyb.rect(x + 3, y + 3, 1, 1, color);
    }

    /**
     * A side's ports: each its icon at the card's edge, how much in large type and its name small under it, a thin line
     * between rows. A port still to wire has its icon ringed with a dashed line that breathes. A row lights up under the
     * mouse, glows while its resource is the one under the mouse, and turns cyan where a dragged wire would land. On a
     * shared machine a recipe with nothing on one side says so.
     */
    private void drawRail(final int section, final List<CardModel.PortView> ports, final boolean output,
        final float z) {
        final int x = CardLayout.railX(output), w = CardLayout.RAIL_W, h = CardLayout.ROW;
        final Fmt.RateUnit unit = session.rateUnit();
        final PortSlot hoveredSlot = getContext().getHovered() instanceof final PortSlot s && s.card() == this ? s
            : null;
        if (ports.isEmpty() && shared()) {
            final int y = layout.railsY(section);
            final String none = output ? "No output" : "No input";
            Hyb.text(none, crisp(x + (w - Hyb.width(none)) / 2f), y + 12, 0xB39A9CA4);
            return;
        }
        final BoardCanvas board = canvas();
        final float b = breathe();
        for (final CardModel.PortView p : ports) {
            final int y = layout.rowY(section, output, p.index());
            if (p.index() > 0) CardPaint.rowSeparator(output, y);
            if (hoveredSlot != null && hoveredSlot.section == section
                && hoveredSlot.output == output
                && hoveredSlot.index == p.index()) Hyb.rect(x, y, w, h, 0x10FFFFFF);
            CardPaint.icon(p.item(), p.fluid(), p.isPower(), output, y, z);
            CardPaint.flowWords(p.perSecond(), p.isPower(), p.isFluid(), p.name(), unit, output, y);
            if (!p.wired()) {
                final int ix = CardLayout.iconX(output) - 2, iy = y + CardLayout.ICON_Y - 2, side = CardLayout.ICON + 4;
                Hyb.dashed(ix, iy, side, side, alpha(0xFFFFFF, 0.3f + 0.55f * b), 3, 3);
            }
            if (session.lit()
                .port(sectionId(section), output, p.index(), p.key())) glow(x, y, w, h);
        }
    }

    /** EU's icon: the power wing's bolt, at a whole scale, on a dark amber square filling the icon's box. */
    public static void euIcon(final float x, final float y, final float size) {
        Hyb.rect(x, y, size, size, 0xFF2A2210);
        Hyb.ring(x, y, size, size, 1, 0xFF6B5418);
        final float s = Math.max(1, (int) ((size - 2) / 11));
        org.lwjgl.opengl.GL11.glPushMatrix();
        org.lwjgl.opengl.GL11.glTranslatef(crisp(x + (size - 7 * s) / 2f), crisp(y + (size - 11 * s) / 2f), 0);
        org.lwjgl.opengl.GL11.glScalef(s, s, 1);
        com.gtnhplanner.ui.power.PowerPicker.bolt(0, 0, POWER_AMBER);
        org.lwjgl.opengl.GL11.glPopMatrix();
    }

    // region Shared machine sections

    /** The keys on a recipe's rule row, from the right: take it off, move it down, move it up. */
    private static final int SECTION_KEY = 12;
    private static final int KEY_OFF_X = RIGHT - SECTION_KEY;
    private static final int KEY_DOWN_X = KEY_OFF_X - 6 - SECTION_KEY;
    private static final int KEY_UP_X = KEY_DOWN_X - 2 - SECTION_KEY;

    /** {section, key} under a card-local point (key 0 up, 1 down, 2 off), or null. */
    private int[] sectionKeyAt(final float x, final float y) {
        if (layout == null || !shared() || glance()) return null;
        for (int s = 0; s < models.size(); s++) {
            final int ky = layout.ruleY(s) + 2;
            if (y < ky || y >= ky + SECTION_KEY) continue;
            if (in(x, y, KEY_UP_X, ky, SECTION_KEY, SECTION_KEY)) return new int[] { s, 0 };
            if (in(x, y, KEY_DOWN_X, ky, SECTION_KEY, SECTION_KEY)) return new int[] { s, 1 };
            if (in(x, y, KEY_OFF_X, ky, SECTION_KEY, SECTION_KEY)) return new int[] { s, 2 };
        }
        return null;
    }

    /**
     * A recipe's rule row on a shared machine. On the left its share: what part of the machine's time it takes and its
     * own machines (or why it takes none); on the right its programmed circuit and the keys that move it up, down, or
     * off the machine.
     */
    private void drawSectionRule(final int s, final float z, final int hotKey) {
        final CardModel m = models.get(s);
        final int y = layout.ruleY(s), h = CardLayout.SECTION_RULE;
        // The share: what part of the machine's time, then its own machines.
        final double total = machinesTotal();
        final String pct = total > 0 ? Math.round(100 * m.machines / total) + "%" : "0%";
        final boolean unwired = anyUnwired(m);
        final String word = unwired ? "NO WIRES" : m.machines <= 0 ? "UNUSED" : "×" + Fmt.machines(m.machines);
        final int wordColor = unwired ? Hyb.AMBER_INK : Hyb.MUTED;
        final int tx = CardLayout.IN_RAIL_X + 4;
        CardPaint.hair(y);
        Hyb.text(pct, tx, y + 4, Hyb.INK);
        Hyb.text(word, tx + 6 + Hyb.width(pct), y + 4, wordColor);
        // The recipe's circuit, then its keys.
        if (m.circuit != null) com.gtnhplanner.ui.gt.CircuitIcons.draw(m.circuit, CardLayout.OUT_RAIL_X, y, 16, z);
        final int ky = y + 2;
        sectionKey(KEY_UP_X, ky, 0, s > 0, hotKey == 0);
        sectionKey(KEY_DOWN_X, ky, 1, s < models.size() - 1, hotKey == 1);
        sectionKey(KEY_OFF_X, ky, 2, true, hotKey == 2);
    }

    private static void sectionKey(final int x, final int y, final int kind, final boolean enabled, final boolean hot) {
        final int ink = !enabled ? 0x4D9A9CA4 : hot ? 0xFFFFFFFF : Hyb.MUTED;
        if (hot && enabled) Hyb.rect(x, y, SECTION_KEY, SECTION_KEY, 0x1AFFFFFF);
        final int cx = x + SECTION_KEY / 2, cy = y + SECTION_KEY / 2;
        switch (kind) {
            case 0 -> {
                for (int i = 0; i < 3; i++) Hyb.rect(cx - i - 1, cy - 2 + i, 2 * i + 2, 1, ink);
            }
            case 1 -> {
                for (int i = 0; i < 3; i++) Hyb.rect(cx - i - 1, cy + 1 - i, 2 * i + 2, 1, ink);
            }
            default -> {
                for (int i = -3; i <= 2; i++) {
                    Hyb.rect(cx + i, cy + i, 1, 1, ink);
                    Hyb.rect(cx + i, cy - 1 - i, 1, 1, ink);
                }
            }
        }
    }

    // endregion

    /** The highlight around a tile whose resource is the one under the mouse: a thin ring and a faint halo. */
    private static void glow(final float x, final float y, final float w, final float h) {
        Hyb.ring(x, y, w, h, 1, alpha(Hyb.LIT & 0xFFFFFF, 0.75f));
        for (int i = 1; i <= 3; i++)
            Hyb.ring(x - i, y - i, w + 2 * i, h + 2 * i, 1, alpha(Hyb.LIT & 0xFFFFFF, 0.24f * (4 - i) / 4f));
    }

    /**
     * The machine in the middle column; the circuit on the picture's bottom left corner (unless it is a row of its
     * own),
     * how many on its bottom right.
     */
    private void drawPicture(final CardModel m, final float z, final Part hover) {
        final int[] box = layout.picture();
        if (com.gtnhplanner.power.CustomRate.is(m.node)) {
            // A dial for the card that is one: the picker key's gauge, four times over.
            org.lwjgl.opengl.GL11.glPushMatrix();
            org.lwjgl.opengl.GL11.glTranslatef(box[0] + (box[2] - 36) / 2f, box[1] + 14, 0);
            org.lwjgl.opengl.GL11.glScalef(4, 4, 1);
            com.gtnhplanner.ui.power.PowerPicker.gauge(0, 0, CUSTOM_RATE_INK);
            org.lwjgl.opengl.GL11.glPopMatrix();
        } else drawMachineArt(m, box[0], box[1], box[2], box[2], CardLayout.ITEM, z, true);
        if (m.circuit != null && !shared() && !m.isPower() && layout.circuitRowY < 0)
            CardPaint.circuitBadge(m.circuit, box, z);
        // Under the count: pinned, or (while the plan has nothing set to solve for) the prompt to set it.
        final boolean pinnedNow = pendingCount > 0 || pinned() && !pendingUnpin;
        final String under = pinnedNow ? CardPaint.PINNED : session.nothingToSolveFor() ? CardPaint.SET_COUNT : null;
        CardPaint.count(
            countText(),
            machinesTotal() <= 0 && pendingCount <= 0,
            under,
            pinnedNow ? Hyb.MUTED : CardPaint.prompt(),
            hover == Part.MACHINES,
            box);
    }

    /**
     * What the machine draws, one more row after its inputs (plans do not wire machine power): the bolt, the power
     * large
     * (the amps, by the board's power key), "Power" small; TIER! in red when the tier is too low to run it.
     */
    private void drawPowerRow(final CardModel m, final boolean hover) {
        final int y = layout.powerRowY, x = CardLayout.IN_RAIL_X;
        if (y > CardLayout.RAILS_Y && !shared()) CardPaint.rowSeparator(false, y);
        if (shared()) CardPaint.hair(y - 3);
        if (hover) Hyb.rect(x, y, CardLayout.RAIL_W, CardLayout.ROW, 0x10FFFFFF);
        euIcon(CardLayout.iconX(false), y + CardLayout.ICON_Y, CardLayout.ICON);
        final double eu = powerTotal();
        final int tierIdx = CardDefaults.tierIndex(m.tier);
        final boolean amps = session.powerKey() == BoardSession.PowerKey.AMPS && m.gregtech && tierIdx >= 0;
        if (anyTierTooLow()) CardPaint.words("TIER!", "", "Power", Hyb.RED_INK, false, y);
        else if (amps)
            CardPaint.words(Fmt.compact(eu / (8L << (2 * tierIdx))), "A " + m.tier, "Power", Hyb.INK, false, y);
        else CardPaint.words(Fmt.power(eu), " EU/t", "Power", eu <= 0 ? 0xFFA8AFBB : Hyb.INK, false, y);
    }

    /**
     * The machine in a box: a multiblock with a bundled render shows the whole structure (fitted, keeping its shape:
     * the power plants are not square); any other GregTech multiblock its structure rendered in game (built once, then
     * cached); anything else its block, at most {@code itemSize}. {@code shadow}: the art casts Factory Flow's shadow.
     */
    private static void drawMachineArt(final CardModel m, final float x, final float y, final float w, final float h,
        final float itemSize, final float z, final boolean shadow) {
        drawMachineArt(
            m.isPower() ? m.node.powerSource : m.machineName,
            m.machineStack,
            x,
            y,
            w,
            h,
            itemSize,
            z,
            shadow);
    }

    /**
     * {@link #drawMachineArt(CardModel, float, float, float, float, float, float, boolean)} by machine name and item.
     */
    private static void drawMachineArt(final String artName, final ItemStack machineStack, final float x, final float y,
        final float w, final float h, final float itemSize, final float z, final boolean shadow) {
        final StructureArt.Art art = StructureArt.forMachine(artName);
        if (art != null) {
            final float scale = Math.min(w / art.width(), h / art.height());
            final float pw = art.width() * scale, ph = art.height() * scale;
            final float px = x + (w - pw) / 2f, py = y + (h - ph) / 2f;
            if (shadow) {
                // Factory Flow's 5px 7px 6px at 60%: offset with the picture's size, its blur baked into the mask.
                final float side = Math.min(pw, ph), pad = StructureArt.SHADOW_PAD * scale;
                Hyb.texture(
                    art.shadow(),
                    px + side * 0.045f - pad,
                    py + side * 0.06f - pad,
                    pw + 2 * pad,
                    ph + 2 * pad,
                    0x7A000000);
            }
            Hyb.texture(art.location(), px, py, pw, ph);
            return;
        }
        // Rendered in game on the well's own background: opaque, so it casts no shadow of its own.
        final MultiblockPictures.Picture structure = MultiblockPictures.get(machineStack);
        if (structure != null) structure.draw(x, y, w, h);
        else if (machineStack != null) {
            final float side = Math.min(itemSize, Math.min(w, h));
            // Near the top when there is room to spare, so the count in the corner keeps clear of it.
            final float ix = x + (w - side) / 2f, iy = y + Math.min((h - side) / 2f, 4);
            if (shadow) Hyb.iconShadow(machineStack, null, ix, iy, side);
            Hyb.item(machineStack, ix, iy, side, z);
        }
    }

    // region Settings strip

    /**
     * The settings strip along the bottom: a hairline, then a chip for each setting pinned to the card
     * ({@link SettingPins}), lit under the mouse where a click changes it; a power card's warnings under it, amber.
     */
    private void drawStrip(final CardModel m, final float z, final Part hover) {
        if (!layout.chips.isEmpty()) CardPaint.hair(layout.stripY);
        final int hot = hover == Part.CHIP ? layout.chipAt(localX(), localY()) : -1;
        for (int i = 0; i < layout.chips.size(); i++) {
            final SettingControls.Control c = layout.chips.get(i);
            final int[] r = layout.chipRect(i);
            final boolean reading = c.type() == SettingControls.Type.READING;
            CardPaint.chip(
                r[0],
                r[1],
                r[2],
                c.label(),
                c.value(),
                c.icon(),
                c.warn(),
                reading,
                !reading && c.enabled() && hot == i,
                z);
        }
        for (int k = 0; k < layout.warningLines.size(); k++)
            Hyb.text(layout.warningLines.get(k), CardLayout.PAD + 2, layout.warningsY + k * 9, Hyb.AMBER_INK);
    }

    /**
     * A click on a setting, from its chip or the settings sheet: the left button flips a switch, asks for a number, or
     * lists the choices (flips one of two); the right steps it back. Popups open at the screen point given.
     */
    void pressSetting(final SettingControls.Control c, final int mouseButton, final int sx, final int sy) {
        if (model == null || !c.enabled() || c.type() == SettingControls.Type.READING) return;
        if (mouseButton == 1 && c.type() != SettingControls.Type.TOGGLE) {
            stepSetting(c, -1);
            return;
        }
        if (c.type() == SettingControls.Type.COIL) {
            openCoils(sx, sy);
            return;
        }
        if (c.key()
            .startsWith("power:")) {
            pressPower(c.key(), sx, sy);
            return;
        }
        if (c.key()
            .startsWith(SettingControls.MACHINE_PREFIX)) {
            pressMachine(
                c.key()
                    .substring(SettingControls.MACHINE_PREFIX.length()),
                sx,
                sy);
            return;
        }
        final SettingDef<?> def = SettingControls.def(model, c.key());
        if (def == null) return;
        final Node node = model.node;
        final MachineConfig cfg = node.machineConfig;
        switch (c.type()) {
            case TOGGLE -> changeSetting(node, c.key(), !CardDefaults.boolSetting(cfg, c.key()));
            case NUMBER -> Popup.open(
                getPanel(),
                NumberPopup.create(
                    def.label,
                    def.minInt + " to " + def.maxInt,
                    CardDefaults.intSetting(cfg, c.key()),
                    def.minInt,
                    def.maxInt,
                    v -> changeSetting(node, c.key(), (int) Math.max(def.minInt, Math.min(def.maxInt, Math.round(v))))),
                sx,
                sy);
            case CHOICE -> {
                final String now = CardDefaults.stringSetting(cfg, c.key());
                final List<PickList.Entry> rows = new ArrayList<>();
                for (final String option : def.options) rows.add(
                    new PickList.Entry(
                        null,
                        option,
                        "",
                        Hyb.INK,
                        option.equals(now),
                        () -> changeSetting(node, c.key(), option)));
                Popup.open(
                    getPanel(),
                    PickList.popup("gtnhplanner_setting_choice", null, rows, rows.size() > 10, 150),
                    sx,
                    sy);
            }
            default -> {}
        }
    }

    /** One step along a setting: the wheel on its chip or row, and the right button. */
    void stepSetting(final SettingControls.Control c, final int step) {
        if (model == null || !c.enabled() || c.type() == SettingControls.Type.READING) return;
        if (c.type() == SettingControls.Type.COIL) {
            stepCoil(step);
            return;
        }
        if (c.key()
            .startsWith("power:")) {
            final com.gtnhplanner.power.PowerSource source = model.power.source();
            final com.gtnhplanner.power.PowerSetting setting = source == null ? null
                : source.setting(
                    c.key()
                        .substring(6));
            if (setting != null) stepPower(setting, step);
            return;
        }
        if (c.key()
            .startsWith(SettingControls.MACHINE_PREFIX)) {
            stepMachine(
                c.key()
                    .substring(SettingControls.MACHINE_PREFIX.length()),
                step);
            return;
        }
        final SettingDef<?> def = SettingControls.def(model, c.key());
        if (def == null) return;
        final Node node = model.node;
        final MachineConfig cfg = node.machineConfig;
        switch (c.type()) {
            case TOGGLE -> changeSetting(node, c.key(), step > 0);
            case NUMBER -> {
                final int next = Math
                    .max(def.minInt, Math.min(def.maxInt, CardDefaults.intSetting(cfg, c.key()) + step));
                if (next != CardDefaults.intSetting(cfg, c.key())) changeSetting(node, c.key(), next);
            }
            case CHOICE -> {
                final int i = def.options.indexOf(CardDefaults.stringSetting(cfg, c.key()));
                final int n = def.options.size();
                changeSetting(node, c.key(), def.options.get(((i + step) % n + n) % n));
            }
            default -> {}
        }
    }

    // region Modelled machines (machines/game/MachineModels)

    @javax.annotation.Nullable
    private com.gtnhplanner.machines.game.MachineModels.Setting machineSetting(final String key) {
        for (final com.gtnhplanner.machines.game.MachineModels.Setting s : com.gtnhplanner.machines.game.MachineModels
            .settings(model.node))
            if (s.key()
                .equals(key)) return s;
        return null;
    }

    /** A modelled machine's setting pressed: a number to type, two choices to switch, or a list (with icons). */
    private void pressMachine(final String key, final int sx, final int sy) {
        final com.gtnhplanner.machines.game.MachineModels.Setting s = machineSetting(key);
        if (s == null) return;
        if (s.number()) {
            Popup.open(
                getPanel(),
                NumberPopup.create(
                    s.label(),
                    s.min() + " to " + s.max(),
                    Double.parseDouble(s.value()),
                    s.min(),
                    s.max(),
                    v -> changeMachine(key, plainNumber(Math.max(s.min(), Math.min(s.max(), v))))),
                sx,
                sy);
            return;
        }
        final String now = currentOption(s);
        if (s.options()
            .size() <= 2) {
            for (final com.gtnhplanner.machines.game.MachineModels.Option o : s.options()) if (!o.key()
                .equals(now)) changeMachine(key, o.key());
            return;
        }
        final List<PickList.Entry> rows = new ArrayList<>();
        for (final com.gtnhplanner.machines.game.MachineModels.Option o : s.options()) rows.add(
            new PickList.Entry(
                o.icon(),
                o.label(),
                "",
                Hyb.INK,
                o.key()
                    .equals(now),
                () -> changeMachine(key, o.key())));
        Popup
            .open(getPanel(), PickList.popup("gtnhplanner_machine_setting", null, rows, rows.size() > 10, 190), sx, sy);
    }

    /** One step along a modelled machine's setting: the next choice, or one more or less. */
    private void stepMachine(final String key, final int step) {
        final com.gtnhplanner.machines.game.MachineModels.Setting s = machineSetting(key);
        if (s == null) return;
        if (s.number()) {
            final double next = Math.max(s.min(), Math.min(s.max(), Math.floor(Double.parseDouble(s.value())) + step));
            changeMachine(key, plainNumber(next));
            return;
        }
        final List<com.gtnhplanner.machines.game.MachineModels.Option> options = s.options();
        int i = 0;
        final String now = currentOption(s);
        for (int k = 0; k < options.size(); k++) if (options.get(k)
            .key()
            .equals(now)) i = k;
        final int n = options.size();
        changeMachine(
            key,
            options.get(((i + step) % n + n) % n)
                .key());
    }

    /** The key of the option a setting shows (its value is the option's label). */
    private static String currentOption(final com.gtnhplanner.machines.game.MachineModels.Setting s) {
        for (final com.gtnhplanner.machines.game.MachineModels.Option o : s.options()) if (o.label()
            .equals(s.value())) return o.key();
        return "";
    }

    private static String plainNumber(final double v) {
        final double rounded = Math.round(v * 10) / 10.0;
        return rounded == Math.rint(rounded) ? Long.toString((long) rounded) : Double.toString(rounded);
    }

    /** Changes a modelled machine's setting; a tree's tools are remembered for its next card, as other settings. */
    private void changeMachine(final String key, final String value) {
        final Node node = model.node;
        if (key.startsWith("tgs")) changeSetting(node, key, value);
        else session.setSetting(node, key, value);
    }

    // endregion

    /** Changes a machine setting, and remembers it for the machine's next card. */
    private void changeSetting(final Node node, final String key, final Object value) {
        session.setSetting(node, key, value);
        SettingMemory.remember(node, key, value);
    }

    /** Changes a power card's setting, and remembers it for its source's next card. */
    private void changePower(final String settingId, final String value) {
        session.setPowerSetting(model.node, settingId, value);
        SettingMemory.rememberPower(model.node.powerSource, settingId, value);
    }

    /** A left click on a power card's setting: flips a toggle or one of two, lists the choices, asks a number. */
    private void pressPower(final String key, final int sx, final int sy) {
        final com.gtnhplanner.power.PowerSource source = model.power.source();
        final java.util.Map<String, String> values = model.node.powerSettings;
        final com.gtnhplanner.power.PowerSetting setting = source == null ? null : source.setting(key.substring(6));
        if (setting == null || !PowerTiles.enabled(source, setting, values)) return;
        switch (setting) {
            case com.gtnhplanner.power.PowerSetting.Toggle toggle -> changePower(setting.id(), toggle.value(values.get(setting.id())) ? "0" : "1");
            case com.gtnhplanner.power.PowerSetting.Select select -> {
                if (select.options()
                    .size() <= 2) {
                    final String now = select.value(values.get(setting.id()));
                    for (final com.gtnhplanner.power.PowerSetting.Option o : select.options())
                        if (!o.key()
                            .equals(now)) changePower(setting.id(), o.key());
                } else openPowerOptions(sx, sy, select);
            }
            case com.gtnhplanner.power.PowerSetting.Number number -> Popup.open(
                getPanel(),
                NumberPopup.create(
                    PowerTiles.caption(number),
                    PowerTiles.plain(number.min()) + " to " + PowerTiles.plain(number.max()),
                    number.value(values.get(setting.id())),
                    number.min(),
                    number.max(),
                    v -> changePower(
                        number.id(),
                        PowerTiles.plain(Math.max(number.min(), Math.min(number.max(), v))))),
                sx,
                sy);
        }
    }

    /** One step along a power setting; false when it is already at that end. */
    private boolean stepPower(final com.gtnhplanner.power.PowerSetting setting, final int step) {
        final java.util.Map<String, String> values = model.node.powerSettings;
        final String next = PowerTiles.step(setting, values, step);
        if (next.equals(PowerTiles.value(setting, values))) return false;
        changePower(setting.id(), next);
        return true;
    }

    private void openPowerOptions(final int sx, final int sy, final com.gtnhplanner.power.PowerSetting.Select select) {
        final String now = select.value(model.node.powerSettings.get(select.id()));
        final List<PickList.Entry> rows = new ArrayList<>();
        for (final com.gtnhplanner.power.PowerSetting.Option option : select.options()) rows.add(
            new PickList.Entry(
                null,
                option.label(),
                "",
                Hyb.INK,
                option.key()
                    .equals(now),
                () -> changePower(select.id(), option.key())));
        Popup.open(
            getPanel(),
            PickList.popup(
                "gtnhplanner_power_setting",
                select.label()
                    .toUpperCase(java.util.Locale.ROOT),
                rows,
                rows.size() > 10,
                150),
            sx,
            sy);
    }

    /** The head's tier chip on a generator: its tier select, a step at a time. */
    private void stepPowerTier(final int step) {
        final com.gtnhplanner.power.PowerSetting.Select tier = powerTier();
        if (tier != null) stepPower(tier, step);
    }

    // endregion

    /** "Kanthal Coil Block" reads as "Kanthal" in the well, as on the website. */
    public static String shortCoilName(final String name) {
        for (final String tail : new String[] { " Coil Block", " Coil" }) {
            if (name.endsWith(tail)) return name.substring(0, name.length() - tail.length());
        }
        return name;
    }

    /** The power panel's figures, worked out once for the model they were taken from. */
    private PowerPanel.Facts powerFacts;
    private CardModel powerFactsFor;

    /** Whether the part under the mouse answers with the power panel: a multiblock's amps, tier or POWER tile. */
    private boolean showsPower(final Part part) {
        return model != null && model.multiblock
            && model.gregtech
            && CardDefaults.tierIndex(model.tier) >= 0
            && (part == Part.AMPS || part == Part.TIER || part == Part.POWER);
    }

    /**
     * Factory Flow's POWER INPUT panel, while the mouse is on a multiblock's amps, tier or POWER tile: above the card,
     * right edges aligned (below it when there is no room), with the guide to the chips beside it. Drawn in screen
     * space; false when it does not apply.
     */
    public boolean drawPower(final int right, final int bottom) {
        if (model == null || !isHovering() || glance()) return false;
        final Part part = partAt(localX(), localY());
        if (!showsPower(part)) return false;
        final BoardCanvas c = canvas();
        if (c == null) return false;
        if (powerFactsFor != model) {
            powerFacts = PowerPanel.facts(model, powerTotal(), shared());
            powerFactsFor = model;
        }
        final int w = PowerPanel.W, h = PowerPanel.height(powerFacts);
        final int gw = PowerPanel.GUIDE_W, gh = PowerPanel.guideHeight();
        final int top = c.screenY(model.node.y), under = c.screenY(model.node.y + layout.height);
        int x = Math.min(c.screenX(model.node.x + CardLayout.W) - w, right - w - 4 - gw);
        x = Math.max(2, x);
        // Above the card, or below it; failing both, beside the part under the mouse so it stays in sight.
        int y = top - 6 - h;
        if (y < 2) {
            final int[] r = partRect(part);
            final int partTop = c.screenY(model.node.y + r[1]), partBottom = c.screenY(model.node.y + r[1] + r[3]);
            if (under + 6 + h <= bottom) y = under + 6;
            else if (partTop - 4 - h >= 2) y = partTop - 4 - h;
            else y = Math.min(partBottom + 4, bottom - h);
            y = Math.max(2, y);
        }
        PowerPanel.draw(powerFacts, x, y);
        final int gx = x + w + 4 + gw <= right ? x + w + 4 : Math.max(2, x - 4 - gw);
        PowerPanel.drawGuide(gx, y + h - gh, part);
        return true;
    }

    /** The tooltip for the part under the mouse, Factory Flow style: what it is, its figures, what the mouse does. */
    public Tip tip() {
        if (model == null || !isHovering()) return null;
        final Part part = partAt(localX(), localY());
        if (part == null || showsPower(part)) return null;
        final CardModel m = model;
        if (m.isPower()) return powerTip(part);
        return switch (part) {
            case PLACE -> placeTip();
            case ACTIONS -> Tip.of("Card actions")
                .action(Tip.Input.LEFT, "Clone, add a recipe, settings, delete");
            case SECTION -> {
                final int[] key = sectionKeyAt(localX(), localY());
                if (key == null) yield null;
                yield switch (key[1]) {
                    case 0 -> Tip.of("Move this recipe up");
                    case 1 -> Tip.of("Move this recipe down");
                    default -> Tip.of("Remove this recipe from the card")
                        .muted("Its wires are removed too.");
                };
            }
            case MACHINE -> {
                final Tip tip = Tip.of(m.machineName)
                    .sub(m.multiblock ? "Multiblock" : "Machine")
                    .row(pinned() ? "Pinned machines" : "Required machines", "×" + Fmt.machines(machinesTotal()));
                if (shared()) tip.row("Recipes", Integer.toString(models.size()));
                if (m.gregtech) tip.row("Configured tier", m.tier);
                tip.row("Time per operation", Fmt.compact(m.durationTicks / 20.0) + " s")
                    .row("Draw per machine", Fmt.power(m.euPerTick) + " EU/t");
                if (m.parallels > 1) tip.row("Parallel operations", Integer.toString(m.parallels));
                if (shared()) tip.muted("One machine runs these recipes in turns.");
                if (machineChoices().size() > 1)
                    tip.action(Tip.Input.LEFT, shared() ? "Machines that run them all" : "Machines that run it")
                        .action(Tip.Input.WHEEL, "Next machine");
                yield tip;
            }
            case TIER -> {
                final Tip tip = Tip.of("Voltage tier")
                    .row("Configured tier", m.tier);
                if (m.node.properties.get(GTKeys.EU_PER_TICK) instanceof final Number eu && eu.longValue() > 0)
                    tip.row("Recipe tier", CardDefaults.TIERS[CardDefaults.recipeTier(eu.longValue())]);
                tip.row("Time per operation", Fmt.compact(m.durationTicks / 20.0) + " s")
                    .row("Draw per machine", Fmt.power(m.euPerTick) + " EU/t");
                if (m.tierTooLow()) tip.note("Below the recipe's tier: it cannot run.", Hyb.RED_INK);
                yield tip.action(Tip.Input.LEFT, "Up")
                    .action(Tip.Input.RIGHT, "Down")
                    .action(Tip.Input.WHEEL, "Step");
            }
            case AMPS -> Tip.of("Energy hatch amps")
                .row("Amps", Fmt.compact(m.amps) + "A");
            case POWER -> Tip.of("Power")
                .row("Draw per machine", Fmt.power(m.euPerTick) + " EU/t")
                .row("Demand for this card", Fmt.power(session.power(m)) + " EU/t")
                .muted("Not wired in plans.");
            case SETTINGS -> settingsTip();
            case CHIP -> chipTip();
            case MACHINES -> {
                final double machines = machinesTotal();
                final boolean pin = pinned();
                final Tip tip = Tip.of(pin ? "Pinned machines" : "Required machines")
                    .row(pin ? "Pinned" : "Calculated", "×" + Fmt.machines(machines));
                final double whole = Math.ceil(machines - 1e-9);
                if (machines > 0 && whole != machines) tip.row("Whole machines", "×" + (long) whole)
                    .row("Average utilization", Fmt.compact(100 * machines / whole) + "%");
                if (shared()) tip.muted("The total for all its recipes, which share the machines.");
                if (machines <= 0) tip.muted("No rate or pinned count in the plan needs this machine yet.");
                yield tip.action(Tip.Input.LEFT, pin ? "Change or unpin" : "Pin count")
                    .action(Tip.Input.WHEEL, "+1 / -1");
            }
            case CIRCUIT -> Tip.of("Programmed circuit")
                .row("Required setting", Integer.toString(m.circuit.getItemDamage()))
                .muted("Not consumed.");
            default -> {
                // Far out the card answers with its reveal panel; close in, the body says nothing.
                yield null;
            }
        };
    }

    /** A generator's tooltips: what it is, its tier, a setting or reading, what it makes, how many. */
    /** The custom rate card's name: what it does with what it holds (the website's title hover). */
    private Tip customRateTip() {
        final com.gtnhplanner.data.flowchart.Node n = model.node;
        final String key = com.gtnhplanner.power.CustomRate.resource(n);
        final Tip tip = Tip.of("Custom Rate");
        if (key == null) return tip.muted("Wire any port to this and it adopts that resource.");
        final String name = com.gtnhplanner.ui.Resources.name(key);
        final String rate = com.gtnhplanner.power.sources.Helpers.formatAmount(com.gtnhplanner.power.CustomRate.rate(n))
            + " "
            + com.gtnhplanner.power.CustomRate.unit(key);
        return (com.gtnhplanner.power.CustomRate.supply(n) ? tip.sub("Supplies " + name)
            .muted("Makes " + name + " at the dialed rate for anything that asks.")
            : tip.sub("Requests " + name)
                .muted("Constantly drains " + name + " at the dialed rate.")).row("Dialed rate", rate)
                    .row(pinned() ? "Pinned" : "Calculated", "×" + Fmt.machines(machinesTotal()));
    }

    private Tip powerTip(final Part part) {
        final CardModel m = model;
        final com.gtnhplanner.power.PowerSource source = m.power.source();
        final com.gtnhplanner.power.PowerModel pm = m.power.model();
        final double each = pm == null ? 0 : pm.euPerTick();
        return switch (part) {
            case PLACE -> placeTip();
            case ACTIONS -> Tip.of("Card actions")
                .action(Tip.Input.LEFT, "Clone, delete");
            case MACHINE -> {
                if (com.gtnhplanner.power.CustomRate.is(m.node)) yield customRateTip();
                final Tip tip = Tip.of(m.machineName)
                    .sub(
                        "Non-recipe machine"
                            + (source != null ? ", " + source.group().title.toLowerCase(java.util.Locale.ROOT) : ""));
                if (source != null) tip.muted(source.blurb());
                if (source == null)
                    tip.note("Unknown machine: this version of GTNH Planner doesn't have it.", Hyb.RED_INK);
                tip.row(each < 0 ? "Draws per machine" : "Makes per machine", Fmt.power(Math.abs(each)) + " EU/t")
                    .row(pinned() ? "Pinned machines" : "Required machines", "×" + Fmt.machines(machinesTotal()));
                if (source != null && source.unlock() != null) tip.row("Unlocks at", source.unlock());
                yield tip;
            }
            case TIER -> Tip.of("Tier")
                .row("Configured tier", powerTier() == null ? "" : powerTier().value(m.node.powerSettings.get("tier")))
                .row("Makes per machine", Fmt.power(each) + " EU/t")
                .action(Tip.Input.LEFT, "Up")
                .action(Tip.Input.RIGHT, "Down")
                .action(Tip.Input.WHEEL, "Step");
            case SETTINGS -> settingsTip();
            case CHIP -> chipTip();
            case POWER -> Tip.of(each < 0 ? "Power drawn" : "Power made")
                .row(each < 0 ? "Draws per machine" : "Makes per machine", Fmt.power(Math.abs(each)) + " EU/t")
                .row("All its machines", Fmt.power(Math.abs(each) * machinesTotal()) + " EU/t")
                .muted(
                    each < 0 ? "Added to the plan's power use."
                        : "To size it, wire its EU output to a drawer and set a rate, or pin a machine count.");
            case MACHINES -> {
                final double machines = machinesTotal();
                final boolean pin = pinned();
                final Tip tip = Tip.of(pin ? "Pinned machines" : "Required machines")
                    .row(pin ? "Pinned" : "Calculated", "×" + Fmt.machines(machines));
                if (machines <= 0) tip.muted("Not sized yet: pin a machine count, or set a rate on its EU drawer.");
                yield tip.action(Tip.Input.LEFT, pin ? "Change or unpin" : "Pin count")
                    .action(Tip.Input.WHEEL, "+1 / -1");
            }
            default -> null;
        };
    }

    /** The gear key's tooltip: what it opens, and what stops the recipe. */
    private Tip settingsTip() {
        final Tip tip = Tip.of("Machine settings")
            .muted("Pin a setting there to show it on the card.");
        if (coilTooCold()) tip.note("The coil is too cold for this recipe: it cannot run.", Hyb.RED_INK);
        return tip.action(Tip.Input.LEFT, "Open");
    }

    /** A pinned setting's chip: what it is set to, why it is moot, how to change it. */
    private Tip chipTip() {
        final int i = layout.chipAt(localX(), localY());
        if (i < 0) return null;
        final SettingControls.Control c = layout.chips.get(i);
        final Tip tip = Tip.of(c.label())
            .row(c.type() == SettingControls.Type.READING ? "Reads" : "Set to", c.value());
        if (c.type() == SettingControls.Type.COIL && model.recipeHeat > 0)
            tip.row("Recipe needs", model.recipeHeat + " K");
        if (c.warn()) tip.note("Too cold for this recipe: it cannot run.", Hyb.RED_INK);
        if (!c.enabled()) {
            final com.gtnhplanner.power.PowerSource source = model.power == null ? null : model.power.source();
            final com.gtnhplanner.power.PowerSetting s = source == null ? null
                : source.setting(
                    c.key()
                        .substring(6));
            if (s != null && s.enabledWhen() != null) {
                final com.gtnhplanner.power.PowerSetting.Condition when = s.enabledWhen();
                final com.gtnhplanner.power.PowerSetting other = source.setting(when.settingId());
                tip.muted(
                    "Only used when " + (other == null ? when.settingId() : other.label())
                        + " is "
                        + optionLabel(other, when.equals())
                        + ".");
            }
        } else if (c.type() != SettingControls.Type.READING) {
            tip.action(Tip.Input.LEFT, switch (c.type()) {
                case TOGGLE -> "Switch";
                case NUMBER -> "Type a value";
                default -> "Choose";
            })
                .action(Tip.Input.RIGHT, c.type() == SettingControls.Type.TOGGLE ? "Switch" : "Step back")
                .action(Tip.Input.WHEEL, "Step");
        }
        return tip.muted("Pinned to the card: unpin it in the settings.");
    }

    /** An option's label for a setting's stored key (the key itself for anything but a select). */
    private static String optionLabel(final com.gtnhplanner.power.PowerSetting setting, final String key) {
        if (setting instanceof final com.gtnhplanner.power.PowerSetting.Select select) {
            final com.gtnhplanner.power.PowerSetting.Option option = select.option(key);
            if (option != null) return option.label();
        }
        if (setting instanceof com.gtnhplanner.power.PowerSetting.Toggle) return "1".equals(key) ? "on" : "off";
        return key;
    }

    // endregion

    // region Controls

    /**
     * The part a left press went down on, and where (card-local): let go without moving, it is a click on that part;
     * moved, it carries the card, whatever part it was on.
     */
    private Part pressed;
    private float pressedX, pressedY;

    @Override
    public Result onMousePressed(final int mouseButton) {
        if (model == null) return Result.IGNORE;
        if (com.gtnhplanner.ui.world.LinkTarget.active()) {
            if (mouseButton == 0) chooseForLink();
            return Result.ACCEPT;
        }
        if (mouseButton == 0) {
            pressed = partAt(localX(), localY());
            pressedX = localX();
            pressedY = localY();
            return Result.ACCEPT;
        }
        return act(partAt(localX(), localY()), mouseButton, localX(), localY());
    }

    /**
     * What a click on a part does: a left one when it is let go without moving, the others when they go down. At
     * {@code x, y} on the card.
     */
    private Result act(final Part part, final int mouseButton, final float x, final float y) {
        final Node node = model.node;
        switch (part) {
            case ACTIONS -> {
                if (mouseButton == 0) openActions();
            }
            case PLACE -> {
                if (mouseButton == 0) com.gtnhplanner.ui.world.LinkPicker.start(
                    session.graph(),
                    node.id,
                    placeName(),
                    model.machineStack,
                    CardModel.structureNeeds(models),
                    (int) Math.min(64, Math.ceil(machinesTotal() - 1e-9)));
                else if (mouseButton == 1 && placed()) com.gtnhplanner.ui.world.WorldLinks.clear(session.graph(), node);
            }
            case SECTION -> {
                final int[] key = sectionKeyAt(x, y);
                if (mouseButton == 0 && key != null) {
                    final UUID section = sectionId(key[0]);
                    if (key[1] == 2) session.removeSection(section);
                    else session.moveSection(section, key[1] == 0 ? -1 : 1);
                }
            }
            case MACHINE -> {
                if (model.isPower()) return act(Part.BODY, mouseButton, x, y);
                if (mouseButton == 0) openMachines();
            }
            case TIER -> {
                if (model.isPower()) stepPowerTier(mouseButton == 1 ? -1 : 1);
                else stepTier(mouseButton == 1 ? -1 : 1);
            }
            case CHIP -> {
                final int i = layout.chipAt(x, y);
                if (i < 0 || mouseButton > 1) return act(Part.BODY, mouseButton, x, y);
                final int[] r = layout.chipRect(i);
                pressSetting(layout.chips.get(i), mouseButton, screenX(r[0]), screenY(r[1] + CardLayout.CHIP + 2));
            }
            case SETTINGS -> {
                if (mouseButton == 0) openSheet();
            }
            case AMPS -> {
                if (mouseButton == 1) session.setSetting(node, "amp", Math.max(1, model.amps - 1));
                else openAmps();
            }
            case MACHINES -> {
                if (mouseButton == 0) openPin();
            }
            default -> {
                // The body: a click selects it (Shift adds); the right button is the board's.
                if (mouseButton != 0) return Result.IGNORE;
                if (canvas() != null) canvas().clickSelect(nodeId);
                return Result.SUCCESS;
            }
        }
        Hyb.click();
        return Result.SUCCESS;
    }

    /**
     * A count or amps being wheeled: shown on the card at once, committed (one edit, one solve) once the wheel has been
     * still for {@value #WHEEL_COMMIT_MS} ms, so a fast spin is one change and not a solve and a save per notch. 0 for
     * none.
     */
    private long pendingCount;
    /** The wheel took a pinned count below one: it unpins when the wheel goes still. */
    private boolean pendingUnpin;
    private int pendingAmps;
    private long wheelAt;
    private static final long WHEEL_COMMIT_MS = 500;

    /**
     * The model the card showed when a wheeled value was committed, or null while none is. The value stays on the card
     * until a newer answer has come back from the solver, so it never flicks back to the old one in between.
     */
    private CardModel committedOver;

    /** Commits a wheeled count or amps once the wheel has gone still; lets go of it once the solve has caught up. */
    private void commitWheel() {
        if (committedOver != null) {
            if (model != committedOver && !session.solving()) {
                committedOver = null;
                pendingCount = 0;
                pendingUnpin = false;
                pendingAmps = 0;
            }
            return;
        }
        if (pendingCount <= 0 && pendingAmps <= 0 && !pendingUnpin
            || System.currentTimeMillis() - wheelAt < WHEEL_COMMIT_MS) return;
        if (pendingCount > 0) session.pin(model.node, pendingCount);
        else if (pendingUnpin) session.pin(model.node, 0);
        if (pendingAmps > 0) session.setSetting(model.node, "amp", pendingAmps);
        committedOver = model;
    }

    @Override
    public boolean onMouseScroll(final UpOrDown direction, final int amount) {
        if (model == null) return false;
        final int step = direction == UpOrDown.UP ? 1 : -1;
        final Node node = model.node;
        switch (partAt(localX(), localY())) {
            case TIER -> {
                if (model.isPower()) stepPowerTier(step);
                else stepTier(step);
            }
            case CHIP -> {
                final int i = layout.chipAt(localX(), localY());
                if (i < 0) return false;
                stepSetting(layout.chips.get(i), step);
            }
            case AMPS -> {
                com.gtnhplanner.ui.sound.Sfx.TICK.play(step > 0 ? 1.12f : 0.9f);
                pendingAmps = stepAmps(pendingAmps > 0 ? pendingAmps : model.amps, step);
                wheelAt = System.currentTimeMillis();
                committedOver = null;
            }
            case MACHINE -> stepMachine(step);
            case MACHINES -> wheelCount(step);
            default -> {
                return false;
            }
        }
        return true;
    }

    /**
     * The wheel on the machine count (the card's, or its line in the overview): a machine more or fewer than it shows,
     * pinned once the wheel goes still; down from one unpins, as a drawer's rate wheeled to nothing clears it.
     */
    public void wheelCount(final int step) {
        if (model == null) return;
        com.gtnhplanner.ui.sound.Sfx.TICK.play(step > 0 ? 1.12f : 0.9f);
        final long from = pendingUnpin ? 0 : pendingCount > 0 ? pendingCount : Math.round(machinesTotal());
        if (from + step <= 0) {
            pendingUnpin = pinned() || pendingCount > 0;
            pendingCount = 0;
        } else {
            pendingUnpin = false;
            pendingCount = Math.max(1, from + step);
        }
        wheelAt = System.currentTimeMillis();
        committedOver = null;
    }

    /** A count being wheeled and not set yet, to show at once: the count, 0 when it will unpin; -1 for none. */
    public long wheeledCount() {
        return pendingUnpin ? 0 : pendingCount > 0 ? pendingCount : -1;
    }

    /**
     * The wheel on the amps chip, as on the website: one at a time, ten with Ctrl, a thousand with Ctrl and Shift, and
     * Shift alone walks the powers of four (a hatch tier's worth at a time).
     */
    private static int stepAmps(final int amps, final int step) {
        final boolean ctrl = org.lwjgl.input.Keyboard.isKeyDown(org.lwjgl.input.Keyboard.KEY_LCONTROL)
            || org.lwjgl.input.Keyboard.isKeyDown(org.lwjgl.input.Keyboard.KEY_RCONTROL);
        final boolean shift = org.lwjgl.input.Keyboard.isKeyDown(org.lwjgl.input.Keyboard.KEY_LSHIFT)
            || org.lwjgl.input.Keyboard.isKeyDown(org.lwjgl.input.Keyboard.KEY_RSHIFT);
        final long next;
        if (shift && !ctrl) next = step > 0 ? amps * 4L : amps / 4;
        else next = amps + (long) step * (ctrl ? shift ? 1000 : 10 : 1);
        return (int) Math.max(1, Math.min(PowerPanel.MAX_AMPS, next));
    }

    private void stepTier(final int step) {
        if (!model.gregtech) return;
        if (isSingle(model.machineStack)) {
            // Each tier of a single block is its own machine: step to the next one there is, and it brings its tier.
            final int now = GtMachines.of(model.machineStack)
                .tier();
            ItemStack next = null;
            int nextTier = step > 0 ? Integer.MAX_VALUE : -1;
            for (final ItemStack m : machineChoices()) {
                if (!isSingle(m)) continue;
                final int t = GtMachines.of(m)
                    .tier();
                if (step > 0 ? t > now && t < nextTier : t < now && t > nextTier) {
                    next = m;
                    nextTier = t;
                }
            }
            if (next != null) {
                com.gtnhplanner.ui.sound.Sfx.tier(nextTier);
                session.chooseMachine(model.node, next, true);
            }
            return;
        }
        int i = CardDefaults.tierIndex(model.tier);
        if (i < 0) i = CardDefaults.recipeTier(model.euPerTick);
        else i = Math.max(0, Math.min(CardDefaults.TIERS.length - 1, i + step));
        if (i != CardDefaults.tierIndex(model.tier)) com.gtnhplanner.ui.sound.Sfx.tier(i);
        session.setVoltage(model.node, CardDefaults.TIERS[i]);
    }

    private void stepCoil(final int step) {
        final List<GtCoils.Coil> coils = GtCoils.all();
        if (coils.isEmpty()) return;
        final GtCoils.Coil now = GtCoils.forHeat(model.coilHeat);
        int i = now == null ? -1 : coils.indexOf(now);
        i = Math.max(0, Math.min(coils.size() - 1, i + step));
        setCoil(coils.get(i));
    }

    private void setCoil(final GtCoils.Coil coil) {
        com.gtnhplanner.ui.sound.Sfx.ADJUST.playStep(
            Math.max(
                0,
                GtCoils.all()
                    .indexOf(coil)),
            0.8f,
            1.05f);
        session.editMachine(model.node, () -> {
            final MachineConfig cfg = model.node.machineConfig;
            cfg.setBoolean("gt_multiblock", true);
            cfg.setInt("machine_heat", coil.heat());
        });
        SettingMemory.remember(model.node, "machine_heat", coil.heat());
    }

    /**
     * The machines the card's menu offers: GregTech's single blocks as one (each tier is its own machine, and the tier
     * chip moves between them), the card's own when it is one, else the lowest tier that runs the recipe; then the
     * rest.
     */
    private List<ItemStack> machineMenu() {
        final List<ItemStack> all = machineChoices(), out = new ArrayList<>();
        ItemStack single = isSingle(model.machineStack) ? model.machineStack : null;
        if (single == null) {
            final Object eut = model.node.properties.get(GTKeys.EU_PER_TICK);
            final int recipe = CardDefaults.recipeTier(eut instanceof final Number n ? n.longValue() : 0);
            int best = -1;
            for (final ItemStack m : all) {
                if (!isSingle(m)) continue;
                final int t = GtMachines.of(m)
                    .tier();
                if (single == null || (t >= recipe != best >= recipe ? t >= recipe : t >= recipe ? t < best : t > best)) {
                    single = m;
                    best = t;
                }
            }
        }
        boolean added = false;
        for (final ItemStack m : all) {
            if (!isSingle(m)) out.add(m);
            else if (!added && single != null) {
                out.add(single);
                added = true;
            }
        }
        return out;
    }

    /** Whether a machine is one of GregTech's electric single blocks: one tier of a machine that comes in many. */
    private boolean isSingle(final ItemStack machine) {
        if (machine == null || model == null || !model.gregtech) return false;
        final GtMachines.Kind kind = GtMachines.of(machine);
        return kind != null && !kind.multiblock() && !kind.steam() && kind.tier() >= 0;
    }

    /** The machines the card can run on: its recipe's, or on a shared machine those that run every recipe on it. */
    private List<ItemStack> machineChoices() {
        return shared() ? session.commonMachines(sections) : model.catalysts;
    }

    private void stepMachine(final int step) {
        final List<ItemStack> machines = machineMenu();
        if (machines.size() < 2) return;
        int i = 0;
        for (int k = 0; k < machines.size(); k++) if (ItemStack.areItemStacksEqual(machines.get(k), model.machineStack)
            || isSingle(machines.get(k)) && isSingle(model.machineStack)) i = k;
        i = (i + step + machines.size()) % machines.size();
        com.gtnhplanner.ui.sound.Sfx.ADJUST.play();
        session.chooseMachine(model.node, machines.get(i), model.gregtech);
    }

    /** Whether the card is placed on a block in the world. */
    private boolean placed() {
        return model != null && !model.node.worldLinks.isEmpty();
    }

    private String placeName() {
        return model.isPower() ? model.node.powerSource : model.machineName;
    }

    private Tip placeTip() {
        if (!placed()) return Tip.of("Place in the world")
            .sub("Show this card over a block in the world")
            .action(Tip.Input.LEFT, "Pick the block");
        final int[] at = model.node.worldLinks.get(0);
        return Tip.of("Placed in the world")
            .sub("On the block at " + at[1] + ", " + at[2] + ", " + at[3])
            .action(Tip.Input.LEFT, "Pick another block")
            .action(Tip.Input.RIGHT, "Remove it from the world");
    }

    private void openActions() {
        final Node node = model.node;
        final List<PickList.Entry> rows = new ArrayList<>();
        rows.add(PickList.Entry.of("Clone node", () -> session.cloneNode(node)));
        if (!model.isPower()) {
            rows.add(PickList.Entry.of("Add another recipe", () -> session.addRecipeTo(node.id)));
        }
        // Its place in the world, once it has one.
        if (placed()) {
            rows.add(
                PickList.Entry
                    .of("Show in the world", () -> com.gtnhplanner.ui.world.WorldView.show(session.graph(), node.id)));
            rows.add(
                PickList.Entry.of(
                    "Remove from the world",
                    () -> com.gtnhplanner.ui.world.WorldLinks.clear(session.graph(), node)));
        }
        rows.add(new PickList.Entry(null, "Delete node", "", Hyb.RED_INK, false, () -> session.delete(node)));
        Popup.open(
            getPanel(),
            PickList.popup("gtnhplanner_actions", null, rows, false, 150),
            screenX(KEY_X),
            screenY(CHIP_Y + CHIP_H + 2));
    }

    private void openMachines() {
        final List<PickList.Entry> rows = new ArrayList<>();
        for (final ItemStack machine : machineMenu()) {
            String detail = "";
            if (model.gregtech) {
                final GtMachines.Kind kind = GtMachines.of(machine);
                if (kind != null) detail = kind.multiblock() ? "Multiblock"
                    : kind.steam() ? "Steam" : isSingle(machine) ? "Single block, tier by the chip" : "";
            }
            final boolean current = ItemStack.areItemStacksEqual(machine, model.machineStack)
                || isSingle(machine) && isSingle(model.machineStack);
            rows.add(new PickList.Entry(machine, machine.getDisplayName(), detail, Hyb.INK, current, () -> {
                if (!(isSingle(machine) && isSingle(model.machineStack))) {
                    com.gtnhplanner.ui.sound.Sfx.ADJUST.play();
                    session.chooseMachine(model.node, machine, model.gregtech);
                }
            }));
        }
        // The machine menu's last row: another recipe for this machine, as on the website.
        rows.add(
            new PickList.Entry(
                null,
                "+  Add another recipe to this machine",
                "",
                Hyb.MUTED,
                false,
                () -> session.addRecipeTo(nodeId)));
        Popup.open(
            getPanel(),
            PickList.popup(
                "gtnhplanner_machines",
                shared() ? "MACHINES THAT RUN EVERY RECIPE ON THIS CARD" : "MACHINES THAT RUN THIS RECIPE",
                rows,
                rows.size() > 8,
                240),
            screenX(BAR_X),
            screenY(CHIP_Y + CHIP_H + 2));
    }

    private void openCoils(final int sx, final int sy) {
        final List<PickList.Entry> rows = new ArrayList<>();
        final GtCoils.Coil now = GtCoils.forHeat(model.coilHeat);
        for (final GtCoils.Coil coil : GtCoils.all()) {
            if (coil.heat() < model.recipeHeat) continue;
            rows.add(
                new PickList.Entry(
                    coil.stack(),
                    coil.name(),
                    coil.heat() + " K",
                    Hyb.INK,
                    coil == now,
                    () -> setCoil(coil)));
        }
        if (rows.isEmpty()) return;
        Popup.open(getPanel(), PickList.popup("gtnhplanner_coils", null, rows, true, 200), sx, sy);
    }

    private void openAmps() {
        Popup.open(
            getPanel(),
            NumberPopup.create(
                "Energy hatch amps",
                "amps, 1 or more",
                model.amps,
                1,
                PowerPanel.MAX_AMPS,
                v -> session
                    .setSetting(model.node, "amp", (int) Math.max(1, Math.min(PowerPanel.MAX_AMPS, Math.round(v))))),
            screenX(AMPS_X),
            screenY(CHIP_Y + CHIP_H + 2));
    }

    private void openPin() {
        openPin(
            screenX(partRect(Part.MACHINES)[0]),
            screenY(partRect(Part.MACHINES)[1] + partRect(Part.MACHINES)[3] + 2));
    }

    /** The box to type a pinned count in, at a screen point (the overview's count line opens it too). */
    public void openPin(final int screenX, final int screenY) {
        if (model == null) return;
        Popup.open(
            getPanel(),
            NumberPopup.create(
                "Pin the machine count (empty unpins)",
                "machines",
                pinned() ? machinesTotal() : 0,
                0,
                100_000,
                v -> session.pin(model.node, v)),
            screenX,
            screenY);
    }

    /** Every setting the machine's profile offers, as rows: toggles flip, lists step, numbers ask. */
    /** The card's settings, under its gear key. */
    private void openSheet() {
        SettingsSheet.open(this, screenX(GEAR_X), screenY(CHIP_Y + CHIP_H + 2));
    }

    // endregion

    // region Moving the card

    @Override
    public boolean onDragStart(final int button) {
        if (button != 0 || model == null || canvas() == null) return false;
        // Pressed anywhere, it may be moved: the board moves it once the mouse goes, with the rest of the selection
        // when it is selected. Let go first, it is a click on what was pressed.
        canvas().beginMove(nodeId);
        return true;
    }

    @Override
    public void onDrag(final int mouseButton, final long timeSinceLastClick) {
        if (canvas() != null) canvas().dragMove();
    }

    @Override
    public void onDragEnd(final boolean successful) {
        final Part part = pressed;
        pressed = null;
        if (canvas() == null || !canvas().endMove(successful) || part == null || model == null) return;
        act(part, 0, pressedX, pressedY);
    }

    @Override
    public void drawMovingState(final ModularGuiContext context, final float partialTicks) {}

    @Override
    public @Nullable Area getMovingArea() {
        return null;
    }

    @Override
    public boolean isMoving() {
        return moving;
    }

    @Override
    public void setMoving(final boolean moving) {
        this.moving = moving;
    }

    // endregion
}

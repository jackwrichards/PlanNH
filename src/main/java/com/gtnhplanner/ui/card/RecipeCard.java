package com.gtnhplanner.ui.card;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.item.ItemStack;

import org.jetbrains.annotations.Nullable;

import com.cleanroommc.modularui.api.UpOrDown;
import com.cleanroommc.modularui.api.widget.IDraggable;
import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.drawable.GuiDraw;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.widget.ParentWidget;
import com.cleanroommc.modularui.widget.sizer.Area;
import com.gtnhplanner.data.MachineConfig;
import com.gtnhplanner.data.RecipeContext;
import com.gtnhplanner.data.SettingDef;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.provider.GTProvider;
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
        COIL,
        MACHINES,
        POWER,
        CIRCUIT,
        /** A key on a shared machine's recipe rule: up, down or off. */
        SECTION,
        /** A power card's setting or reading tile. */
        SETTING,
        BODY
    }

    private static final int CHIP_Y = CardLayout.HEAD_Y;
    private static final int CHIP_H = CardLayout.HEAD;
    private static final int KEY_X = CardLayout.PAD;
    private static final int KEY_W = CardLayout.HEAD;
    private static final int RIGHT = CardLayout.W - CardLayout.PAD;
    /** A multiblock's amps and tier chips, touching (Factory Flow's 64 at 380); a single block's lone tier chip. */
    private static final int CHIP_W = 48;
    private static final int SINGLE_TIER_W = 42;
    private static final int AMPS_X = RIGHT - 2 * CHIP_W;
    private static final int PLACE_X = KEY_X + KEY_W + 2;
    private static final int BAR_X = PLACE_X + KEY_W + 4;
    private static final int COIL_X = CardLayout.PAD + 3;
    private static final int COIL_W = CardLayout.W - 2 * CardLayout.PAD - 6;
    private static final int COIL_DY = 12;
    private static final int COIL_H = 18;
    private static final int CIRCUIT_W = 30;
    private static final int CIRCUIT_X = RIGHT - CIRCUIT_W;
    private static final int POWER_X = CardLayout.PAD;
    private static final int POWER_W = (CIRCUIT_X - 8 - POWER_X) / 2;
    private static final int MACHINES_X = POWER_X + POWER_W + 4;
    private static final int MACHINES_W = CIRCUIT_X - 4 - MACHINES_X;

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

    /** Where a wire meets the card for a port counted across all its recipes, one after another (for Arrange). */
    public int anchorYAcross(final boolean output, final int index) {
        int i = index;
        for (int s = 0; s < models.size(); s++) {
            final int n = (output ? models.get(s).outputs : models.get(s).inputs).size();
            if (i < n) return layout.anchorY(s, output, i);
            i -= n;
        }
        return layout.anchorY(0, output, 0);
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
        final CardLayout nextLayout = new CardLayout(next);
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
        if (reopenSettingsAfter != null && model != reopenSettingsAfter) {
            reopenSettingsAfter = null;
            openSettings();
        }
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
        // A recipe outside GregTech has no voltage: no tier chip, and the name bar runs to the edge.
        if (model != null && !hasTier()) return RIGHT;
        return (model != null && model.multiblock ? AMPS_X : tierX()) - 4;
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
        if (powerTileAt(x, y) >= 0) return Part.SETTING;
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
            case COIL -> layout.settingRows > 0 && !model.isPower()
                ? new int[] { COIL_X, layout.settingsY + COIL_DY, COIL_W, COIL_H }
                : null;
            case POWER -> new int[] { POWER_X, layout.footY, POWER_W, CardLayout.FOOT };
            case MACHINES -> new int[] { MACHINES_X, layout.footY, machinesW(), CardLayout.FOOT };
            case CIRCUIT -> model.circuit != null && !shared() && !model.isPower()
                ? new int[] { CIRCUIT_X, layout.footY, CIRCUIT_W, CardLayout.FOOT }
                : null;
            case SECTION, SETTING -> null;
            case BODY -> new int[] { 0, 0, CardLayout.W, layout.height };
        };
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
        final Part hover = isHovering() ? partAt(localX(), localY()) : null;
        if (canvas() != null && canvas().isCarried(nodeId)) {
            // Lifted while it is carried: a soft shadow under it.
            Hyb.rect(5, 7, CardLayout.W, layout.height, 0x50000000);
            Hyb.rect(3, 4, CardLayout.W, layout.height, 0x40000000);
        } else Hyb.dropShadow(0, 0, CardLayout.W, layout.height);
        boolean unwired = false;
        for (final CardModel each : models) unwired |= anyUnwired(each);
        if (unwired) drawUnwiredRing();
        if (session.isSelected(nodeId)) Hyb.ring(0, 0, CardLayout.W, layout.height, 2, Hyb.SELECTION);
        Hyb.cardFrame(0, 0, CardLayout.W, layout.height);
        Hyb.rect(CardLayout.PAD, layout.hairY, CardLayout.W - 2 * CardLayout.PAD, 1, 0xFF2A2C31);
        if (anyTierTooLow()) {
            // Can't run: the tier is below the recipe's. Red ring outside the card; the POWER tile says TIER!.
            Hyb.rect(-2, -2, CardLayout.W + 4, 2, Hyb.RED_INK);
            Hyb.rect(-2, layout.height, CardLayout.W + 4, 2, Hyb.RED_INK);
            Hyb.rect(-2, 0, 2, layout.height, Hyb.RED_INK);
            Hyb.rect(CardLayout.W, 0, 2, layout.height, Hyb.RED_INK);
        }
        drawHead(m, hover);
        drawPicture(m, z);
        final int[] key = shared() ? sectionKeyAt(localX(), localY()) : null;
        for (int s = 0; s < models.size(); s++) {
            drawRail(s, models.get(s).inputs, false, z);
            drawRail(s, models.get(s).outputs, true, z);
            if (shared()) drawSectionRule(s, z, isHovering() && key != null && key[0] == s ? key[1] : -1);
        }
        if (m.isPower()) drawPowerTiles(m, hover == Part.SETTING ? powerTileAt(localX(), localY()) : -1);
        else if (layout.settingRows > 0) drawCoil(m, z, hover == Part.COIL);
        drawFooter(m, z, hover);
    }

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
        // Factory Flow's identity tile: the card keeps its frame and takes the machine's colour, deep-dimmed, so the
        // picture is the bright thing; the rim holds two screen pixels however far out.
        Hyb.dropShadow(0, 0, w, h);
        final StructureArt.Art art = StructureArt.forMachine(m.isPower() ? m.node.powerSource : m.machineName);
        int tint = art != null ? art.tint()
            : m.machineStack != null ? com.gtnhplanner.client.IngredientColors.itemColor(m.machineStack) : -1;
        if (tint < 0) tint = 0x8A93A6;
        final float rim = Math.max(2, 1 / zoom);
        Hyb.rect(0, 0, w, h, Hyb.mix(tint, 0x262B34, 0.55f));
        Hyb.rect(rim, rim, w - 2 * rim, h - 2 * rim, Hyb.mix(tint, 0x07090C, 0.26f));
        if (m.tierTooLow()) Hyb.ring(-rim, -rim, w + 2 * rim, h + 2 * rim, rim, Hyb.RED_INK);
        if (session.isSelected(nodeId)) Hyb.ring(-3 * rim, -3 * rim, w + 6 * rim, h + 6 * rim, 2 * rim, Hyb.SELECTION);
        // The machine, big and centred: the whole structure where there is a picture of it.
        // As large as the tile allows either way: a tall card (a shared machine) is no wider for it.
        final float side = Math.min(w, h) - 2 * rim - 12;
        drawMachineArt(m, (w - side) / 2f, (h - side) / 2f, side, side, side, z, true);
        // How many, in a dark pill in the corner, at whole screen pixels per font pixel so it stays sharp.
        final String count = "×" + Fmt.machines(machinesTotal());
        // Two screen pixels per font pixel, or one far out: whole pixels at every wheel step, so it stays sharp.
        float cs = 1 / zoom;
        if (cs > 4) cs /= 2;
        while (cs > 1 && Hyb.width(count) * cs > w * 0.8f) cs /= 2;
        final float pad = cs;
        final float tw = Hyb.width(count) * cs, th = 8 * cs;
        final float px = w - rim - 4 - tw - 2 * pad, py = h - rim - 4 - th - 2 * pad;
        Hyb.rect(px, py, tw + 2 * pad, th + 2 * pad, 0xC0101114);
        Hyb.text(count, px + pad, py + pad, cs, pinned() ? Hyb.GOLD : machinesTotal() <= 0 ? Hyb.MUTED : Hyb.INK);
        if (canvas() != null) canvas().labelCard(m.machineName, m.node.x, m.node.y, w, h);
    }

    /**
     * Zoomed out, hovering a card shows what it is, as Factory Flow's glance does: a panel at the screen's own scale
     * with the name, the count, tier and power, and what goes in and comes out with its rates. Drawn in the screen's
     * foreground; false when the card is not in the glance view (the ordinary tooltip applies).
     */
    public boolean drawReveal(final int mouseX, final int mouseY, final int right, final int bottom) {
        final CardModel m = model;
        if (m == null || !glance() || !isHovering()) return false;
        // Every recipe's ports, on a shared machine one after another.
        final List<CardModel.PortView> ins = new ArrayList<>(), outs = new ArrayList<>();
        for (final CardModel each : models) {
            ins.addAll(each.inputs);
            outs.addAll(each.outputs);
        }
        final int rows = Math.max(1, Math.max(ins.size(), outs.size()));
        final int colW = 136, rowH = 22, w = 2 * colW + 28, h = 6 + 18 + 6 + 10 + 6 + rows * rowH + 4;
        int x = mouseX + 14, y = mouseY + 14;
        if (x + w > right) x = Math.max(2, mouseX - 14 - w);
        if (y + h > bottom) y = Math.max(2, bottom - h);
        Tip.beginPanel();
        Tip.chrome(x, y, w, h);
        // The name bar the card wears zoomed in.
        Hyb.bevel(x + 6, y + 6, w - 12, 18, Hyb.KEY, Hyb.KEY_HI, Hyb.KEY_LO, Hyb.KEY_EDGE, 1);
        Hyb.textCentered(Hyb.fit(m.machineName, w - 24), x + w / 2f, y + 11, Hyb.INK);
        // How many, at what tier, drawing how much.
        float lx = x + 8;
        final int ly = y + 30;
        final String count = "×" + Fmt.machines(machinesTotal());
        Hyb.text(count, lx, ly, pinned() ? Hyb.GOLD : Hyb.INK);
        lx += Hyb.width(count) + 8;
        if (m.gregtech) {
            final Hyb.Tier tier = Hyb.tier(m.tier);
            Hyb.text(tier.name(), lx, ly, tier.bg());
            lx += Hyb.width(tier.name()) + 8;
        }
        Hyb.text(Fmt.power(powerTotal()) + " EU/t", lx, ly, Hyb.MUTED);
        // Inputs, the arrow, outputs: the card's own reading order.
        final int top = y + 46;
        for (int i = 0; i < ins.size(); i++) revealPort(ins.get(i), x + 8, top + i * rowH, colW - 8);
        for (int i = 0; i < outs.size(); i++) revealPort(outs.get(i), x + 8 + colW + 20, top + i * rowH, colW - 8);
        final float ax = x + 8 + colW + 4, ay = top + 8;
        Hyb.triangle(ax + 8, ay, ax, ay - 4, ax, ay + 4, Hyb.MUTED);
        Tip.endPanel();
        return true;
    }

    private void revealPort(final CardModel.PortView p, final float x, final float y, final int width) {
        Hyb.icon(p.item(), p.fluid(), x, y + 2, 16, 300);
        Hyb.text(Hyb.fit(p.name(), width - 22), x + 20, y, Hyb.INK);
        Hyb.text(Fmt.rate(p.perSecond(), session.rateUnit(), p.isFluid()), x + 20, y + 10, Hyb.MUTED);
    }

    /** Rounds to the nearest half pixel: one screen pixel at the game's GUI scale 2, so text stays crisp. */
    static float crisp(final float v) {
        return Math.round(v * 2) / 2f;
    }

    /** Factory Flow's breathing: 0 to 1 and back over 1.9 s, for what wants attention (an unwired port). */
    private static float breathe() {
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
        pin(PLACE_X + 6, y + 4, placed() ? Hyb.GOLD : Hyb.INK);

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
                hover == Part.AMPS);
            chip(tierX(), y, tierW(), CHIP_H, tier, tier.name(), tier.underline(), hover == Part.TIER);
        } else if (powerTier() != null) {
            final Hyb.Tier tier = Hyb.tier(powerTier().value(m.node.powerSettings.get("tier")));
            chip(tierX(), y, tierW(), CHIP_H, tier, tier.name(), tier.underline(), hover == Part.TIER);
        }

        final int bw = barRight() - BAR_X;
        Hyb.bevel(
            BAR_X + 1,
            y + 1,
            bw - 2,
            CHIP_H - 2,
            m.isPower() ? Hyb.mix(POWER_AMBER, hover == Part.MACHINE ? 0x34363C : 0x2D2F35, 0.22f)
                : hover == Part.MACHINE ? 0xFF34363C : Hyb.NAMEBAR,
            Hyb.KEY_HI,
            0xFF1A1C20,
            Hyb.SHADOW,
            1);
        // The chevron says the bar is a menu: only when there is another machine to pick.
        // The bar is always a menu: the machines that run the card, and another recipe for it.
        // A generator has no other machine to pick: its name alone.
        final boolean menu = !m.isPower();
        if (menu) chevron(BAR_X + 5, y + 8, 0xFFFFFFFF);
        final int left = menu ? 15 : 6, room = bw - left - 6;
        final String name = Hyb.fit(m.machineName, room);
        Hyb.text(
            name,
            crisp(BAR_X + left + (room - Hyb.width(name)) / 2f),
            y + 6.5f,
            m.isPower() ? 0xFFFEF3C7 : 0xFFFFFFFF);
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
        Hyb.rect(x, y, w, h, tier.border());
        Hyb.rect(x + 1, y + 1, w - 2, h - 2, hover ? Hyb.mix(tier.bg(), 0xFFFFFF, 0.88f) : tier.bg());
        Hyb.rect(x + 1, y + 1, w - 2, 1, 0x8CFFFFFF);
        Hyb.rect(x + 1, y + 1, 1, h - 2, 0x8CFFFFFF);
        Hyb.rect(x + 1, y + h - 2, w - 2, 1, 0x73000000);
        Hyb.rect(x + w - 2, y + 1, 1, h - 2, 0x73000000);
        final float s = 1.5f;
        final float tw = (Hyb.width(label) - 1) * s;
        final float tx = crisp(x + (w - tw) / 2f), ty = crisp(y + (h - 7 * s) / 2f);
        GuiDraw.drawText(label, tx + 1, ty + 1, s, tier.border(), false);
        GuiDraw.drawText(label, tx, ty, s, tier.text(), false);
        if (underline) Hyb.rect(tx, ty + 8 * s, tw, 1, tier.text());
    }

    private static void chevron(final int x, final int y, final int color) {
        Hyb.rect(x, y, 7, 1, color);
        Hyb.rect(x + 1, y + 1, 5, 1, color);
        Hyb.rect(x + 2, y + 2, 3, 1, color);
        Hyb.rect(x + 3, y + 3, 1, 1, color);
    }

    /**
     * Each port is a tile of its own, as on the website: the icon bare with its shadow, the name on one or two lines,
     * the rate under it. A port still to wire is dashed and breathes. The tiles go down first, then what lights them,
     * so a glow is never covered by the tile below. On a shared machine a recipe with nothing on one side says so.
     */
    private void drawRail(final int section, final List<CardModel.PortView> ports, final boolean output,
        final float z) {
        final int x = CardLayout.railX(output), w = CardLayout.RAIL_W, h = CardLayout.ROW;
        final Fmt.RateUnit unit = session.rateUnit();
        final PortSlot hoveredSlot = getContext().getHovered() instanceof final PortSlot s && s.card() == this ? s
            : null;
        if (ports.isEmpty() && shared()) {
            final int y = layout.railsY(section);
            Hyb.dashed(x, y, w, h, 0xFF25272C, 3, 3);
            final String none = output ? "No output" : "No input";
            Hyb.text(none, crisp(x + (w - Hyb.width(none)) / 2f), y + 13, 0xB39A9CA4);
            return;
        }
        for (final CardModel.PortView p : ports) {
            final int y = layout.rowY(section, output, p.index());
            if (p.wired()) Hyb.tile(x, y, w, h);
            else {
                Hyb.rect(x, y, w, h, 0xFF2F3640);
                Hyb.dashed(x, y, w, h, 0xFF8F9BAD, 3, 3);
            }
            if (hoveredSlot != null && hoveredSlot.section == section
                && hoveredSlot.output == output
                && hoveredSlot.index == p.index()) Hyb.rect(x + 1, y + 1, w - 2, h - 2, 0x14FFFFFF);
            if (p.isPower()) euIcon(x + CardLayout.ICON_X, y + CardLayout.ICON_Y, CardLayout.ICON);
            else Hyb.icon(p.item(), p.fluid(), x + CardLayout.ICON_X, y + CardLayout.ICON_Y, CardLayout.ICON, z);
            final List<String> name = layout.nameLines(section, output, p.index());
            final float textX = x + CardLayout.TEXT_X;
            final float top = crisp(y + (h - ((name.size() + 1) * 9 - 1)) / 2f);
            for (int line = 0; line < name.size(); line++) Hyb.text(name.get(line), textX, top + line * 9, Hyb.INK);
            Hyb.text(
                Hyb.fit(
                    p.isPower() ? Fmt.power(p.perSecond() / 20) + " EU/t" : Fmt.rate(p.perSecond(), unit, p.isFluid()),
                    CardLayout.TEXT_W),
                textX,
                top + name.size() * 9,
                Hyb.MUTED);
        }
        final BoardCanvas board = canvas();
        final float b = breathe();
        for (final CardModel.PortView p : ports) {
            final int y = layout.rowY(section, output, p.index());
            if (!p.wired()) {
                Hyb.dashed(x - 1, y - 1, w + 2, h + 2, alpha(0xFFFFFF, 0.75f * b), 3, 3);
                Hyb.ring(x - 1, y - 1, w + 2, h + 2, 1, alpha(0xEEF2F8, 0.3f * b));
            }
            if (p.key()
                .equals(session.hoverKey())) glow(x, y, w, h);
            // While a wire is dragged out of another card, the ports that would take it light up.
            if (board != null && board.acceptsDrag(sectionId(section), output, p.key())) {
                Hyb.rect(x + 1, y + 1, w - 2, h - 2, 0x2053EAFD);
                Hyb.ring(x, y, w, h, 1, 0xFF53EAFD);
            }
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
        // The share, Factory Flow's little reading tile.
        final double total = machinesTotal();
        final String pct = total > 0 ? Math.round(100 * m.machines / total) + "%" : "0%";
        final boolean unwired = anyUnwired(m);
        final String word = unwired ? "NO WIRES" : m.machines <= 0 ? "UNUSED" : "×" + Fmt.machines(m.machines);
        final int wordColor = unwired ? Hyb.AMBER_INK : Hyb.MUTED;
        final int tx = CardLayout.IN_RAIL_X, tw = Hyb.width(pct) + Hyb.width(word) + 14;
        Hyb.tile(tx, y + 1, tw, h - 2);
        Hyb.text(pct, tx + 4, y + 4, Hyb.INK);
        Hyb.rect(tx + 7 + Hyb.width(pct), y + 3, 1, h - 6, Hyb.TILE_EDGE);
        Hyb.text(word, tx + 10 + Hyb.width(pct), y + 4, wordColor);
        // The recipe's circuit, then its keys.
        if (m.circuit != null) Hyb.icon(m.circuit, null, CardLayout.OUT_RAIL_X, y, 16, z);
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

    private void drawPicture(final CardModel m, final float z) {
        final int x = CardLayout.PICTURE_X, y = CardLayout.RAILS_Y, w = CardLayout.PICTURE_W, h = layout.railsH;
        // A sunken window: dark top and left inside the border, a whisper of light at the bottom and right.
        Hyb.rect(x, y, w, h, Hyb.TILE_EDGE);
        Hyb.rect(x + 1, y + 1, w - 2, h - 2, Hyb.PICTURE);
        Hyb.rect(x + 1, y + 1, w - 2, 1, 0x4D000000);
        Hyb.rect(x + 1, y + 1, 1, h - 2, 0x4D000000);
        Hyb.rect(x + 1, y + h - 2, w - 2, 1, 0x0AFFFFFF);
        Hyb.rect(x + w - 2, y + 1, 1, h - 2, 0x0AFFFFFF);
        drawMachineArt(m, x + 4, y + 4, w - 8, h - 8, 48, z, true);
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
                    0x99000000);
            }
            Hyb.texture(art.location(), px, py, pw, ph);
            return;
        }
        // Rendered in game on the well's own background: opaque, so it casts no shadow of its own.
        final MultiblockPictures.Picture structure = MultiblockPictures.get(machineStack);
        if (structure != null) structure.draw(x, y, w, h);
        else if (machineStack != null) {
            final float side = Math.min(itemSize, Math.min(w, h));
            final float ix = x + (w - side) / 2f, iy = y + (h - side) / 2f;
            if (shadow) Hyb.iconShadow(machineStack, null, ix, iy, side);
            Hyb.item(machineStack, ix, iy, side, z);
        }
    }

    /** The coil, Factory Flow's setting tile: its caption above a raised well with the coil, its name and a chevron. */
    private void drawCoil(final CardModel m, final float z, final boolean hover) {
        final int x = CardLayout.PAD, y = layout.settingsY, w = CardLayout.W - 2 * CardLayout.PAD;
        Hyb.tile(x, y, w, CardLayout.SETTING_ROW);
        Hyb.text("COIL", x + 4, y + 3, Hyb.MUTED);
        final int wy = y + COIL_DY;
        Hyb.rect(COIL_X, wy, COIL_W, COIL_H, Hyb.TILE_EDGE);
        Hyb.rect(COIL_X + 1, wy + 1, COIL_W - 2, COIL_H - 2, hover ? Hyb.TILE_HI : Hyb.WELL);
        Hyb.rect(COIL_X + 1, wy + 1, COIL_W - 2, 1, Hyb.HIGHLIGHT);
        Hyb.rect(COIL_X + 1, wy + 1, 1, COIL_H - 2, Hyb.HIGHLIGHT);
        Hyb.rect(COIL_X + 1, wy + COIL_H - 2, COIL_W - 2, 1, 0xFF282A2F);
        Hyb.rect(COIL_X + COIL_W - 2, wy + 1, 1, COIL_H - 2, 0xFF282A2F);
        final GtCoils.Coil coil = m.coilHeat > 0 ? GtCoils.forHeat(m.coilHeat) : null;
        final String label = coil == null ? "Pick a coil" : shortCoilName(coil.name());
        final int lw = (coil == null ? 0 : 20) + Hyb.width(label) + 4 + 7;
        float lx = crisp(COIL_X + (COIL_W - lw) / 2f);
        if (coil != null) {
            Hyb.item(coil.stack(), lx, wy + 1, 16, z);
            lx += 20;
        }
        final boolean tooCold = coil != null && m.recipeHeat > coil.heat();
        Hyb.text(label, lx, wy + 5, coil == null ? Hyb.MUTED : tooCold ? Hyb.RED_INK : Hyb.INK);
        chevron(Math.round(lx + Hyb.width(label) + 4), wy + 8, Hyb.MUTED);
    }

    // region Power tiles

    /** The well on a power setting's tile, card-local {x, y, w, h}, and the arrow ends that step it. */
    private static final int WELL_DY = 12, WELL_H = 18, ARROW_W = 12;

    /** Which power tile is under a card-local point, or -1. */
    private int powerTileAt(final float x, final float y) {
        if (layout == null || model == null || !model.isPower()) return -1;
        for (int i = 0; i < layout.powerTiles.size(); i++) {
            final int[] at = layout.tileAt(i);
            if (in(x, y, at[0], at[1], CardLayout.TILE_W, CardLayout.SETTING_ROW)) return i;
        }
        return -1;
    }

    /** Where on a setting's well a point is: -1 the left arrow, 1 the right, 0 the middle. */
    private int wellZone(final int tile, final float x) {
        final int wx = layout.tileAt(tile)[0] + 3, ww = CardLayout.TILE_W - 6;
        return x < wx + ARROW_W ? -1 : x >= wx + ww - ARROW_W ? 1 : 0;
    }

    /**
     * The website's power panel on the card: each setting on a tile, its caption over a raised well with the value and
     * arrows that step it (grayed while another setting makes it moot); then the readings; then any warning, amber.
     */
    private void drawPowerTiles(final CardModel m, final int hot) {
        final com.gtnhplanner.power.PowerSource source = m.power.source();
        final java.util.Map<String, String> values = m.node.powerSettings;
        final float mx = localX();
        for (int i = 0; i < layout.powerTiles.size(); i++) {
            final PowerTiles.Tile tile = layout.powerTiles.get(i);
            final int[] at = layout.tileAt(i);
            final int x = at[0], y = at[1], w = CardLayout.TILE_W;
            Hyb.tile(x, y, w, CardLayout.SETTING_ROW);
            if (!tile.isSetting()) {
                final com.gtnhplanner.power.PowerModel.Stat stat = tile.stat();
                Hyb.text(
                    Hyb.fit(
                        stat.label()
                            .toUpperCase(java.util.Locale.ROOT),
                        w - 8),
                    x + 4,
                    y + 3,
                    Hyb.MUTED);
                Hyb.text(Hyb.fit(stat.value(), w - 8), x + 4, y + 17, Hyb.INK);
                continue;
            }
            final com.gtnhplanner.power.PowerSetting setting = tile.setting();
            final boolean live = source != null && PowerTiles.enabled(source, setting, values);
            Hyb.text(
                Hyb.fit(
                    PowerTiles.caption(setting)
                        .toUpperCase(java.util.Locale.ROOT),
                    w - 8),
                x + 4,
                y + 3,
                live ? Hyb.MUTED : 0xFF5A5C65);
            final int wx = x + 3, wy = y + WELL_DY, ww = w - 6;
            final int zone = hot == i ? wellZone(i, mx) : 2;
            Hyb.rect(wx, wy, ww, WELL_H, Hyb.TILE_EDGE);
            Hyb.rect(wx + 1, wy + 1, ww - 2, WELL_H - 2, !live ? 0xFF303237 : zone == 0 ? Hyb.TILE_HI : Hyb.WELL);
            if (live && zone == -1) Hyb.rect(wx + 1, wy + 1, ARROW_W - 1, WELL_H - 2, Hyb.TILE_HI);
            if (live && zone == 1) Hyb.rect(wx + ww - ARROW_W, wy + 1, ARROW_W - 1, WELL_H - 2, Hyb.TILE_HI);
            Hyb.rect(wx + 1, wy + 1, ww - 2, 1, live ? Hyb.HIGHLIGHT : 0xFF3A3C42);
            Hyb.rect(wx + 1, wy + 1, 1, WELL_H - 2, live ? Hyb.HIGHLIGHT : 0xFF3A3C42);
            Hyb.rect(wx + 1, wy + WELL_H - 2, ww - 2, 1, 0xFF282A2F);
            Hyb.rect(wx + ww - 2, wy + 1, 1, WELL_H - 2, 0xFF282A2F);
            final String shown = Hyb.fit(PowerTiles.shown(setting, values), ww - 2 * ARROW_W - 4);
            Hyb.text(shown, crisp(wx + (ww - Hyb.width(shown)) / 2f), wy + 5, live ? Hyb.INK : 0xFF74767E);
            // The arrows: only where there is a step to take.
            final boolean down = !PowerTiles.step(setting, values, -1)
                .equals(PowerTiles.value(setting, values)),
                up = !PowerTiles.step(setting, values, 1)
                    .equals(PowerTiles.value(setting, values));
            final int ac = live ? Hyb.INK : 0xFF5A5C65;
            if (down) arrow(wx + 4, wy + 5, false, ac);
            if (up) arrow(wx + ww - 8, wy + 5, true, ac);
        }
        for (int k = 0; k < layout.warningLines.size(); k++)
            Hyb.text(layout.warningLines.get(k), CardLayout.PAD + 2, layout.warningsY + k * 9, Hyb.AMBER_INK);
    }

    /** A small arrowhead, 4 x 7, pointing left or right. */
    private static void arrow(final int x, final int y, final boolean right, final int color) {
        for (int i = 0; i < 4; i++) {
            final int len = 7 - 2 * i;
            Hyb.rect(right ? x + i : x + 3 - i, y + i, 1, len, color);
        }
    }

    /** A click on a power tile: the arrows step, the middle lists a select's options, flips a toggle, asks a number. */
    private boolean pressPowerTile(final int tile, final int mouseButton) {
        final PowerTiles.Tile t = layout.powerTiles.get(tile);
        final com.gtnhplanner.power.PowerSource source = model.power.source();
        final java.util.Map<String, String> values = model.node.powerSettings;
        if (!t.isSetting() || source == null || !PowerTiles.enabled(source, t.setting(), values)) return false;
        final com.gtnhplanner.power.PowerSetting setting = t.setting();
        final int zone = mouseButton == 1 ? -1 : wellZone(tile, localX());
        if (zone != 0) return stepPower(setting, zone);
        switch (setting) {
            case com.gtnhplanner.power.PowerSetting.Toggle toggle -> session
                .setPowerSetting(model.node, setting.id(), toggle.value(values.get(setting.id())) ? "0" : "1");
            case com.gtnhplanner.power.PowerSetting.Select select -> {
                if (select.options()
                    .size() <= 2) {
                    final String now = select.value(values.get(setting.id()));
                    for (final com.gtnhplanner.power.PowerSetting.Option o : select.options())
                        if (!o.key()
                            .equals(now)) session.setPowerSetting(model.node, setting.id(), o.key());
                } else openPowerOptions(tile, select);
            }
            case com.gtnhplanner.power.PowerSetting.Number number -> {
                final int[] at = layout.tileAt(tile);
                Popup.open(
                    getPanel(),
                    NumberPopup.create(
                        PowerTiles.caption(number),
                        PowerTiles.plain(number.min()) + " to " + PowerTiles.plain(number.max()),
                        number.value(values.get(setting.id())),
                        number.min(),
                        number.max(),
                        v -> session.setPowerSetting(
                            model.node,
                            number.id(),
                            PowerTiles.plain(Math.max(number.min(), Math.min(number.max(), v))))),
                    screenX(at[0]),
                    screenY(at[1] + CardLayout.SETTING_ROW + 2));
            }
        }
        return true;
    }

    /** One step along a power setting; false when it is already at that end. */
    private boolean stepPower(final com.gtnhplanner.power.PowerSetting setting, final int step) {
        final java.util.Map<String, String> values = model.node.powerSettings;
        final String next = PowerTiles.step(setting, values, step);
        if (next.equals(PowerTiles.value(setting, values))) return false;
        session.setPowerSetting(model.node, setting.id(), next);
        return true;
    }

    private void openPowerOptions(final int tile, final com.gtnhplanner.power.PowerSetting.Select select) {
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
                () -> session.setPowerSetting(model.node, select.id(), option.key())));
        final int[] at = layout.tileAt(tile);
        Popup.open(
            getPanel(),
            PickList.popup(
                "gtnhplanner_power_setting",
                select.label()
                    .toUpperCase(java.util.Locale.ROOT),
                rows,
                rows.size() > 10,
                Math.max(150, CardLayout.TILE_W)),
            screenX(at[0]),
            screenY(at[1] + CardLayout.SETTING_ROW + 2));
    }

    /** The head's tier chip on a generator: its tier select, a step at a time. */
    private void stepPowerTier(final int step) {
        final com.gtnhplanner.power.PowerSetting.Select tier = powerTier();
        if (tier != null) stepPower(tier, step);
    }

    // endregion

    /** "Kanthal Coil Block" reads as "Kanthal" in the well, as on the website. */
    private static String shortCoilName(final String name) {
        for (final String tail : new String[] { " Coil Block", " Coil" }) {
            if (name.endsWith(tail)) return name.substring(0, name.length() - tail.length());
        }
        return name;
    }

    /** The MACHINES tile: as far as the circuit, or to the edge on a shared machine, whose circuits are per recipe. */
    private int machinesW() {
        return shared() || model != null && model.isPower() ? RIGHT - MACHINES_X : MACHINES_W;
    }

    private void drawFooter(final CardModel m, final float z, final Part hover) {
        final int y = layout.footY;

        Hyb.tile(POWER_X, y, POWER_W, CardLayout.FOOT);
        if (hover == Part.POWER) Hyb.rect(POWER_X + 1, y + 1, POWER_W - 2, CardLayout.FOOT - 2, 0x10FFFFFF);
        if (m.isPower()) drawMakes(m, y);
        else drawPowerTile(m, y);
        drawMachinesTile(m, y, hover);
        if (shared() || m.isPower()) return;
        drawCircuit(m, y, z, hover);
    }

    /**
     * A generator's power: what its machines make, in amber; one machine's while the plan asks for none. A parasitic
     * one (a net draw) says what it draws.
     */
    private void drawMakes(final CardModel m, final int y) {
        final double each = m.power.model() == null ? 0
            : m.power.model()
                .euPerTick();
        final boolean draws = each < 0;
        final double machines = machinesTotal();
        final String figure = Fmt.power(Math.abs(each) * (machines > 0 ? machines : 1));
        Hyb.text(draws ? "DRAWS" : "MAKES", POWER_X + 4, y + 3, Hyb.MUTED);
        Hyb.text(figure, POWER_X + 4, y + 14, Hyb.FIGURE, draws ? Hyb.INK : 0xFFFCD34D);
        Hyb.text(
            machines > 0 ? "EU/t" : "EU/t each",
            POWER_X + 7 + Hyb.width(figure) * Hyb.FIGURE,
            y + 17.5f,
            Hyb.MUTED);
    }

    private void drawPowerTile(final CardModel m, final int y) {
        Hyb.text("POWER", POWER_X + 4, y + 3, Hyb.MUTED);
        final boolean tooLow = anyTierTooLow();
        final double eu = powerTotal();
        final int tierIdx = CardDefaults.tierIndex(m.tier);
        final boolean amps = session.powerKey() == BoardSession.PowerKey.AMPS && m.gregtech && tierIdx >= 0;
        final String power = tooLow ? "TIER!" : amps ? Fmt.compact(eu / (8L << (2 * tierIdx))) : Fmt.power(eu);
        final String unit = amps ? "A " + m.tier : "EU/t";
        Hyb.text(power, POWER_X + 4, y + 14, Hyb.FIGURE, tooLow ? Hyb.RED_INK : Hyb.INK);
        if (!tooLow) Hyb.text(unit, POWER_X + 7 + Hyb.width(power) * Hyb.FIGURE, y + 17.5f, Hyb.MUTED);
    }

    private void drawMachinesTile(final CardModel m, final int y, final Part hover) {

        // A shared machine's count is all its recipes' together, and takes the circuit's room: each recipe's circuit
        // is on its own rule.
        final int mw = machinesW();
        final double machines = machinesTotal();
        Hyb.tile(MACHINES_X, y, mw, CardLayout.FOOT);
        if (hover == Part.MACHINES) Hyb.rect(MACHINES_X + 1, y + 1, mw - 2, CardLayout.FOOT - 2, 0x10FFFFFF);
        Hyb.text("MACHINES", MACHINES_X + 4, y + 3, Hyb.MUTED);
        final String aside = shared() ? models.size() + " RECIPES"
            : m.parallels > 1 ? "PARALLEL ×" + m.parallels : null;
        if (aside != null) Hyb.textRight(aside, MACHINES_X + mw - 4, y + 3, Hyb.MUTED);
        // A count being wheeled shows at once, gold (it will be a pin), before the solve catches up.
        final String count = "×" + Fmt.machines(pendingCount > 0 ? pendingCount : machines);
        final int color = pendingCount > 0 || pinned() ? Hyb.GOLD : machines <= 0 ? Hyb.MUTED : Hyb.INK;
        Hyb.text(count, MACHINES_X + 4, y + 14, Hyb.FIGURE, color);
        final int cw = Math.round(Hyb.width(count) * Hyb.FIGURE);
        for (int dx = 0; dx < cw; dx += 3) Hyb.rect(MACHINES_X + 4 + dx, y + 27, 1, 1, Hyb.MUTED);
        final int pencil = hover == Part.MACHINES ? Hyb.INK : Hyb.MUTED;
        for (int i = 0; i < 5; i++) Hyb.rect(MACHINES_X + 8 + cw + i, y + 24 - i, 2, 2, pencil);
    }

    private void drawCircuit(final CardModel m, final int y, final float z, final Part hover) {
        if (m.circuit != null) {
            // The pack draws each circuit's number on its icon, so the icon alone, as large as the tile allows.
            Hyb.tile(CIRCUIT_X, y, CIRCUIT_W, CardLayout.FOOT);
            if (hover == Part.CIRCUIT) Hyb.rect(CIRCUIT_X + 1, y + 1, CIRCUIT_W - 2, CardLayout.FOOT - 2, 0x10FFFFFF);
            Hyb.icon(m.circuit, null, CIRCUIT_X + 3, y + 3, 24, z);
        } else {
            // No circuit: a sunken socket with a faint chip in it.
            Hyb.well(CIRCUIT_X, y, CIRCUIT_W, CardLayout.FOOT, Hyb.TILE_EDGE, Hyb.SHADOW, 0xFF2A2C31);
            final int cx = CIRCUIT_X + 10, cy = y + 10, faint = 0x509A9CA4;
            Hyb.ring(cx, cy, 10, 10, 1, faint);
            for (int i = 0; i < 3; i++) {
                Hyb.rect(cx + 1 + i * 3, cy - 3, 1, 2, faint);
                Hyb.rect(cx + 1 + i * 3, cy + 11, 1, 2, faint);
                Hyb.rect(cx - 3, cy + 1 + i * 3, 2, 1, faint);
                Hyb.rect(cx + 11, cy + 1 + i * 3, 2, 1, faint);
            }
        }
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
                if (m.node.properties.get(GTProvider.EU_PER_TICK) instanceof final Number eu && eu.longValue() > 0)
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
                .row("Demand for this card", Fmt.power(session.power(m)) + " EU/t");
            case COIL -> {
                final GtCoils.Coil coil = GtCoils.forHeat(m.coilHeat);
                final Tip tip = Tip.of("Heating coil")
                    .sub(coil == null ? "None picked" : coil.name() + ", " + coil.heat() + " K");
                if (m.recipeHeat > 0) tip.row("Recipe needs", m.recipeHeat + " K");
                tip.row("Time per operation", Fmt.compact(m.durationTicks / 20.0) + " s")
                    .row("Draw per machine", Fmt.power(m.euPerTick) + " EU/t");
                if (coil != null && m.recipeHeat > coil.heat())
                    tip.note("Too cold for this recipe: it cannot run.", Hyb.RED_INK);
                yield tip.action(Tip.Input.LEFT, "Pick")
                    .action(Tip.Input.WHEEL, "Step");
            }
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
            case SETTING -> {
                final int i = powerTileAt(localX(), localY());
                if (i < 0) yield null;
                final PowerTiles.Tile t = layout.powerTiles.get(i);
                if (!t.isSetting()) yield Tip.of(
                    t.stat()
                        .label())
                    .sub(
                        t.stat()
                            .value());
                final com.gtnhplanner.power.PowerSetting s = t.setting();
                final Tip tip = Tip.of(PowerTiles.caption(s))
                    .row("Set to", PowerTiles.shown(s, m.node.powerSettings));
                if (source != null && !PowerTiles.enabled(source, s, m.node.powerSettings)) {
                    final com.gtnhplanner.power.PowerSetting.Condition when = s.enabledWhen();
                    final com.gtnhplanner.power.PowerSetting other = source.setting(when.settingId());
                    yield tip.muted(
                        "Only used when " + (other == null ? when.settingId() : other.label())
                            + " is "
                            + optionLabel(other, when.equals())
                            + ".");
                }
                if (s instanceof com.gtnhplanner.power.PowerSetting.Select sel && sel.options()
                    .size() > 2) tip.action(Tip.Input.LEFT, "Choose (the arrows step)");
                else if (s instanceof com.gtnhplanner.power.PowerSetting.Number)
                    tip.action(Tip.Input.LEFT, "Type a value (the arrows step)");
                else tip.action(Tip.Input.LEFT, "Toggle");
                yield tip.action(Tip.Input.WHEEL, "Step");
            }
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

    @Override
    public Result onMousePressed(final int mouseButton) {
        if (model == null) return Result.IGNORE;
        if (com.gtnhplanner.ui.world.LinkTarget.active()) {
            if (mouseButton == 0) chooseForLink();
            return Result.ACCEPT;
        }
        final Part part = partAt(localX(), localY());
        final Node node = model.node;
        switch (part) {
            case ACTIONS -> {
                if (mouseButton == 0) openActions();
            }
            case PLACE -> {
                if (mouseButton == 0) com.gtnhplanner.ui.world.LinkPicker
                    .start(session.graph(), node.id, placeName(), model.machineStack);
                else if (mouseButton == 1 && placed()) com.gtnhplanner.ui.world.WorldLinks.clear(session.graph(), node);
            }
            case SECTION -> {
                final int[] key = sectionKeyAt(localX(), localY());
                if (mouseButton == 0 && key != null) {
                    final UUID section = sectionId(key[0]);
                    if (key[1] == 2) session.removeSection(section);
                    else session.moveSection(section, key[1] == 0 ? -1 : 1);
                }
            }
            case MACHINE -> {
                if (model.isPower()) return mouseButton == 0 ? Result.ACCEPT : Result.IGNORE;
                if (mouseButton == 0) openMachines();
            }
            case TIER -> {
                if (model.isPower()) stepPowerTier(mouseButton == 1 ? -1 : 1);
                else stepTier(mouseButton == 1 ? -1 : 1);
            }
            case SETTING -> {
                final int tile = powerTileAt(localX(), localY());
                if (tile < 0 || !pressPowerTile(tile, mouseButton)) return Result.ACCEPT;
            }
            case AMPS -> {
                if (mouseButton == 1) session.setSetting(node, "amp", Math.max(1, model.amps - 1));
                else openAmps();
            }
            case COIL -> {
                if (mouseButton == 0) openCoils();
            }
            case MACHINES -> {
                if (mouseButton == 0) openPin();
            }
            default -> {
                return mouseButton == 0 ? Result.ACCEPT : Result.IGNORE;
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
                pendingAmps = 0;
            }
            return;
        }
        if (pendingCount <= 0 && pendingAmps <= 0 || System.currentTimeMillis() - wheelAt < WHEEL_COMMIT_MS) return;
        if (pendingCount > 0) session.pin(model.node, pendingCount);
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
            case SETTING -> {
                final int tile = powerTileAt(localX(), localY());
                if (tile < 0 || !layout.powerTiles.get(tile)
                    .isSetting()) return false;
                stepPower(
                    layout.powerTiles.get(tile)
                        .setting(),
                    step);
            }
            case AMPS -> {
                pendingAmps = stepAmps(pendingAmps > 0 ? pendingAmps : model.amps, step);
                wheelAt = System.currentTimeMillis();
                committedOver = null;
            }
            case COIL -> stepCoil(step);
            case MACHINE -> stepMachine(step);
            case MACHINES -> {
                pendingCount = Math.max(1, (pendingCount > 0 ? pendingCount : Math.round(machinesTotal())) + step);
                wheelAt = System.currentTimeMillis();
                committedOver = null;
            }
            default -> {
                return false;
            }
        }
        return true;
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
        int i = CardDefaults.tierIndex(model.tier);
        if (i < 0) i = CardDefaults.recipeTier(model.euPerTick);
        else i = Math.max(0, Math.min(CardDefaults.TIERS.length - 1, i + step));
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
        session.editMachine(model.node, () -> {
            final MachineConfig cfg = model.node.machineConfig;
            cfg.setBoolean("gt_multiblock", true);
            cfg.setInt("machine_heat", coil.heat());
        });
    }

    /** The machines the card can run on: its recipe's, or on a shared machine those that run every recipe on it. */
    private List<ItemStack> machineChoices() {
        return shared() ? session.commonMachines(sections) : model.catalysts;
    }

    private void stepMachine(final int step) {
        final List<ItemStack> machines = machineChoices();
        if (machines.size() < 2) return;
        int i = 0;
        for (int k = 0; k < machines.size(); k++)
            if (ItemStack.areItemStacksEqual(machines.get(k), model.machineStack)) i = k;
        i = (i + step + machines.size()) % machines.size();
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

    /** A map pin: a round head with a hole, on a point. */
    private static void pin(final int x, final int y, final int color) {
        Hyb.rect(x + 2, y, 4, 1, color);
        Hyb.rect(x + 1, y + 1, 6, 1, color);
        Hyb.rect(x, y + 2, 2, 3, color);
        Hyb.rect(x + 6, y + 2, 2, 3, color);
        Hyb.rect(x + 3, y + 2, 2, 1, color);
        Hyb.rect(x + 2, y + 3, 1, 1, color);
        Hyb.rect(x + 5, y + 3, 1, 1, color);
        Hyb.rect(x + 3, y + 4, 2, 1, color);
        Hyb.rect(x + 1, y + 5, 6, 1, color);
        Hyb.rect(x + 2, y + 6, 4, 1, color);
        Hyb.rect(x + 3, y + 7, 2, 2, color);
        Hyb.rect(x + 3.5f, y + 9, 1, 2, color);
    }

    private void openActions() {
        final Node node = model.node;
        final List<PickList.Entry> rows = new ArrayList<>();
        rows.add(PickList.Entry.of("Clone node", () -> session.cloneNode(node)));
        if (!model.isPower()) {
            rows.add(PickList.Entry.of("Add another recipe", () -> session.addRecipeTo(node.id)));
            rows.add(PickList.Entry.of("Machine settings", this::openSettings));
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
        for (final ItemStack machine : machineChoices()) {
            String detail = "";
            if (model.gregtech) {
                final GtMachines.Kind kind = GtMachines.of(machine);
                if (kind != null) detail = kind.multiblock() ? "multi"
                    : kind.tier() >= 0 && kind.tier() < CardDefaults.TIERS.length ? CardDefaults.TIERS[kind.tier()]
                        : "";
            }
            rows.add(
                new PickList.Entry(
                    machine,
                    machine.getDisplayName(),
                    detail,
                    Hyb.INK,
                    ItemStack.areItemStacksEqual(machine, model.machineStack),
                    () -> session.chooseMachine(model.node, machine, model.gregtech)));
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

    private void openCoils() {
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
        Popup.open(
            getPanel(),
            PickList.popup("gtnhplanner_coils", null, rows, true, 200),
            screenX(COIL_X),
            screenY(layout.settingsY + COIL_DY + COIL_H + 2));
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
        Popup.open(
            getPanel(),
            NumberPopup.create(
                "Pin the machine count (empty unpins)",
                "machines",
                pinned() ? machinesTotal() : 0,
                0,
                100_000,
                v -> session.pin(model.node, v)),
            screenX(MACHINES_X),
            screenY(layout.footY + CardLayout.FOOT + 2));
    }

    /** Every setting the machine's profile offers, as rows: toggles flip, lists step, numbers ask. */
    private void openSettings() {
        final Node node = model.node;
        final MachineConfig cfg = node.machineConfig;
        if (cfg.getProfile() == null) return;
        final List<PickList.Entry> rows = new ArrayList<>();
        for (final SettingDef<?> def : cfg.getProfile()
            .visibleSettings(new RecipeContext(node.properties), cfg.settings)) {
            final String key = def.key;
            if ("machines".equals(key)) continue;
            if (def.type == Boolean.class) {
                final boolean on = CardDefaults.boolSetting(cfg, key);
                rows.add(new PickList.Entry(null, def.label, on ? "on" : "off", Hyb.INK, on, () -> {
                    session.setSetting(node, key, !on);
                    reopenSettingsNextTick();
                }));
            } else if (def.type == Integer.class) {
                final int value = CardDefaults.intSetting(cfg, key);
                rows.add(
                    new PickList.Entry(
                        null,
                        def.label,
                        Integer.toString(value),
                        Hyb.INK,
                        false,
                        () -> Popup.open(
                            getPanel(),
                            NumberPopup.create(
                                def.label,
                                def.minInt + " to " + def.maxInt,
                                value,
                                def.minInt,
                                def.maxInt,
                                v -> session.setSetting(
                                    node,
                                    key,
                                    (int) Math.max(def.minInt, Math.min(def.maxInt, Math.round(v))))),
                            screenX(KEY_X),
                            screenY(CHIP_Y + CHIP_H + 2))));
            } else if (def.options != null && !def.options.isEmpty()) {
                final String value = CardDefaults.stringSetting(cfg, key);
                rows.add(new PickList.Entry(null, def.label, value, Hyb.INK, false, () -> {
                    final int i = def.options.indexOf(value);
                    session.setSetting(node, key, def.options.get((i + 1) % def.options.size()));
                    reopenSettingsNextTick();
                }));
            }
        }
        if (rows.isEmpty()) return;
        Popup.open(
            getPanel(),
            PickList.popup("gtnhplanner_settings", "MACHINE SETTINGS", rows, false, 260),
            screenX(KEY_X),
            screenY(CHIP_Y + CHIP_H + 2));
    }

    /** The model shown when a setting was toggled; once a newer one arrives the settings list opens again. */
    private CardModel reopenSettingsAfter;

    /** Toggling closes the list (it is a pick list); bring it back, re-solved, so settings can be changed in a row. */
    private void reopenSettingsNextTick() {
        reopenSettingsAfter = model;
    }

    // endregion

    // region Moving the card

    @Override
    public boolean onDragStart(final int button) {
        if (button != 0 || model == null || partAt(localX(), localY()) != Part.BODY || canvas() == null) return false;
        // The board moves it, with the rest of the selection when it is selected.
        canvas().beginMove(nodeId);
        return true;
    }

    @Override
    public void onDrag(final int mouseButton, final long timeSinceLastClick) {
        if (canvas() != null) canvas().dragMove();
    }

    @Override
    public void onDragEnd(final boolean successful) {
        if (canvas() != null) canvas().endMove(successful);
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

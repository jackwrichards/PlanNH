package com.sbancuz.plannh.ui.card;

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
import com.sbancuz.plannh.api.PlanAPI;
import com.sbancuz.plannh.data.MachineConfig;
import com.sbancuz.plannh.data.RecipeContext;
import com.sbancuz.plannh.data.SettingDef;
import com.sbancuz.plannh.data.flowchart.Node;
import com.sbancuz.plannh.data.provider.GTProvider;
import com.sbancuz.plannh.ui.BoardSession;
import com.sbancuz.plannh.ui.canvas.BoardCanvas;
import com.sbancuz.plannh.ui.gt.GtCoils;
import com.sbancuz.plannh.ui.gt.GtMachines;
import com.sbancuz.plannh.ui.gt.MultiblockPictures;
import com.sbancuz.plannh.ui.popup.NumberPopup;
import com.sbancuz.plannh.ui.popup.PickList;
import com.sbancuz.plannh.ui.popup.Popup;
import com.sbancuz.plannh.ui.theme.Fmt;
import com.sbancuz.plannh.ui.theme.Hyb;

/**
 * One recipe on the board, drawn as Factory Flow's recipe card in Solve mode: head (actions, machine, amps, tier),
 * rails of NEI slots with names and rates, the machine, settings, and a footer with power and the solved machine
 * count. Lives in world space inside the canvas; dragging its body moves the recipe, its controls edit it.
 */
public final class RecipeCard extends ParentWidget<RecipeCard> implements Interactable, IDraggable {

    /** The parts of a card that react to the mouse. */
    public enum Part {
        ACTIONS,
        MACHINE,
        AMPS,
        TIER,
        COIL,
        MACHINES,
        BODY
    }

    private static final int CHIP_Y = CardLayout.PAD + 2;
    private static final int KEY_X = CardLayout.PAD + 2;
    private static final int TIER_W = 26;
    private static final int AMPS_W = 28;
    private static final int TIER_X = CardLayout.W - CardLayout.PAD - 2 - TIER_W;
    private static final int AMPS_X = TIER_X - 2 - AMPS_W;
    private static final int BAR_X = KEY_X + 16 + 4;
    private static final int COIL_X = CardLayout.PAD + 40;
    private static final int COIL_W = CardLayout.W - 2 * CardLayout.PAD - 42;
    private static final int MACHINES_X = CardLayout.PAD + 154;
    private static final int MACHINES_W = 128;

    private final BoardSession session;
    public final UUID nodeId;
    private CardModel model;
    private CardLayout layout;

    private int dragStartX, dragStartY, dragMouseX, dragMouseY;
    private String dragUndo;
    private boolean moving;

    public RecipeCard(final BoardSession session, final UUID nodeId) {
        this.session = session;
        this.nodeId = nodeId;
        refresh();
        final CardModel m = model;
        if (m != null) {
            for (int i = 0; i < m.inputs.size(); i++) child(new PortSlot(this, false, i));
            for (int i = 0; i < m.outputs.size(); i++) child(new PortSlot(this, true, i));
        }
    }

    public CardModel model() {
        return model;
    }

    public BoardSession session() {
        return session;
    }

    public CardLayout layout() {
        return layout;
    }

    /** Re-reads the model after a solve; resizes when the card's shape changed. */
    public void refresh() {
        final CardModel next = session.model(nodeId);
        if (next == null) return;
        model = next;
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
        pos(next.node.x, next.node.y);
    }

    /** True when the port count no longer matches the slot widgets (the recipe was replaced). */
    public boolean shapeChanged() {
        final CardModel next = session.model(nodeId);
        return next != null && model != null
            && (next.inputs.size() != model.inputs.size() || next.outputs.size() != model.outputs.size());
    }

    @Override
    public void onUpdate() {
        super.onUpdate();
        if (session.model(nodeId) != model) refresh();
        if (reopenSettingsAfter != null && model != reopenSettingsAfter) {
            reopenSettingsAfter = null;
            openSettings();
        }
    }

    /** ModularUI only counts a parent widget as hovered when it has a background or tooltip; the card draws itself. */
    @Override
    public boolean canHover() {
        return true;
    }

    // region Geometry and hit testing

    private int barRight() {
        return (model != null && model.multiblock ? AMPS_X : TIER_X) - 4;
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
        if (in(x, y, KEY_X, CHIP_Y, 16, 16)) return Part.ACTIONS;
        if (in(x, y, TIER_X, CHIP_Y, TIER_W, 16)) return Part.TIER;
        if (model.multiblock && in(x, y, AMPS_X, CHIP_Y, AMPS_W, 16)) return Part.AMPS;
        if (in(x, y, BAR_X, CHIP_Y, barRight() - BAR_X, 16)) return Part.MACHINE;
        if (layout.settingRows > 0 && in(x, y, COIL_X, layout.settingsY + 1, COIL_W, 16)) return Part.COIL;
        if (in(x, y, MACHINES_X, layout.footY, MACHINES_W, CardLayout.FOOT)) return Part.MACHINES;
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
            case ACTIONS -> new int[] { KEY_X, CHIP_Y, 16, 16 };
            case TIER -> new int[] { TIER_X, CHIP_Y, TIER_W, 16 };
            case AMPS -> model.multiblock ? new int[] { AMPS_X, CHIP_Y, AMPS_W, 16 } : null;
            case MACHINE -> new int[] { BAR_X, CHIP_Y, barRight() - BAR_X, 16 };
            case COIL -> layout.settingRows > 0 ? new int[] { COIL_X, layout.settingsY + 1, COIL_W, 16 } : null;
            case MACHINES -> new int[] { MACHINES_X, layout.footY, MACHINES_W, CardLayout.FOOT };
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
        final CardModel m = model;
        if (m == null) return;
        final float z = context.getCurrentDrawingZ();
        if (session.graph()
            .getZoom() <= GLANCE_ZOOM) {
            drawGlance(m, z);
            return;
        }
        final Part hover = isHovering() ? partAt(localX(), localY()) : null;
        Hyb.cardFrame(0, 0, CardLayout.W, layout.height);
        if (m.tierTooLow()) {
            // Can't run: the tier is below the recipe's. Red ring outside the card; the POWER tile says TIER!.
            Hyb.rect(-2, -2, CardLayout.W + 4, 2, Hyb.RED_INK);
            Hyb.rect(-2, layout.height, CardLayout.W + 4, 2, Hyb.RED_INK);
            Hyb.rect(-2, 0, 2, layout.height, Hyb.RED_INK);
            Hyb.rect(CardLayout.W, 0, 2, layout.height, Hyb.RED_INK);
        }
        drawHead(m, hover);
        drawRail(m.inputs, false, z);
        drawRail(m.outputs, true, z);
        drawPicture(m, z);
        if (layout.settingRows > 0) drawCoil(m, z, hover == Part.COIL);
        drawFooter(m, z, hover == Part.MACHINES);
    }

    /** At this zoom and below the 1x text is too small to read; the card shows the glance view instead. */
    public static final float GLANCE_ZOOM = 0.5f;

    /**
     * The zoomed-out card: the machine, its name and tier, and the two numbers that matter, in type large enough to
     * read at half zoom or less. Ports stay where they are so wires still meet the card.
     */
    private void drawGlance(final CardModel m, final float z) {
        final int h = layout.height;
        Hyb.cardFrame(0, 0, CardLayout.W, h);
        final int icon = Math.min(96, h - 24);
        if (m.machineStack != null) Hyb.item(m.machineStack, CardLayout.PAD + 8, (h - icon) / 2f, icon, z);
        final int tx = CardLayout.PAD + 16 + icon;
        final int room = (CardLayout.W - tx - 8) / 2;
        Hyb.text(Hyb.fit(m.machineName, room), tx, 12, 2f, 0xFFFFFFFF);
        final Hyb.Tier tier = Hyb.tier(m.tier);
        if (m.gregtech) Hyb.text(tier.name(), tx, 36, 2f, tier.bg());
        final String count = "x" + Fmt.machines(m.machines);
        Hyb.text(count, tx, h - 60, 3f, m.pinned ? Hyb.GOLD : m.machines <= 0 ? Hyb.MUTED : Hyb.INK);
        Hyb.text(Fmt.power(session.power(m)) + " EU/t", tx, h - 28, 2f, Hyb.MUTED);
    }

    private void drawHead(final CardModel m, final Part hover) {
        final int y = CHIP_Y;
        Hyb.bevel(
            KEY_X,
            y,
            16,
            16,
            hover == Part.ACTIONS ? Hyb.KEY_HOVER : Hyb.KEY,
            Hyb.KEY_HI,
            Hyb.KEY_LO,
            Hyb.KEY_EDGE,
            1);
        for (int i = 0; i < 3; i++) Hyb.rect(KEY_X + 3, y + 4 + i * 3, 10, 2, Hyb.INK);

        chip(TIER_X, y, TIER_W, Hyb.tier(m.tier), hover == Part.TIER);
        if (m.multiblock) {
            Hyb.bevel(
                AMPS_X,
                y,
                AMPS_W,
                16,
                hover == Part.AMPS ? 0xFF5A5C65 : Hyb.WELL,
                0xFF5A5C65,
                Hyb.SHADOW,
                Hyb.KEY_EDGE,
                1);
            Hyb.textCentered(Fmt.compact(m.amps) + "A", AMPS_X + AMPS_W / 2f, y + 4, Hyb.INK);
        }

        final int bw = barRight() - BAR_X;
        Hyb.rect(BAR_X - 1, y - 1, bw + 2, 18, Hyb.SHADOW);
        Hyb.rect(BAR_X, y, bw, 16, hover == Part.MACHINE ? 0xFF34363C : Hyb.NAMEBAR);
        Hyb.rect(BAR_X, y, bw, 1, Hyb.KEY_HI);
        Hyb.rect(BAR_X, y, 1, 16, Hyb.KEY_HI);
        chevron(BAR_X + 4, y + 6, Hyb.MUTED);
        Hyb.textCentered(Hyb.fit(m.machineName, bw - 20), BAR_X + 8 + (bw - 8) / 2f, y + 4, 0xFFFFFFFF);
    }

    private static void chip(final int x, final int y, final int w, final Hyb.Tier tier, final boolean hover) {
        Hyb.rect(x - 1, y - 1, w + 2, 18, hover ? 0xFFFFFFFF : tier.border());
        Hyb.rect(x, y, w, 16, tier.bg());
        Hyb.rect(x, y, w, 1, 0x8CFFFFFF);
        Hyb.rect(x, y, 1, 16, 0x8CFFFFFF);
        Hyb.rect(x, y + 15, w, 1, 0x73000000);
        Hyb.rect(x + w - 1, y, 1, 16, 0x73000000);
        final int tw = Hyb.width(tier.name());
        final float tx = x + (w - tw) / 2f;
        GuiDraw.drawText(tier.name(), tx, y + 4, 1f, tier.text(), false);
        if (tier.underline()) Hyb.rect(tx, y + 13, tw, 1, tier.text());
    }

    private static void chevron(final int x, final int y, final int color) {
        Hyb.rect(x, y, 7, 1, color);
        Hyb.rect(x + 1, y + 1, 5, 1, color);
        Hyb.rect(x + 2, y + 2, 3, 1, color);
        Hyb.rect(x + 3, y + 3, 1, 1, color);
    }

    private void drawRail(final List<CardModel.PortView> ports, final boolean output, final float z) {
        final int x = CardLayout.railX(output);
        final Fmt.RateUnit unit = session.rateUnit();
        final PortSlot hoveredSlot = getContext().getHovered() instanceof final PortSlot s && s.card() == this ? s : null;
        for (final CardModel.PortView p : ports) {
            final int y = layout.rowY(output, p.index());
            // The whole row is the handle; show it.
            if (hoveredSlot != null && hoveredSlot.output == output && hoveredSlot.index == p.index())
                Hyb.rect(x - 1, y - 1, CardLayout.RAIL_W + 1, layout.rowH(output, p.index()) + 2, 0x18FFFFFF);
            Hyb.slot(x, y + 1, p.isFluid());
            if (!p.wired()) Hyb.dashed(x - 2, y - 1, 22, 22, Hyb.AMBER_INK);
            if (p.key()
                .equals(session.hoverKey())) glow(x, y + 1);
            if (p.isFluid()) Hyb.fluid(p.fluid(), x + 1, y + 2, 16, z);
            else Hyb.item(p.item(), x + 1, y + 2, 16, z);
            final int textX = x + CardLayout.TEXT_X;
            final List<String> name = layout.nameLines(output, p.index());
            for (int line = 0; line < name.size(); line++) Hyb.text(name.get(line), textX, y + 2 + line * 9, Hyb.INK);
            String rate = Fmt.rate(p.perSecond(), unit, p.isFluid());
            if (p.chance() < 0.9999f) rate += " 00b7 " + Fmt.compact(p.chance() * 100) + "%";
            Hyb.text(Hyb.fit(rate, CardLayout.TEXT_W), textX, y + 2 + name.size() * 9, Hyb.MUTED);
        }
    }

    /** A gold ring around a slot whose resource is the one under the mouse. */
    private static void glow(final int x, final int y) {
        Hyb.rect(x - 2, y - 2, 22, 2, Hyb.GOLD);
        Hyb.rect(x - 2, y + 18, 22, 2, Hyb.GOLD);
        Hyb.rect(x - 2, y, 2, 18, Hyb.GOLD);
        Hyb.rect(x + 18, y, 2, 18, Hyb.GOLD);
    }

    private void drawPicture(final CardModel m, final float z) {
        final int x = CardLayout.PICTURE_X, y = CardLayout.RAILS_Y, w = CardLayout.PICTURE_W, h = layout.railsH;
        Hyb.well(x, y, w, h, Hyb.PICTURE, Hyb.SHADOW, Hyb.TILE_EDGE);
        // A multiblock with a bundled render shows the whole structure; anything else its machine block.
        final StructureArt.Art art = StructureArt.forMachine(m.machineName);
        if (art != null) {
            // Fit the picture in the well, keeping its shape (the power plants are not square).
            final float scale = Math.min((w - 4f) / art.width(), (h - 4f) / art.height());
            final float pw = art.width() * scale, ph = art.height() * scale;
            Hyb.texture(art.location(), x + (w - pw) / 2f, y + (h - ph) / 2f, pw, ph);
            return;
        }
        // Otherwise, for any other GregTech multiblock, its structure rendered in game (built once, then cached).
        final MultiblockPictures.Picture structure = MultiblockPictures.get(m.machineStack);
        if (structure != null) structure.draw(x + 2, y + 2, w - 4, h - 4);
        else if (m.machineStack != null) Hyb.item(m.machineStack, x + (w - 64) / 2f, y + (h - 64) / 2f, 64, z);
    }

    private void drawCoil(final CardModel m, final float z, final boolean hover) {
        final int x = CardLayout.PAD, y = layout.settingsY, w = CardLayout.W - 2 * CardLayout.PAD;
        Hyb.tile(x, y, w, CardLayout.SETTING_ROW);
        Hyb.text("COIL", x + 4, y + 5, Hyb.MUTED);
        Hyb.well(COIL_X, y + 1, COIL_W, 16, hover ? Hyb.TILE_HI : Hyb.WELL, 0xFF282A2F, 0xFF5A5C65);
        final GtCoils.Coil coil = m.coilHeat > 0 ? GtCoils.forHeat(m.coilHeat) : null;
        final String label = coil == null ? "Pick a coil" : coil.name();
        final int lw = Hyb.width(label) + (coil == null ? 0 : 20);
        final float lx = COIL_X + (COIL_W - lw) / 2f;
        if (coil != null) Hyb.item(coil.stack(), lx, y + 1, 16, z);
        final boolean tooCold = coil != null && m.recipeHeat > coil.heat();
        Hyb.text(
            label,
            lx + (coil == null ? 0 : 20),
            y + 5,
            coil == null ? Hyb.MUTED : tooCold ? Hyb.RED_INK : Hyb.INK);
        chevron(COIL_X + COIL_W - 12, y + 7, Hyb.MUTED);
    }

    private void drawFooter(final CardModel m, final float z, final boolean hoverMachines) {
        final int y = layout.footY;
        final int x = CardLayout.PAD;

        Hyb.tile(x, y, 150, CardLayout.FOOT);
        Hyb.text("POWER", x + 4, y + 3, Hyb.MUTED);
        final boolean tooLow = m.tierTooLow();
        final double eu = session.power(m);
        final int tierIdx = CardDefaults.tierIndex(m.tier);
        final boolean amps = session.powerKey() == BoardSession.PowerKey.AMPS && m.gregtech && tierIdx >= 0;
        final String power = tooLow ? "TIER!" : amps ? Fmt.compact(eu / (8L << (2 * tierIdx))) : Fmt.power(eu);
        final String unit = amps ? "A " + m.tier : "EU/t";
        Hyb.text(power, x + 4, y + 14, Hyb.FIGURE, tooLow ? Hyb.RED_INK : Hyb.INK);
        if (!tooLow) Hyb.text(unit, x + 7 + Hyb.width(power) * Hyb.FIGURE, y + 18, Hyb.MUTED);

        if (hoverMachines) Hyb.rect(MACHINES_X, y, MACHINES_W, CardLayout.FOOT, Hyb.TILE_HI);
        else Hyb.tile(MACHINES_X, y, MACHINES_W, CardLayout.FOOT);
        Hyb.text("MACHINES", MACHINES_X + 4, y + 3, Hyb.MUTED);
        if (m.parallels > 1) Hyb.textRight("PARALLEL x" + m.parallels, MACHINES_X + MACHINES_W - 4, y + 3, Hyb.MUTED);
        final String count = "x" + Fmt.machines(m.machines);
        final int color = m.pinned ? Hyb.GOLD : m.machines <= 0 ? Hyb.MUTED : Hyb.INK;
        Hyb.text(count, MACHINES_X + 4, y + 14, Hyb.FIGURE, color);
        final int cw = Math.round(Hyb.width(count) * Hyb.FIGURE);
        for (int dx = 0; dx < cw; dx += 3) Hyb.rect(MACHINES_X + 4 + dx, y + 27, 1, 1, Hyb.MUTED);
        for (int i = 0; i < 5; i++) Hyb.rect(MACHINES_X + 8 + cw + i, y + 24 - i, 2, 2, Hyb.MUTED);

        final int cx = CardLayout.W - CardLayout.PAD - 30;
        if (m.circuit != null) {
            Hyb.tile(cx, y, 30, CardLayout.FOOT);
            Hyb.item(m.circuit, cx + 7, y + 5, 16, z);
            // Every programmed circuit looks alike; its number is what matters.
            Hyb.textRight(Integer.toString(m.circuit.getItemDamage()), cx + 27, y + 19, Hyb.INK);
        } else {
            Hyb.well(cx, y, 30, CardLayout.FOOT, Hyb.TILE_EDGE, Hyb.SHADOW, 0xFF2A2C31);
        }
    }

    /** A GregTech recipe set below its own tier cannot run. */
    /** Tooltip lines for the part under the mouse, Factory Flow style: what it is, then what the mouse does. */
    public List<String> hoverLines() {
        if (model == null || !isHovering()) return null;
        final Part part = partAt(localX(), localY());
        if (part == null) return null;
        final List<String> lines = new ArrayList<>();
        final String hint = "§7";
        switch (part) {
            case ACTIONS -> {
                lines.add("Card actions");
                lines.add(hint + "Click: clone, replace, settings, delete");
            }
            case MACHINE -> {
                lines.add("Machine: " + model.machineName);
                lines.add(hint + "Click: machines that run this recipe");
                lines.add(hint + "Wheel: next machine");
            }
            case TIER -> {
                lines.add("Voltage tier: " + model.tier);
                final Object baseEu = model.node.properties.get(GTProvider.EU_PER_TICK);
                final Object baseDur = model.node.properties.get(com.sbancuz.plannh.api.RecipePropertyAPI.DURATION_TICKS);
                if (baseEu instanceof final Number eu && baseDur instanceof final Number dur && eu.longValue() > 0) {
                    lines.add(
                        hint + "Recipe: " + CardDefaults.TIERS[CardDefaults.recipeTier(eu.longValue())] + ", " + Fmt.power(eu.longValue())
                            + " EU/t, " + Fmt.compact(dur.intValue() / 20.0) + " s");
                    lines.add(
                        hint + "Here: " + Fmt.power(model.euPerTick) + " EU/t, " + Fmt.compact(model.durationTicks / 20.0)
                            + " s per run");
                }
                lines.add(hint + "Left click: up  Right click: down  Wheel: step");
            }
            case AMPS -> {
                lines.add("Energy hatch amps: " + model.amps);
                lines.add(hint + "Click: type  Right click: -1  Wheel: +/-1");
            }
            case COIL -> {
                final GtCoils.Coil coil = GtCoils.forHeat(model.coilHeat);
                lines.add("Coil: " + (coil == null ? "none" : coil.name() + " (" + coil.heat() + " K)"));
                if (model.recipeHeat > 0) lines.add("Recipe needs " + model.recipeHeat + " K");
                lines.add(hint + "Click: pick  Wheel: step");
            }
            case MACHINES -> {
                lines
                    .add("Machines: x" + Fmt.machines(model.machines) + (model.pinned ? " (pinned)" : " (worked out)"));
                lines.add(hint + "Click: type a count to pin it  Empty: unpin");
                lines.add(hint + "Wheel: +/-1");
            }
            default -> {
                return null;
            }
        }
        return lines;
    }

    // endregion

    // region Controls

    @Override
    public Result onMousePressed(final int mouseButton) {
        if (model == null) return Result.IGNORE;
        final Part part = partAt(localX(), localY());
        final Node node = model.node;
        switch (part) {
            case ACTIONS -> {
                if (mouseButton == 0) openActions();
            }
            case MACHINE -> {
                if (mouseButton == 0) openMachines();
            }
            case TIER -> stepTier(mouseButton == 1 ? -1 : 1);
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
        return Result.SUCCESS;
    }

    @Override
    public boolean onMouseScroll(final UpOrDown direction, final int amount) {
        if (model == null) return false;
        final int step = direction == UpOrDown.UP ? 1 : -1;
        final Node node = model.node;
        switch (partAt(localX(), localY())) {
            case TIER -> stepTier(step);
            case AMPS -> session.setSetting(node, "amp", Math.max(1, Math.min(64, model.amps + step)));
            case COIL -> stepCoil(step);
            case MACHINE -> stepMachine(step);
            case MACHINES -> session.pin(node, Math.max(1, Math.round(model.machines) + step));
            default -> {
                return false;
            }
        }
        return true;
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
        session.edit(() -> {
            final MachineConfig cfg = model.node.machineConfig;
            cfg.setBoolean("gt_multiblock", true);
            cfg.setInt("machine_heat", coil.heat());
        });
    }

    private void stepMachine(final int step) {
        final List<ItemStack> machines = model.catalysts;
        if (machines.size() < 2) return;
        int i = 0;
        for (int k = 0; k < machines.size(); k++) if (machines.get(k) == model.machineStack) i = k;
        i = (i + step + machines.size()) % machines.size();
        session.chooseMachine(model.node, machines.get(i), model.gregtech);
    }

    private void openActions() {
        final Node node = model.node;
        final List<PickList.Entry> rows = new ArrayList<>();
        rows.add(PickList.Entry.of("Clone node", () -> session.cloneNode(node)));
        rows.add(
            PickList.Entry.of(
                "Replace the recipe",
                () -> session.beginReplace(
                    node,
                    model.outputs.isEmpty() ? null
                        : model.outputs.get(0)
                            .lookupStack())));
        rows.add(PickList.Entry.of("Machine settings", this::openSettings));
        rows.add(new PickList.Entry(null, "Delete node", "", Hyb.RED_INK, false, () -> session.delete(node)));
        Popup.open(
            getPanel(),
            PickList.popup("plannh_actions", null, rows, false, 150),
            screenX(KEY_X),
            screenY(CHIP_Y + 18));
    }

    private void openMachines() {
        final List<PickList.Entry> rows = new ArrayList<>();
        for (final ItemStack machine : model.catalysts) {
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
                    machine == model.machineStack,
                    () -> session.chooseMachine(model.node, machine, model.gregtech)));
        }
        if (rows.isEmpty()) return;
        Popup.open(
            getPanel(),
            PickList.popup("plannh_machines", "MACHINES THAT RUN THIS RECIPE", rows, rows.size() > 8, 240),
            screenX(BAR_X),
            screenY(CHIP_Y + 18));
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
            PickList.popup("plannh_coils", null, rows, true, 200),
            screenX(COIL_X),
            screenY(layout.settingsY + 18));
    }

    private void openAmps() {
        Popup.open(
            getPanel(),
            NumberPopup.create(
                "Energy hatch amps",
                "1 to 64",
                model.amps,
                1,
                64,
                v -> session.setSetting(model.node, "amp", (int) Math.max(1, Math.min(64, Math.round(v))))),
            screenX(AMPS_X),
            screenY(CHIP_Y + 18));
    }

    private void openPin() {
        Popup.open(
            getPanel(),
            NumberPopup.create(
                "Pin the machine count (empty unpins)",
                "machines",
                model.pinned ? model.machines : 0,
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
                            screenY(CHIP_Y + 18))));
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
            PickList.popup("plannh_settings", "MACHINE SETTINGS", rows, false, 260),
            screenX(KEY_X),
            screenY(CHIP_Y + 18));
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
        if (button != 0 || model == null || partAt(localX(), localY()) != Part.BODY) return false;
        final Node node = model.node;
        dragStartX = node.x;
        dragStartY = node.y;
        dragMouseX = getContext().getAbsMouseX();
        dragMouseY = getContext().getAbsMouseY();
        dragUndo = PlanAPI.undoHistory()
            .beginEdit(session.graph());
        return true;
    }

    @Override
    public void onDrag(final int mouseButton, final long timeSinceLastClick) {
        if (model == null || dragUndo == null) return;
        final float zoom = session.graph()
            .getZoom();
        final Node node = model.node;
        node.x = Math.round(dragStartX + (getContext().getAbsMouseX() - dragMouseX) / zoom);
        node.y = Math.round(dragStartY + (getContext().getAbsMouseY() - dragMouseY) / zoom);
        pos(node.x, node.y);
    }

    @Override
    public void onDragEnd(final boolean successful) {
        if (model == null || dragUndo == null) return;
        if (successful) {
            PlanAPI.undoHistory()
                .commitEdit(dragUndo, session.graph());
            session.graph()
                .touchLayout();
            PlanAPI.save();
        } else {
            model.node.x = dragStartX;
            model.node.y = dragStartY;
            pos(dragStartX, dragStartY);
        }
        dragUndo = null;
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

package com.sbancuz.plannh.ui.card;

import java.util.List;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.cleanroommc.modularui.api.widget.IDraggable;
import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.widget.ParentWidget;
import com.cleanroommc.modularui.widget.sizer.Area;
import com.sbancuz.plannh.api.PlanAPI;
import com.sbancuz.plannh.data.flowchart.Node;
import com.sbancuz.plannh.ui.BoardSession;
import com.sbancuz.plannh.ui.gt.GtCoils;
import com.sbancuz.plannh.ui.theme.Fmt;
import com.sbancuz.plannh.ui.theme.Hyb;

/**
 * One recipe on the board, drawn as Factory Flow's recipe card in Solve mode: head (actions, machine, amps, tier),
 * rails of NEI slots with names and rates, the machine, settings, and a footer with power and the solved machine
 * count. Lives in world space inside the canvas; dragging it moves the recipe.
 */
public final class RecipeCard extends ParentWidget<RecipeCard> implements Interactable, IDraggable {

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
    }

    // region Drawing

    @Override
    public void draw(final ModularGuiContext context, final WidgetThemeEntry<?> widgetTheme) {
        final CardModel m = model;
        if (m == null) return;
        final int w = CardLayout.W, h = layout.height;
        final float z = context.getCurrentDrawingZ();
        Hyb.cardFrame(0, 0, w, h);
        drawHead(m);
        drawRail(m.inputs, false, z);
        drawRail(m.outputs, true, z);
        drawPicture(m, z);
        if (layout.settingRows > 0) drawCoil(m, z);
        drawFooter(m, z);
    }

    private void drawHead(final CardModel m) {
        final int y = CardLayout.PAD + 2;
        // Card actions key: a hamburger in Factory Flow's bevelled key.
        final int kx = CardLayout.PAD + 2;
        Hyb.bevel(kx, y, 16, 16, Hyb.KEY, Hyb.KEY_HI, Hyb.KEY_LO, Hyb.KEY_EDGE, 1);
        for (int i = 0; i < 3; i++) Hyb.rect(kx + 3, y + 4 + i * 3, 10, 2, Hyb.INK);

        // Chips from the right: tier, then amps on multiblocks.
        final int tierX = CardLayout.W - CardLayout.PAD - 2 - 26;
        chip(tierX, y, 26, Hyb.tier(m.tier));
        int barRight = tierX - 4;
        if (m.multiblock) {
            final int ampsX = tierX - 2 - 28;
            Hyb.bevel(ampsX, y, 28, 16, Hyb.WELL, 0xFF5A5C65, Hyb.SHADOW, Hyb.KEY_EDGE, 1);
            Hyb.textCentered(Fmt.compact(m.amps) + "A", ampsX + 14, y + 4, Hyb.INK);
            barRight = ampsX - 4;
        }

        // Machine switch: the name bar, chevron on the left.
        final int bx = kx + 16 + 4;
        final int bw = barRight - bx;
        Hyb.rect(bx - 1, y - 1, bw + 2, 18, Hyb.SHADOW);
        Hyb.rect(bx, y, bw, 16, Hyb.NAMEBAR);
        Hyb.rect(bx, y, bw, 1, Hyb.KEY_HI);
        Hyb.rect(bx, y, 1, 16, Hyb.KEY_HI);
        chevron(bx + 4, y + 6, Hyb.MUTED);
        final String name = Hyb.fit(m.machineName, bw - 20);
        Hyb.textCentered(name, bx + 8 + (bw - 8) / 2f, y + 4, 0xFFFFFFFF);
    }

    private static void chip(final int x, final int y, final int w, final Hyb.Tier tier) {
        Hyb.rect(x - 1, y - 1, w + 2, 18, tier.border());
        Hyb.rect(x, y, w, 16, tier.bg());
        Hyb.rect(x, y, w, 1, 0x8CFFFFFF);
        Hyb.rect(x, y, 1, 16, 0x8CFFFFFF);
        Hyb.rect(x, y + 15, w, 1, 0x73000000);
        Hyb.rect(x + w - 1, y, 1, 16, 0x73000000);
        final int tw = Hyb.width(tier.name());
        final float tx = x + (w - tw) / 2f;
        com.cleanroommc.modularui.drawable.GuiDraw.drawText(tier.name(), tx, y + 4, 1f, tier.text(), false);
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
        for (final CardModel.PortView p : ports) {
            final int y = CardLayout.portRowY(p.index());
            Hyb.slot(x, y + 1, p.isFluid());
            if (p.isFluid()) Hyb.fluid(p.fluid(), x + 1, y + 2, 16, z);
            else Hyb.item(p.item(), x + 1, y + 2, 16, z);
            final int textX = x + 21;
            final int room = CardLayout.RAIL_W - 21;
            Hyb.text(Hyb.fit(p.name(), room), textX, y + 2, Hyb.INK);
            String rate = Fmt.rate(p.perSecond(), unit, p.isFluid());
            if (p.chance() < 0.9999f) rate += " " + Fmt.compact(p.chance() * 100) + "%";
            Hyb.text(Hyb.fit(rate, room), textX, y + 11, Hyb.MUTED);
        }
    }

    private void drawPicture(final CardModel m, final float z) {
        final int x = CardLayout.PICTURE_X, y = CardLayout.RAILS_Y, w = CardLayout.PICTURE_W, h = layout.railsH;
        Hyb.well(x, y, w, h, Hyb.PICTURE, Hyb.SHADOW, Hyb.TILE_EDGE);
        if (m.machineStack != null) Hyb.item(m.machineStack, x + (w - 64) / 2f, y + (h - 64) / 2f, 64, z);
    }

    private void drawCoil(final CardModel m, final float z) {
        final int x = CardLayout.PAD, y = layout.settingsY, w = CardLayout.W - 2 * CardLayout.PAD;
        Hyb.tile(x, y, w, CardLayout.SETTING_ROW);
        Hyb.text("COIL", x + 4, y + 5, Hyb.MUTED);
        final int wx = x + 40, ww = w - 42;
        Hyb.well(wx, y + 1, ww, 16, Hyb.WELL, 0xFF282A2F, 0xFF5A5C65);
        final GtCoils.Coil coil = m.coilHeat > 0 ? GtCoils.forHeat(m.coilHeat) : null;
        final String label = coil == null ? "Pick a coil" : coil.name();
        final int lw = Hyb.width(label) + (coil == null ? 0 : 20);
        final float lx = wx + (ww - lw) / 2f;
        if (coil != null) Hyb.item(coil.stack(), lx, y + 1, 16, z);
        Hyb.text(label, lx + (coil == null ? 0 : 20), y + 5, coil == null ? Hyb.MUTED : Hyb.INK);
        chevron(wx + ww - 12, y + 7, Hyb.MUTED);
    }

    private void drawFooter(final CardModel m, final float z) {
        final int y = layout.footY;
        final int x = CardLayout.PAD;

        Hyb.tile(x, y, 150, CardLayout.FOOT);
        Hyb.text("POWER", x + 4, y + 3, Hyb.MUTED);
        final String power = Fmt.power(m.powerEuPerTick());
        Hyb.text(power, x + 4, y + 13, 2f, Hyb.INK);
        Hyb.text("EU/t", x + 6 + Hyb.width(power) * 2, y + 20, Hyb.MUTED);

        final int mx = x + 154;
        Hyb.tile(mx, y, 128, CardLayout.FOOT);
        Hyb.text("MACHINES", mx + 4, y + 3, Hyb.MUTED);
        final String count = "x" + Fmt.machines(m.machines);
        final int color = m.pinned ? Hyb.GOLD : m.machines <= 0 ? Hyb.MUTED : Hyb.INK;
        Hyb.text(count, mx + 4, y + 13, 2f, color);
        final int cw = Hyb.width(count) * 2;
        for (int dx = 0; dx < cw; dx += 3) Hyb.rect(mx + 4 + dx, y + 29, 1, 1, Hyb.MUTED);
        pencil(mx + 8 + cw, y + 18, Hyb.MUTED);

        final int cx = CardLayout.W - CardLayout.PAD - 30;
        if (m.circuit != null) {
            Hyb.tile(cx, y, 30, CardLayout.FOOT);
            Hyb.item(m.circuit, cx + 7, y + 7, 16, z);
        } else {
            Hyb.well(cx, y, 30, CardLayout.FOOT, Hyb.TILE_EDGE, Hyb.SHADOW, 0xFF2A2C31);
        }
    }

    private static void pencil(final int x, final int y, final int color) {
        for (int i = 0; i < 6; i++) Hyb.rect(x + i, y + 6 - i, 2, 2, color);
    }

    // endregion

    // region Moving the card

    @Override
    public Result onMousePressed(final int mouseButton) {
        return mouseButton == 0 ? Result.ACCEPT : Result.IGNORE;
    }

    @Override
    public boolean onDragStart(final int button) {
        if (button != 0 || model == null) return false;
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
        if (model == null) return;
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

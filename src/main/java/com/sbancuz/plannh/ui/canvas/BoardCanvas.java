package com.sbancuz.plannh.ui.canvas;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

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
import com.sbancuz.plannh.data.flowchart.Graph;
import com.sbancuz.plannh.ui.BoardSession;
import com.sbancuz.plannh.ui.card.CardModel;
import com.sbancuz.plannh.ui.card.RecipeCard;
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
    private int builtStructure = -1;

    private float panStartX, panStartY;
    private int panMouseX, panMouseY;
    private boolean panning;

    public BoardCanvas(final BoardSession session, final ModularPanel panel) {
        this.session = session;
        this.panel = panel;
    }

    private Graph graph() {
        return session.graph();
    }

    @Override
    public void onUpdate() {
        super.onUpdate();
        session.tick();
        boolean rebuild = builtStructure != session.structure();
        for (final RecipeCard card : cards.values()) rebuild |= card.shapeChanged();
        if (rebuild) rebuildCards();
    }

    private void rebuildCards() {
        builtStructure = session.structure();
        removeAll();
        cards.clear();
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
        if (!transformed) Stencil.applyAtZero(getArea(), context);
    }

    @Override
    public void postDraw(final ModularGuiContext context, final boolean transformed) {
        if (!transformed) Stencil.remove();
    }

    // endregion

    // region Pan and zoom

    @Override
    public Result onMousePressed(final int mouseButton) {
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

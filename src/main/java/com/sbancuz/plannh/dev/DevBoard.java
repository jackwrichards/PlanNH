package com.sbancuz.plannh.dev;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;

import com.sbancuz.plannh.data.flowchart.Graph;
import com.sbancuz.plannh.ui.BoardScreen;
import com.sbancuz.plannh.ui.Notice;
import com.sbancuz.plannh.ui.Planner;
import com.sbancuz.plannh.ui.canvas.BoardCanvas;
import com.sbancuz.plannh.ui.card.CardLayout;
import com.sbancuz.plannh.ui.card.CardModel;
import com.sbancuz.plannh.ui.card.RecipeCard;
import com.sbancuz.plannh.ui.drawer.DrawerCard;
import com.sbancuz.plannh.ui.drawer.DrawerModel;
import com.sbancuz.plannh.ui.gt.GtCoils;

/**
 * The open board as data for scripted tests: the view, and per card its state and the GUI rectangle of every control,
 * so a test clicks "the tier chip of card 2" instead of guessing pixels. Client thread only.
 */
final class DevBoard {

    private DevBoard() {}

    static BoardScreen screen() {
        return Planner.screenOf(Minecraft.getMinecraft().currentScreen) instanceof final BoardScreen b ? b : null;
    }

    static Map<String, Object> board() {
        final BoardScreen screen = screen();
        if (screen == null) throw new IllegalStateException("the planner is not open");
        final BoardCanvas canvas = screen.canvas();
        final Graph graph = screen.session()
            .graph();
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("zoom", graph.getZoom());
        m.put("panX", graph.getPanX());
        m.put("panY", graph.getPanY());
        m.put("canvas", rect(canvas.getArea().x, canvas.getArea().y, canvas.getArea().width, canvas.getArea().height));
        final List<Object> cards = new ArrayList<>();
        int i = 0;
        for (final CardModel model : screen.session()
            .models()
            .values()) {
            final RecipeCard card = canvas.cards()
                .get(model.node.id);
            final Map<String, Object> c = new LinkedHashMap<>();
            c.put("index", i++);
            c.put("id", model.node.id.toString());
            c.put("machine", model.machineName);
            c.put("tier", model.tier);
            c.put("amps", model.amps);
            c.put("multiblock", model.multiblock);
            final GtCoils.Coil coil = model.coilHeat > 0 ? GtCoils.forHeat(model.coilHeat) : null;
            c.put("coil", coil == null ? null : coil.name());
            c.put("coilHeat", model.coilHeat);
            c.put("recipeHeat", model.recipeHeat);
            c.put("machines", model.machines);
            c.put("pinned", model.pinned);
            c.put(
                "selected",
                screen.session()
                    .isSelected(model.node.id));
            c.put("euPerTick", model.euPerTick);
            c.put("durationTicks", model.durationTicks);
            final List<Object> ports = new ArrayList<>();
            for (final CardModel.PortView p : model.inputs) ports.add(port(p));
            for (final CardModel.PortView p : model.outputs) ports.add(port(p));
            c.put("ports", ports);
            if (card != null) {
                final RecipeCard.Part under = card.partUnderMouse();
                c.put("underMouse", under == null ? null : under.name());
                final Map<String, Object> parts = new LinkedHashMap<>();
                for (final RecipeCard.Part part : RecipeCard.Part.values()) {
                    final int[] r = card.partRect(part);
                    if (r == null) continue;
                    final int x0 = canvas.screenX(model.node.x + r[0]), y0 = canvas.screenY(model.node.y + r[1]);
                    final int x1 = canvas.screenX(model.node.x + r[0] + r[2]),
                        y1 = canvas.screenY(model.node.y + r[1] + r[3]);
                    parts.put(part.name(), rect(x0, y0, x1 - x0, y1 - y0));
                }
                for (final CardModel.PortView p : model.inputs) parts
                    .put("IN" + p.index(), slot(canvas, card.layout(), model.node.x, model.node.y, false, p.index()));
                for (final CardModel.PortView p : model.outputs) parts
                    .put("OUT" + p.index(), slot(canvas, card.layout(), model.node.x, model.node.y, true, p.index()));
                c.put("parts", parts);
            }
            cards.add(c);
        }
        m.put("cards", cards);
        final List<Object> drawers = new ArrayList<>();
        int d = 0;
        for (final DrawerModel model : screen.session()
            .drawerModels()
            .values()) {
            final Map<String, Object> o = new LinkedHashMap<>();
            o.put("index", d++);
            o.put(
                "id",
                model.drawer.getId()
                    .toString());
            o.put("kind", model.kind.name());
            o.put("label", model.label);
            o.put("rule", model.rule.name());
            o.put("target", model.target);
            o.put("rate", model.rate);
            o.put("unmet", model.unmet);
            o.put(
                "selected",
                screen.session()
                    .isSelected(model.drawer.getId()));
            o.put(
                "links",
                model.drawer.getLinks()
                    .size());
            final DrawerCard widget = canvas.drawers()
                .get(model.drawer.getId());
            if (widget != null) {
                final Map<String, Object> parts = new LinkedHashMap<>();
                for (final DrawerCard.Part part : DrawerCard.Part.values()) {
                    final int[] r = widget.partRect(part);
                    if (r == null) continue;
                    final int x0 = canvas.screenX(model.drawer.getX() + r[0]),
                        y0 = canvas.screenY(model.drawer.getY() + r[1]);
                    final int x1 = canvas.screenX(model.drawer.getX() + r[0] + r[2]),
                        y1 = canvas.screenY(model.drawer.getY() + r[1] + r[3]);
                    parts.put(part.name(), rect(x0, y0, x1 - x0, y1 - y0));
                }
                o.put("parts", parts);
            }
            drawers.add(o);
        }
        m.put("drawers", drawers);
        m.put(
            "edges",
            graph.getEdges()
                .size());
        m.put(
            "solving",
            screen.session()
                .solving());
        final List<String> notices = new ArrayList<>();
        for (final Notice n : screen.session()
            .notices()) notices.add(n.severity() + ": " + n.text());
        m.put("notices", notices);
        return m;
    }

    /** Sets zoom and pan so tests start from a known view. */
    static void view(final float zoom, final float panX, final float panY) {
        final BoardScreen screen = screen();
        if (screen == null) throw new IllegalStateException("the planner is not open");
        final Graph graph = screen.session()
            .graph();
        graph.setZoom(zoom);
        graph.setPanX(panX);
        graph.setPanY(panY);
    }

    private static Map<String, Object> port(final CardModel.PortView p) {
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("side", p.output() ? "out" : "in");
        m.put("name", p.name());
        m.put("perSecond", p.perSecond());
        if (p.chance() < 0.9999f) m.put("chance", p.chance());
        return m;
    }

    /** A port's NEI slot on screen. */
    private static Map<String, Object> slot(final BoardCanvas canvas, final CardLayout layout, final int nx,
        final int ny, final boolean output, final int index) {
        final int lx = CardLayout.railX(output), ly = layout.rowY(output, index) + 1;
        final int x0 = canvas.screenX(nx + lx), y0 = canvas.screenY(ny + ly);
        return rect(x0, y0, canvas.screenX(nx + lx + 18) - x0, canvas.screenY(ny + ly + 18) - y0);
    }

    /** {x, y, w, h} plus the centre, which is what a click wants. */
    private static Map<String, Object> rect(final int x, final int y, final int w, final int h) {
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("x", x);
        m.put("y", y);
        m.put("w", w);
        m.put("h", h);
        m.put("cx", x + w / 2);
        m.put("cy", y + h / 2);
        return m;
    }
}

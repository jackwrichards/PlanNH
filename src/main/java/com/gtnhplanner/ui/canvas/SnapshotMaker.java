package com.gtnhplanner.ui.canvas;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.gtnhplanner.data.flowchart.Drawer;
import com.gtnhplanner.data.flowchart.Edge;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.ui.BoardSession;
import com.gtnhplanner.ui.card.CardModel;
import com.gtnhplanner.ui.card.RecipeCard;
import com.gtnhplanner.ui.card.StructureArt;
import com.gtnhplanner.ui.drawer.DrawerCard;
import com.gtnhplanner.ui.drawer.DrawerModel;
import com.gtnhplanner.ui.world.PlanSnapshot;

/** Takes the board as drawn into a {@link PlanSnapshot} for the minimap and the world views. */
final class SnapshotMaker {

    private SnapshotMaker() {}

    static PlanSnapshot make(final BoardSession session, final Map<UUID, RecipeCard> cards,
        final Map<UUID, DrawerCard> drawers, final List<WireLayer.Wire> wires, final float viewX, final float viewY) {
        final Graph graph = session.graph();
        final List<PlanSnapshot.Card> outCards = new ArrayList<>();
        for (final RecipeCard card : new LinkedHashSet<>(cards.values())) {
            final CardModel m = card.model();
            if (m == null || card.layout() == null) continue;
            final List<UUID> ids = session.sectionsOf(card.nodeId);
            final List<PlanSnapshot.Flow> ins = new ArrayList<>(), outs = new ArrayList<>();
            double machines = 0, eu = 0, made = 0;
            for (final UUID id : ids) {
                final CardModel s = session.model(id);
                if (s == null) continue;
                machines += s.machines;
                eu += session.power(s);
                made += s.madeEuPerTick();
                for (final CardModel.PortView p : s.inputs) ins.add(flow(p));
                for (final CardModel.PortView p : s.outputs) outs.add(flow(p));
            }
            final Node n = m.node;
            final StructureArt.Art art = StructureArt.forMachine(m.isPower() ? n.powerSource : m.machineName);
            final int tint = art != null ? art.tint()
                : m.machineStack != null ? com.gtnhplanner.client.IngredientColors.itemColor(m.machineStack) : -1;
            outCards.add(
                new PlanSnapshot.Card(
                    card.nodeId,
                    List.copyOf(ids),
                    n.x,
                    n.y,
                    com.gtnhplanner.ui.card.CardLayout.W,
                    card.layout().height,
                    m.machineName,
                    m.machineStack,
                    art,
                    tint,
                    machines,
                    m.pinned,
                    ins,
                    outs,
                    eu,
                    made,
                    copyLinks(n.worldLinks),
                    m.gregtech && m.tier != null ? m.tier : "",
                    m.multiblock ? m.amps : 0,
                    m.isPower() ? null : m.circuit,
                    settings(m)));
        }
        final List<PlanSnapshot.Box> boxes = new ArrayList<>();
        for (final Drawer d : graph.getDrawers()) {
            final DrawerModel m = session.drawerModel(d.getId());
            if (m == null) continue;
            boxes.add(
                new PlanSnapshot.Box(
                    d.getId(),
                    d.getX(),
                    d.getY(),
                    DrawerCard.W,
                    DrawerCard.H,
                    m.label,
                    m.item,
                    m.fluid,
                    m.isPower(),
                    m.kind == Drawer.Kind.SOURCE,
                    DrawerCard.tint(m.kind),
                    m.rate));
        }
        final List<PlanSnapshot.Line> lines = new ArrayList<>(wires.size());
        for (final WireLayer.Wire w : wires) {
            if (w.path() == null || w.path()
                .size() < 2) continue;
            final Edge e = w.edge();
            lines.add(
                new PlanSnapshot.Line(
                    w.path(),
                    w.color(),
                    w.width(),
                    w.perSecond() > 0,
                    w.resource(),
                    e == null ? null : e.sourceNodeId,
                    e == null ? null : e.targetNodeId));
        }
        return new PlanSnapshot(
            graph,
            graph.getName(),
            graph.version(),
            outCards,
            boxes,
            lines,
            viewX,
            viewY,
            session.rateUnit());
    }

    private static PlanSnapshot.Flow flow(final CardModel.PortView p) {
        return new PlanSnapshot.Flow(p.name(), p.item(), p.fluid(), p.isPower(), p.perSecond(), p.key());
    }

    private static List<int[]> copyLinks(final List<int[]> links) {
        final List<int[]> out = new ArrayList<>(links.size());
        for (final int[] l : links) out.add(l.clone());
        return out;
    }

    /** The card's settings strip, as the board's card shows it. */
    private static List<PlanSnapshot.Setting> settings(final com.gtnhplanner.ui.card.CardModel m) {
        final List<PlanSnapshot.Setting> out = new ArrayList<>();
        for (final com.gtnhplanner.ui.card.CardChips.Chip c : com.gtnhplanner.ui.card.CardChips.of(m)) out.add(
            new PlanSnapshot.Setting(
                c.label(),
                c.value(),
                c.icon(),
                c.warn(),
                c.kind() == com.gtnhplanner.ui.card.CardChips.Kind.READING));
        return out;
    }
}

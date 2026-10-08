package com.gtnhplanner.ui.world;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MovingObjectPosition;

import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.ui.theme.Fmt;
import com.gtnhplanner.ui.theme.Hyb;

/**
 * Linking the block under the crosshair to a card from the world: the cards of the plan last open in the planner,
 * those of the same machine first. Clicking a card links the block to it, or unlinks it when it was.
 */
public final class LinkChooser extends GuiScreen {

    private static final int W = 320, ROW = 18, HEAD = 34, MAX_ROWS = 12;

    private record Row(@Nullable PlanSnapshot.Card card, String heading) {}

    private final Graph graph;
    private final String planName;
    private final int dim, bx, by, bz;
    private final String blockName;
    @Nullable
    private final ItemStack block;
    private final List<Row> rows = new ArrayList<>();
    private int scroll;

    private LinkChooser(final PlanSnapshot snap, final int dim, final int x, final int y, final int z,
        @Nullable final ItemStack block) {
        this.graph = snap.graph();
        this.planName = snap.planName();
        this.dim = dim;
        this.bx = x;
        this.by = y;
        this.bz = z;
        this.block = block;
        this.blockName = block != null ? block.getDisplayName() : "this block";
        final List<PlanSnapshot.Card> same = new ArrayList<>(), other = new ArrayList<>();
        for (final PlanSnapshot.Card c : snap.cards()) {
            if (block != null && c.machine() != null && LinkPicker.same(block, c.machine())) same.add(c);
            else other.add(c);
        }
        if (!same.isEmpty()) {
            rows.add(new Row(null, "SAME MACHINE"));
            for (final PlanSnapshot.Card c : same) rows.add(new Row(c, ""));
        }
        if (!other.isEmpty()) {
            rows.add(new Row(null, same.isEmpty() ? "CARDS" : "OTHER CARDS"));
            for (final PlanSnapshot.Card c : other) rows.add(new Row(c, ""));
        }
    }

    /** Opens the chooser for the block under the crosshair (the lens's machine first); false when there is none. */
    public static boolean openForCrosshair() {
        final Minecraft mc = Minecraft.getMinecraft();
        final PlanSnapshot snap = PlanSnapshot.latest();
        if (mc.theWorld == null || snap == null) return false;
        int[] at = ArLens.on() ? ArLens.INSTANCE.lookedAt() : null;
        MovingObjectPosition hit = mc.objectMouseOver;
        if (at == null && mc.renderViewEntity != null) {
            hit = mc.renderViewEntity.rayTrace(64, 1f);
            if (hit != null && hit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK)
                at = new int[] { hit.blockX, hit.blockY, hit.blockZ };
        }
        if (at == null) return false;
        ItemStack block = null;
        if (hit != null && hit.blockX == at[0] && hit.blockY == at[1] && hit.blockZ == at[2])
            block = LinkPicker.pickBlock(mc, hit);
        mc.displayGuiScreen(new LinkChooser(snap, mc.theWorld.provider.dimensionId, at[0], at[1], at[2], block));
        return true;
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private int visibleRows() {
        return Math.min(MAX_ROWS, rows.size());
    }

    private int panelH() {
        return HEAD + Math.max(1, visibleRows()) * ROW + 16;
    }

    private int left() {
        return (width - W) / 2;
    }

    private int top() {
        return (height - panelH()) / 2;
    }

    @Override
    public void drawScreen(final int mouseX, final int mouseY, final float partialTicks) {
        drawDefaultBackground();
        final int x = left(), y = top(), h = panelH();
        Hyb.rect(x - 1, y - 1, W + 2, h + 2, Hyb.FRAME);
        Hyb.rect(x, y, W, h, 0xF0141416);
        if (block != null) Hyb.item(block, x + 6, y + 6, 16, 0);
        GL11.glDisable(GL11.GL_LIGHTING);
        Hyb.text(Hyb.fit("Link " + blockName + " to a card", W - 34), x + 26, y + 6, Hyb.INK);
        Hyb.text(Hyb.fit("In " + planName, W - 34), x + 26, y + 17, Hyb.MUTED);
        if (rows.isEmpty()) Hyb.text("This plan has no cards yet", x + 8, y + HEAD + 4, Hyb.MUTED);
        final int n = visibleRows();
        for (int i = 0; i < n && i + scroll < rows.size(); i++) {
            final Row r = rows.get(i + scroll);
            final int ry = y + HEAD + i * ROW;
            if (r.card() == null) {
                Hyb.text(r.heading(), x + 8, ry + 6, Hyb.MUTED);
                continue;
            }
            final PlanSnapshot.Card c = r.card();
            final boolean hover = mouseX >= x && mouseX < x + W && mouseY >= ry && mouseY < ry + ROW;
            final Node node = graph.nodes.get(c.id());
            final boolean linked = node != null && WorldLinks.indexOf(node, dim, bx, by, bz) >= 0;
            if (hover) Hyb.rect(x + 2, ry, W - 4, ROW, 0x18FFFFFF);
            if (c.machine() != null) Hyb.item(c.machine(), x + 8, ry + 1, 16, 0);
            GL11.glDisable(GL11.GL_LIGHTING);
            final String count = "×" + Fmt.machines(c.machines());
            final String state = linked ? "Linked"
                : node != null && !node.worldLinks.isEmpty() ? node.worldLinks.size() + " in world" : "";
            // The machine, then what it makes: several cards often share one machine.
            final int room = W - 100 - Hyb.width(count);
            final String name = Hyb.fit(c.name(), room);
            Hyb.text(name, x + 28, ry + 5, Hyb.INK);
            if (!c.outputs()
                .isEmpty() && Hyb.width(name) + 30 < room) {
                final String makes = Hyb.fit(
                    c.outputs()
                        .get(0)
                        .name(),
                    room - Hyb.width(name) - 8);
                Hyb.text(makes, x + 34 + Hyb.width(name), ry + 5, Hyb.MUTED);
            }
            Hyb.text(count, x + W - 66 - Hyb.width(count), ry + 5, c.pinned() ? Hyb.GOLD : Hyb.MUTED);
            Hyb.textRight(state, x + W - 8, ry + 5, linked ? Hyb.GOLD : Hyb.MUTED);
        }
        if (rows.size() > n) {
            final String more = (scroll + n) + " of " + rows.size() + ", scroll for more";
            Hyb.textRight(more, x + W - 8, y + h - 12, Hyb.MUTED);
        }
        Hyb.text("Esc to close", x + 8, y + h - 12, Hyb.MUTED);
        GL11.glColor4f(1, 1, 1, 1);
    }

    @Override
    protected void mouseClicked(final int mouseX, final int mouseY, final int button) {
        if (button != 0) return;
        final int x = left(), y = top();
        if (mouseX < x || mouseX >= x + W || mouseY < y + HEAD) return;
        final int i = (mouseY - y - HEAD) / ROW + scroll;
        if ((mouseY - y - HEAD) / ROW >= visibleRows() || i >= rows.size()) return;
        final PlanSnapshot.Card c = rows.get(i)
            .card();
        if (c == null) return;
        final Node node = graph.nodes.get(c.id());
        if (node == null) return;
        WorldLinks.toggle(graph, node, dim, bx, by, bz);
        Hyb.click();
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        final int wheel = Mouse.getEventDWheel();
        if (wheel != 0) scroll = Math.max(0, Math.min(rows.size() - visibleRows(), scroll + (wheel > 0 ? -1 : 1)));
    }
}

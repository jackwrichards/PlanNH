package com.gtnhplanner.ui.world;

import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.util.MovingObjectPosition;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;

import org.lwjgl.opengl.GL11;

import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.ui.PlannerSettings;
import com.gtnhplanner.ui.theme.Fmt;
import com.gtnhplanner.ui.theme.Hyb;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Cards' blocks in the world: outlined while they are being picked ({@link LinkPicker}); outlined with a beam and a
 * label for a while after "Show in the world"; and, for the linked block under the crosshair, outlined (with the rest
 * of its card's) and named under the crosshair, its card rung on the minimap. A linked block that is broken is
 * unlinked.
 */
public final class WorldView {

    public static final WorldView INSTANCE = new WorldView();

    /** How far the crosshair looks for a linked block, in blocks: further than the arm reaches. */
    private static final double LOOK_RANGE = 64;
    private static final long SHOW_MILLIS = 30_000;

    /** The card shown with "Show in the world", until when; set from a menu, so the planner closes next tick. */
    @Nullable
    private Graph shownGraph;
    @Nullable
    private UUID shownId;
    private long shownUntil;
    private boolean closing;
    /** A line at the top of the screen for a few seconds (the shown card being somewhere else). */
    private String note = "";
    private int noteColor = Hyb.AMBER_INK;
    private long noteUntil;

    /** A line at the top of the screen for a few seconds, after an action that sends the player back to the world. */
    public static void say(final String text) {
        INSTANCE.note = text;
        INSTANCE.noteColor = Hyb.INK;
        INSTANCE.noteUntil = System.currentTimeMillis() + 4000;
    }

    /** The linked block under the crosshair and its card, found each tick. */
    @Nullable
    private WorldLinks.Hit look;
    private int lookX, lookY, lookZ;

    private WorldView() {}

    /** Outlines a card's blocks for a while, with beams to find them by; the planner closes. */
    public static void show(final Graph graph, final UUID nodeId) {
        INSTANCE.shownGraph = graph;
        INSTANCE.shownId = nodeId;
        INSTANCE.shownUntil = System.currentTimeMillis() + SHOW_MILLIS;
        INSTANCE.closing = true;
    }

    @Nullable
    private Node shown() {
        if (shownGraph == null || System.currentTimeMillis() > shownUntil) return null;
        return WorldLinks.node(shownGraph, shownId);
    }

    @SubscribeEvent
    public void onTick(final TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null) {
            look = null;
            return;
        }
        if (closing) {
            closing = false;
            mc.displayGuiScreen(null);
            final Node n = shown();
            if (n != null && WorldLinks.countIn(n, mc.theWorld.provider.dimensionId) == 0) {
                note = "Its blocks are in another dimension";
                noteColor = Hyb.AMBER_INK;
                noteUntil = System.currentTimeMillis() + 4000;
            }
        }
        findLook(mc);
    }

    /** The linked block under the crosshair, further out than the arm reaches; and the minimap follows its card. */
    private void findLook(final Minecraft mc) {
        final WorldLinks.Hit was = look;
        look = null;
        if (!LinkPicker.active()) {
            final WorldLinks.Spot spot = WorldLinks.onRay(mc, LOOK_RANGE, null);
            if (spot != null) {
                look = spot.hit();
                lookX = spot.x();
                lookY = spot.y();
                lookZ = spot.z();
            }
        }
        final PlanSnapshot snap = PlanSnapshot.latest();
        if (look != null && snap != null && look.graph() == snap.graph()) Minimap.INSTANCE.focus(look.node().id);
        else if (was != null) Minimap.INSTANCE.focus(null);
    }

    @SubscribeEvent
    public void onRenderWorld(final RenderWorldLastEvent event) {
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null) return;
        final int dim = mc.theWorld.provider.dimensionId;
        final Node picking = LinkPicker.active() ? LinkPicker.node() : null;
        final Node shown = shown();
        final Node looked = look != null && PlannerSettings.worldHighlight() ? look.node() : null;
        if (picking == null && shown == null && looked == null) return;
        WorldMarks.begin();
        if (picking != null) {
            // Where the card would go: the machine's ghost in front of the face looked at.
            final MovingObjectPosition hit = mc.objectMouseOver;
            if (hit != null && hit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK) {
                final int[] at = WorldLinks.inFront(hit);
                if (LinkPicker.machine() != null) WorldMarks.ghost(LinkPicker.machine(), at[0], at[1], at[2]);
                WorldMarks.outline(at[0], at[1], at[2], Hyb.LIT & 0xFFFFFF, 0.004f, 1.5f, 0.6f);
            }
        }
        if (shown != null) {
            for (final int[] l : shown.worldLinks) {
                if (l[0] != dim) continue;
                WorldMarks.outline(l[1], l[2], l[3], Hyb.LIT & 0xFFFFFF, 0.004f, 2f, 0.8f);
                WorldMarks.beam(l[1], l[2], l[3], Hyb.LIT & 0xFFFFFF);
            }
        }
        if (looked != null && looked != shown && looked != picking) {
            for (final int[] l : looked.worldLinks) if (l[0] == dim) {
                final boolean it = l[1] == lookX && l[2] == lookY && l[3] == lookZ;
                WorldMarks.outline(l[1], l[2], l[3], Hyb.LIT & 0xFFFFFF, 0.004f, 1.5f, it ? 0.7f : 0.45f);
            }
        }
        if (shown != null) labels(shown, dim);
        WorldMarks.end();
    }

    /** The shown card's name and distance over each of its blocks. */
    private static void labels(final Node node, final int dim) {
        final String name = cardName(node);
        final FontRenderer font = Hyb.font();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        for (final int[] l : node.worldLinks) {
            if (l[0] != dim) continue;
            final double d = WorldMarks.distance(l[1], l[2], l[3]);
            final float scale = (float) (0.012 * Math.max(1, d / 10));
            WorldMarks.beginPanel(l[1] + 0.5, l[2] + 1.6 + scale * 12, l[3] + 0.5, scale);
            final String far = Math.round(d) + " m";
            final int w = Math.max(font.getStringWidth(name), font.getStringWidth(far));
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            Hyb.rect(-w / 2f - 3, -2, w + 6, 21, 0xC0101114);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            font.drawString(name, -font.getStringWidth(name) / 2, 0, Hyb.INK);
            font.drawString(far, -font.getStringWidth(far) / 2, 10, Hyb.MUTED);
            WorldMarks.endPanel();
        }
        GL11.glDisable(GL11.GL_TEXTURE_2D);
    }

    /** A card's machine name as the board shows it (its snapshot's), else its node's. */
    static String cardName(final Node node) {
        final PlanSnapshot snap = PlanSnapshot.latest();
        final PlanSnapshot.Card card = snap == null ? null : snap.cardOf(node.id);
        if (card != null && card.name() != null) return card.name();
        if (node.isPower()) return node.powerSource;
        // A card of a plan not open since: its machine is saved as an item key.
        if (node.machineName != null && node.machineName.startsWith("item:")) {
            final net.minecraft.item.ItemStack machine = com.gtnhplanner.ui.Resources.item(node.machineName);
            if (machine != null) return machine.getDisplayName();
        }
        return node.machineName != null ? node.machineName : "Machine";
    }

    /** Under the crosshair: the linked block's card (name, count) and plan; at the top, a passing note. */
    @SubscribeEvent
    public void onOverlay(final RenderGameOverlayEvent.Post event) {
        if (event.type != RenderGameOverlayEvent.ElementType.ALL) return;
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc.currentScreen != null || mc.gameSettings.hideGUI) return;
        final ScaledResolution sr = event.resolution;
        final float cx = sr.getScaledWidth() / 2f, cy = sr.getScaledHeight() / 2f;
        if (System.currentTimeMillis() < noteUntil) {
            Hyb.rect(cx - Hyb.width(note) / 2f - 4, 6, Hyb.width(note) + 8, 13, 0xE0141414);
            Hyb.textCentered(note, cx, 9, noteColor);
        }
        if (look == null || !PlannerSettings.worldHighlight() || PlanOverlay.on()) return;
        final Node node = look.node();
        final PlanSnapshot snap = PlanSnapshot.latest();
        final PlanSnapshot.Card card = snap != null && snap.graph() == look.graph() ? snap.cardOf(node.id) : null;
        String line = cardName(node);
        if (card != null) line += "  ×" + Fmt.machines(card.machines());
        final String plan = "In " + look.graph()
            .getName();
        final int w = Math.max(Hyb.width(line), Hyb.width(plan)) + 10;
        final float x = cx - w / 2f, y = cy + 12;
        Hyb.rect(x, y, w, 24, 0xD0141414);
        Hyb.rect(x, y, 2, 24, Hyb.LIT);
        Hyb.textCentered(line, cx + 1, y + 3, card != null && card.pinned() ? Hyb.GOLD : Hyb.INK);
        Hyb.textCentered(plan, cx + 1, y + 13, Hyb.MUTED);
        GL11.glColor4f(1, 1, 1, 1);
    }
}

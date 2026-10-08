package com.gtnhplanner.ui.world;

import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiIngameMenu;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MovingObjectPosition;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;

import org.lwjgl.opengl.GL11;

import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.ui.Planner;
import com.gtnhplanner.ui.theme.Hyb;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Linking a card to blocks in the world. Started from the card's menu: the planner closes and the crosshair picks
 * blocks, a left-click linking the block under it (or unlinking it, when linked), until a right-click or Esc, which
 * opens the planner again. Opening the planner any other way ends it too. The card's blocks are outlined meanwhile
 * ({@link WorldView}).
 */
public final class LinkPicker {

    public static final LinkPicker INSTANCE = new LinkPicker();

    @Nullable
    private Graph graph;
    @Nullable
    private UUID nodeId;
    private String name = "";
    @Nullable
    private ItemStack machine;
    /** The line under the hint after a click, its colour, and until when it shows. */
    private String note = "";
    private int noteColor;
    private long noteUntil;
    /** Set from a menu or an event: the screen changes on the next tick, outside them. */
    private boolean starting, finishing;

    private LinkPicker() {}

    /** Starts picking blocks for a card; the planner closes on the next tick. */
    public static void start(final Graph graph, final UUID nodeId, final String name,
        @Nullable final ItemStack machine) {
        INSTANCE.graph = graph;
        INSTANCE.nodeId = nodeId;
        INSTANCE.name = name == null ? "" : name;
        INSTANCE.machine = machine;
        INSTANCE.note = "";
        INSTANCE.starting = true;
        INSTANCE.finishing = false;
    }

    public static boolean active() {
        return INSTANCE.graph != null && !INSTANCE.starting;
    }

    /** The card being linked, while picking. */
    @Nullable
    static Node node() {
        return WorldLinks.node(INSTANCE.graph, INSTANCE.nodeId);
    }

    private void stop() {
        graph = null;
        nodeId = null;
        machine = null;
        starting = finishing = false;
    }

    @SubscribeEvent
    public void onTick(final TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || graph == null) return;
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || node() == null) {
            stop();
            return;
        }
        if (starting) {
            starting = false;
            mc.displayGuiScreen(null);
        } else if (finishing) {
            stop();
            Planner.open();
        }
    }

    /** Left-click links or unlinks; right-click finishes. Neither reaches the game while picking. */
    @SubscribeEvent
    public void onMouse(final MouseEvent event) {
        if (!active() || event.button < 0 || event.button > 1 || Minecraft.getMinecraft().currentScreen != null) return;
        event.setCanceled(true);
        if (!event.buttonstate) return;
        if (event.button == 1) finishing = true;
        else pick();
    }

    /** Esc finishes instead of pausing; the planner opening (its key) ends picking. */
    @SubscribeEvent
    public void onGuiOpen(final GuiOpenEvent event) {
        if (!active()) return;
        if (event.gui instanceof GuiIngameMenu) {
            event.setCanceled(true);
            finishing = true;
        } else if (Planner.isPlanner(event.gui)) stop();
    }

    private void pick() {
        final Minecraft mc = Minecraft.getMinecraft();
        final Node node = node();
        final MovingObjectPosition hit = mc.objectMouseOver;
        if (node == null) return;
        if (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) {
            say("Look at a block, then click", Hyb.MUTED);
            return;
        }
        final int dim = mc.theWorld.provider.dimensionId;
        final boolean linked = WorldLinks.toggle(graph, node, dim, hit.blockX, hit.blockY, hit.blockZ);
        Hyb.click();
        if (!linked) {
            say("Unlinked", Hyb.MUTED);
            return;
        }
        final ItemStack block = pickBlock(mc, hit);
        if (machine != null && block != null && !same(block, machine))
            say("Linked. This block is " + block.getDisplayName() + ", not " + name, Hyb.AMBER_INK);
        else say("Linked", Hyb.PRODUCT_INK);
    }

    private void say(final String text, final int color) {
        note = text;
        noteColor = color;
        noteUntil = System.currentTimeMillis() + 3000;
    }

    /** What the block under the crosshair is, as its pick-block item, or null. */
    @Nullable
    static ItemStack pickBlock(final Minecraft mc, final MovingObjectPosition hit) {
        try {
            final Block block = mc.theWorld.getBlock(hit.blockX, hit.blockY, hit.blockZ);
            return block == null ? null : block.getPickBlock(hit, mc.theWorld, hit.blockX, hit.blockY, hit.blockZ);
        } catch (final RuntimeException e) {
            return null;
        }
    }

    static boolean same(final ItemStack a, final ItemStack b) {
        return a.getItem() == b.getItem() && a.getItemDamage() == b.getItemDamage();
    }

    // region HUD

    /** A picking crosshair in place of the game's. */
    @SubscribeEvent
    public void onCrosshair(final RenderGameOverlayEvent.Pre event) {
        if (event.type != RenderGameOverlayEvent.ElementType.CROSSHAIRS || !active()) return;
        event.setCanceled(true);
        final ScaledResolution sr = event.resolution;
        final float cx = sr.getScaledWidth() / 2f, cy = sr.getScaledHeight() / 2f;
        final Node node = node();
        final Minecraft mc = Minecraft.getMinecraft();
        final MovingObjectPosition hit = mc.objectMouseOver;
        final boolean onBlock = hit != null && hit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK;
        final boolean linked = onBlock && node != null
            && WorldLinks.indexOf(node, mc.theWorld.provider.dimensionId, hit.blockX, hit.blockY, hit.blockZ) >= 0;
        final int c = linked ? Hyb.GOLD : onBlock ? Hyb.INK : Hyb.MUTED;
        // Four corners of a square, and a dot.
        final float r = 6, l = 3;
        for (final int sx : new int[] { -1, 1 }) for (final int sy : new int[] { -1, 1 }) {
            final float x = cx + sx * r, y = cy + sy * r;
            Hyb.rect(sx < 0 ? x : x - l + 1, y, l, 1, c);
            Hyb.rect(x, sy < 0 ? y : y - l + 1, 1, l, c);
        }
        Hyb.rect(cx - 0.5f, cy - 0.5f, 1, 1, c);
        if (onBlock) {
            final String what = linked ? "Click: unlink" : "Click: link";
            Hyb.textCentered(what, cx, cy + 12, c);
        }
        // The hotbar draws next and expects textures on.
        GL11.glColor4f(1, 1, 1, 1);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
    }

    /** What is being linked and how to finish, at the top of the screen. */
    @SubscribeEvent
    public void onOverlay(final RenderGameOverlayEvent.Post event) {
        if (event.type != RenderGameOverlayEvent.ElementType.ALL || !active()) return;
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc.currentScreen != null || mc.gameSettings.hideGUI) return;
        final Node node = node();
        if (node == null) return;
        final int linked = node.worldLinks.size();
        final String title = "Linking " + name;
        final String how = "Left-click a block to link or unlink it. Right-click or Esc when done.";
        final String count = linked == 0 ? "No blocks linked yet"
            : linked == 1 ? "1 block linked" : linked + " blocks linked";
        final boolean noting = System.currentTimeMillis() < noteUntil && !note.isEmpty();
        final int w = Math
            .max(Math.max(Hyb.width(title) + 22 + Hyb.width(count) + 12, Hyb.width(how)), noting ? Hyb.width(note) : 0)
            + 12;
        final int h = noting ? 44 : 29;
        final float x = (event.resolution.getScaledWidth() - w) / 2f, y = 6;
        Hyb.rect(x - 1, y - 1, w + 2, h + 2, Hyb.FRAME);
        Hyb.rect(x, y, w, h, 0xE0141414);
        if (machine != null) Hyb.item(machine, x + 5, y + 3, 12, 0);
        Hyb.text(title, x + (machine != null ? 21 : 6), y + 5, Hyb.GOLD);
        Hyb.textRight(count, x + w - 6, y + 5, Hyb.INK);
        Hyb.text(how, x + 6, y + 17, Hyb.MUTED);
        if (noting) {
            Hyb.rect(x + 6, y + 28, w - 12, 1, Hyb.TILE);
            Hyb.text(note, x + 6, y + 32, noteColor);
        }
        GL11.glColor4f(1, 1, 1, 1);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
    }

    // endregion
}

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

import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.ui.Planner;
import com.gtnhplanner.ui.gt.StructureGhosts;
import com.gtnhplanner.ui.theme.Hyb;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.InputEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Placing a plan card in the world. Started from the card's place key: the planner closes, and the card's machine shows
 * as a ghost on an imaginary block in front of the face under the crosshair (a multiblock standing on it); a left-click
 * places the card there (taking the spot from any other card), and the player stays in the world. R turns it, [ and ]
 * size a structure that comes in sizes. A right-click or Esc gives up; opening the planner ends it too. Also picks up
 * one placement to put it down elsewhere ({@link #move}).
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
    private StructureGhosts.Needs needs = StructureGhosts.Needs.NONE;
    /** How many of the card's machines can be placed, and how many have been this time. */
    private int max = 1, placed;
    /**
     * Quarter turns clockwise from the facing it takes (toward the player, or a moved placement's own), and the
     * structure's size (0: as its recipe needs).
     */
    private int turn, size;
    /** The placement being moved, or null when placing. */
    @Nullable
    private int[] moving;
    /** Set from a menu or an event: the screen changes on the next tick, outside them. */
    private boolean starting, finishing;

    private LinkPicker() {}

    /**
     * Starts picking spots for a card, one for each of its {@code machines} (at least one); the planner closes on the
     * next tick.
     */
    public static void start(final Graph graph, final UUID nodeId, final String name, @Nullable final ItemStack machine,
        final StructureGhosts.Needs needs, final int machines) {
        begin(graph, nodeId, name, machine, needs);
        INSTANCE.max = Math.max(1, machines);
    }

    /** Picks up one of a card's placements, to put it down elsewhere as it is (its facing and size kept). */
    static void move(final Graph graph, final UUID nodeId, final String name, @Nullable final ItemStack machine,
        final StructureGhosts.Needs needs, final int[] link) {
        begin(graph, nodeId, name, machine, needs);
        INSTANCE.moving = link.clone();
        INSTANCE.size = WorldLinks.size(link);
    }

    private static void begin(final Graph graph, final UUID nodeId, final String name,
        @Nullable final ItemStack machine, final StructureGhosts.Needs needs) {
        INSTANCE.graph = graph;
        INSTANCE.nodeId = nodeId;
        INSTANCE.name = name == null ? "" : name;
        INSTANCE.machine = machine;
        INSTANCE.needs = needs == null ? StructureGhosts.Needs.NONE : needs;
        INSTANCE.max = 1;
        INSTANCE.placed = 0;
        INSTANCE.turn = 0;
        INSTANCE.size = 0;
        INSTANCE.moving = null;
        INSTANCE.starting = true;
        INSTANCE.finishing = false;
    }

    public static boolean active() {
        return INSTANCE.graph != null && !INSTANCE.starting;
    }

    /** The machine of the card being placed, for its ghost. */
    @Nullable
    static ItemStack machine() {
        return INSTANCE.machine;
    }

    /** The placement picked up to move, while it is: it shows where it goes, not where it was. Else null. */
    @Nullable
    static int[] moving() {
        return active() ? INSTANCE.moving : null;
    }

    /**
     * Where the machine in hand would go now, as a placement {dim, x, y, z, facing, size}, or null when the crosshair
     * is
     * on no block.
     */
    @Nullable
    static int[] preview() {
        final MovingObjectPosition hit = active() ? aim() : null;
        if (hit == null) return null;
        final int[] at = spot(hit);
        return WorldLinks
            .link(Minecraft.getMinecraft().theWorld.provider.dimensionId, at[0], at[1], at[2], facing(), INSTANCE.size);
    }

    /**
     * Whether putting it down takes the card off where it was: moving one placement takes that one, the first of a
     * card's placements all of them.
     */
    static boolean replacing() {
        return INSTANCE.moving != null || INSTANCE.placed == 0;
    }

    /** What the card being placed asks of its structure. */
    static StructureGhosts.Needs needs() {
        return INSTANCE.needs;
    }

    /** The size the structure being placed is set to: 0 for as its recipe needs. */
    static int size() {
        return INSTANCE.size;
    }

    /** The structure being placed, once its ghost is built; null for a single block. */
    @Nullable
    static StructureGhosts.Ghost ghost() {
        return StructureGhosts.peek(INSTANCE.machine, INSTANCE.needs, INSTANCE.size);
    }

    /** How far the crosshair reaches when placing: past the game's reach, to stand back from a big structure. */
    private static final double REACH = 48;

    /** The block face under the crosshair, as far as placing reaches, or null. */
    @Nullable
    static MovingObjectPosition aim() {
        final net.minecraft.entity.EntityLivingBase eye = Minecraft.getMinecraft().renderViewEntity;
        if (eye == null || Minecraft.getMinecraft().theWorld == null) return null;
        final MovingObjectPosition hit = eye.rayTrace(REACH, 1f);
        return hit != null && hit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK ? hit : null;
    }

    /**
     * Where the machine being placed would go for a block face hit: on the spot in front of it, or, for a multiblock,
     * its controller as high above that spot as stands the structure's bottom on it.
     */
    static int[] spot(final MovingObjectPosition hit) {
        final int[] at = WorldLinks.inFront(hit);
        at[1] += StructureGhosts.lift(INSTANCE.machine, INSTANCE.needs, INSTANCE.size);
        return at;
    }

    /**
     * The way the machine being placed would face: its front toward the player (a moved one as it faced), turned by R.
     */
    static int facing() {
        final int[] was = INSTANCE.moving;
        final net.minecraft.entity.EntityLivingBase eye = Minecraft.getMinecraft().renderViewEntity;
        final int base = was != null ? WorldLinks.facing(was)
            : eye == null ? 0 : WorldLinks.facingToward(eye.rotationYaw);
        return (base + INSTANCE.turn) & 3;
    }

    /** The card being placed, while picking. */
    @Nullable
    static Node node() {
        return WorldLinks.node(INSTANCE.graph, INSTANCE.nodeId);
    }

    private void stop() {
        graph = null;
        nodeId = null;
        machine = null;
        moving = null;
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
        } else if (finishing) stop();
    }

    /** R turns what is being placed a quarter; [ and ] size a structure that comes in sizes. */
    @SubscribeEvent
    public void onKey(final InputEvent.KeyInputEvent event) {
        if (!active() || Minecraft.getMinecraft().currentScreen != null || !Keyboard.getEventKeyState()) return;
        switch (Keyboard.getEventKey()) {
            case Keyboard.KEY_R -> {
                turn = (turn + 1) & 3;
                Hyb.click();
            }
            case Keyboard.KEY_LBRACKET -> resize(-1);
            case Keyboard.KEY_RBRACKET -> resize(1);
            default -> {}
        }
    }

    private void resize(final int step) {
        final StructureGhosts.Ghost g = ghost();
        if (g == null || !g.sized()) return;
        final int next = Math.max(1, Math.min(g.sizes(), g.size() + step));
        if (next == g.size()) return;
        // Back at the size its recipe needs, it follows the recipe again.
        final StructureGhosts.Ghost auto = StructureGhosts.get(machine, needs, 0);
        size = auto != null && auto.size() == next ? 0 : next;
        Hyb.click();
    }

    /** Left-click places the card on the block; right-click gives up. Neither reaches the game while picking. */
    @SubscribeEvent
    public void onMouse(final MouseEvent event) {
        if (!active() || event.button < 0 || event.button > 1 || Minecraft.getMinecraft().currentScreen != null) return;
        event.setCanceled(true);
        if (!event.buttonstate) return;
        if (event.button == 0) pick();
        else finishing = true;
    }

    /** Esc gives up instead of pausing; the planner opening (its key) ends picking. */
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
        final MovingObjectPosition hit = aim();
        if (node == null || hit == null) return;
        final int[] at = spot(hit);
        final int dim = mc.theWorld.provider.dimensionId;
        com.gtnhplanner.ui.sound.Sfx.WORLD_PLACE.play();
        if (moving != null) {
            WorldLinks.relink(graph, node, moving, WorldLinks.link(dim, at[0], at[1], at[2], facing(), size), true);
            WorldView.say("Moved " + name + " to " + at[0] + ", " + at[1] + ", " + at[2]);
            finishing = true;
            return;
        }
        // The first spot this time replaces where the card was; the rest are its other machines.
        final WorldLinks.Hit was = WorldLinks.assign(graph, node, dim, at[0], at[1], at[2], facing(), size, placed > 0);
        placed++;
        WorldView.say(
            "Placed " + name
                + (max > 1 ? " " + placed + " of " + max : "")
                + " at "
                + at[0]
                + ", "
                + at[1]
                + ", "
                + at[2]
                + (was == null || was.node() == node ? "" : ". Taken from " + WorldView.cardName(was.node())));
        if (placed >= max) finishing = true;
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

    // region HUD

    /** A picking crosshair in place of the game's. */
    @SubscribeEvent
    public void onCrosshair(final RenderGameOverlayEvent.Pre event) {
        if (event.type != RenderGameOverlayEvent.ElementType.CROSSHAIRS || !active()) return;
        event.setCanceled(true);
        final ScaledResolution sr = event.resolution;
        final float cx = sr.getScaledWidth() / 2f, cy = sr.getScaledHeight() / 2f;
        final boolean onBlock = aim() != null;
        final int c = onBlock ? Hyb.GOLD : Hyb.MUTED;
        // Four corners of a square, and a dot.
        final float r = 6, l = 3;
        for (final int sx : new int[] { -1, 1 }) for (final int sy : new int[] { -1, 1 }) {
            final float x = cx + sx * r, y = cy + sy * r;
            Hyb.rect(sx < 0 ? x : x - l + 1, y, l, 1, c);
            Hyb.rect(x, sy < 0 ? y : y - l + 1, 1, l, c);
        }
        Hyb.rect(cx - 0.5f, cy - 0.5f, 1, 1, c);
        // The hotbar draws next and expects textures on.
        GL11.glColor4f(1, 1, 1, 1);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
    }

    /** What is being placed and how, at the top of the screen. */
    @SubscribeEvent
    public void onOverlay(final RenderGameOverlayEvent.Post event) {
        if (event.type != RenderGameOverlayEvent.ElementType.ALL || !active()) return;
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc.currentScreen != null || mc.gameSettings.hideGUI) return;
        final StructureGhosts.Ghost g = ghost();
        final String title = (moving != null ? "Move " : "Place ") + name
            + (max > 1 ? ": " + (placed + 1) + " of " + max : "")
            + (g == null ? "" : "   " + dimensions(g));
        final String how = placed > 0 ? "Click for the next one. R turns it. Right-click or Esc to stop here."
            : "Click a block face to place it there. R turns it" + (g != null && g.sized() ? ", [ ] sizes it" : "")
                + ". Right-click or Esc to cancel.";
        final int w = Math.max(Hyb.width(title) + 22, Hyb.width(how)) + 12, h = 29;
        final float x = (event.resolution.getScaledWidth() - w) / 2f, y = 6;
        Hyb.rect(x - 1, y - 1, w + 2, h + 2, Hyb.FRAME);
        Hyb.rect(x, y, w, h, 0xE0141414);
        if (machine != null) Hyb.item(machine, x + 5, y + 3, 12, 0);
        GL11.glDisable(GL11.GL_LIGHTING);
        Hyb.text(title, x + (machine != null ? 21 : 6), y + 5, Hyb.GOLD);
        Hyb.text(how, x + 6, y + 17, Hyb.MUTED);
        GL11.glColor4f(1, 1, 1, 1);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
    }

    /** A structure's size in blocks, across, high and deep as it faces you: "3 x 7 x 3". */
    static String dimensions(final StructureGhosts.Ghost g) {
        return (g.maxX() - g.minX() + 1) + " x " + (g.maxY() - g.minY() + 1) + " x " + (g.maxZ() - g.minZ() + 1);
    }

    // endregion
}

package com.gtnhplanner.ui.world;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MovingObjectPosition;

import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.ui.Planner;
import com.gtnhplanner.ui.PlannerSettings;
import com.gtnhplanner.ui.theme.Hyb;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Placing a plan card on a block from the world: the Link key on a block opens the planner in link mode. The board says
 * which block, rings every card (the one on it now in gold); any plan can be opened meanwhile. Clicking a card places
 * it on the block (taking the block from any other card) and goes back to the world; clicking the card on it now
 * removes it. Esc, or leaving the planner, cancels.
 */
public final class LinkTarget {

    public static final LinkTarget INSTANCE = new LinkTarget();

    /** The block being linked, and what it is. */
                                                  /**
                                                   * The spot a card goes on, and the block it is in front of (null when
                                                   * the spot holds a card already).
                                                   */
    public record Block(int dim, int x, int y, int z, @Nullable ItemStack item) {}

    @Nullable
    private static Block target;
    /** Why the last click was refused, and until when it shows. */
    private static String note = "";
    private static long noteUntil;
    private static boolean closing;
    /** Whether each card can take the block, worked out once per card. */
    private static final Map<UUID, Boolean> FITS = new HashMap<>();

    private LinkTarget() {}

    public static boolean active() {
        return target != null;
    }

    @Nullable
    public static Block target() {
        return target;
    }

    /** Link mode for the machine under the crosshair (as far as the lens reaches); false when there is none. */
    public static boolean beginForCrosshair() {
        final Minecraft mc = Minecraft.getMinecraft();
        final EntityLivingBase eye = mc.renderViewEntity;
        if (mc.theWorld == null || eye == null) return false;
        final double reach = Math.max(64, PlannerSettings.arRange());
        final int dim = mc.theWorld.provider.dimensionId;
        final MovingObjectPosition hit = eye.rayTrace(reach, 1f);
        final boolean onBlock = hit != null && hit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK;
        // A placed spot the crosshair meets before any block face is the target itself; otherwise the spot in front
        // of the face.
        final WorldLinks.Spot spot = WorldLinks.onRay(mc, reach, null);
        if (spot != null && (!onBlock || spot.distance() <= hit.hitVec.distanceTo(eye.getPosition(1f))))
            target = new Block(dim, spot.x(), spot.y(), spot.z(), null);
        else if (onBlock) {
            final int[] at = WorldLinks.inFront(hit);
            target = new Block(dim, at[0], at[1], at[2], LinkPicker.pickBlock(mc, hit));
        } else return false;
        FITS.clear();
        note = "";
        Planner.open();
        return true;
    }

    public static void cancel() {
        target = null;
        FITS.clear();
    }

    /** Whether a card can take the block: any card can. */
    public static boolean fits(final Graph graph, final Node card) {
        return target != null;
    }

    /** Whether the block is on this card now. */
    public static boolean holds(final Node card) {
        final Block b = target;
        return b != null && WorldLinks.indexOf(card, b.dim(), b.x(), b.y(), b.z()) >= 0;
    }

    /** A click on a card in link mode. */
    public static void choose(final Graph graph, final Node card) {
        final Block b = target;
        if (b == null) return;
        if (holds(card)) {
            WorldLinks.unlink(graph, card, b.dim(), b.x(), b.y(), b.z());
            WorldView.say("Removed " + WorldView.cardName(card) + " from the world");
        } else {
            final WorldLinks.Hit was = WorldLinks.assign(graph, card, b.dim(), b.x(), b.y(), b.z());
            WorldView.say(
                "Placed " + WorldView.cardName(card)
                    + " here"
                    + (was == null || was.node() == card ? "" : ". Taken from " + WorldView.cardName(was.node())));
        }
        Hyb.click();
        cancel();
        closing = true;
    }

    /** The refusal to show under the banner, or empty. */
    public static String note() {
        return System.currentTimeMillis() < noteUntil ? note : "";
    }

    /** Back to the world after a pick (outside the click that made it); out of link mode once the planner is gone. */
    @SubscribeEvent
    public void onTick(final TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        final Minecraft mc = Minecraft.getMinecraft();
        if (closing) {
            closing = false;
            if (Planner.isPlanner(mc.currentScreen)) mc.displayGuiScreen(null);
        }
        if (target != null && mc.currentScreen == null) cancel();
    }
}

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
 * Linking a machine in the world to a card from the planner: the Link key on a block opens the planner in link mode.
 * The board says what is being linked, lights the cards whose recipe runs on that machine (the one it is on now in
 * gold) and dims the rest; any plan can be opened meanwhile. Clicking a lit card links the block to it (taking it off
 * any other card) and goes back to the world; clicking its own card unlinks it. Esc, or leaving the planner, cancels.
 */
public final class LinkTarget {

    public static final LinkTarget INSTANCE = new LinkTarget();

    /** The block being linked, and what it is. */
    public record Block(int dim, int x, int y, int z, ItemStack item) {}

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
        final MovingObjectPosition hit = eye.rayTrace(Math.max(64, PlannerSettings.arRange()), 1f);
        if (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) return false;
        final ItemStack item = LinkPicker.pickBlock(mc, hit);
        if (item == null) return false;
        target = new Block(mc.theWorld.provider.dimensionId, hit.blockX, hit.blockY, hit.blockZ, item);
        FITS.clear();
        note = "";
        Planner.open();
        return true;
    }

    public static void cancel() {
        target = null;
        FITS.clear();
    }

    /** Whether a card can take the block. */
    public static boolean fits(final Graph graph, final Node card) {
        final Block b = target;
        if (b == null) return false;
        return FITS.computeIfAbsent(card.id, id -> MachineMatch.fits(graph, card, b.item()));
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
            WorldView.say("Unlinked from " + WorldView.cardName(card));
        } else {
            final String why = MachineMatch.refuse(graph, card, b.item());
            if (why != null) {
                note = why;
                noteUntil = System.currentTimeMillis() + 3500;
                return;
            }
            final WorldLinks.Hit was = WorldLinks.assign(graph, card, b.dim(), b.x(), b.y(), b.z());
            WorldView.say(
                "Linked to " + WorldView.cardName(card)
                    + " in "
                    + graph.getName()
                    + (was == null ? "" : ". Moved from " + WorldView.cardName(was.node())));
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

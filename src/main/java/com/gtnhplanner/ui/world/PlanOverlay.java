package com.gtnhplanner.ui.world;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.MovingObjectPosition;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.util.glu.GLU;

import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.ui.PlannerSettings;
import com.gtnhplanner.ui.card.CardLayout;
import com.gtnhplanner.ui.card.PlanCardView;
import com.gtnhplanner.ui.card.RecipeCard;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * The plan overlaid on the world: every card of the plan last open in the planner that has been placed on a block is
 * drawn floating over that block as the board draws it, a stem down to the block, which is boxed in the machine's
 * colour; as big as an object two and a half blocks wide (so it shrinks with
 * distance, and turns into the board's zoomed-out card when small), nearer cards over farther ones; the plan's wires
 * between placed cards run between their blocks as connectors with arrows. Nothing shows until a card is placed. The
 * card looked at is ringed.
 */
public final class PlanOverlay {

    public static final PlanOverlay INSTANCE = new PlanOverlay();

    /** A card's width in the world, in blocks, and how far over its block it floats. */
    private static final float CARD_BLOCKS = 2.5f, LIFT = 1.2f;

    /** A placed card this frame: where its block is, and where and how big its card is on the screen. */
    private static final class Placed {

        final PlanSnapshot.Card card;
        final int x, y, z;
        double distance;
        /** The card's foot on the screen, its block's top, and its scale. */
        float sx, sy, bx, by, scale;

        Placed(final PlanSnapshot.Card card, final int[] at) {
            this.card = card;
            this.x = at[1];
            this.y = at[2];
            this.z = at[3];
        }
    }

    @Nullable
    private UUID looked;
    /** The wire under the crosshair, found on the screen last frame, and drawn lit. */
    @Nullable
    private Conn hovered;

    /**
     * A wire of the plan between two placed cards: its ends, what goes along it (from the card that makes it), and
     * where its ends fell on the screen this frame.
     */
    private record Conn(Placed from, Placed to, PlanSnapshot.Line line, @Nullable PlanSnapshot.Flow flow) {

        boolean same(@Nullable final Conn o) {
            return o != null && o.line.from()
                .equals(line.from())
                && o.line.to()
                    .equals(line.to())
                && o.line.resource()
                    .equals(line.resource());
        }
    }

    private final FloatBuffer modelview = BufferUtils.createFloatBuffer(16),
        projection = BufferUtils.createFloatBuffer(16);
    private final IntBuffer viewport = BufferUtils.createIntBuffer(16);
    private final FloatBuffer out = BufferUtils.createFloatBuffer(3);
    private double camX, camY, camZ;
    private boolean cameraKnown;

    private PlanOverlay() {}

    public static boolean on() {
        return PlannerSettings.arLens();
    }

    /** The cards placed in this dimension within range, nearest first. */
    private static List<Placed> placed(final Minecraft mc) {
        final List<Placed> list = new ArrayList<>();
        final PlanSnapshot snap = PlanSnapshot.latest();
        if (snap == null || mc.theWorld == null || mc.renderViewEntity == null) return list;
        final int dim = mc.theWorld.provider.dimensionId;
        final double range = PlannerSettings.arRange();
        final EntityLivingBase eye = mc.renderViewEntity;
        for (final PlanSnapshot.Card c : snap.cards()) {
            final Node n = snap.graph().nodes.get(c.id());
            if (n == null) continue;
            for (final int[] l : n.worldLinks) {
                if (l[0] != dim) continue;
                final Placed p = new Placed(c, l);
                final double dx = p.x + 0.5 - eye.posX, dy = p.y + 0.5 - eye.posY, dz = p.z + 0.5 - eye.posZ;
                p.distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (p.distance <= range) list.add(p);
            }
        }
        list.sort(Comparator.comparingDouble(p -> p.distance));
        return list;
    }

    /** The placed card under the crosshair, as far as the overlay reaches. */
    @SubscribeEvent
    public void onTick(final TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        looked = null;
        final Minecraft mc = Minecraft.getMinecraft();
        if (!on() || mc.theWorld == null || mc.renderViewEntity == null || mc.currentScreen != null) return;
        final MovingObjectPosition hit = mc.renderViewEntity.rayTrace(PlannerSettings.arRange(), 1f);
        if (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) return;
        for (final Placed p : placed(mc)) if (p.x == hit.blockX && p.y == hit.blockY && p.z == hit.blockZ) {
            looked = p.card.id();
            return;
        }
    }

    // region The world: the camera, the connectors, the block looked at

    @SubscribeEvent
    public void onRenderWorld(final RenderWorldLastEvent event) {
        cameraKnown = false;
        final Minecraft mc = Minecraft.getMinecraft();
        if (!on() || PlanSnapshot.latest() == null || mc.theWorld == null) return;
        modelview.clear();
        projection.clear();
        viewport.clear();
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, modelview);
        GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, projection);
        GL11.glGetInteger(GL11.GL_VIEWPORT, viewport);
        camX = WorldMarks.cameraX();
        camY = WorldMarks.cameraY();
        camZ = WorldMarks.cameraZ();
        cameraKnown = true;
        final List<Placed> placed = placed(mc);
        if (placed.isEmpty()) return;
        WorldMarks.begin();
        // Each placed card's block: the machine's ghost (unless the machine is built there), boxed in its colour (the
        // one
        // looked at in the selection colour).
        for (final Placed p : placed) {
            final boolean ringed = p.card.id()
                .equals(looked);
            if (p.card.machine() != null && !built(mc, p)) WorldMarks.ghost(p.card.machine(), p.x, p.y, p.z);
            WorldMarks.outline(p.x, p.y, p.z, ringed ? 0x22D3EE : boxColour(p.card), 0.012f, ringed ? 2.5f : 1.5f);
        }
        for (final Conn c : conns(placed)) WorldMarks.connector(
            c.from.x + 0.5,
            c.from.y + 0.5,
            c.from.z + 0.5,
            c.to.x + 0.5,
            c.to.y + 0.5,
            c.to.z + 0.5,
            c.line.color() & 0xFFFFFF,
            c.line.flowing(),
            c.same(hovered));
        WorldMarks.end();
    }

    /** Whether the card's machine is built on its block already: the block there is the machine. */
    private static boolean built(final Minecraft mc, final Placed p) {
        final net.minecraft.block.Block block = mc.theWorld.getBlock(p.x, p.y, p.z);
        if (block == null || block.isAir(mc.theWorld, p.x, p.y, p.z)) return false;
        final net.minecraft.item.ItemStack here = LinkPicker.pickBlock(
            mc,
            new MovingObjectPosition(p.x, p.y, p.z, 1, net.minecraft.util.Vec3.createVectorHelper(p.x, p.y, p.z)));
        final net.minecraft.item.ItemStack machine = p.card.machine();
        return here != null && machine != null
            && here.getItem() == machine.getItem()
            && here.getItemDamage() == machine.getItemDamage();
    }

    /** A placed card's box: its machine's colour, lightened to show on the ground. */
    private static int boxColour(final PlanSnapshot.Card c) {
        final int tint = c.tint() < 0 ? 0x8A93A6 : c.tint() & 0xFFFFFF;
        return com.gtnhplanner.ui.theme.Hyb.mix(0xFF000000 | tint, 0xFFFFFFFF, 0.35f) & 0xFFFFFF;
    }

    /** Each wire of the plan between two placed cards, with what goes along it. */
    private static List<Conn> conns(final List<Placed> placed) {
        final List<Conn> out = new ArrayList<>();
        final PlanSnapshot snap = PlanSnapshot.latest();
        if (snap == null) return out;
        final Map<UUID, Placed> byNode = new HashMap<>();
        for (final Placed p : placed) for (final UUID id : p.card.nodeIds()) byNode.put(id, p);
        for (final PlanSnapshot.Line w : snap.wires()) {
            if (w.from() == null || w.to() == null) continue;
            final Placed a = byNode.get(w.from()), b = byNode.get(w.to());
            if (a == null || b == null || a == b) continue;
            PlanSnapshot.Flow flow = null;
            for (final PlanSnapshot.Flow f : a.card.outputs()) if (f.key()
                .equals(w.resource())) flow = f;
            out.add(new Conn(a, b, w, flow));
        }
        return out;
    }

    // endregion

    // region The cards

    /** Under the minimap, which goes over them. */
    @SubscribeEvent(priority = cpw.mods.fml.common.eventhandler.EventPriority.HIGH)
    public void onOverlay(final RenderGameOverlayEvent.Post event) {
        if (event.type != RenderGameOverlayEvent.ElementType.ALL || !on() || !cameraKnown) return;
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc.currentScreen != null || mc.gameSettings.hideGUI || LinkPicker.active()) return;
        final PlanSnapshot snap = PlanSnapshot.latest();
        if (snap == null) return;
        final ScaledResolution sr = event.resolution;
        final List<Placed> all = placed(mc);
        final List<Placed> shown = new ArrayList<>();
        for (final Placed p : all) if (project(p, sr, mc)) shown.add(p);
        // Each wire's middle on the screen: what goes along it, as its icon; and the one nearest the crosshair.
        final float cx = sr.getScaledWidth() / 2f, cy = sr.getScaledHeight() / 2f;
        Conn near = null;
        float nearest = 7;
        for (final Conn c : conns(all)) {
            final float[] a = screen(c.from.x + 0.5, c.from.y + 0.5, c.from.z + 0.5, sr, mc);
            final float[] b = screen(c.to.x + 0.5, c.to.y + 0.5, c.to.z + 0.5, sr, mc);
            if (a == null || b == null) continue;
            final float d = distance(cx, cy, a, b);
            if (d < nearest) {
                nearest = d;
                near = c;
            }
            final float[] mid = { (a[0] + b[0]) / 2f, (a[1] + b[1]) / 2f };
            if (c.flow != null && Math.hypot(b[0] - a[0], b[1] - a[1]) > 40)
                wireIcon(c.flow, mid[0], mid[1], c.same(hovered));
        }
        hovered = near;
        // Far to near, so nearer cards cover farther ones as objects do.
        for (int i = shown.size() - 1; i >= 0; i--) {
            final Placed p = shown.get(i);
            final boolean ringed = p.card.id()
                .equals(looked);
            final float w = PlanCardView.width() * p.scale, h = PlanCardView.height(p.card) * p.scale;
            // The stem from the card down to its block.
            final int stem = ringed ? 0xE022D3EE : 0xD0000000 | boxColour(p.card);
            final float sw = Math.max(1, Math.round(2 * Math.min(1, p.scale * 2)));
            com.gtnhplanner.ui.theme.Hyb.rect(p.sx - sw / 2f, p.sy, sw, Math.max(1, p.by - p.sy), stem);
            com.gtnhplanner.ui.theme.Hyb.rect(p.bx - sw - 1, p.by - sw / 2f, 2 * sw + 2, sw, stem);
            GL11.glPushMatrix();
            GL11.glTranslatef(Math.round(p.sx - w / 2f), Math.round(p.sy - h), 0);
            GL11.glScalef(p.scale, p.scale, 1);
            if (p.scale <= RecipeCard.GLANCE_ZOOM) PlanCardView.glance(p.card, p.scale, ringed);
            else PlanCardView.draw(p.card, snap.rateUnit(), ringed);
            GL11.glPopMatrix();
        }
        if (near != null) wireLabel(near, snap, cx, cy);
        GL11.glColor4f(1, 1, 1, 1);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
    }

    /** A point's distance from a segment on the screen. */
    private static float distance(final float px, final float py, final float[] a, final float[] b) {
        final float dx = b[0] - a[0], dy = b[1] - a[1];
        final float len2 = dx * dx + dy * dy;
        final float t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((px - a[0]) * dx + (py - a[1]) * dy) / len2));
        return (float) Math.hypot(px - (a[0] + t * dx), py - (a[1] + t * dy));
    }

    /** What goes along a wire, at its middle: the item or fluid on a small dark disc. */
    private static void wireIcon(final PlanSnapshot.Flow f, final float x, final float y, final boolean lit) {
        final int size = 12;
        final float ix = Math.round(x - size / 2f), iy = Math.round(y - size / 2f);
        com.gtnhplanner.ui.theme.Hyb.rect(ix - 2, iy - 1, size + 4, size + 2, lit ? 0xE022D3EE : 0xD0141416);
        com.gtnhplanner.ui.theme.Hyb.rect(ix - 1, iy - 2, size + 2, size + 4, lit ? 0xE022D3EE : 0xD0141416);
        if (f.power()) RecipeCard.euIcon(ix, iy, size);
        else com.gtnhplanner.ui.theme.Hyb.icon(f.item(), f.fluid(), ix, iy, size, 0);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
    }

    /** The wire under the crosshair, as the board would name it: what, how much, from which card to which. */
    private static void wireLabel(final Conn c, final PlanSnapshot snap, final float cx, final float cy) {
        final String what = c.flow != null ? c.flow.name() : "Wire";
        final String rate = c.flow == null ? ""
            : c.flow.power() ? com.gtnhplanner.ui.theme.Fmt.power(c.flow.perSecond() / 20) + " EU/t"
                : com.gtnhplanner.ui.theme.Fmt.rate(c.flow.perSecond(), snap.rateUnit(), c.flow.fluid() != null);
        final String route = c.from.card.name() + "  →  " + c.to.card.name();
        final int w = Math.max(
            22 + com.gtnhplanner.ui.theme.Hyb.width(what) + 8 + com.gtnhplanner.ui.theme.Hyb.width(rate),
            com.gtnhplanner.ui.theme.Hyb.width(route)) + 12;
        final float x = Math.round(cx + 12), y = Math.round(cy + 10);
        com.gtnhplanner.ui.theme.Hyb.rect(x - 1, y - 1, w + 2, 32, com.gtnhplanner.ui.theme.Hyb.FRAME);
        com.gtnhplanner.ui.theme.Hyb.rect(x, y, w, 30, 0xF0141416);
        com.gtnhplanner.ui.theme.Hyb.rect(x, y, 2, 30, 0xFF000000 | c.line.color());
        if (c.flow != null) {
            if (c.flow.power()) RecipeCard.euIcon(x + 6, y + 3, 12);
            else com.gtnhplanner.ui.theme.Hyb.icon(c.flow.item(), c.flow.fluid(), x + 6, y + 3, 12, 0);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
        }
        com.gtnhplanner.ui.theme.Hyb.text(what, x + 22, y + 5, com.gtnhplanner.ui.theme.Hyb.INK);
        com.gtnhplanner.ui.theme.Hyb.textRight(rate, x + w - 6, y + 5, com.gtnhplanner.ui.theme.Hyb.PRODUCT_INK);
        com.gtnhplanner.ui.theme.Hyb.text(route, x + 6, y + 18, com.gtnhplanner.ui.theme.Hyb.MUTED);
    }

    /**
     * Where a card's foot (floating {@link #LIFT} blocks over its block) and its block's top fall on the GUI, and the
     * card's scale there: a card is {@link #CARD_BLOCKS} wide, measured by how many GUI pixels a block's height takes
     * at that spot. False when it is behind the camera.
     */
    private boolean project(final Placed p, final ScaledResolution sr, final Minecraft mc) {
        final float[] block = screen(p.x + 0.5, p.y + 1.02, p.z + 0.5, sr, mc);
        final float[] top = screen(p.x + 0.5, p.y + 1.02 + LIFT, p.z + 0.5, sr, mc);
        final float[] up = screen(p.x + 0.5, p.y + 2.02 + LIFT, p.z + 0.5, sr, mc);
        if (block == null || top == null || up == null) return false;
        p.sx = top[0];
        p.sy = top[1];
        p.bx = block[0];
        p.by = block[1];
        final float perBlock = Math.abs(top[1] - up[1]);
        p.scale = Math.min(1f, CARD_BLOCKS * perBlock / CardLayout.W);
        final float w = CardLayout.W * p.scale;
        return p.scale > 0.02f && p.sx > -w
            && p.sx < sr.getScaledWidth() + w
            && p.sy > -20
            && p.sy < sr.getScaledHeight() + PlanCardView.height(p.card) * p.scale;
    }

    @Nullable
    private float[] screen(final double x, final double y, final double z, final ScaledResolution sr,
        final Minecraft mc) {
        out.clear();
        if (!GLU.gluProject(
            (float) (x - camX),
            (float) (y - camY),
            (float) (z - camZ),
            modelview,
            projection,
            viewport,
            out)) return null;
        final float wz = out.get(2);
        if (wz <= 0 || wz >= 1) return null;
        final int f = sr.getScaleFactor();
        return new float[] { out.get(0) / f, (mc.displayHeight - out.get(1)) / f };
    }

    // endregion
}

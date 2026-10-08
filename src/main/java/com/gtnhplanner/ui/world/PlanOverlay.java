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
        // Each placed card's block, boxed in its machine's colour (the one looked at in the selection colour).
        for (final Placed p : placed) {
            final boolean ringed = p.card.id()
                .equals(looked);
            WorldMarks.outline(p.x, p.y, p.z, ringed ? 0x22D3EE : boxColour(p.card), 0.006f, ringed ? 2.5f : 2f);
        }
        connectors(placed);
        WorldMarks.end();
    }

    /** A placed card's box: its machine's colour, lightened to show on the ground. */
    private static int boxColour(final PlanSnapshot.Card c) {
        final int tint = c.tint() < 0 ? 0x8A93A6 : c.tint() & 0xFFFFFF;
        return com.gtnhplanner.ui.theme.Hyb.mix(0xFF000000 | tint, 0xFFFFFFFF, 0.35f) & 0xFFFFFF;
    }

    /**
     * Each wire of the plan between two placed cards: a connector from the block of the card that makes it to the
     * other.
     */
    private static void connectors(final List<Placed> placed) {
        final PlanSnapshot snap = PlanSnapshot.latest();
        if (snap == null) return;
        final Map<UUID, Placed> byNode = new HashMap<>();
        for (final Placed p : placed) for (final UUID id : p.card.nodeIds()) byNode.put(id, p);
        for (final PlanSnapshot.Line w : snap.wires()) {
            if (w.from() == null || w.to() == null) continue;
            final Placed a = byNode.get(w.from()), b = byNode.get(w.to());
            if (a == null || b == null || a == b) continue;
            // Along the tops of the blocks, so a card on the ground still shows its wires.
            WorldMarks.connector(
                a.x + 0.5,
                a.y + 1.08,
                a.z + 0.5,
                b.x + 0.5,
                b.y + 1.08,
                b.z + 0.5,
                w.color() & 0xFFFFFF,
                w.flowing());
        }
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
        final List<Placed> shown = new ArrayList<>();
        for (final Placed p : placed(mc)) if (project(p, sr, mc)) shown.add(p);
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
        GL11.glColor4f(1, 1, 1, 1);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
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

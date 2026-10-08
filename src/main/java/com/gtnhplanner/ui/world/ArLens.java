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
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.MovingObjectPosition;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.util.glu.GLU;

import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Plan;
import com.gtnhplanner.ui.PlannerSettings;
import com.gtnhplanner.ui.theme.Hyb;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * The AR lens: the machines around the player as they are in the world (GregTech's single blocks and multiblock
 * controllers, and any block linked to a plan card), each as a small panel in the planner's look ({@link ArPanel}):
 * what its recipe takes and makes with recent rates ({@link MachineStats}), what it is doing, its power, and its plan
 * card; far ones as a tile. The machine looked at (as far as the lens reaches) is ringed and drawn over the rest.
 * Panels fade in and out, and towards the edge of the range. Connectors run between linked machines whose cards are
 * wired in the plan last open.
 *
 * <p>
 * Panels are drawn flat on the HUD over each machine's place, projected with the world's last camera, so they look
 * as any of the game's windows do; near ones are raised clear of nearer ones.
 */
public final class ArLens {

    public static final ArLens INSTANCE = new ArLens();

    private static final boolean GREGTECH = Loader.isModLoaded("gregtech");
    /** Panels for this many of the nearest machines; tiles for the rest. */
    private static final int NEAR = 8;
    private static final int MAX_SPOTS = 96;
    /** The last blocks of the range, over which panels fade out. */
    private static final float EDGE = 8;

    /** A machine (or linked block) near the player, its plan card when linked, what it is doing, and its fade. */
    private static final class Spot {

        final int x, y, z;
        @Nullable
        WorldLinks.Hit card;
        @Nullable
        MachineStatus status;
        @Nullable
        ItemStack block;
        double distance;
        float fade;
        /** Where the point over it is on the GUI, and whether it is in front of the camera. */
        float sx, sy;
        boolean seen;
        /** Its near panel's place this frame, raised off nearer panels. */
        float px, py;
        int pw, ph;
        /** Where its panel is drawn, and the place it chose (steps up or down, to the side): kept while it can be. */
        float dx, dy;
        boolean placed;
        int step, side;
        /** When it first got a panel: the earlier, the firmer its claim to its place. */
        long order;

        Spot(final int x, final int y, final int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private List<Spot> spots = List.of();
    private long orders;
    private int ticks;
    /** The machine looked at: its panel is ringed and drawn over the rest. */
    @Nullable
    private Spot looked;
    private long lastFrame;

    private final FloatBuffer modelview = BufferUtils.createFloatBuffer(16),
        projection = BufferUtils.createFloatBuffer(16);
    private final IntBuffer viewport = BufferUtils.createIntBuffer(16);
    private final FloatBuffer out = BufferUtils.createFloatBuffer(3);
    private double camX, camY, camZ;
    private boolean cameraKnown;

    private ArLens() {}

    public static boolean on() {
        return PlannerSettings.arLens();
    }

    // region Finding machines

    @SubscribeEvent
    public void onTick(final TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        final Minecraft mc = Minecraft.getMinecraft();
        if (!on() || mc.theWorld == null || mc.thePlayer == null) {
            spots = List.of();
            looked = null;
            return;
        }
        if (ticks++ % 10 == 0) scan(mc);
        // The near ones and the one looked at are read every other tick, so their progress moves smoothly.
        if (ticks % 2 == 0) for (int i = 0; i < spots.size(); i++) {
            final Spot s = spots.get(i);
            if (i < NEAR || s == looked) s.status = read(mc, s);
        }
        findLooked(mc);
    }

    /** The machines within range, GregTech's and every plan's linked blocks, nearest first, with their cards. */
    private void scan(final Minecraft mc) {
        final double range = PlannerSettings.arRange(), r2 = range * range;
        final double px = mc.thePlayer.posX, py = mc.thePlayer.posY, pz = mc.thePlayer.posZ;
        final int dim = mc.theWorld.provider.dimensionId;
        final Map<Long, Spot> found = new HashMap<>(), before = new HashMap<>();
        for (final Spot s : spots) before.put(key(s.x, s.y, s.z), s);
        final boolean linkedOnly = PlannerSettings.arLinkedOnly();
        if (GREGTECH && !linkedOnly) for (final Object o : mc.theWorld.loadedTileEntityList) {
            if (!(o instanceof final TileEntity te)) continue;
            final double dx = te.xCoord + 0.5 - px, dy = te.yCoord + 0.5 - py, dz = te.zCoord + 0.5 - pz;
            if (dx * dx + dy * dy + dz * dz > r2 || !GtMachineStatus.isMachine(te)) continue;
            final long k = key(te.xCoord, te.yCoord, te.zCoord);
            found.put(k, before.getOrDefault(k, new Spot(te.xCoord, te.yCoord, te.zCoord)));
        }
        final Map<Long, WorldLinks.Hit> cards = new HashMap<>();
        final Plan plans = Plan.loaded();
        if (plans != null) for (final Graph g : plans.getGraphs()) for (final Node n : g.nodes.values())
            for (final int[] l : n.worldLinks) {
                if (l[0] != dim) continue;
                final double dx = l[1] + 0.5 - px, dy = l[2] + 0.5 - py, dz = l[3] + 0.5 - pz;
                if (dx * dx + dy * dy + dz * dz > r2) continue;
                final long k = key(l[1], l[2], l[3]);
                cards.putIfAbsent(k, new WorldLinks.Hit(g, n));
                found.computeIfAbsent(k, kk -> before.getOrDefault(kk, new Spot(l[1], l[2], l[3])));
            }
        final List<Spot> list = new ArrayList<>(found.values());
        for (final Spot s : list) {
            final double dx = s.x + 0.5 - px, dy = s.y + 0.5 - py, dz = s.z + 0.5 - pz;
            s.distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            s.card = cards.get(key(s.x, s.y, s.z));
        }
        list.sort(Comparator.comparingDouble(s -> s.distance));
        spots = list.size() > MAX_SPOTS ? new ArrayList<>(list.subList(0, MAX_SPOTS)) : list;
        for (final Spot s : spots) {
            if (s.status == null || !before.containsKey(key(s.x, s.y, s.z))) s.status = read(mc, s);
            if (s.status == null && s.block == null) s.block = blockItem(mc, s);
        }
    }

    @Nullable
    private static MachineStatus read(final Minecraft mc, final Spot s) {
        if (!GREGTECH) return null;
        final TileEntity te = mc.theWorld.getTileEntity(s.x, s.y, s.z);
        return te == null ? null : GtMachineStatus.read(mc.theWorld, te);
    }

    /** What a block that is not a GregTech machine is, by its pick-block item. */
    @Nullable
    private static ItemStack blockItem(final Minecraft mc, final Spot s) {
        try {
            final net.minecraft.block.Block b = mc.theWorld.getBlock(s.x, s.y, s.z);
            return b == null ? null
                : b.getPickBlock(
                    new MovingObjectPosition(
                        s.x,
                        s.y,
                        s.z,
                        1,
                        net.minecraft.util.Vec3.createVectorHelper(s.x, s.y, s.z)),
                    mc.theWorld,
                    s.x,
                    s.y,
                    s.z);
        } catch (final RuntimeException e) {
            return null;
        }
    }

    /** The machine under the crosshair, as far as the lens reaches. */
    private void findLooked(final Minecraft mc) {
        Spot now = null;
        final EntityLivingBase eye = mc.renderViewEntity;
        if (eye != null && mc.currentScreen == null) {
            final MovingObjectPosition hit = eye.rayTrace(PlannerSettings.arRange(), 1f);
            if (hit != null && hit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK)
                for (final Spot s : spots) if (s.x == hit.blockX && s.y == hit.blockY && s.z == hit.blockZ) {
                    now = s;
                    break;
                }
        }
        if (now == looked) return;
        looked = now;
        if (looked != null && looked.status == null) looked.status = read(mc, looked);
    }

    private static long key(final int x, final int y, final int z) {
        return ((long) x & 0x3FFFFFF) << 38 | ((long) z & 0x3FFFFFF) << 12 | (y & 0xFFF);
    }

    // endregion

    // region The world: the camera, the connectors, the machine looked at

    @SubscribeEvent
    public void onRenderWorld(final RenderWorldLastEvent event) {
        if (!on()) {
            cameraKnown = false;
            return;
        }
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
        WorldMarks.begin();
        if (looked != null) WorldMarks.outline(looked.x, looked.y, looked.z, 0x22D3EE, 0.004f, 2f);
        drawConnectors();
        WorldMarks.end();
    }

    /**
     * Each wire of the plan last open between two cards that both have blocks here: a connector from the block(s) of
     * the card that makes it to those of the card that uses it.
     */
    private void drawConnectors() {
        final PlanSnapshot snap = PlanSnapshot.latest();
        final Minecraft mc = Minecraft.getMinecraft();
        if (snap == null || mc.theWorld == null) return;
        final int dim = mc.theWorld.provider.dimensionId;
        final Map<UUID, double[]> centres = new HashMap<>();
        for (final PlanSnapshot.Line w : snap.wires()) {
            if (w.from() == null || w.to() == null) continue;
            final double[] a = centres.computeIfAbsent(w.from(), id -> centre(snap.graph(), id, dim));
            final double[] b = centres.computeIfAbsent(w.to(), id -> centre(snap.graph(), id, dim));
            if (a.length == 0 || b.length == 0) continue;
            WorldMarks.connector(a[0], a[1], a[2], b[0], b[1], b[2], w.color() & 0xFFFFFF, w.flowing());
        }
    }

    /** The middle of a card's blocks in this dimension; empty when it has none here. */
    private static double[] centre(final Graph graph, final UUID nodeId, final int dim) {
        final Node n = graph.nodes.get(nodeId);
        if (n == null) return new double[0];
        double x = 0, y = 0, z = 0;
        int k = 0;
        for (final int[] l : n.worldLinks) {
            if (l[0] != dim) continue;
            x += l[1] + 0.5;
            y += l[2] + 0.5;
            z += l[3] + 0.5;
            k++;
        }
        return k == 0 ? new double[0] : new double[] { x / k, y / k, z / k };
    }

    // endregion

    // region The panels

    /** Before the minimap, which goes over them. */
    @SubscribeEvent(priority = cpw.mods.fml.common.eventhandler.EventPriority.HIGH)
    public void onOverlay(final RenderGameOverlayEvent.Post event) {
        if (event.type != RenderGameOverlayEvent.ElementType.ALL || !on() || !cameraKnown) return;
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc.currentScreen != null || mc.gameSettings.hideGUI || LinkPicker.active()) return;
        final long nowMs = System.currentTimeMillis();
        final float dt = lastFrame == 0 ? 0 : Math.min(0.1f, (nowMs - lastFrame) / 1000f);
        lastFrame = nowMs;
        final ScaledResolution sr = event.resolution;
        final float range = PlannerSettings.arRange();
        final List<Spot> shown = new ArrayList<>();
        for (final Spot s : spots) {
            project(s, sr, mc);
            // Fade in when it comes, and out towards the edge of the range.
            final float target = s.seen ? Math.max(0, Math.min(1, (float) (range - s.distance) / EDGE)) : 0;
            s.fade += (target - s.fade) * Math.min(1, dt * 7);
            if (s.seen && s.fade > 0.02f) shown.add(s);
        }
        // Panels for the nearest few and the one looked at, raised clear of nearer ones; tiles for the rest, behind.
        final float scale = PlannerSettings.arScale();
        final List<Spot> near = new ArrayList<>();
        if (looked != null && shown.contains(looked)) near.add(looked);
        for (int i = 0; i < shown.size() && near.size() < NEAR; i++) if (shown.get(i) != looked) near.add(shown.get(i));
        // Panels placed before keep their claim: placed in the order they came, so a newcomer finds room around them.
        for (final Spot s : spots) if (!near.contains(s)) s.placed = false;
        for (final Spot s : near) if (!s.placed) s.order = ++orders;
        near.sort(java.util.Comparator.comparingLong(s -> s.order));
        final Map<Spot, ArPanel.View> views = new HashMap<>();
        final List<float[]> taken = new ArrayList<>();
        final int[] map = Minimap.bounds(sr);
        if (map != null) taken.add(new float[] { map[0], map[1], map[2], map[3] });
        for (final Spot s : near) {
            final ArPanel.View v = view(s, mc);
            views.put(s, v);
            s.pw = Math.round(ArPanel.width(v) * scale);
            s.ph = Math.round(ArPanel.height(v) * scale);
            s.px = Math.round(s.sx - s.pw / 2f);
            s.py = Math.round(s.sy - s.ph - 6);
            place(s, taken, sr.getScaledWidth(), sr.getScaledHeight());
            taken.add(new float[] { s.px, s.py, s.pw, s.ph });
            s.dx = s.px;
            s.dy = s.py;
            s.placed = true;
        }
        near.sort(java.util.Comparator.comparingDouble(s -> s.distance));
        for (int i = shown.size() - 1; i >= 0; i--) {
            final Spot s = shown.get(i);
            if (near.contains(s)) continue;
            GL11.glPushMatrix();
            GL11.glTranslatef(Math.round(s.sx - ArPanel.TILE / 2f), Math.round(s.sy - ArPanel.TILE - 4), 0);
            ArPanel.far(view(s, mc), s.fade);
            GL11.glPopMatrix();
        }
        // Far to near, the one looked at last, over everything.
        for (int i = near.size() - 1; i >= 0; i--) {
            final Spot s = near.get(i);
            if (s == looked) continue;
            panel(s, views.get(s), scale, false);
        }
        if (looked != null && views.containsKey(looked)) panel(looked, views.get(looked), scale, true);
        if (spots.isEmpty()) {
            final String none = GREGTECH ? "No machines within " + PlannerSettings.arRange() + " blocks"
                : "No linked machines within " + PlannerSettings.arRange() + " blocks";
            Hyb.rect(sr.getScaledWidth() / 2f - Hyb.width(none) / 2f - 4, 6, Hyb.width(none) + 8, 13, 0xC0141414);
            Hyb.textCentered(none, sr.getScaledWidth() / 2f, 9, Hyb.MUTED);
        }
        GL11.glColor4f(1, 1, 1, 1);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
    }

    /**
     * Puts a panel where it covers no nearer one (nor the minimap): over its machine if it can, else the nearest free
     * place among a few steps up and to either side, on screen. With none free it stays over its machine.
     */
    private static void place(final Spot s, final List<float[]> taken, final int screenW, final int screenH) {
        final float x0 = s.px, y0 = s.py;
        float bestX = x0, bestY = y0, best = Float.MAX_VALUE;
        int bestStep = 0, bestSide = 0;
        // Steps up from over the machine, and down from under it (past the block), to either side.
        for (int step = -3; step <= 3; step++) for (int side = -3; side <= 3; side++) {
            final float x = x0 + side * (s.pw + 4);
            final float y = step >= 0 ? y0 - step * (s.ph + 4) : s.sy + 60 + (-step - 1) * (s.ph + 4);
            final float up = step >= 0 ? step : 0.6f - step;
            if (x < 2 || x + s.pw > screenW - 2 || y < 2 || y + s.ph > screenH - 2) continue;
            float cost = Math.abs(side) * 1.1f + (step >= 0 ? up : up + 0.5f);
            // The place it had is worth keeping: it moves only when it must.
            if (s.placed && step == s.step && side == s.side) cost -= 2.5f;
            if (cost >= best || collides(x, y, s.pw, s.ph, taken)) continue;
            best = cost;
            bestX = x;
            bestY = y;
            bestStep = step;
            bestSide = side;
        }
        s.px = bestX;
        s.py = bestY;
        s.step = bestStep;
        s.side = bestSide;
    }

    private static boolean collides(final float x, final float y, final int w, final int h, final List<float[]> taken) {
        for (final float[] r : taken)
            if (x < r[0] + r[2] + 3 && x + w + 3 > r[0] && y < r[1] + r[3] + 3 && y + h + 3 > r[1]) return true;
        return false;
    }

    /** A near machine's panel, with a stem to the machine: down from over it, or up from under it. */
    private static void panel(final Spot s, final ArPanel.View v, final float scale, final boolean lookedAt) {
        final int stem = alphaOf(lookedAt ? 0xE022D3EE : 0xB0101114, s.fade);
        final float px = Math.round(s.dx), py = Math.round(s.dy);
        final float sx = Math.max(px + 4, Math.min(px + s.pw - 6, s.sx - 1));
        if (py > s.sy) Hyb.rect(sx, s.sy + 40, 2, Math.max(1, py - s.sy - 40), stem);
        else Hyb.rect(sx, py + s.ph, 2, Math.max(1, s.sy - py - s.ph), stem);
        GL11.glPushMatrix();
        GL11.glTranslatef(px, py, 0);
        GL11.glScalef(scale, scale, 1);
        ArPanel.near(v, s.fade, lookedAt);
        GL11.glPopMatrix();
    }

    /** What a spot's panel shows: the machine, what it is doing, its plan card, and what it has done lately. */
    private static ArPanel.View view(final Spot s, final Minecraft mc) {
        final MachineStatus st = s.status;
        ArPanel.Tag tag = null;
        if (s.card != null) {
            final Node n = s.card.node();
            final PlanSnapshot snap = PlanSnapshot.latest();
            final PlanSnapshot.Card planned = snap != null && snap.graph() == s.card.graph() ? snap.cardOf(n.id) : null;
            tag = new ArPanel.Tag(
                WorldView.cardName(n),
                s.card.graph()
                    .getName(),
                n.worldLinks.size(),
                planned == null ? -1 : (int) Math.ceil(planned.machines() - 1e-9));
        }
        final String name = st != null ? st.name()
            : s.block != null ? s.block.getDisplayName() : s.card != null ? WorldView.cardName(s.card.node()) : "Block";
        return new ArPanel.View(
            name,
            st != null ? st.icon() : s.block,
            st,
            tag,
            MachineStats.INSTANCE.track(mc.theWorld.provider.dimensionId, s.x, s.y, s.z));
    }

    /** Where the point just over a machine falls on the GUI; not seen when behind the camera. */
    private void project(final Spot s, final ScaledResolution sr, final Minecraft mc) {
        out.clear();
        final boolean ok = GLU.gluProject(
            (float) (s.x + 0.5 - camX),
            (float) (s.y + 1.05 - camY),
            (float) (s.z + 0.5 - camZ),
            modelview,
            projection,
            viewport,
            out);
        final float wz = out.get(2);
        s.seen = ok && wz > 0 && wz < 1;
        if (!s.seen) return;
        final int f = sr.getScaleFactor();
        s.sx = out.get(0) / f;
        s.sy = (mc.displayHeight - out.get(1)) / f;
        s.seen = s.sx > -200 && s.sx < sr.getScaledWidth() + 200 && s.sy > -20 && s.sy < sr.getScaledHeight() + 160;
    }

    private static int alphaOf(final int argb, final float fade) {
        return (Math.round((argb >>> 24) * fade) & 0xFF) << 24 | argb & 0xFFFFFF;
    }

    // endregion
}

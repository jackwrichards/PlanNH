package com.gtnhplanner.ui.world;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.settings.GameSettings;
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
import com.gtnhplanner.ui.PlannerSettings;
import com.gtnhplanner.ui.card.WorldCard;
import com.gtnhplanner.ui.theme.Hyb;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * The AR lens: a panel over every machine around the player (GregTech's single blocks and multiblock controllers, and
 * any block linked to a card), saying what it is doing now (running with its progress and what it is making, idle,
 * turned off, or why it stopped) and, when linked to a card of the plan last open in the planner, what the plan has it
 * do. The wires between linked machines are drawn between them. The machine under the crosshair can be linked to a
 * card from here ({@link LinkChooser}).
 *
 * <p>
 * Each machine is drawn as the planner draws a card ({@link WorldCard}), flat on the screen over its place (projected
 * with the world's last camera), so text and icons look as on the board: a card for the nearest few and the one looked
 * at, a glance tile (the board's zoomed-out card) for the rest.
 */
public final class ArLens {

    public static final ArLens INSTANCE = new ArLens();

    private static final boolean GREGTECH = Loader.isModLoaded("gregtech");
    /** Full panels for this many of the nearest machines (and the one looked at); chips for the rest. */
    private static final int FULL = 8;
    private static final int MAX_SPOTS = 96;

    /** A machine (or linked block) near the player, its card when linked, and what it is doing. */
    private static final class Spot {

        final int x, y, z;
        @Nullable
        Node card;
        @Nullable
        MachineStatus status;
        double distance;
        /** Where the point over it is on the GUI, and whether it is in front of the camera. */
        float sx, sy;
        boolean seen;
        /** Its panel's place and size this frame, raised off nearer panels. */
        float px, py;
        int pw, ph;

        Spot(final int x, final int y, final int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private List<Spot> spots = List.of();
    private int ticks;
    @Nullable
    private Spot looked;

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

    /** The machine under the crosshair while the lens is on: its block, for linking it to a card. */
    @Nullable
    int[] lookedAt() {
        return looked == null ? null : new int[] { looked.x, looked.y, looked.z };
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
        if (ticks % 2 == 0) for (final Spot s : spots) s.status = read(mc, s);
        findLooked(mc);
    }

    /** The machines within range: GregTech's, and the plan's linked blocks, nearest first. */
    private void scan(final Minecraft mc) {
        final double range = PlannerSettings.arRange(), r2 = range * range;
        final double px = mc.thePlayer.posX, py = mc.thePlayer.posY, pz = mc.thePlayer.posZ;
        final int dim = mc.theWorld.provider.dimensionId;
        final Map<Long, Spot> found = new HashMap<>();
        final Map<Long, Spot> before = new HashMap<>();
        for (final Spot s : spots) before.put(key(s.x, s.y, s.z), s);
        if (GREGTECH) for (final Object o : mc.theWorld.loadedTileEntityList) {
            if (!(o instanceof final TileEntity te)) continue;
            final double dx = te.xCoord + 0.5 - px, dy = te.yCoord + 0.5 - py, dz = te.zCoord + 0.5 - pz;
            if (dx * dx + dy * dy + dz * dz > r2 || !GtMachineStatus.isMachine(te)) continue;
            final long k = key(te.xCoord, te.yCoord, te.zCoord);
            found.put(k, before.getOrDefault(k, new Spot(te.xCoord, te.yCoord, te.zCoord)));
        }
        final Graph graph = planGraph();
        if (graph != null) for (final Node n : graph.nodes.values()) for (final int[] l : n.worldLinks) {
            if (l[0] != dim) continue;
            final double dx = l[1] + 0.5 - px, dy = l[2] + 0.5 - py, dz = l[3] + 0.5 - pz;
            if (dx * dx + dy * dy + dz * dz > r2) continue;
            final long k = key(l[1], l[2], l[3]);
            found.computeIfAbsent(k, kk -> before.getOrDefault(kk, new Spot(l[1], l[2], l[3])));
        }
        final List<Spot> list = new ArrayList<>(found.values());
        for (final Spot s : list) {
            final double dx = s.x + 0.5 - px, dy = s.y + 0.5 - py, dz = s.z + 0.5 - pz;
            s.distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            s.card = null;
        }
        if (graph != null) for (final Node n : graph.nodes.values()) for (final int[] l : n.worldLinks) {
            if (l[0] != dim) continue;
            final Spot s = found.get(key(l[1], l[2], l[3]));
            if (s != null && s.card == null) s.card = n;
        }
        list.sort(Comparator.comparingDouble(s -> s.distance));
        spots = list.size() > MAX_SPOTS ? new ArrayList<>(list.subList(0, MAX_SPOTS)) : list;
        for (final Spot s : spots) if (s.status == null) s.status = read(mc, s);
    }

    @Nullable
    private static MachineStatus read(final Minecraft mc, final Spot s) {
        if (!GREGTECH) return null;
        final TileEntity te = mc.theWorld.getTileEntity(s.x, s.y, s.z);
        return te == null ? null : GtMachineStatus.read(mc.theWorld, te);
    }

    private void findLooked(final Minecraft mc) {
        looked = null;
        final EntityLivingBase eye = mc.renderViewEntity;
        if (eye == null) return;
        final MovingObjectPosition hit = eye.rayTrace(PlannerSettings.arRange(), 1f);
        if (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) return;
        for (final Spot s : spots) if (s.x == hit.blockX && s.y == hit.blockY && s.z == hit.blockZ) {
            looked = s;
            return;
        }
    }

    /** The plan the lens tells about: the one last open in the planner. */
    @Nullable
    private static Graph planGraph() {
        final PlanSnapshot snap = PlanSnapshot.latest();
        return snap == null ? null : snap.graph();
    }

    private static long key(final int x, final int y, final int z) {
        return ((long) x & 0x3FFFFFF) << 38 | ((long) z & 0x3FFFFFF) << 12 | (y & 0xFFF);
    }

    // endregion

    // region The world: the camera, and the wires between linked machines

    @SubscribeEvent
    public void onRenderWorld(final RenderWorldLastEvent event) {
        if (!on() || spots.isEmpty() && planGraph() == null) {
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
        drawWires();
    }

    /** Each wire of the plan between two cards that both have blocks here, from one's blocks to the other's. */
    private void drawWires() {
        final PlanSnapshot snap = PlanSnapshot.latest();
        final Minecraft mc = Minecraft.getMinecraft();
        if (snap == null || mc.theWorld == null) return;
        final int dim = mc.theWorld.provider.dimensionId;
        final Map<java.util.UUID, double[]> centres = new HashMap<>();
        boolean any = false;
        for (final PlanSnapshot.Line w : snap.wires()) {
            if (w.from() == null || w.to() == null) continue;
            final double[] a = centres.computeIfAbsent(w.from(), id -> centre(snap.graph(), id, dim));
            final double[] b = centres.computeIfAbsent(w.to(), id -> centre(snap.graph(), id, dim));
            if (a.length == 0 || b.length == 0) continue;
            if (!any) WorldMarks.begin();
            any = true;
            WorldMarks.line(a[0], a[1], a[2], b[0], b[1], b[2], w.color() & 0xFFFFFF, w.flowing() ? 4f : 3f);
        }
        if (any) WorldMarks.end();
    }

    /** The middle of a card's blocks in this dimension, a little above them; empty when it has none here. */
    private static double[] centre(final Graph graph, final java.util.UUID nodeId, final int dim) {
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
        return k == 0 ? new double[0] : new double[] { x / k, y / k + 0.2, z / k };
    }

    // endregion

    // region The cards

    /** World cards against the board's size (a setting): at half, a font pixel to a screen pixel at GUI scale 2. */
    private static float scale() {
        return PlannerSettings.arScale();
    }

    /** A far machine's glance tile, GUI pixels. */
    private static final int GLANCE = 30;

    /** Before the minimap, which goes over the cards. */
    @SubscribeEvent(priority = cpw.mods.fml.common.eventhandler.EventPriority.HIGH)
    public void onOverlay(final RenderGameOverlayEvent.Post event) {
        if (event.type != RenderGameOverlayEvent.ElementType.ALL || !on() || !cameraKnown) return;
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc.currentScreen != null || mc.gameSettings.hideGUI || LinkPicker.active()) return;
        final ScaledResolution sr = event.resolution;
        final List<Spot> shown = new ArrayList<>();
        for (final Spot s : spots) {
            project(s, sr, mc);
            if (s.seen) shown.add(s);
        }
        // Cards for the nearest few and the one looked at; glance tiles for the rest.
        final List<Spot> full = new ArrayList<>();
        for (int i = 0; i < shown.size() && full.size() < FULL; i++) full.add(shown.get(i));
        if (looked != null && looked.seen && !full.contains(looked)) full.add(looked);
        // Each card over its machine, raised clear of the nearer ones already placed (its stem grows to reach).
        final List<float[]> taken = new ArrayList<>();
        final List<WorldCard.Data> data = new ArrayList<>();
        for (final Spot s : full) {
            final WorldCard.Data d = data(s);
            data.add(d);
            s.pw = Math.round(WorldCard.W * scale());
            s.ph = Math.round(WorldCard.height(d) * scale()) + (s == looked && hint() ? 12 : 0);
            s.px = Math.round(s.sx - s.pw / 2f);
            s.py = Math.round(s.sy - s.ph - 6);
            for (int tries = 0; tries < 12; tries++) {
                float[] hit = null;
                for (final float[] r : taken) if (s.px < r[0] + r[2] + 2 && s.px + s.pw + 2 > r[0]
                    && s.py < r[1] + r[3] + 2
                    && s.py + s.ph + 2 > r[1]) hit = r;
                if (hit == null) break;
                s.py = hit[1] - s.ph - 3;
            }
            taken.add(new float[] { s.px, s.py, s.pw, s.ph });
        }
        for (int i = shown.size() - 1; i >= 0; i--) {
            final Spot s = shown.get(i);
            if (!full.contains(s)) glance(s);
        }
        for (int i = full.size() - 1; i >= 0; i--) card(full.get(i), data.get(i), full.get(i) == looked);
        if (shown.isEmpty()) {
            final String none = GREGTECH ? "No machines within " + PlannerSettings.arRange() + " blocks"
                : "No linked machines within " + PlannerSettings.arRange() + " blocks";
            Hyb.rect(sr.getScaledWidth() / 2f - Hyb.width(none) / 2f - 4, 6, Hyb.width(none) + 8, 13, 0xC0141414);
            Hyb.textCentered(none, sr.getScaledWidth() / 2f, 9, Hyb.MUTED);
        }
        GL11.glColor4f(1, 1, 1, 1);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
    }

    /** Where the point just over a machine falls on the GUI; not seen when behind the camera or off screen. */
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
        final float half = WorldCard.W * scale();
        s.seen = s.sx > -half && s.sx < sr.getScaledWidth() + half && s.sy > -20 && s.sy < sr.getScaledHeight() + 120;
    }

    private static void glance(final Spot s) {
        final MachineStatus st = s.status;
        GL11.glPushMatrix();
        GL11.glTranslatef(Math.round(s.sx - GLANCE / 2f), Math.round(s.sy - GLANCE - 3), 0);
        WorldCard.glance(
            name(s),
            icon(s),
            st != null ? st.state()
                .ink() : Hyb.MUTED,
            st != null ? st.progress() : -1,
            s.card != null,
            GLANCE);
        GL11.glPopMatrix();
    }

    /** A near machine's card, its stem down to the machine, and how to link it when it is the one looked at. */
    private static void card(final Spot s, final WorldCard.Data d, final boolean lookedAt) {
        final float cardH = WorldCard.height(d) * scale();
        Hyb.rect(s.sx - 0.5f, s.py + cardH, 1, Math.max(1, s.sy - s.py - cardH), lookedAt ? Hyb.SELECTION : 0xC03C3E45);
        GL11.glPushMatrix();
        GL11.glTranslatef(s.px, s.py, 0);
        GL11.glScalef(scale(), scale(), 1);
        WorldCard.draw(d, lookedAt);
        GL11.glPopMatrix();
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        if (lookedAt && hint()) {
            final String key = GameSettings.getKeyDisplayString(PlannerKeys.LINK.getKeyCode());
            final String text = key + (s.card != null ? ": change its cards" : ": link to a card");
            final float tw = Hyb.width(text) + 8, tx = Math.round(s.px + (s.pw - tw) / 2f), ty = s.py + cardH + 2;
            Hyb.rect(tx, ty, tw, 11, 0xE0101114);
            Hyb.text(text, tx + 4, ty + 2, Hyb.SELECTION);
        }
    }

    private static boolean hint() {
        return PlannerKeys.LINK.getKeyCode() != 0 && planGraph() != null;
    }

    @Nullable
    private static PlanSnapshot.Card planned(final Spot s) {
        final PlanSnapshot snap = PlanSnapshot.latest();
        return s.card != null && snap != null ? snap.cardOf(s.card.id) : null;
    }

    private static String name(final Spot s) {
        return s.status != null ? s.status.name() : s.card != null ? WorldView.cardName(s.card) : "Linked block";
    }

    @Nullable
    private static ItemStack icon(final Spot s) {
        if (s.status != null) return s.status.icon();
        final PlanSnapshot.Card planned = planned(s);
        return planned != null ? planned.machine() : null;
    }

    /**
     * What a machine's card shows: its state and the recipe it runs (or last ran); when it is linked, the card's
     * count. A linked block the lens cannot read shows the card's ports from the plan.
     */
    private static WorldCard.Data data(final Spot s) {
        final MachineStatus st = s.status;
        final PlanSnapshot.Card planned = planned(s);
        final List<WorldCard.Port> ins = new ArrayList<>(), outs = new ArrayList<>();
        if (st != null && !(st.inputs()
            .isEmpty()
            && st.outputs()
                .isEmpty())) {
            final boolean live = st.state() == MachineStatus.State.RUNNING && st.progress() >= 0;
            for (final MachineStatus.Flow f : st.inputs())
                ins.add(new WorldCard.Port(f.name(), f.item(), f.fluid(), f.perSecond(), live));
            for (final MachineStatus.Flow f : st.outputs())
                outs.add(new WorldCard.Port(f.name(), f.item(), f.fluid(), f.perSecond(), live));
        } else if (planned != null) {
            for (final PlanSnapshot.Flow f : planned.inputs())
                if (!f.power()) ins.add(new WorldCard.Port(f.name(), f.item(), f.fluid(), 0, false));
            for (final PlanSnapshot.Flow f : planned.outputs())
                if (!f.power()) outs.add(new WorldCard.Port(f.name(), f.item(), f.fluid(), 0, false));
        }
        final MachineStatus.State state = st != null ? st.state() : MachineStatus.State.IDLE;
        return new WorldCard.Data(
            name(s),
            icon(s),
            st != null ? st.tier() : "",
            st != null && st.multiblock() ? st.amps() : 0,
            st == null ? "Linked" : state.word,
            state.ink(),
            st != null ? st.detail() : "",
            st != null ? st.progress() : -1,
            st != null ? st.ticksLeft() : -1,
            st != null ? st.euPerTick() : 0,
            st != null ? st.maxEuPerTick() : 0,
            ins,
            outs,
            s.card == null ? Double.NaN : planned != null ? planned.machines() : 0,
            planned != null && planned.pinned());
    }

    // endregion
}

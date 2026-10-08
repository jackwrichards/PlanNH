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
import com.gtnhplanner.ui.theme.Hyb;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * The plan overlaid on the world: every card of the plan last open in the planner that has been placed on a spot is
 * drawn floating over it as the board draws it, a stem down to the spot, where a ghost of its machine stands; the same
 * size within ten blocks and shrinking with distance beyond. Cards move out of each other's way, their stems pointing
 * back to their spots. The plan's wires between placed
 * cards are
 * drawn flat on the screen as the board draws them, from block to block, with arrowheads moving towards the card fed
 * and a tag naming what goes along and how much. Nothing shows until a card is placed. What the crosshair is on is
 * highlighted.
 */
public final class PlanOverlay {

    public static final PlanOverlay INSTANCE = new PlanOverlay();

    /**
     * Cards and wire tags are drawn at {@link #SIZE} of the board's size within this many blocks of the eye, and shrink
     * with distance beyond it as objects do. How far a card floats over its spot, in blocks.
     */
    private static final float FULL_SIZE_WITHIN = 10f, SIZE = 0.6f, LIFT = 0.35f;

    /** The room kept between cards (and wire tags) on the screen, in GUI pixels. */
    private static final float CARD_GAP = 3;

    /** How far the crosshair reaches to a wire's line, in GUI pixels. */
    private static final float WIRE_REACH = 24;

    /** A placed card this frame: where its block is, and where and how big its card is on the screen. */
    private static final class Placed {

        final PlanSnapshot.Card card;
        final int x, y, z;
        double distance;
        /**
         * Where the card's foot would be on the screen, straight over its spot; its spot's top; its scale and size
         * there; and where it is drawn, its top left, once moved out of the other cards' way.
         */
        float sx, sy, bx, by, scale, w, h, left, top;

        Placed(final PlanSnapshot.Card card, final int[] at) {
            this.card = card;
            this.x = at[1];
            this.y = at[2];
            this.z = at[3];
        }

        boolean covers(final float px, final float py) {
            return px >= left && px < left + w && py >= top && py < top + h;
        }
    }

    @Nullable
    private UUID looked;
    /** The card the crosshair is on, its spot or the card itself, found on the screen last frame; drawn highlighted. */
    @Nullable
    private UUID lit;
    /** The wire under the crosshair, found on the screen last frame, and drawn lit. */
    @Nullable
    private Conn hovered;

    /**
     * A wire of the plan between two placed cards: its ends, what goes along it (from the card that makes it), and
     * where its ends fell on the screen this frame.
     */
    private record Conn(Placed from, Placed to, PlanSnapshot.Line line, @Nullable PlanSnapshot.Flow flow) {

        String what() {
            return flow != null ? flow.name() : "Wire";
        }

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
        final List<Placed> all = new ArrayList<>();
        final PlanSnapshot snap = PlanSnapshot.latest();
        if (snap == null || mc.theWorld == null || mc.renderViewEntity == null) return all;
        final int dim = mc.theWorld.provider.dimensionId;
        for (final PlanSnapshot.Card c : snap.cards()) {
            final Node n = snap.graph().nodes.get(c.id());
            if (n == null) continue;
            for (final int[] l : n.worldLinks) if (l[0] == dim) all.add(new Placed(c, l));
        }
        final double range = PlannerSettings.arRange();
        final EntityLivingBase eye = mc.renderViewEntity;
        final List<Placed> list = new ArrayList<>();
        for (final Placed p : all) {
            final double dx = p.x + 0.5 - eye.posX, dy = p.y + 0.5 - eye.posY, dz = p.z + 0.5 - eye.posZ;
            p.distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (p.distance <= range) list.add(p);
        }
        list.sort(Comparator.comparingDouble(p -> p.distance));
        return list;
    }

    /** The placed spot under the crosshair, as far as the overlay reaches. */
    @SubscribeEvent
    public void onTick(final TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        looked = null;
        final Minecraft mc = Minecraft.getMinecraft();
        if (!on() || mc.theWorld == null || mc.renderViewEntity == null || mc.currentScreen != null) return;
        // The spot the crosshair meets first, through blocks: spots are often empty, and the plan shows over the world.
        final net.minecraft.util.Vec3 from = mc.renderViewEntity.getPosition(1f), dir = mc.renderViewEntity.getLook(1f);
        double first = PlannerSettings.arRange();
        for (final Placed p : placed(mc)) {
            final double t = WorldLinks.enter(from, dir, p.x, p.y, p.z);
            if (t >= 0 && t < first) {
                first = t;
                looked = p.card.id();
            }
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
        // Each placed card's spot: the machine's ghost (unless the machine is built there); the one looked at outlined.
        for (final Placed p : placed) {
            if (p.card.machine() != null && !built(mc, p)) WorldMarks.ghost(p.card.machine(), p.x, p.y, p.z);
            if (p.card.id()
                .equals(lit)) WorldMarks.outline(p.x, p.y, p.z, Hyb.LIT & 0xFFFFFF, 0.012f, 1.5f, 0.6f);
        }
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
        if (event.type != RenderGameOverlayEvent.ElementType.ALL) return;
        Minimap.INSTANCE.point(null, null);
        if (!on() || !cameraKnown) return;
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc.currentScreen != null || mc.gameSettings.hideGUI || LinkPicker.active()) return;
        final PlanSnapshot snap = PlanSnapshot.latest();
        if (snap == null) return;
        final ScaledResolution sr = event.resolution;
        final List<Placed> all = placed(mc);
        final List<Placed> shown = new ArrayList<>();
        for (final Placed p : all) if (project(p, sr, mc)) shown.add(p);
        // The plan's wires, flat on the screen as the board draws them, and the one the crosshair is on.
        final float cx = sr.getScaledWidth() / 2f, cy = sr.getScaledHeight() / 2f;
        final List<Run> runs = runs(conns(all), sr, mc, snap);
        layout(shown, runs);
        // What the crosshair is on: a wire's tag, else the nearest card it is over, else a spot (its ghost), else the
        // wire
        // whose line is within reach.
        Run near = null;
        for (final Run r : runs) if (r.covers(cx, cy)) near = r;
        Placed over = null;
        if (near == null) for (final Placed p : shown) if (p.covers(cx, cy)) {
            over = p;
            break;
        }
        final UUID card = near != null ? null : over != null ? over.card.id() : looked;
        if (near == null && card == null) {
            float nearest = WIRE_REACH;
            for (final Run r : runs) {
                final float d = distance(cx, cy, r.ax, r.ay, r.bx, r.by);
                if (d < nearest) {
                    nearest = d;
                    near = r;
                }
            }
        }
        hovered = near == null ? null : near.conn;
        lit = card;
        Minimap.INSTANCE.point(card, hovered == null ? null : hovered.line);
        Hyb.beginBatch();
        for (final Run r : runs) wire(r, r.conn.same(hovered));
        Hyb.endBatch();
        // Far to near, so nearer cards cover farther ones as objects do; the one the crosshair is on over them all.
        Placed top = null;
        for (int i = shown.size() - 1; i >= 0; i--) {
            final Placed p = shown.get(i);
            if (p.card.id()
                .equals(lit)) top = p;
            else card(p, snap, false);
        }
        if (top != null) card(top, snap, true);
        // Over the cards: what each wire carries.
        for (final Run r : runs) if (!r.conn.same(hovered)) tag(r, false);
        if (near != null) tag(near, true);
        GL11.glColor4f(1, 1, 1, 1);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
    }

    // region The wires

    /** Height of a wire's tag, unscaled: a port tile as the card's. */
    private static final int TAG_H = CardLayout.ROW;

    /**
     * A wire on the screen this frame: its ends (where it leaves one block and meets the other, cut short at the
     * camera), how big its tag is drawn, and the tag's text and box.
     */
    private static final class Run {

        final Conn conn;
        final float ax, ay, bx, by, scale;
        final String name, rate, route;
        float tx, ty, tw, th;

        Run(final Conn conn, final float[] a, final float[] b, final float scale, final String name,
            final String rate) {
            this.conn = conn;
            this.ax = a[0];
            this.ay = a[1];
            this.bx = b[0];
            this.by = b[1];
            this.scale = scale;
            this.name = name;
            this.rate = rate;
            this.route = conn.from.card.name() + "  →  " + conn.to.card.name();
        }

        boolean covers(final float x, final float y) {
            return x >= tx && x < tx + tw && y >= ty && y < ty + th;
        }
    }

    /** Each wire's run on the screen, its tag placed at its middle (stacked where two cards share several wires). */
    private List<Run> runs(final List<Conn> conns, final ScaledResolution sr, final Minecraft mc,
        final PlanSnapshot snap) {
        final List<Run> out = new ArrayList<>();
        final Map<String, List<Run>> between = new HashMap<>();
        for (final Conn c : conns) {
            // From where the wire leaves the one block to where it meets the other.
            final double dx = c.to.x - c.from.x, dy = c.to.y - c.from.y, dz = c.to.z - c.from.z;
            final double edge = 0.5 / Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz)));
            if (edge >= 0.5) continue;
            final double[] a = { c.from.x + 0.5 + dx * edge, c.from.y + 0.5 + dy * edge, c.from.z + 0.5 + dz * edge };
            final double[] b = { c.to.x + 0.5 - dx * edge, c.to.y + 0.5 - dy * edge, c.to.z + 0.5 - dz * edge };
            if (!inFront(a, b)) continue;
            final float[] sa = screen(a[0], a[1], a[2], sr, mc), sb = screen(b[0], b[1], b[2], sr, mc);
            final double mx = (a[0] + b[0]) / 2, my = (a[1] + b[1]) / 2, mz = (a[2] + b[2]) / 2;
            final float[] mid = screen(mx, my, mz, sr, mc);
            if (sa == null || sb == null || mid == null) continue;
            // Sized as the cards are: full size near, shrinking with distance.
            final double far = Math
                .sqrt((mx - camX) * (mx - camX) + (my - camY) * (my - camY) + (mz - camZ) * (mz - camZ));
            final float scale = (float) (SIZE * Math.min(1, FULL_SIZE_WITHIN / far));
            final PlanSnapshot.Flow f = c.flow;
            final String rate = f == null ? ""
                : f.power() ? com.gtnhplanner.ui.theme.Fmt.power(f.perSecond() / 20) + " EU/t"
                    : com.gtnhplanner.ui.theme.Fmt.rate(f.perSecond(), snap.rateUnit(), f.fluid() != null);
            final Run r = new Run(c, sa, sb, scale, Hyb.fit(c.what(), 140), rate);
            r.tw = (CardLayout.TEXT_X + Math.max(Hyb.width(r.name), Hyb.width(rate)) + 6) * scale;
            r.th = TAG_H * scale;
            r.tx = mid[0] - r.tw / 2;
            r.ty = mid[1] - r.th / 2;
            final UUID lo = c.from.card.id()
                .compareTo(c.to.card.id()) < 0 ? c.from.card.id() : c.to.card.id();
            final UUID hi = lo == c.from.card.id() ? c.to.card.id() : c.from.card.id();
            between.computeIfAbsent(lo + "/" + hi, key -> new ArrayList<>())
                .add(r);
            out.add(r);
        }
        // Several wires between the same two cards share a middle: their tags stack, centred on it.
        for (final List<Run> group : between.values()) {
            if (group.size() < 2) continue;
            float total = 0;
            for (final Run r : group) total += r.th + 2;
            float y = group.get(0).ty + group.get(0).th / 2 - total / 2;
            for (final Run r : group) {
                r.ty = y;
                y += r.th + 2;
            }
        }
        return out;
    }

    /** Cuts a segment short where it passes behind the camera; false when all of it is behind. */
    private boolean inFront(final double[] a, final double[] b) {
        final float near = 0.1f;
        final float za = eyeZ(a), zb = eyeZ(b);
        if (za > -near && zb > -near) return false;
        if (za > -near || zb > -near) {
            final double t = (-near - za) / (zb - za);
            final double[] cut = { a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t };
            System.arraycopy(cut, 0, za > -near ? a : b, 0, 3);
        }
        return true;
    }

    /** How far in front of the camera a point is, negative in front (the camera looks down its -z). */
    private float eyeZ(final double[] p) {
        final float x = (float) (p[0] - camX), y = (float) (p[1] - camY), z = (float) (p[2] - camZ);
        return modelview.get(2) * x + modelview.get(6) * y + modelview.get(10) * z + modelview.get(14);
    }

    // region Making room

    /**
     * Each card's offset from its place straight over its spot, in its own widths (right) and heights (up), kept from
     * frame to frame: where it is going {x, y} and where it is drawn now {x, y}.
     */
    private final Map<UUID, float[]> offsets = new HashMap<>();
    private long laidOut;

    /**
     * Moves cards out of each other's way, as map labels are: nearest first, each keeps where it is while that is clear
     * of the cards already placed and the wires' tags, and otherwise takes the clear place nearest its own (sideways or
     * up, never below its spot), its stem pointing back. A card that moved goes home again once that is clear, and
     * moves quickly rather than gliding.
     */
    private void layout(final List<Placed> shown, final List<Run> runs) {
        final long now = System.currentTimeMillis();
        final float ease = laidOut == 0 ? 1 : (float) (1 - Math.exp(-(now - laidOut) / 50.0));
        laidOut = now;
        final List<float[]> taken = new ArrayList<>();
        for (final Run r : runs) taken.add(new float[] { r.tx, r.ty, r.tw, r.th });
        for (final Placed p : shown) {
            final float[] o = offsets.computeIfAbsent(p.card.id(), id -> new float[4]);
            float bestX = o[0], bestY = o[1];
            final boolean clear = clear(p, bestX, bestY, taken);
            final float now0 = cost(p, bestX, bestY);
            if (!clear || now0 > 0) {
                // A clear place, nearest home first; where it is now is clear too, only one much nearer home.
                float best = clear ? now0 * 0.6f : Float.MAX_VALUE;
                for (float dy = 0; dy <= 2.01f; dy += 0.25f) for (float dx = -1.5f; dx <= 1.51f; dx += 0.125f) {
                    final float c = cost(p, dx, dy);
                    if (c < best && clear(p, dx, dy, taken)) {
                        best = c;
                        bestX = dx;
                        bestY = dy;
                    }
                }
            }
            o[0] = bestX;
            o[1] = bestY;
            o[2] += (o[0] - o[2]) * ease;
            o[3] += (o[1] - o[3]) * ease;
            p.left = p.sx + o[2] * p.w - p.w / 2;
            p.top = p.sy - o[3] * p.h - p.h;
            taken.add(new float[] { p.sx + o[0] * p.w - p.w / 2, p.sy - o[1] * p.h - p.h, p.w, p.h });
        }
    }

    /** How far from home an offset takes a card, on the screen; going up costs a little more than going aside. */
    private static float cost(final Placed p, final float dx, final float dy) {
        return Math.abs(dx) * p.w + 1.15f * dy * p.h;
    }

    /** Whether a card at an offset keeps clear of everything placed. */
    private static boolean clear(final Placed p, final float dx, final float dy, final List<float[]> taken) {
        final float x = p.sx + dx * p.w - p.w / 2 - CARD_GAP, y = p.sy - dy * p.h - p.h - CARD_GAP;
        final float w = p.w + 2 * CARD_GAP, h = p.h + 2 * CARD_GAP;
        for (final float[] t : taken)
            if (x < t[0] + t[2] && t[0] < x + w && y < t[1] + t[3] && t[1] < y + h) return false;
        return true;
    }

    // endregion

    /**
     * A card where the layout put it, with its stem from its bottom edge down to its spot (a pin when the card moved
     * aside).
     */
    private static void card(final Placed p, final PlanSnapshot snap, final boolean ringed) {
        final int stem = ringed ? 0x90000000 | Hyb.LIT & 0xFFFFFF : 0x70A4A8B0;
        final float footX = Math.max(p.left + 6, Math.min(p.left + p.w - 6, p.bx)), footY = p.top + p.h;
        if (p.by - footY > 1 || Math.abs(footX - p.bx) > 1) band(footX, footY, p.bx, p.by, 3, stem);
        Hyb.rect(p.bx - 2, p.by - 2, 4, 4, stem);
        GL11.glPushMatrix();
        GL11.glTranslatef(Math.round(p.left), Math.round(p.top), 0);
        GL11.glScalef(p.scale, p.scale, 1);
        PlanCardView.draw(p.card, snap.rateUnit(), ringed);
        GL11.glPopMatrix();
    }

    /** A point's distance from a segment on the screen. */
    private static float distance(final float px, final float py, final float ax, final float ay, final float bx,
        final float by) {
        final float dx = bx - ax, dy = by - ay;
        final float len2 = dx * dx + dy * dy;
        final float t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / len2));
        return (float) Math.hypot(px - (ax + t * dx), py - (ay + t * dy));
    }

    /**
     * A wire as the board draws one, in the resource's colour on a dark edge, with arrowheads sliding along towards the
     * card it feeds; faint where nothing flows yet; brighter and thicker when the crosshair is on it.
     */
    private static void wire(final Run r, final boolean lit) {
        final float dx = r.bx - r.ax, dy = r.by - r.ay;
        final float len = (float) Math.hypot(dx, dy);
        if (len < 4) return;
        final float ux = dx / len, uy = dy / len;
        final int base = 0xFF000000 | r.conn.line.color() & 0xFFFFFF;
        final int colour = lit ? Hyb.mix(base, Hyb.LIT, 0.35f) : base;
        final boolean flowing = r.conn.line.flowing();
        final float w = lit ? Math.max(2f, 4.5f * r.scale) : Math.max(1.2f, 3f * r.scale);
        band(r.ax, r.ay, r.bx, r.by, w + 2, flowing || lit ? 0xB0000000 : 0x70000000);
        band(r.ax, r.ay, r.bx, r.by, w, flowing || lit ? colour : colour & 0x00FFFFFF | 0x99000000);
        // Arrowheads every so often, moving at a steady pace.
        final float gap = 40 + 30 * r.scale, head = 4.5f * w, half = 2.4f * w;
        final float offset = (System.currentTimeMillis() % 600L) / 600f * gap;
        final int tip = Hyb.mix(colour, 0xFFFFFFFF, 0.45f);
        for (float at = offset; at < len; at += gap) {
            if (at < head || at > len - head) continue;
            final float px = r.ax + ux * at, py = r.ay + uy * at;
            final float fx = px + ux * head * 0.6f, fy = py + uy * head * 0.6f;
            final float bx = px - ux * head * 0.4f, by = py - uy * head * 0.4f;
            Hyb.triangle(
                fx + ux,
                fy + uy,
                bx - uy * (half + 1),
                by + ux * (half + 1),
                bx + uy * (half + 1),
                by - ux * (half + 1),
                0xB0000000);
            Hyb.triangle(fx, fy, bx - uy * half, by + ux * half, bx + uy * half, by - ux * half, tip);
        }
    }

    /** A straight band {@code w} wide from one point to another. */
    private static void band(final float ax, final float ay, final float bx, final float by, final float w,
        final int colour) {
        final float len = (float) Math.hypot(bx - ax, by - ay), h = w / 2;
        final float nx = -(by - ay) / len * h, ny = (bx - ax) / len * h;
        Hyb.triangle(ax + nx, ay + ny, bx + nx, by + ny, bx - nx, by - ny, colour);
        Hyb.triangle(ax + nx, ay + ny, bx - nx, by - ny, ax - nx, ay - ny, colour);
    }

    /**
     * What a wire carries, at its middle, as a port tile of the card (and as big as the cards' are there): the item or
     * fluid, its name over its rate. The wire the crosshair is on is highlighted and also names the cards it runs
     * between.
     */
    private static void tag(final Run r, final boolean lit) {
        GL11.glPushMatrix();
        GL11.glTranslatef(r.tx, r.ty, 0);
        GL11.glScalef(r.scale, r.scale, 1);
        final int w = Math.round(r.tw / r.scale), h = TAG_H;
        if (lit) Hyb.ring(-2, -2, w + 4, h + 4, 2, 0xC0000000 | Hyb.LIT & 0xFFFFFF);
        Hyb.tile(0, 0, w, h);
        final PlanSnapshot.Flow f = r.conn.flow;
        if (f != null) {
            if (f.power()) RecipeCard.euIcon(CardLayout.ICON_X, CardLayout.ICON_Y, CardLayout.ICON);
            else Hyb.icon(f.item(), f.fluid(), CardLayout.ICON_X, CardLayout.ICON_Y, CardLayout.ICON, 0);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
        }
        final int top = (CardLayout.ROW - 17) / 2;
        Hyb.text(r.name, CardLayout.TEXT_X, top, Hyb.INK);
        Hyb.text(r.rate, CardLayout.TEXT_X, top + 9, Hyb.MUTED);
        GL11.glPopMatrix();
        // The two cards, at the screen's own scale so they read however far the wire is.
        if (lit) {
            final int rw = Hyb.width(r.route) + 8;
            final float rx = Math.round(r.tx + (r.tw - rw) / 2f), ry = Math.round(r.ty + r.th + 4);
            Hyb.rect(rx, ry, rw, 11, 0xE0101114);
            Hyb.text(r.route, rx + 4, ry + 2, Hyb.MUTED);
        }
    }

    // endregion

    /**
     * Where a card's foot (floating {@link #LIFT} blocks over its block) and its block's top fall on the GUI, and the
     * card's scale: {@link #SIZE} within {@link #FULL_SIZE_WITHIN} blocks, shrinking with distance beyond. False when
     * it is
     * behind the camera or off the screen.
     */
    private boolean project(final Placed p, final ScaledResolution sr, final Minecraft mc) {
        final float[] block = screen(p.x + 0.5, p.y + 1.02, p.z + 0.5, sr, mc);
        final float[] top = screen(p.x + 0.5, p.y + 1.02 + LIFT, p.z + 0.5, sr, mc);
        if (block == null || top == null) return false;
        p.sx = top[0];
        p.sy = top[1];
        p.bx = block[0];
        p.by = block[1];
        p.scale = (float) (SIZE * Math.min(1, FULL_SIZE_WITHIN / Math.max(0.5, p.distance)));
        p.w = PlanCardView.width() * p.scale;
        p.h = PlanCardView.height(p.card) * p.scale;
        return p.scale > 0.02f && p.sx > -p.w
            && p.sx < sr.getScaledWidth() + p.w
            && p.sy > -20
            && p.sy < sr.getScaledHeight() + p.h;
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

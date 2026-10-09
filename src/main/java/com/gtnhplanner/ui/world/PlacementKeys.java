package com.gtnhplanner.ui.world;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.EntityLivingBase;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;

import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.ui.gt.StructureGhosts;
import com.gtnhplanner.ui.theme.Hyb;

import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.InputEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Adjusting a placed machine from the world. Hold the adjust key (Ctrl) with the crosshair on a placed machine and it
 * is
 * in hand while the key is held: the arrows move it a block (up is away from you, as you look), Shift with up and down
 * raises and lowers it, R turns it about its middle (Shift: the other way), [ and ] size a structure that comes in
 * sizes, G picks it up to put down elsewhere, and Delete takes it out of the world. Each is one undo step in its plan.
 * While the crosshair is on a placed machine, or one is in hand, the keys show under the minimap.
 */
public final class PlacementKeys {

    public static final PlacementKeys INSTANCE = new PlacementKeys();

    static final KeyBinding ADJUST = new KeyBinding(
        "key.gtnhplanner.adjust",
        Keyboard.KEY_LCONTROL,
        PlannerKeys.CATEGORY);

    /** The placement in hand while the adjust key is held: its plan, card and where it is now; null when none. */
    @Nullable
    private Graph graph;
    @Nullable
    private Node node;
    @Nullable
    private int[] link;

    private PlacementKeys() {}

    public static void register() {
        ClientRegistry.registerKeyBinding(ADJUST);
    }

    static boolean shift() {
        return Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT);
    }

    /**
     * Whether the adjust key is held in the world (not while placing): the keys it takes are not the minimap's then.
     */
    static boolean adjusting() {
        return ADJUST.getKeyCode() != Keyboard.KEY_NONE && ADJUST.getIsKeyPressed()
            && Minecraft.getMinecraft().currentScreen == null
            && !LinkPicker.active();
    }

    /** Takes the placement under the crosshair in hand as the key goes down, and lets go when it comes up. */
    @SubscribeEvent
    public void onTick(final TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!adjusting()) {
            link = null;
            return;
        }
        grab();
    }

    /** Takes the placement under the crosshair in hand, unless one still is. */
    private void grab() {
        if (link != null && node != null && WorldLinks.indexOf(node, link[0], link[1], link[2], link[3]) >= 0) return;
        link = null;
        final WorldLinks.Spot spot = WorldView.looked();
        if (spot == null) return;
        final Node n = spot.hit()
            .node();
        final int i = WorldLinks
            .indexOf(n, Minecraft.getMinecraft().theWorld.provider.dimensionId, spot.x(), spot.y(), spot.z());
        if (i < 0) return;
        graph = spot.hit()
            .graph();
        node = n;
        link = n.worldLinks.get(i)
            .clone();
    }

    @SubscribeEvent
    public void onKey(final InputEvent.KeyInputEvent event) {
        if (!adjusting() || !Keyboard.getEventKeyState()) return;
        // The key can come in the same tick as the adjust key, before the tick takes the placement in hand.
        grab();
        if (link == null) return;
        final int key = Keyboard.getEventKey();
        switch (key) {
            case Keyboard.KEY_UP, Keyboard.KEY_DOWN, Keyboard.KEY_LEFT, Keyboard.KEY_RIGHT -> step(key);
            case Keyboard.KEY_R -> put(turned(shift() ? 3 : 1), "Turned");
            case Keyboard.KEY_LBRACKET -> resize(-1);
            case Keyboard.KEY_RBRACKET -> resize(1);
            case Keyboard.KEY_G -> pickUp();
            case Keyboard.KEY_DELETE, Keyboard.KEY_BACK -> remove();
            default -> {}
        }
    }

    /** A block in the way an arrow points as the player looks; Shift with up and down, a block up or down. */
    private void step(final int key) {
        final int[] to = link.clone();
        if (shift() && (key == Keyboard.KEY_UP || key == Keyboard.KEY_DOWN)) {
            to[2] += key == Keyboard.KEY_UP ? 1 : -1;
        } else {
            final EntityLivingBase eye = Minecraft.getMinecraft().renderViewEntity;
            // The way the player looks, to the nearest side: 0 south, 1 west, 2 north, 3 east.
            final int look = eye == null ? 0 : Math.floorMod(Math.round(eye.rotationYaw / 90f), 4);
            final int fx = look == 1 ? -1 : look == 3 ? 1 : 0, fz = look == 0 ? 1 : look == 2 ? -1 : 0;
            // The player's right, with forward south (+z), is west (-x).
            final int rx = -fz, rz = fx;
            switch (key) {
                case Keyboard.KEY_UP -> {
                    to[1] += fx;
                    to[3] += fz;
                }
                case Keyboard.KEY_DOWN -> {
                    to[1] -= fx;
                    to[3] -= fz;
                }
                case Keyboard.KEY_RIGHT -> {
                    to[1] += rx;
                    to[3] += rz;
                }
                default -> {
                    to[1] -= rx;
                    to[3] -= rz;
                }
            }
        }
        put(to, null);
    }

    /** The placement turned a quarter (clockwise from above) about the middle of what it fills. */
    private int[] turned(final int quarters) {
        final int[] to = WorldLinks
            .link(link[0], link[1], link[2], link[3], WorldLinks.facing(link) + quarters, WorldLinks.size(link));
        final double[] a = WorldLinks.extent(graph, node, link), b = WorldLinks.extent(graph, node, to);
        to[1] += (int) Math.round(((a[0] + a[3]) - (b[0] + b[3])) / 2);
        to[3] += (int) Math.round(((a[2] + a[5]) - (b[2] + b[5])) / 2);
        return to;
    }

    /** A structure that comes in sizes a step smaller or larger, its bottom where it was. */
    private void resize(final int step) {
        final PlanSnapshot.Card card = card();
        if (card == null) return;
        final int size = WorldLinks.size(link);
        final StructureGhosts.Ghost now = StructureGhosts.get(card.machine(), card.needs(), size);
        if (now == null || !now.sized()) {
            WorldView.say(now == null ? "Only a structure comes in sizes" : "This structure has one size");
            return;
        }
        final int next = Math.max(1, Math.min(now.sizes(), now.size() + step));
        if (next == now.size()) return;
        final StructureGhosts.Ghost then = StructureGhosts.get(card.machine(), card.needs(), next);
        // Back at the size its recipe needs, it follows the recipe again.
        final StructureGhosts.Ghost auto = StructureGhosts.get(card.machine(), card.needs(), 0);
        final int keep = auto != null && auto.size() == next ? 0 : next;
        final int[] to = WorldLinks.link(link[0], link[1], link[2], link[3], WorldLinks.facing(link), keep);
        if (then != null) to[2] += now.minY() - then.minY();
        put(to, then == null ? null : LinkPicker.dimensions(then));
    }

    private void pickUp() {
        final PlanSnapshot.Card card = card();
        LinkPicker.move(
            graph,
            node.id,
            WorldView.cardName(node),
            card == null ? null : card.machine(),
            card == null ? StructureGhosts.Needs.NONE : card.needs(),
            link);
        link = null;
    }

    private void remove() {
        WorldLinks.unlink(graph, node, link[0], link[1], link[2], link[3]);
        WorldView.say("Removed " + WorldView.cardName(node) + " from the world");
        Hyb.click();
        link = null;
    }

    /** Puts the placement in hand where {@code to} says, unless another machine is there. */
    private void put(final int[] to, @Nullable final String said) {
        if (!WorldLinks.relink(graph, node, link, to, false)) {
            WorldView.say("Another machine is placed there");
            return;
        }
        link = to;
        Hyb.click();
        if (said != null) WorldView.say(said);
    }

    /** The card in hand as the plan last open shows it (its machine and what its structure needs), or null. */
    @Nullable
    private PlanSnapshot.Card card() {
        final PlanSnapshot snap = PlanSnapshot.latest();
        return snap == null || snap.graph() != graph || node == null ? null : snap.cardOf(node.id);
    }

    // region Drawing

    /** The placement in hand, outlined bright. */
    @SubscribeEvent
    public void onRenderWorld(final RenderWorldLastEvent event) {
        if (link == null || !adjusting()
            || Minecraft.getMinecraft().theWorld == null
            || Minecraft.getMinecraft().theWorld.provider.dimensionId != link[0]) return;
        WorldMarks.begin();
        WorldMarks.outline(WorldLinks.extent(graph, node, link), Hyb.LIT & 0xFFFFFF, 0.012f, 2.5f, 1f);
        WorldMarks.end();
    }

    /** One row of the keys panel: its keys, then what they do. */
    private record Row(String[] keys, String what) {}

    /**
     * The keys, under the minimap (or in its corner when it is off), while a placed machine is looked at or in hand.
     */
    @SubscribeEvent
    public void onOverlay(final RenderGameOverlayEvent.Post event) {
        if (event.type != RenderGameOverlayEvent.ElementType.ALL) return;
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc.currentScreen != null || mc.gameSettings.hideGUI || LinkPicker.active()) return;
        final boolean held = link != null && adjusting();
        final WorldLinks.Spot spot = held ? null : WorldView.looked();
        if (!held && spot == null) return;
        final Node n = held ? node
            : spot.hit()
                .node();
        final Graph g = held ? graph
            : spot.hit()
                .graph();
        final PlanSnapshot snap = PlanSnapshot.latest();
        final PlanSnapshot.Card card = snap == null || snap.graph() != g ? null : snap.cardOf(n.id);
        final int[] at = held ? link : find(n, spot);
        final StructureGhosts.Ghost ghost = card == null || at == null ? null
            : StructureGhosts.peek(card.machine(), card.needs(), WorldLinks.size(at));
        final String hold = keyName(ADJUST.getKeyCode());
        final java.util.List<Row> rows = new java.util.ArrayList<>();
        rows.add(new Row(new String[] { "up", "down", "left", "right" }, "Move a block"));
        rows.add(new Row(new String[] { "Shift", "up", "down" }, "Raise, lower"));
        rows.add(new Row(new String[] { "R" }, "Turn"));
        if (ghost != null && ghost.sized()) rows.add(new Row(new String[] { "[", "]" }, "Smaller, larger"));
        rows.add(new Row(new String[] { "G" }, "Pick up and put down"));
        rows.add(new Row(new String[] { "Del" }, "Remove from the world"));

        final ScaledResolution sr = event.resolution;
        // Sized to what it says: the keys in a column, what they do in the next.
        final String title = WorldView.cardName(n) + (ghost == null ? "" : "  " + LinkPicker.dimensions(ghost));
        final String sub = held ? "Adjusting while " + hold + " is held" : "Hold " + hold + " to adjust:";
        float keysW = 0, whatW = 0;
        for (final Row r : rows) {
            float kw = 0;
            for (final String k : r.keys()) kw += keyWidth(k) + 2;
            keysW = Math.max(keysW, kw);
            whatW = Math.max(whatW, Hyb.width(r.what()));
        }
        final int column = (int) keysW + 6;
        final int w = Math.min(
            sr.getScaledWidth() - 12,
            Math.max(6 + column + (int) whatW + 8, Math.max(Hyb.width(title), Hyb.width(sub)) + 12));
        final int rowH = 13, head = 25, h = head + rows.size() * rowH + 4;
        final int[] map = Minimap.bounds(sr);
        final boolean right = map == null || map[0] > sr.getScaledWidth() / 2;
        final boolean below = map == null || map[1] < sr.getScaledHeight() / 2;
        final float x = map == null ? sr.getScaledWidth() - 6 - w : right ? map[0] + map[2] - w : map[0];
        final float y = map == null ? 6 : below ? map[1] + map[3] + 14 : map[1] - h - 6;
        GL11.glPushMatrix();
        GL11.glDisable(GL11.GL_LIGHTING);
        Hyb.roundRect(x, y, w, h, 3, 0xD0141414);
        Hyb.text(Hyb.fit(title, w - 12), x + 6, y + 4, held ? Hyb.GOLD : Hyb.INK);
        Hyb.text(Hyb.fit(sub, w - 12), x + 6, y + 14, Hyb.MUTED);
        float ry = y + head;
        for (final Row r : rows) {
            float kx = x + 6;
            for (final String k : r.keys()) kx += key(k, kx, ry, held) + 2;
            Hyb.text(r.what(), x + 6 + column, ry + 2, held ? Hyb.INK : Hyb.MUTED);
            ry += rowH;
        }
        GL11.glPopMatrix();
        GL11.glColor4f(1, 1, 1, 1);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
    }

    /** The looked-at spot's placement, or null. */
    @Nullable
    private static int[] find(final Node n, final WorldLinks.Spot spot) {
        for (final int[] l : n.worldLinks) if (l[1] == spot.x() && l[2] == spot.y() && l[3] == spot.z()) return l;
        return null;
    }

    private static boolean arrow(final String k) {
        return k.equals("up") || k.equals("down") || k.equals("left") || k.equals("right");
    }

    private static float keyWidth(final String k) {
        return arrow(k) ? 11 : Math.max(11, Hyb.width(k) + 6);
    }

    /** A key cap: its label, or an arrow for up, down, left and right. Returns its width. */
    private static float key(final String k, final float x, final float y, final boolean held) {
        final boolean arrow = arrow(k);
        final float w = keyWidth(k), h = 11;
        Hyb.roundRect(x, y, w, h, 2, held ? 0xFF4A4636 : 0xFF2E3036);
        Hyb.rect(x, y + h - 1, w, 1, 0x60000000);
        final int c = held ? Hyb.GOLD : Hyb.INK;
        if (!arrow) {
            Hyb.text(k, x + (w - Hyb.width(k)) / 2f, y + 2, c);
            return w;
        }
        final float cx = x + w / 2f, cy = y + h / 2f - 0.5f, r = 2.5f;
        switch (k) {
            case "up" -> Hyb.triangle(cx, cy - r, cx + r, cy + r, cx - r, cy + r, c);
            case "down" -> Hyb.triangle(cx - r, cy - r, cx + r, cy - r, cx, cy + r, c);
            case "left" -> Hyb.triangle(cx - r, cy, cx + r, cy - r, cx + r, cy + r, c);
            default -> Hyb.triangle(cx + r, cy, cx - r, cy + r, cx - r, cy - r, c);
        }
        return w;
    }

    /** A key's name as people say it: Ctrl, Shift, Alt, else the game's. */
    static String keyName(final int code) {
        return switch (code) {
            case Keyboard.KEY_LCONTROL, Keyboard.KEY_RCONTROL -> "Ctrl";
            case Keyboard.KEY_LSHIFT, Keyboard.KEY_RSHIFT -> "Shift";
            case Keyboard.KEY_LMENU, Keyboard.KEY_RMENU -> "Alt";
            default -> {
                final String n = Keyboard.getKeyName(code);
                yield n == null ? "?"
                    : n.length() <= 1 ? n
                        : n.charAt(0) + n.substring(1)
                            .toLowerCase();
            }
        };
    }

    // endregion
}

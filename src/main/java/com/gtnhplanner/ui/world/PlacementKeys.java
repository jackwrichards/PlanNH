package com.gtnhplanner.ui.world;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;

import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.ui.gt.StructureGhosts;
import com.gtnhplanner.ui.theme.Hyb;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.InputEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Adjusting a placed machine from the world. Hold sneak (Shift) with the crosshair on a placed machine and it is in
 * hand while sneak is held: R turns it about its middle, G picks it up to put down elsewhere (the picker, where [ and ]
 * size a structure that comes in sizes), and Delete takes it out of the world. Each is one undo step in its plan.
 * Moving
 * is picking up and putting down only: no keys nudge it, so the minimap keeps its arrows and [ ]. While the crosshair
 * is on a placed machine, or one is in hand, the keys show under the minimap.
 *
 * <p>
 * Sneak, not a key binding of its own: in 1.7.10 a key drives one binding, so one of ours on Shift would take it from
 * sneaking. Whatever sneak is bound to holds.
 */
public final class PlacementKeys {

    public static final PlacementKeys INSTANCE = new PlacementKeys();

    /** The placement in hand while sneak is held: its plan, card and where it is now; null when none. */
    @Nullable
    private Graph graph;
    @Nullable
    private Node node;
    @Nullable
    private int[] link;

    private PlacementKeys() {}

    /** Whether sneak is held in the world, not while placing. */
    static boolean adjusting() {
        final Minecraft mc = Minecraft.getMinecraft();
        return sneaking() && mc.currentScreen == null && !LinkPicker.active();
    }

    /** Whether sneak is held: by its binding, or its key down (input the binding has not seen yet). */
    static boolean sneaking() {
        final net.minecraft.client.settings.KeyBinding sneak = Minecraft.getMinecraft().gameSettings.keyBindSneak;
        final int code = sneak.getKeyCode();
        return sneak.getIsKeyPressed() || code > 0 && code < 256 && Keyboard.isKeyDown(code);
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
        final WorldLinks.Spot spot = target();
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

    /**
     * The placement the keys act on: the one the plan over the world has lit gold (its card or its ghost under the
     * crosshair, with the overlay's margins), else, with the overlay off, the placed block looked at.
     */
    @Nullable
    private static WorldLinks.Spot target() {
        return PlanOverlay.on() ? PlanOverlay.highlighted() : WorldView.looked();
    }

    @SubscribeEvent
    public void onKey(final InputEvent.KeyInputEvent event) {
        if (!adjusting() || !Keyboard.getEventKeyState()) return;
        // The key can come in the same tick as sneak, before the tick takes the placement in hand.
        grab();
        if (link == null) return;
        switch (Keyboard.getEventKey()) {
            case Keyboard.KEY_R -> turn();
            case Keyboard.KEY_G -> pickUp();
            case Keyboard.KEY_DELETE, Keyboard.KEY_BACK -> remove();
            default -> {}
        }
    }

    /** Turns the placement a quarter, clockwise from above, about the middle of what it fills. */
    private void turn() {
        final int[] to = WorldLinks
            .link(link[0], link[1], link[2], link[3], WorldLinks.facing(link) + 1, WorldLinks.size(link));
        final double[] a = WorldLinks.extent(graph, node, link), b = WorldLinks.extent(graph, node, to);
        to[1] += (int) Math.round(((a[0] + a[3]) - (b[0] + b[3])) / 2);
        to[3] += (int) Math.round(((a[2] + a[5]) - (b[2] + b[5])) / 2);
        if (!WorldLinks.relink(graph, node, link, to, false)) {
            WorldView.say("Another machine is placed there");
            return;
        }
        link = to;
        Hyb.click();
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

    /** One line of the keys list: the keys, and what they do. */
    private record Item(String key, String what, boolean live) {}

    /**
     * The keys, plainly, one to a line, under the minimap (or in its corner when it is off): always while the plan is
     * over the world, else while a placed machine is looked at. The machine lit (or in hand) heads them; the lines that
     * act on it are dim while there is none.
     */
    @SubscribeEvent
    public void onOverlay(final RenderGameOverlayEvent.Post event) {
        if (event.type != RenderGameOverlayEvent.ElementType.ALL) return;
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc.currentScreen != null || mc.gameSettings.hideGUI || LinkPicker.active()) return;
        final boolean held = link != null && adjusting();
        final WorldLinks.Spot spot = held ? null : target();
        if (!held && spot == null && !PlanOverlay.on()) return;
        final boolean some = held || spot != null;
        String title = null;
        if (some) {
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
            title = WorldView.cardName(n) + (ghost == null ? "" : "  " + LinkPicker.dimensions(ghost));
        }
        final String shift = keyName(mc.gameSettings.keyBindSneak.getKeyCode()) + " + ";
        final java.util.List<Item> items = java.util.List.of(
            new Item(shift + "R", "Turn the highlighted machine", some),
            new Item(shift + "G", "Pick up and move it", some),
            new Item(shift + "Del", "Remove it from the world", some),
            new Item(
                shift + "Y",
                com.gtnhplanner.ui.PlannerSettings.arFocus() ? "Show every card" : "Show only cards you look at",
                true));

        final ScaledResolution sr = event.resolution;
        final int[] map = Minimap.bounds(sr);
        float keysW = 0, whatW = title == null ? 0 : Hyb.width(title);
        for (final Item it : items) {
            keysW = Math.max(keysW, Hyb.width(it.key()));
            whatW = Math.max(whatW, Hyb.width(it.what()));
        }
        final int column = (int) keysW + 8;
        final int w = Math.min(sr.getScaledWidth() - 12, (int) Math.max(column + whatW, whatW) + 10);
        final int rowH = 10, h = (title == null ? 0 : rowH + 2) + items.size() * rowH + 6;
        final boolean right = map == null || map[0] > sr.getScaledWidth() / 2;
        final boolean below = map == null || map[1] < sr.getScaledHeight() / 2;
        final float x = map == null ? sr.getScaledWidth() - 6 - w : right ? map[0] + map[2] - w : map[0];
        final float y = map == null ? 6 : below ? map[1] + map[3] + 14 : map[1] - h - 6;
        GL11.glPushMatrix();
        GL11.glDisable(GL11.GL_LIGHTING);
        Hyb.rect(x, y, w, h, 0x70000000);
        float ry = y + 3;
        if (title != null) {
            Hyb.text(Hyb.fit(title, w - 10), x + 5, ry, held ? Hyb.GOLD : 0xD0E8C878);
            ry += rowH + 2;
        }
        for (final Item it : items) {
            final int key = it.live() ? 0xD0D8DADF : 0x60D8DADF, what = it.live() ? 0xC09A9CA4 : 0x509A9CA4;
            Hyb.text(it.key(), x + 5, ry, key);
            Hyb.text(Hyb.fit(it.what(), w - column - 10), x + 5 + column, ry, what);
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

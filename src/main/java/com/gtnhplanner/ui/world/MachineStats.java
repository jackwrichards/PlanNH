package com.gtnhplanner.ui.world;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.fluids.FluidStack;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * What each GregTech machine near the player has done while the player was about: how much of the time it ran, and
 * what it made and used, over the last minute, the last hour and in all. Sampled twice a second from what the machine
 * reports: in single player its running recipe, so the amounts are its recipe's rates over the time it ran; on a
 * server only whether it runs. Kept for this session, for machines within {@link #RANGE} blocks.
 */
public final class MachineStats {

    public static final MachineStats INSTANCE = new MachineStats();

    static final int RANGE = 128;
    private static final int EVERY = 10;
    private static final boolean GREGTECH = Loader.isModLoaded("gregtech");

    private record Pos(int dim, int x, int y, int z) {}

    /** A count per second for the last minute and per minute for the last hour, and in all. */
    public static final class Window {

        private final double[] sec = new double[60], min = new double[60];
        private long secAt = -1, minAt = -1;
        private double total;

        void add(final long second, final double v) {
            roll(second);
            sec[(int) (second % 60)] += v;
            min[(int) (second / 60 % 60)] += v;
            total += v;
        }

        private void roll(final long second) {
            if (secAt < 0) {
                secAt = second;
                minAt = second / 60;
                return;
            }
            for (long s = secAt + 1; s <= second && s <= secAt + 60; s++) sec[(int) (s % 60)] = 0;
            if (second > secAt) secAt = second;
            final long m = second / 60;
            for (long k = minAt + 1; k <= m && k <= minAt + 60; k++) min[(int) (k % 60)] = 0;
            if (m > minAt) minAt = m;
        }

        public double lastMinute(final long now) {
            roll(now);
            double s = 0;
            for (final double v : sec) s += v;
            return s;
        }

        public double lastHour(final long now) {
            roll(now);
            double s = 0;
            for (final double v : min) s += v;
            return s;
        }

        /** Each of the last 60 seconds, oldest first. */
        public double[] seconds(final long now) {
            roll(now);
            final double[] out = new double[60];
            for (int i = 0; i < 60; i++) {
                final long s = now - 59 + i;
                out[i] = s < 0 ? 0 : sec[(int) (s % 60)];
            }
            return out;
        }

        /** Each of the last 60 minutes, oldest first. */
        public double[] minutes(final long now) {
            roll(now);
            final double[] out = new double[60];
            final long m = now / 60;
            for (int i = 0; i < 60; i++) {
                final long k = m - 59 + i;
                out[i] = k < 0 ? 0 : min[(int) (k % 60)];
            }
            return out;
        }

        public double total() {
            return total;
        }
    }

    /** One thing a machine made or used: what it is, and how much. */
    public static final class Amount {

        @Nullable
        public final ItemStack item;
        @Nullable
        public final FluidStack fluid;
        public final Window window = new Window();

        Amount(@Nullable final ItemStack item, @Nullable final FluidStack fluid) {
            this.item = item;
            this.fluid = fluid;
        }

        public String name() {
            return fluid != null ? fluid.getLocalizedName() : item != null ? item.getDisplayName() : "";
        }
    }

    /** One machine's record. */
    public static final class Track {

        /** The world second it was first seen, and last. */
        public long since, last;
        /**
         * Seconds it was watched, and seconds it ran; EU/t times seconds while it ran (EU/t on average, per watched
         * second).
         */
        public final Window seen = new Window(), ran = new Window(), energy = new Window();
        public final Map<String, Amount> made = new LinkedHashMap<>(), used = new LinkedHashMap<>();

        public List<Amount> made() {
            return new ArrayList<>(made.values());
        }

        public List<Amount> used() {
            return new ArrayList<>(used.values());
        }

        /** The share of the watched time it ran, over the last minute or hour; -1 when it was not watched. */
        public float busy(final long now, final boolean hour) {
            final double seen = hour ? this.seen.lastHour(now) : this.seen.lastMinute(now);
            if (seen <= 0) return -1;
            return (float) Math.min(1, (hour ? ran.lastHour(now) : ran.lastMinute(now)) / seen);
        }
    }

    private final Map<Pos, Track> tracks = new HashMap<>();
    private long lastTick = -1;
    private int ticks;

    private MachineStats() {}

    /** The world's clock in seconds: it stops when the game is paused. */
    public static long now() {
        final Minecraft mc = Minecraft.getMinecraft();
        return mc.theWorld == null ? 0 : mc.theWorld.getTotalWorldTime() / 20;
    }

    @Nullable
    public Track track(final int dim, final int x, final int y, final int z) {
        return tracks.get(new Pos(dim, x, y, z));
    }

    @SubscribeEvent
    public void onTick(final TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !GREGTECH) return;
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || mc.thePlayer == null) {
            tracks.clear();
            lastTick = -1;
            return;
        }
        if (++ticks % EVERY != 0) return;
        final long tick = mc.theWorld.getTotalWorldTime();
        final double seconds = lastTick < 0 ? 0 : Math.min(5, (tick - lastTick) / 20.0);
        lastTick = tick;
        if (seconds <= 0) return;
        final long now = tick / 20;
        final int dim = mc.theWorld.provider.dimensionId;
        final double px = mc.thePlayer.posX, py = mc.thePlayer.posY, pz = mc.thePlayer.posZ;
        for (final Object o : mc.theWorld.loadedTileEntityList) {
            if (!(o instanceof final TileEntity te)) continue;
            final double dx = te.xCoord - px, dy = te.yCoord - py, dz = te.zCoord - pz;
            if (dx * dx + dy * dy + dz * dz > RANGE * RANGE || !GtMachineStatus.isMachine(te)) continue;
            final MachineStatus st = GtMachineStatus.read(mc.theWorld, te);
            if (st == null) continue;
            final Track t = tracks.computeIfAbsent(new Pos(dim, te.xCoord, te.yCoord, te.zCoord), p -> {
                final Track n = new Track();
                n.since = now;
                return n;
            });
            t.last = now;
            t.seen.add(now, seconds);
            if (st.state() != MachineStatus.State.RUNNING) continue;
            t.ran.add(now, seconds);
            if (st.euPerTick() > 0) t.energy.add(now, st.euPerTick() * seconds);
            for (final MachineStatus.Flow f : st.outputs()) add(t.made, f, now, seconds);
            for (final MachineStatus.Flow f : st.inputs()) add(t.used, f, now, seconds);
        }
        // Machines gone for an hour are forgotten.
        for (final Iterator<Track> it = tracks.values()
            .iterator(); it.hasNext();) if (now - it.next().last > 3600) it.remove();
    }

    /** In single player, the integrated server's thread reads the machines the client asked about. */
    @SubscribeEvent
    public void onServerTick(final TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END && GREGTECH) GtMachineStatus.serverTick();
    }

    private static void add(final Map<String, Amount> into, final MachineStatus.Flow f, final long now,
        final double seconds) {
        if (f.perSecond() <= 0) return;
        final String key = f.fluid() != null ? "fluid:" + f.fluid()
            .getFluid()
            .getName()
            : f.item() != null ? "item:" + net.minecraft.item.Item.getIdFromItem(
                f.item()
                    .getItem())
                + ":"
                + f.item()
                    .getItemDamage()
                : null;
        if (key == null) return;
        into.computeIfAbsent(key, k -> new Amount(f.item(), f.fluid())).window.add(now, f.perSecond() * seconds);
    }
}

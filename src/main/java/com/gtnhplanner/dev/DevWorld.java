package com.gtnhplanner.dev;

import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;

import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.MTEBasicMachine;

/**
 * GregTech machines in the dev world, for checking the AR lens:
 * {@code call 'machine?x&y&z&feed=minecraft:iron_ore&count=16'}
 * fills a single-block machine's energy and puts the stack in its input, so it runs a real recipe;
 * {@code call 'machine?own=1'} gives every GregTech machine near the player that has no owner the player as owner
 * (machines made with /setblock have none, and GregTech crashes breaking an ownerless multiblock). Single player
 * only; it reaches into the integrated server's copy from the client thread, which is fine for a test world.
 */
public final class DevWorld {

    static final DevWorld INSTANCE = new DevWorld();

    private DevWorld() {}

    /** Machines kept running for a demo ({@code keep=1}): their energy topped up and their input refilled. */
    private record Kept(int dim, int x, int y, int z, ItemStack feed) {}

    private static final java.util.List<Kept> KEPT = new java.util.concurrent.CopyOnWriteArrayList<>();
    private int ticks;

    @cpw.mods.fml.common.eventhandler.SubscribeEvent
    public void onServerTick(final cpw.mods.fml.common.gameevent.TickEvent.ServerTickEvent event) {
        if (event.phase != cpw.mods.fml.common.gameevent.TickEvent.Phase.END || KEPT.isEmpty() || ++ticks % 10 != 0)
            return;
        for (final Kept k : KEPT) {
            final WorldServer ws = DimensionManager.getWorld(k.dim());
            if (ws == null || !ws.blockExists(k.x(), k.y(), k.z())) continue;
            if (!(ws.getTileEntity(k.x(), k.y(), k.z()) instanceof final IGregTechTileEntity base)
                || !(base.getMetaTileEntity() instanceof final MTEBasicMachine machine)) continue;
            base.increaseStoredEnergyUnits(base.getEUCapacity(), true);
            if (k.feed() == null) continue;
            final ItemStack in = machine.getStackInSlot(machine.getInputSlot());
            if (in == null || in.stackSize < 16) machine.setInventorySlotContents(machine.getInputSlot(), k.feed()
                .copy());
            // Outputs are emptied, so it never stops for a full output.
            for (int i = 0; i < machine.mOutputItems.length; i++)
                machine.setInventorySlotContents(machine.getOutputSlot() + i, null);
        }
    }

    static Map<String, Object> machine(final Map<String, String> q) {
        if (q.containsKey("own")) return own();
        if (q.containsKey("release")) {
            KEPT.clear();
            return Map.of("kept", 0);
        }
        final int x = Integer.parseInt(q.get("x")), y = Integer.parseInt(q.get("y")), z = Integer.parseInt(q.get("z"));
        final WorldServer ws = DimensionManager.getWorld(Integer.parseInt(q.getOrDefault("dim", "0")));
        final Map<String, Object> out = new LinkedHashMap<>();
        if (ws == null) return Map.of("error", "no such dimension loaded");
        final TileEntity te = ws.getTileEntity(x, y, z);
        if (!(te instanceof final IGregTechTileEntity base)
            || !(base.getMetaTileEntity() instanceof final MTEBasicMachine machine))
            return Map.of("error", "not a GregTech single-block machine: " + te);
        base.increaseStoredEnergyUnits(base.getEUCapacity(), true);
        out.put("eu", base.getStoredEU());
        // A programmed circuit, for recipes that need one (bending ingots into plates: 1).
        if (q.containsKey("circuit")) {
            final Item circuit = (Item) Item.itemRegistry.getObject("gregtech:gt.integrated_circuit");
            if (circuit != null) machine.setInventorySlotContents(
                machine.getCircuitSlot(),
                new ItemStack(circuit, 0, Integer.parseInt(q.get("circuit"))));
        }
        if (q.containsKey("feed")) {
            final Item item = (Item) Item.itemRegistry.getObject(q.get("feed"));
            if (item == null) return Map.of("error", "no item " + q.get("feed"));
            final ItemStack stack = new ItemStack(
                item,
                Integer.parseInt(q.getOrDefault("count", "16")),
                Integer.parseInt(q.getOrDefault("meta", "0")));
            machine.setInventorySlotContents(machine.getInputSlot(), stack);
            out.put("fed", stack.toString());
            if ("1".equals(q.get("keep")))
                KEPT.add(new Kept(Integer.parseInt(q.getOrDefault("dim", "0")), x, y, z, stack.copy()));
        } else if ("1".equals(q.get("keep")))
            KEPT.add(new Kept(Integer.parseInt(q.getOrDefault("dim", "0")), x, y, z, null));
        out.put("progress", machine.mProgresstime + "/" + machine.mMaxProgresstime);
        return out;
    }

    private static Map<String, Object> own() {
        final net.minecraft.client.entity.EntityPlayerSP me = net.minecraft.client.Minecraft.getMinecraft().thePlayer;
        final WorldServer ws = DimensionManager.getWorld(me.dimension);
        if (ws == null) return Map.of("error", "no server world");
        int owned = 0;
        for (final Object o : new java.util.ArrayList<>(ws.loadedTileEntityList)) {
            if (!(o instanceof final IGregTechTileEntity base) || base.getOwnerUuid() != null) continue;
            final TileEntity te = (TileEntity) o;
            if (te.getDistanceFrom(me.posX, me.posY, me.posZ) > 128 * 128) continue;
            base.setOwnerName(me.getCommandSenderName());
            base.setOwnerUuid(me.getUniqueID());
            owned++;
        }
        return Map.of("owned", owned);
    }
}

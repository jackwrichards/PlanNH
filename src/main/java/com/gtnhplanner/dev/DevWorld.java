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
final class DevWorld {

    private DevWorld() {}

    static Map<String, Object> machine(final Map<String, String> q) {
        if (q.containsKey("own")) return own();
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
        if (q.containsKey("feed")) {
            final Item item = (Item) Item.itemRegistry.getObject(q.get("feed"));
            if (item == null) return Map.of("error", "no item " + q.get("feed"));
            final ItemStack stack = new ItemStack(
                item,
                Integer.parseInt(q.getOrDefault("count", "16")),
                Integer.parseInt(q.getOrDefault("meta", "0")));
            machine.setInventorySlotContents(machine.getInputSlot(), stack);
            out.put("fed", stack.toString());
        }
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

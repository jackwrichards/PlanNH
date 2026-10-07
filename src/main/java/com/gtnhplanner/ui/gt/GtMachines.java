package com.gtnhplanner.ui.gt;

import net.minecraft.block.Block;
import net.minecraft.item.ItemStack;

import gregtech.api.GregTechAPI;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEBasicMachineBronze;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;
import gregtech.api.metatileentity.implementations.MTETieredMachineBlock;

/**
 * What kind of GregTech machine a catalyst stack is: a multiblock, or a single block of some tier. Only touch this
 * class when GregTech is loaded.
 */
public final class GtMachines {

    /**
     * {@code tier} is GregTech's index (0 = ULV, 1 = LV, ...) or -1 when unknown; {@code steam} for the bronze and
     * steel machines, which run on steam rather than EU.
     */
    public record Kind(boolean multiblock, int tier, boolean steam) {}

    private GtMachines() {}

    public static Kind of(final ItemStack stack) {
        if (stack == null) return null;
        try {
            if (Block.getBlockFromItem(stack.getItem()) != GregTechAPI.sBlockMachines) return null;
            final int meta = stack.getItemDamage();
            if (meta < 0 || meta >= GregTechAPI.METATILEENTITIES.length) return null;
            final IMetaTileEntity mte = GregTechAPI.METATILEENTITIES[meta];
            if (mte instanceof MTEMultiBlockBase) return new Kind(true, -1, false);
            if (mte instanceof final MTETieredMachineBlock tiered) return new Kind(false, tiered.mTier, mte instanceof MTEBasicMachineBronze);
        } catch (final LinkageError e) {
            return null;
        }
        return null;
    }
}

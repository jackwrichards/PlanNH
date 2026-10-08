package com.gtnhplanner.ui.world;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.world.ChunkPosition;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.fluids.FluidStack;

import gregtech.api.enums.GTValues;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.logic.ProcessingLogic;
import gregtech.api.metatileentity.implementations.MTEBasicMachine;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;
import gregtech.api.recipe.check.CheckRecipeResult;
import gregtech.api.util.GTRecipe;
import gregtech.api.util.shutdown.ShutDownReason;

/**
 * GregTech's machines for the AR lens: finding the processing machines (single blocks and multiblock controllers)
 * near the player, and reading what each is doing. In single player the numbers come from the integrated server's
 * copy of the machine (the client's has no progress or recipe); on a server only the running light is known. The
 * recipe a machine runs is kept in protected fields, read by reflection; without them the lens still shows what the
 * machine is making. Only loaded when GregTech is.
 */
final class GtMachineStatus {

    private GtMachineStatus() {}

    @Nullable
    private static final Field LOGIC = field(MTEMultiBlockBase.class, "processingLogic");
    @Nullable
    private static final Field LOGIC_RECIPE = field(ProcessingLogic.class, "lastRecipe");
    @Nullable
    private static final Field BASIC_RECIPE = field(MTEBasicMachine.class, "mLastRecipe");

    @Nullable
    private static Field field(final Class<?> owner, final String name) {
        for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
            try {
                final Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (final NoSuchFieldException ignored) {} catch (final RuntimeException e) {
                return null;
            }
        }
        return null;
    }

    @Nullable
    private static Object get(@Nullable final Field f, @Nullable final Object from) {
        if (f == null || from == null) return null;
        try {
            return f.get(from);
        } catch (final ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** Whether a tile is a processing machine: a GregTech single block or multiblock controller. */
    static boolean isMachine(final TileEntity te) {
        if (!(te instanceof final IGregTechTileEntity base)) return false;
        final IMetaTileEntity mte = base.getMetaTileEntity();
        return mte instanceof MTEMultiBlockBase || mte instanceof MTEBasicMachine;
    }

    /** What a machine is doing, or null when the tile is not one. */
    @Nullable
    static MachineStatus read(final World world, final TileEntity te) {
        if (!(te instanceof final IGregTechTileEntity base)) return null;
        final IMetaTileEntity mte = base.getMetaTileEntity();
        if (!(mte instanceof MTEMultiBlockBase) && !(mte instanceof MTEBasicMachine)) return null;
        final ItemStack icon = mte.getStackForm(1);
        final String name = icon != null ? icon.getDisplayName() : base.getInventoryName();
        final boolean multi = mte instanceof MTEMultiBlockBase;
        final TileEntity serverTile = serverTile(world.provider.dimensionId, te.xCoord, te.yCoord, te.zCoord);
        if (!(serverTile instanceof final IGregTechTileEntity server)
            || !(server.getMetaTileEntity() instanceof final IMetaTileEntity smte)) {
            // On a server: the running light only.
            return new MachineStatus(
                name,
                icon,
                base.isActive() ? MachineStatus.State.RUNNING : MachineStatus.State.IDLE,
                "",
                -1,
                -1,
                0,
                mte instanceof final MTEBasicMachine b ? tierName(b.mTier) : "",
                0,
                0,
                List.of(),
                List.of(),
                1,
                multi);
        }
        if (smte instanceof final MTEMultiBlockBase m) return multiblock(name, icon, server, m);
        if (smte instanceof final MTEBasicMachine b) return single(name, icon, server, b);
        return null;
    }

    private static MachineStatus multiblock(final String name, final ItemStack icon, final IGregTechTileEntity base,
        final MTEMultiBlockBase m) {
        final int max = m.mMaxProgresstime, at = m.mProgresstime;
        final Object logic = get(LOGIC, m);
        final GTRecipe recipe = get(LOGIC_RECIPE, logic) instanceof final GTRecipe r ? r : null;
        int parallels = Math.max(1, m.lastParallel);
        if (logic instanceof final ProcessingLogic pl) parallels = Math.max(parallels, pl.getCurrentParallels());
        MachineStatus.State state;
        String detail = "";
        final boolean maintenance = m.getRepairStatus() < m.getIdealStatus();
        if (max > 0) {
            state = MachineStatus.State.RUNNING;
            if (maintenance) detail = "Needs maintenance";
        } else if (!m.mMachine) {
            state = MachineStatus.State.PROBLEM;
            detail = "Structure incomplete";
        } else if (!base.isAllowedToWork()) {
            state = MachineStatus.State.OFF;
            final ShutDownReason why = base.wasShutdown() ? base.getLastShutDownReason() : null;
            if (why != null) {
                state = MachineStatus.State.PROBLEM;
                detail = plain(why.getDisplayString());
            }
        } else if (maintenance) {
            state = MachineStatus.State.PROBLEM;
            detail = "Needs maintenance";
        } else {
            state = MachineStatus.State.IDLE;
            final CheckRecipeResult check = m.getCheckRecipeResult();
            if (check != null && !check.wasSuccessful()) detail = plain(check.getDisplayString());
        }
        final double seconds = max > 0 ? max / 20.0 : 0;
        final List<MachineStatus.Flow> outs = max > 0 ? made(m.mOutputItems, m.mOutputFluids, seconds)
            : recipeOutputs(recipe, parallels);
        return new MachineStatus(
            name,
            icon,
            state,
            detail,
            max > 0 ? Math.min(1f, at / (float) max) : -1,
            max > 0 ? max - at : -1,
            Math.abs((long) m.mEUt),
            m.getMaxInputVoltage() > 0 ? tierName((int) m.getInputVoltageTier()) : "",
            m.getMaxInputAmps(),
            m.getMaxInputEu(),
            recipeInputs(recipe, parallels, seconds),
            outs.isEmpty() && max > 0 ? recipeOutputs(recipe, parallels) : outs,
            parallels,
            true);
    }

    private static MachineStatus single(final String name, final ItemStack icon, final IGregTechTileEntity base,
        final MTEBasicMachine b) {
        final int max = b.mMaxProgresstime, at = b.mProgresstime;
        final GTRecipe recipe = get(BASIC_RECIPE, b) instanceof final GTRecipe r ? r : null;
        MachineStatus.State state;
        String detail = "";
        if (b.mStuttering) {
            state = MachineStatus.State.PROBLEM;
            detail = "Not enough power";
        } else if (max > 0) {
            state = MachineStatus.State.RUNNING;
        } else if (!base.isAllowedToWork()) {
            state = MachineStatus.State.OFF;
        } else if (b.mOutputBlocked > 0) {
            state = MachineStatus.State.PROBLEM;
            detail = "Output full";
        } else state = MachineStatus.State.IDLE;
        final double seconds = max > 0 ? max / 20.0 : 0;
        final List<MachineStatus.Flow> outs = max > 0
            ? made(b.mOutputItems, b.mOutputFluid == null ? null : new FluidStack[] { b.mOutputFluid }, seconds)
            : recipeOutputs(recipe, 1);
        return new MachineStatus(
            name,
            icon,
            state,
            detail,
            max > 0 ? Math.min(1f, at / (float) max) : -1,
            max > 0 ? max - at : -1,
            Math.abs((long) b.mEUt),
            tierName(b.mTier),
            b.maxAmperesIn(),
            b.mTier >= 0 && b.mTier < GTValues.V.length ? GTValues.V[b.mTier] * b.maxAmperesIn() : 0,
            recipeInputs(recipe, 1, seconds),
            outs,
            1,
            false);
    }

    /** A voltage tier's name ("MV"), or empty past the known ones. */
    private static String tierName(final int tier) {
        return tier >= 0 && tier < GTValues.VN.length ? GTValues.VN[tier] : "";
    }

    /** What the running cycle will put out, per second over the cycle; like stacks together. */
    private static List<MachineStatus.Flow> made(@Nullable final ItemStack[] items, @Nullable final FluidStack[] fluids,
        final double seconds) {
        final List<MachineStatus.Flow> out = new ArrayList<>();
        if (items != null)
            for (final ItemStack s : items) if (s != null && s.stackSize > 0) addItem(out, s, s.stackSize, seconds);
        if (fluids != null)
            for (final FluidStack f : fluids) if (f != null && f.amount > 0) addFluid(out, f, f.amount, seconds);
        return out;
    }

    /** The recipe's ingredients for its parallels, per second over the cycle (0 when not running). */
    private static List<MachineStatus.Flow> recipeInputs(@Nullable final GTRecipe r, final int parallels,
        final double seconds) {
        final List<MachineStatus.Flow> out = new ArrayList<>();
        if (r == null) return out;
        if (r.mInputs != null) for (final ItemStack s : r.mInputs)
            if (s != null && s.stackSize > 0) addItem(out, s, (long) s.stackSize * parallels, seconds);
        if (r.mFluidInputs != null) for (final FluidStack f : r.mFluidInputs)
            if (f != null && f.amount > 0) addFluid(out, f, (long) f.amount * parallels, seconds);
        return out;
    }

    /** The recipe's products for its parallels, at rest (a machine idle since it last ran it). */
    private static List<MachineStatus.Flow> recipeOutputs(@Nullable final GTRecipe r, final int parallels) {
        final List<MachineStatus.Flow> out = new ArrayList<>();
        if (r == null) return out;
        if (r.mOutputs != null) for (final ItemStack s : r.mOutputs)
            if (s != null && s.stackSize > 0) addItem(out, s, (long) s.stackSize * parallels, 0);
        if (r.mFluidOutputs != null) for (final FluidStack f : r.mFluidOutputs)
            if (f != null && f.amount > 0) addFluid(out, f, (long) f.amount * parallels, 0);
        return out;
    }

    private static void addItem(final List<MachineStatus.Flow> out, final ItemStack s, final long amount,
        final double seconds) {
        for (int i = 0; i < out.size(); i++) {
            final MachineStatus.Flow f = out.get(i);
            if (f.item() != null && f.item()
                .isItemEqual(s) && ItemStack.areItemStackTagsEqual(f.item(), s)) {
                final long n = f.perCycle() + amount;
                out.set(i, new MachineStatus.Flow(f.item(), null, n, seconds > 0 ? n / seconds : 0));
                return;
            }
        }
        out.add(new MachineStatus.Flow(s.copy(), null, amount, seconds > 0 ? amount / seconds : 0));
    }

    private static void addFluid(final List<MachineStatus.Flow> out, final FluidStack s, final long amount,
        final double seconds) {
        for (int i = 0; i < out.size(); i++) {
            final MachineStatus.Flow f = out.get(i);
            if (f.fluid() != null && f.fluid()
                .isFluidEqual(s)) {
                final long n = f.perCycle() + amount;
                out.set(i, new MachineStatus.Flow(null, f.fluid(), n, seconds > 0 ? n / seconds : 0));
                return;
            }
        }
        out.add(new MachineStatus.Flow(null, s.copy(), amount, seconds > 0 ? amount / seconds : 0));
    }

    /**
     * The integrated server's copy of a tile, in single player, read from its chunk's map without loading or creating
     * anything (the server thread owns it; a stale read only shows an old number for a moment). Null on a server.
     */
    @Nullable
    private static TileEntity serverTile(final int dim, final int x, final int y, final int z) {
        try {
            if (!Minecraft.getMinecraft()
                .isSingleplayer() || MinecraftServer.getServer() == null) return null;
            final WorldServer ws = DimensionManager.getWorld(dim);
            if (ws == null || !ws.getChunkProvider()
                .chunkExists(x >> 4, z >> 4)) return null;
            return (TileEntity) ws.getChunkFromChunkCoords(x >> 4, z >> 4).chunkTileEntityMap
                .get(new ChunkPosition(x & 15, y, z & 15));
        } catch (final RuntimeException e) {
            return null;
        }
    }

    /** GregTech's messages carry colour codes and sometimes several lines; the first line, plain. */
    private static String plain(final String s) {
        if (s == null) return "";
        final String flat = EnumChatFormatting.getTextWithoutFormattingCodes(s)
            .trim();
        final int nl = flat.indexOf('\n');
        return nl >= 0 ? flat.substring(0, nl)
            .trim() : flat;
    }
}

package com.gtnhplanner.ui.world;

import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import com.gtnhplanner.ui.theme.Hyb;

/**
 * A machine in the world and what it is doing now, as far as the client can know: in single player everything the
 * machine itself knows (its recipe's inputs and outputs per second, progress, EU/t, why it stopped); on a server only
 * whether it is running.
 *
 * @param progress     how far through its recipe, 0 to 1, or -1 when unknown
 * @param ticksLeft    ticks to the end of the recipe, or -1
 * @param euPerTick    what the recipe draws now, 0 when unknown
 * @param tier         the voltage it takes (a multiblock's energy hatches'), e.g. "MV"; empty when unknown
 * @param amps         the amps it takes, 0 when unknown
 * @param maxEuPerTick the most it can take, EU/t, 0 when unknown
 * @param detail       why it is not running, or a warning while it is; empty when there is nothing to say
 * @param inputs       what its recipe takes, per second while it runs (0 when idle: the last recipe it ran)
 * @param outputs      what its recipe makes, likewise
 * @param parallels    how many recipes it runs at once
 * @param multiblock   whether it is a multiblock's controller
 */
public record MachineStatus(String name, @Nullable ItemStack icon, State state, String detail, float progress,
    int ticksLeft, long euPerTick, String tier, long amps, long maxEuPerTick, List<Flow> inputs, List<Flow> outputs,
    int parallels, boolean multiblock) {

    /** One ingredient or product of the running recipe: per cycle, and per second while running. */
    public record Flow(@Nullable ItemStack item, @Nullable FluidStack fluid, long perCycle, double perSecond) {

        public String name() {
            return fluid != null ? fluid.getLocalizedName() : item != null ? item.getDisplayName() : "";
        }
    }

    public enum State {

        RUNNING("Running"),
        IDLE("Idle"),
        OFF("Turned off"),
        PROBLEM("Stopped");

        public final String word;

        State(final String word) {
            this.word = word;
        }

        /** Its light and words. */
        public int ink() {
            return this == RUNNING ? Hyb.PRODUCT_INK : this == PROBLEM ? Hyb.RED_INK : Hyb.MUTED;
        }
    }
}

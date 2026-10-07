package com.sbancuz.plannh.ui;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;

import org.jetbrains.annotations.Nullable;

import com.sbancuz.plannh.data.flowchart.Port;
import com.sbancuz.plannh.ui.card.CardDefaults;

/**
 * The resource a drawer holds, as the key saved with it: {@code item:<registry name>:<meta>} or {@code fluid:<name>}.
 * The engine treats the key as opaque; the board uses it to draw the drawer and to match ports to drawers.
 */
public final class Resources {

    private Resources() {}

    /** The key of what flows through a port, or "" when the port holds something else. */
    public static String key(final Port<?> port) {
        final Object value = port.getValue();
        if (value instanceof final FluidStack fluid && fluid.getFluid() != null) return "fluid:" + fluid.getFluid()
            .getName();
        if (value instanceof final ItemStack stack && stack.getItem() != null) return CardDefaults.itemKey(stack);
        return "";
    }

    /** The key of a stack as NEI shows it; fluid display items (GT's fluid cells in NEI) become the fluid. */
    public static String keyOf(final ItemStack stack) {
        final FluidStack fluid = codechicken.nei.recipe.StackInfo.getFluid(stack);
        if (fluid != null && fluid.getFluid() != null) return "fluid:" + fluid.getFluid()
            .getName();
        return CardDefaults.itemKey(stack);
    }

    public static boolean isFluid(final String key) {
        return key.startsWith("fluid:");
    }

    @Nullable
    public static FluidStack fluid(final String key) {
        if (!isFluid(key)) return null;
        return FluidRegistry.getFluidStack(key.substring("fluid:".length()), 1000);
    }

    /**
     * The stack NEI should see for a resource (R, U, bookmarks): the item itself, or for a fluid GregTech's display
     * item, as NEI shows fluids; null when there is none.
     */
    @Nullable
    public static ItemStack lookupStack(@Nullable final ItemStack item, @Nullable final FluidStack fluid) {
        if (item != null) return item.copy();
        if (fluid == null || !com.sbancuz.plannh.Compat.GREGTECH.isLoaded) return null;
        return com.sbancuz.plannh.data.provider.gregtech.GTHooks.fluidDisplayStack(fluid);
    }

    @Nullable
    public static ItemStack item(final String key) {
        if (!key.startsWith("item:")) return null;
        final int lastColon = key.lastIndexOf(':');
        if (lastColon <= "item:".length()) return null;
        final Object item = Item.itemRegistry.getObject(key.substring("item:".length(), lastColon));
        if (!(item instanceof final Item found)) return null;
        int meta = 0;
        try {
            meta = Integer.parseInt(key.substring(lastColon + 1));
        } catch (final NumberFormatException ignored) {}
        return new ItemStack(found, 1, meta);
    }

    /** The resource's display name, for drawers saved without a label. */
    public static String name(final String key) {
        final FluidStack fluid = fluid(key);
        if (fluid != null) return fluid.getLocalizedName();
        final ItemStack item = item(key);
        if (item != null) {
            try {
                return item.getDisplayName();
            } catch (final RuntimeException e) {
                return key;
            }
        }
        return key;
    }
}

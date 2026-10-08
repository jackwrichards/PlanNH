package com.gtnhplanner.ui.gt;

import javax.annotation.Nullable;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

import com.gtnhplanner.ui.theme.Hyb;

/**
 * GregTech's programmed circuits drawn so their number reads at a glance: the pack's own icons with the faint "88" left
 * off the screen and the lit segments darker, nothing else changed (made by {@code tools/dev/circuit-icons.mjs}, in
 * {@code assets/gtnhplanner/textures/circuits/}). Anything else is drawn as the game draws it.
 */
public final class CircuitIcons {

    private static final String CIRCUIT = "gregtech:gt.integrated_circuit";
    private static final ResourceLocation[] ICONS = new ResourceLocation[25];

    static {
        for (int n = 0; n < ICONS.length; n++)
            ICONS[n] = new ResourceLocation("gtnhplanner", "textures/circuits/" + n + ".png");
    }

    private CircuitIcons() {}

    /** Draws a circuit's icon, the clearer one for a programmed circuit, at a size. */
    public static void draw(@Nullable final ItemStack stack, final float x, final float y, final float size,
        final float z) {
        final ResourceLocation icon = icon(stack);
        if (icon != null) Hyb.texture(icon, x, y, size, size);
        else Hyb.icon(stack, null, x, y, size, z);
    }

    /** The clearer icon for a programmed circuit, or null for anything else. */
    @Nullable
    private static ResourceLocation icon(@Nullable final ItemStack stack) {
        if (stack == null || !CIRCUIT.equals(Item.itemRegistry.getNameForObject(stack.getItem()))) return null;
        final int n = stack.getItemDamage();
        return n >= 0 && n < ICONS.length ? ICONS[n] : null;
    }
}

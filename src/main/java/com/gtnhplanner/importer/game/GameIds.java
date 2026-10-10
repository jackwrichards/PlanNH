package com.gtnhplanner.importer.game;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nullable;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.oredict.OreDictionary;

import com.gtnhplanner.importer.FfIds;

/** Game stacks as Factory Flow ids, and back. */
public final class GameIds {

    private static final String OREDICT = "oredict:";

    private GameIds() {}

    /** The stack's FF id ({@code gregtech:gt.metaitem.01@2377}). */
    public static String itemId(final ItemStack stack) {
        return FfIds.itemId(String.valueOf(Item.itemRegistry.getNameForObject(stack.getItem())), stack.getItemDamage());
    }

    /** The stack's FF id, then each ore dictionary name it carries as {@code oredict:<name>}. */
    static List<String> itemIds(final ItemStack stack) {
        final List<String> ids = new ArrayList<>();
        ids.add(itemId(stack));
        try {
            for (final int ore : OreDictionary.getOreIDs(stack)) {
                final String id = OREDICT + OreDictionary.getOreName(ore)
                    .toLowerCase(Locale.ROOT);
                if (!ids.contains(id)) ids.add(id);
            }
        } catch (final RuntimeException ignored) {
            // A stack the ore dictionary chokes on just has no ore names.
        }
        return ids;
    }

    public static String fluidId(final FluidStack fluid) {
        return fluid.getFluid()
            .getName()
            .toLowerCase(Locale.ROOT);
    }

    /**
     * A stack of what an FF item id names: a registry name with an optional {@code @meta} (FF lowercases names, the
     * registry does not, so case is ignored), or the first ore of an {@code oredict:} name. Null when the game has
     * none.
     */
    @Nullable
    static ItemStack stackOf(final String ffId) {
        if (ffId.startsWith(OREDICT)) {
            final String name = ffId.substring(OREDICT.length());
            for (final String ore : OreDictionary.getOreNames()) {
                if (!ore.equalsIgnoreCase(name)) continue;
                final List<ItemStack> ores = OreDictionary.getOres(ore);
                if (!ores.isEmpty() && ores.getFirst() != null) return ores.getFirst()
                    .copy();
            }
            return null;
        }
        final int at = ffId.lastIndexOf('@');
        final boolean hasMeta = at > 0 && ffId.substring(at + 1)
            .matches("\\d{1,9}");
        final String name = hasMeta ? ffId.substring(0, at) : ffId;
        final int meta = hasMeta ? Integer.parseInt(ffId.substring(at + 1)) : 0;
        Object item = Item.itemRegistry.getObject(name);
        if (!(item instanceof Item)) {
            for (final Object key : Item.itemRegistry.getKeys()) {
                if (String.valueOf(key)
                    .equalsIgnoreCase(name)) {
                    item = Item.itemRegistry.getObject(key);
                    break;
                }
            }
        }
        return item instanceof final Item found ? new ItemStack(found, 1, meta) : null;
    }
}

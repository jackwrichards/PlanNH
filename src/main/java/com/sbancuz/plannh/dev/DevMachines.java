package com.sbancuz.plannh.dev;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.item.ItemStack;

import com.sbancuz.plannh.ui.card.StructureArt;

import gregtech.api.GregTechAPI;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;

/**
 * GregTech's machines as the game names them, for matching bundled pictures and checking machine lookups. Only loaded
 * when GregTech is (the endpoint catches the linkage error otherwise). Client thread only.
 */
final class DevMachines {

    private DevMachines() {}

    /** Every GregTech machine whose display name contains {@code query}, multiblocks only unless {@code all}. */
    static Map<String, Object> list(final String query, final boolean all, final boolean art) {
        final String q = query.toLowerCase(Locale.ROOT);
        final List<String> found = new ArrayList<>();
        for (int meta = 0; meta < GregTechAPI.METATILEENTITIES.length; meta++) {
            final IMetaTileEntity mte = GregTechAPI.METATILEENTITIES[meta];
            if (mte == null || !all && !(mte instanceof MTEMultiBlockBase)) continue;
            final String name;
            try {
                final ItemStack stack = mte.getStackForm(1);
                name = stack == null ? "?" : stack.getDisplayName();
            } catch (final RuntimeException e) {
                continue;
            }
            if (!q.isEmpty() && !name.toLowerCase(Locale.ROOT)
                .contains(q)) continue;
            String line = meta + " | "
                + name
                + " | "
                + mte.getClass()
                    .getSimpleName();
            if (art) {
                final StructureArt.Art picture = StructureArt.forMachine(name);
                line += " | " + (picture == null ? "-"
                    : picture.location()
                        .getResourcePath());
            }
            found.add(line);
        }
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("count", found.size());
        m.put("machines", found);
        return m;
    }
}

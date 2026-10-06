package com.sbancuz.plannh.ui.gt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.item.ItemStack;

import com.sbancuz.plannh.PlanNH;

import gregtech.api.GregTechAPI;
import gregtech.api.enums.HeatingCoilLevel;
import gregtech.common.blocks.BlockCasings5;

/**
 * GregTech's heating coils as the card shows them: name, heat and block. The engine stores coil heat as a plain
 * number (the {@code machine_heat} setting); this maps it to the coil a player recognises. Only touch this class
 * when GregTech is loaded.
 */
public final class GtCoils {

    public record Coil(String name, int heat, ItemStack stack) {}

    private static List<Coil> coils;

    private GtCoils() {}

    public static List<Coil> all() {
        if (coils == null) coils = load();
        return coils;
    }

    /** The coil whose heat matches, else the best coil at or below it, else null. */
    public static Coil forHeat(final int heat) {
        Coil best = null;
        for (final Coil c : all()) {
            if (c.heat <= heat && (best == null || c.heat > best.heat)) best = c;
        }
        return best;
    }

    private static List<Coil> load() {
        try {
            final List<Coil> out = new ArrayList<>();
            for (final HeatingCoilLevel level : HeatingCoilLevel.values()) {
                if (level == HeatingCoilLevel.None) continue;
                final int meta = BlockCasings5.getMetaFromCoilHeat(level);
                if (meta < 0) continue;
                final ItemStack stack = new ItemStack(GregTechAPI.sBlockCasings5, 1, meta);
                final String name = stack.getDisplayName()
                    .replace(" Coil Block", "");
                out.add(new Coil(name, (int) level.getHeat(), stack));
            }
            return out;
        } catch (final RuntimeException | LinkageError e) {
            PlanNH.LOG.warn("GregTech coils unavailable", e);
            return Collections.emptyList();
        }
    }
}

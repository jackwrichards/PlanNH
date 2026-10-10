package com.gtnhplanner.ui.card;

import java.util.List;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import com.gtnhplanner.data.MachineConfig;
import com.gtnhplanner.data.SettingDef;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.provider.GTKeys;
import com.gtnhplanner.ui.gt.GtCoils;
import com.gtnhplanner.ui.gt.GtMachines;

import codechicken.nei.PositionedStack;
import codechicken.nei.recipe.RecipeCatalysts;
import codechicken.nei.recipe.RecipeHandlerRef;

/**
 * Sensible starting settings for a recipe that just landed on the board, and the small vocabulary for the machine
 * choice that is saved with it.
 */
public final class CardDefaults {

    /** GregTech's tiers in setting order (the voltage setting also accepts "OFF"). */
    public static final String[] TIERS = { "ULV", "LV", "MV", "HV", "EV", "IV", "LuV", "ZPM", "UV", "UHV", "UEV", "UIV",
        "UMV", "UXV", "MAX" };

    private CardDefaults() {}

    /**
     * GregTech recipes start at their own tier (no overclock), as a multiblock when the first machine that runs
     * them is one, and with the lowest coil that is hot enough.
     */
    public static void apply(final Node node) {
        final MachineConfig cfg = node.machineConfig;
        if (!CardModel.GT_PROFILE.equals(cfg.profileId)) return;
        if ("OFF".equals(stringSetting(cfg, "voltage"))) {
            final Object eut = node.properties.get(GTKeys.EU_PER_TICK);
            cfg.setString("voltage", TIERS[recipeTier(eut instanceof Number n ? n.longValue() : 0)]);
        }
        final ItemStack machine = firstCatalyst(node);
        final GtMachines.Kind kind = GtMachines.of(machine);
        if (kind != null && kind.multiblock()) cfg.setBoolean("gt_multiblock", true);
        if (machine != null) node.machineName = itemKey(machine);
        final Object heat = node.properties.get(GTKeys.COIL_HEAT);
        if (heat instanceof final Number h && h.intValue() > 0 && intSetting(cfg, "machine_heat") <= 0) {
            for (final GtCoils.Coil coil : GtCoils.all()) {
                if (coil.heat() >= h.intValue()) {
                    cfg.setInt("machine_heat", coil.heat());
                    break;
                }
            }
        }
    }

    /**
     * A new GregTech card's hatches as the website seeds them (machines/web HatchInput): a multiblock at its recipe's
     * tier with whole amps enough for every structural parallel. Nothing for a singleblock or a card the website's
     * maths do not cover.
     */
    public static void seedHatches(final Node node) {
        final com.gtnhplanner.machines.web.Web.Recipe recipe = com.gtnhplanner.machines.game.WebEffect.recipe(node);
        if (recipe == null) return;
        final com.gtnhplanner.machines.web.Web.Node fresh = fresh(node, recipe);
        if (!com.gtnhplanner.machines.web.NodeMath.covers(recipe, fresh)) return;
        final com.gtnhplanner.machines.web.HatchInput.Hatches h = com.gtnhplanner.machines.web.HatchInput
            .seed(recipe, fresh);
        if (h == null) return;
        node.machineConfig.setString("voltage", h.tier());
        node.machineConfig.setInt("amp", (int) Math.max(1, Math.min(PowerPanel.MAX_AMPS, h.amps())));
    }

    /**
     * Runs the recipe on another machine, carrying the voltage as the website does: a multiblock becoming a single
     * block takes its hatch tier, a single block becoming a multiblock one hatch of its tier.
     */
    public static void switchMachine(final Node node, final ItemStack machine, final boolean gregtech) {
        final com.gtnhplanner.machines.web.Web.Recipe recipe = gregtech
            ? com.gtnhplanner.machines.game.WebEffect.recipe(node)
            : null;
        final com.gtnhplanner.machines.web.Web.Node before = recipe == null ? null
            : com.gtnhplanner.machines.game.WebCards.node(node, node.machineConfig, recipe);
        useMachine(node, machine, gregtech);
        if (before == null) return;
        final com.gtnhplanner.machines.web.HatchInput.Hatches h = com.gtnhplanner.machines.web.HatchInput
            .carry(recipe, before, com.gtnhplanner.machines.game.WebCards.handlerId(node, recipe));
        if (h == null) return;
        node.machineConfig.setString("voltage", h.tier());
        if (h.amps() > 0) node.machineConfig.setInt("amp", 1);
    }

    /** The card's machine and settings as the website reads them, without the hatches a new card has yet to get. */
    private static com.gtnhplanner.machines.web.Web.Node fresh(final Node node,
        final com.gtnhplanner.machines.web.Web.Recipe recipe) {
        final com.gtnhplanner.machines.web.Web.Node web = com.gtnhplanner.machines.game.WebCards
            .node(node, node.machineConfig, recipe);
        web.hatchVoltageTier = null;
        web.hatchAmps = null;
        web.powerInputMode = null;
        return web;
    }

    /** Smallest tier whose voltage (8 * 4^tier) covers the recipe's EU/t. */
    public static int recipeTier(final long euPerTick) {
        int tier = 0;
        while (tier < TIERS.length - 1 && 8L << (2 * tier) < euPerTick) tier++;
        return tier;
    }

    public static int tierIndex(final String name) {
        for (int i = 0; i < TIERS.length; i++) if (TIERS[i].equalsIgnoreCase(name)) return i;
        return -1;
    }

    public static ItemStack firstCatalyst(final Node node) {
        final RecipeHandlerRef ref = RecipeHandlerRef.of(node.recipeId);
        if (ref == null) return null;
        final List<PositionedStack> stacks = RecipeCatalysts.getRecipeCatalysts(ref.handler);
        return stacks.isEmpty() || stacks.get(0) == null ? null : stacks.get(0).item;
    }

    /** Runs the recipe on {@code machine}: GregTech single blocks bring their tier, multiblocks their options. */
    public static void useMachine(final Node node, final ItemStack machine, final boolean gregtech) {
        node.machineName = itemKey(machine);
        if (!gregtech) return;
        final GtMachines.Kind kind = GtMachines.of(machine);
        if (kind == null) return;
        node.machineConfig.setBoolean("gt_multiblock", kind.multiblock());
        if (!kind.multiblock() && kind.tier() >= 0 && kind.tier() < TIERS.length) {
            node.machineConfig.setString("voltage", TIERS[kind.tier()]);
        }
    }

    /** "item:modid:name:meta", how a chosen machine is remembered in the node's machine name. */
    public static String itemKey(final ItemStack stack) {
        final Object name = Item.itemRegistry.getNameForObject(stack.getItem());
        return "item:" + name + ":" + stack.getItemDamage();
    }

    public static boolean matches(final ItemStack stack, final String key) {
        return stack != null && key != null && key.equals(itemKey(stack));
    }

    /** A setting as the engine reads it: the stored value, or the profile's default when a saved plan left it out. */
    public static Object setting(final MachineConfig cfg, final String key) {
        final Object v = cfg.settings.get(key);
        if (v != null) return v;
        for (final SettingDef<?> def : cfg.getProfile()
            .settings()) {
            if (def.key.equals(key)) return def.defaultValue;
        }
        return null;
    }

    public static int intSetting(final MachineConfig cfg, final String key) {
        return setting(cfg, key) instanceof final Number n ? n.intValue() : 0;
    }

    public static boolean boolSetting(final MachineConfig cfg, final String key) {
        return setting(cfg, key) instanceof final Boolean b && b;
    }

    public static String stringSetting(final MachineConfig cfg, final String key) {
        return setting(cfg, key) instanceof final String s ? s : "";
    }
}

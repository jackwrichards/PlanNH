package com.gtnhplanner.machines.game;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import com.google.gson.JsonObject;
import com.gtnhplanner.api.RecipePropertyAPI;
import com.gtnhplanner.data.MachineConfig;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Port;
import com.gtnhplanner.data.provider.GTKeys;
import com.gtnhplanner.data.provider.GTProvider;
import com.gtnhplanner.importer.game.GameIds;
import com.gtnhplanner.machines.web.HandlerData;
import com.gtnhplanner.machines.web.MachineTable;
import com.gtnhplanner.machines.web.RecipeRules;
import com.gtnhplanner.machines.web.Tiers;
import com.gtnhplanner.machines.web.Web;
import com.gtnhplanner.ui.card.CardDefaults;
import com.gtnhplanner.ui.card.CardModel;

import codechicken.nei.recipe.FurnaceRecipeHandler;
import codechicken.nei.recipe.RecipeHandlerRef;
import gregtech.api.recipe.RecipeMap;

/**
 * A GregTech card as the website's machine maths see it (machines/web): its recipe as the website's dataset carries it
 * (map, numbers, ports, special value, handlers and settings from handlers.json), and its settings as the website's
 * node fields (docs/design/machine-table-port.md has the table of keys).
 */
public final class WebCards {

    private WebCards() {}

    /** The card's settings keys for the website's node fields. */
    public static final String VOLTAGE = "voltage", AMPS = "amp", HATCH_TYPE = "energy_hatch_type", COIL = "coil",
        CONTROL_PREFIX = "machine:";

    /** The website's vanilla furnace map: 200 ticks at no EU, run by GT's furnaces at 128 ticks and 4 EU/t. */
    private static final String SMELTING = "smelting";

    /** The game recipe map's id as handlers.json keys it, or null when the card is no GregTech recipe. */
    @Nullable
    public static String mapId(final Node node) {
        if (node.isPower()) return null;
        if (node.properties.get(GTProvider.RECIPE_MAP) instanceof final RecipeMap<?> map) return map.unlocalizedName;
        final RecipeHandlerRef ref = RecipeHandlerRef.of(node.recipeId);
        return ref != null && ref.handler instanceof FurnaceRecipeHandler ? SMELTING : null;
    }

    /** The card's recipe as the website's dataset carries it, or null when handlers.json has no such map. */
    @Nullable
    public static Web.Recipe recipe(final Node node) {
        final String mapId = mapId(node);
        final HandlerData.MapEntry entry = entry(mapId);
        if (entry == null) return null;
        final boolean smelting = SMELTING.equals(mapId);
        final Web.Recipe r = new Web.Recipe();
        r.id = mapId + ":" + node.recipeId;
        r.name = node.machineName;
        r.kind = "gregtech_machine";
        r.category = "gregtech";
        r.machineType = entry.name;
        r.durationTicks = smelting ? 200 : number(node.properties.get(RecipePropertyAPI.DURATION_TICKS));
        r.eut = smelting ? 0 : number(node.properties.get(GTKeys.EU_PER_TICK));
        r.minimumTier = smelting ? "NONE" : HandlerData.recipeMinimumTier(r.eut);
        final double special = number(node.properties.get(GTProvider.SPECIAL_VALUE));
        r.specialValue = special;
        r.nei = new Web.Nei();
        r.nei.additionalInfo = List.of("Special value: " + (long) special);
        r.source = new Web.Source();
        r.source.recipeMap = entry.name;
        r.metadata = new JsonObject();
        r.metadata.addProperty("recipeMapId", entry.id);
        r.metadata.addProperty("specialValue", special);
        if (node.properties.get(GTProvider.FUSION_THRESHOLD) instanceof final Number startup)
            r.metadata.addProperty("fusionStartupEu", startup.doubleValue());
        r.inputs = resources(node.inputs);
        r.outputs = resources(node.outputs);
        // The game's own ladder, as the website's oracle runs it for every GregTech map recipe.
        if (!smelting) r.runtimeCalculation = RuntimeVariants.of(r.durationTicks, r.eut, r.outputs);
        r.machineConfigControls = HandlerData.recipeControls(entry, special);
        r.machineHandlers = HandlerData.handlers(entry, r.minimumTier, r.durationTicks, r.eut, r.machineConfigControls);
        return r;
    }

    /**
     * The website's entry for a game map id. Its dataset came from a pack where GT++'s maps were named gtpp.recipe.*;
     * this game names them gt.recipe.*, and the Industrial Coke Oven's map changed its name.
     */
    @Nullable
    public static HandlerData.MapEntry entry(@Nullable final String mapId) {
        if (mapId == null) return null;
        final HandlerData.MapEntry entry = HandlerData.map(mapId);
        if (entry != null) return entry;
        if (mapId.equals("gt.recipe.industrialcokeoven")) return HandlerData.map("gtpp.recipe.cokeoven");
        return mapId.startsWith("gt.recipe.") ? HandlerData.map("gtpp.recipe." + mapId.substring(10)) : null;
    }

    private static double number(@Nullable final Object value) {
        return value instanceof final Number n ? n.doubleValue() : 0;
    }

    private static List<Web.Resource> resources(final List<Port<?>> ports) {
        final List<Web.Resource> out = new ArrayList<>();
        for (final Port<?> port : ports) {
            final Web.Resource res = new Web.Resource();
            if (port.getValue() instanceof final FluidStack fluid) {
                res.kind = "fluid";
                res.id = GameIds.fluidId(fluid);
            } else {
                res.kind = "item";
                res.id = port.getValue() instanceof final ItemStack stack ? GameIds.itemId(stack) : "";
            }
            res.amount = port.amount();
            // GT keeps a chance in ten-thousandths: the website has it as that exact fraction.
            if (port.getChance() < 1) res.chance = Math.round(port.getChance() * 10_000d) / 10_000d;
            res.displayName = port.getDisplayName();
            out.add(res);
        }
        return out;
    }

    /**
     * The card's settings ({@code cfg}: its own, or a what-if copy's) as the website's node fields, its machine matched
     * to one of the recipe's handlers.
     */
    public static Web.Node node(final Node node, final MachineConfig cfg, final Web.Recipe recipe) {
        final Web.Node n = new Web.Node();
        final String voltage = string(cfg.settings.get(VOLTAGE));
        if (Tiers.isName(voltage)) {
            n.overclockTier = voltage;
            n.hatchVoltageTier = voltage;
        }
        n.hatchAmps = cfg.settings.get(AMPS) instanceof final Number amps ? amps.doubleValue() : 1.0;
        n.powerInputMode = "amps";
        n.energyHatchType = string(cfg.settings.get(HATCH_TYPE));
        n.coilTier = string(cfg.settings.get(COIL));
        final Map<String, String> tiers = new LinkedHashMap<>();
        for (final Map.Entry<String, Object> e : cfg.settings.entrySet())
            if (e.getKey()
                .startsWith(CONTROL_PREFIX) && e.getValue() != null)
                tiers.put(
                    e.getKey()
                        .substring(CONTROL_PREFIX.length()),
                    String.valueOf(e.getValue()));
        n.machineConfigTiers = tiers;
        n.machineHandlerId = handlerId(node, recipe);
        n.parallel = 1.0;
        n.machineCount = 1.0;
        return n;
    }

    @Nullable
    private static String string(@Nullable final Object value) {
        return value instanceof final String s && !s.isEmpty() ? s : null;
    }

    /**
     * Which of the recipe's handlers the card's machine is: by its item among the handlers' machines, else a multiblock
     * by its display name (or a table alias of it), a singleblock by its family; null (the map's primary machine) when
     * none matches. Names drift between packs ("Fusion Control Computer Mk-I" here, "Mark I" in the website's data).
     */
    @Nullable
    public static String handlerId(final Node node, final Web.Recipe recipe) {
        final ItemStack machine = machineStack(node);
        if (machine == null) return null;
        final List<Web.Handler> handlers = RecipeRules.machineHandlers(recipe);
        // By the machine itself: the template that lists it, or the family it folded into.
        final HandlerData.MapEntry entry = entry(mapId(node));
        if (entry != null && entry.handlers != null) {
            final String item = GameIds.itemId(machine);
            for (final HandlerData.Template t : entry.handlers) {
                if (t.items == null || !t.items.contains(item)) continue;
                for (final Web.Handler h : handlers) if (h.id.equals(t.id)) return h.id;
                final String family = RecipeRules.familyLabel(t.label);
                for (final Web.Handler h : handlers) if (h.label.equals(family)) return h.id;
            }
        }
        // By name, where the data has no ids: its display name or a table alias, else its family.
        final String name = machine.getDisplayName();
        final String wanted = MachineTable.normalizeMachineName(name);
        for (final Web.Handler h : handlers) if (MachineTable.normalizeMachineName(h.label)
            .equals(wanted)) return h.id;
        final MachineTable.Behaviour table = MachineTable.behaviour(name);
        if (table != null)
            for (final Web.Handler h : handlers) if (MachineTable.behaviour(h.machineType) == table) return h.id;
        final String family = RecipeRules.familyLabel(name);
        for (final Web.Handler h : handlers) if (h.label.equalsIgnoreCase(family)) return h.id;
        return null;
    }

    /** The catalyst the card runs on: its chosen machine, else the recipe's first. */
    @Nullable
    public static ItemStack machineStack(final Node node) {
        final List<ItemStack> catalysts = CardModel.catalystsOf(node);
        for (final ItemStack s : catalysts) if (CardDefaults.matches(s, node.machineName)) return s;
        return catalysts.isEmpty() ? null : catalysts.get(0);
    }
}

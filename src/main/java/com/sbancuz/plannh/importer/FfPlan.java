package com.sbancuz.plannh.importer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

/**
 * A Factory Flow plan (FF's {@code FactoryProject}) as the importer reads it: the fields that can carry over to a
 * PlanNH plan, with FF's defaults filled in, positions flattened out of FF's boards and legacy trash cans already
 * turned into trash drawers. Built by {@link FfPlanParser}. Ids keep FF's spelling (item ids are lowercase registry
 * names with {@code @meta} when the meta is not 0).
 *
 * @param notes what the parser saw and left out (boards, annotations, pool scopes), for the import report
 */
public record FfPlan(String name, boolean solveMode, boolean poolMode, List<FfRecipe> recipes, List<FfNode> nodes,
    List<FfEdge> edges, List<FfStorage> storages, @Nullable FfTarget targetRate, List<String> notes) {

    /** FF's synthetic "dial a rate" card; see FF's custom-rate.ts. */
    public static final String CUSTOM_RATE_MACHINE = "Custom Rate";
    /** FF's legacy trash can card, which the parser turns into trash drawers. */
    public static final String TRASH_CAN_MACHINE = "Trash Can";
    /** FF's free, instant canner; a Canner recipe in game. */
    public static final String TANK_MAP = "planner.tank";

    public Map<String, FfRecipe> recipesById() {
        final Map<String, FfRecipe> map = new LinkedHashMap<>();
        for (final FfRecipe r : recipes) map.putIfAbsent(r.id(), r);
        return map;
    }

    /**
     * One recipe slot (FF's {@code RecipeInput}/{@code RecipeOutput}), also used for an input override.
     *
     * @param chance       1 when FF gave none
     * @param consumed     false for slots the machine keeps, like a programmed circuit
     * @param alternatives other ids this exact slot accepts (FF's {@code alternatives[]}), normalized
     */
    public record FfSlot(String kind, String id, double amount, double chance, boolean consumed,
        List<String> alternatives, String displayName) {

        /** The slot's id and every alternative, normalized, slot id first. */
        public List<String> ids() {
            final List<String> ids = new ArrayList<>();
            ids.add(FfIds.normalize(id));
            for (final String alt : alternatives) {
                final String n = FfIds.normalize(alt);
                if (!ids.contains(n)) ids.add(n);
            }
            return ids;
        }
    }

    /** A machine that can run a recipe (FF's {@code MachineHandler}); {@code kind} is "single", "multiblock", ... */
    public record FfHandler(String id, String label, String kind, boolean perfectOverclock) {

        public boolean multiblock() {
            return "multiblock".equals(kind);
        }
    }

    /**
     * A recipe body as the plan carries it.
     *
     * @param kind        FF's recipe kind ("gregtech_machine", "bee_produce", "custom", ...), "" when absent
     * @param category    "gregtech", "crafting", "furnace", "custom-rate", ... ("" when absent)
     * @param recipeMapId GregTech's unlocalized recipe map name ({@code metadata.recipeMapId}), when the plan kept it
     * @param power       a power card's synthesized recipe (FF's {@code recipe.power})
     */
    public record FfRecipe(String id, String name, String kind, String category, String machineType, String minimumTier,
        int durationTicks, double eut, @Nullable Double specialValue, @Nullable String programmedCircuit,
        List<FfSlot> inputs, List<FfSlot> outputs, List<FfHandler> handlers, @Nullable String recipeMapId,
        @Nullable String rawRecipeId, boolean power) {

        private static final Pattern ORACLE_ID = Pattern.compile("^oracle:[^:]*:gregtech:([^:]+):");
        private static final Pattern CIRCUIT_SETTING = Pattern.compile("^\\d{1,2}$");

        public boolean isCustomRate() {
            return CUSTOM_RATE_MACHINE.equals(machineType);
        }

        public boolean isTrashCan() {
            return TRASH_CAN_MACHINE.equals(machineType);
        }

        /** FF's Tank: a Canner recipe made free and instant. */
        public boolean isPlannerTank() {
            return TANK_MAP.equals(mapId()) || "planner-tank".equals(mapSlug());
        }

        /** The machine the card runs: the handler with that id, else the recipe's first handler, else null. */
        @Nullable
        public FfHandler handler(@Nullable final String handlerId) {
            for (final FfHandler h : handlers) if (h.id()
                .equals(handlerId)) return h;
            return handlers.isEmpty() ? null : handlers.getFirst();
        }

        /**
         * The GregTech recipe map, as far as the plan says: {@code metadata.recipeMapId}, else the part of
         * {@code source.rawRecipeId} before its last colon. Null when neither names one (slimmed plans); then
         * {@link #mapSlug()} still may.
         */
        @Nullable
        public String mapId() {
            if (recipeMapId != null && !recipeMapId.isEmpty()) return recipeMapId;
            if (rawRecipeId != null) {
                final int colon = rawRecipeId.lastIndexOf(':');
                if (colon > 0 && rawRecipeId.contains(".recipe.")) return rawRecipeId.substring(0, colon);
                if (colon > 0 && rawRecipeId.startsWith(TANK_MAP + ":")) return TANK_MAP;
            }
            return null;
        }

        /**
         * The recipe map as FF's dataset slugs it into recipe ids ({@code gt-recipe-largechemicalreactor}), from the
         * map id or else from the recipe id itself; null for recipes that are not GregTech's.
         */
        @Nullable
        public String mapSlug() {
            final String map = mapId();
            if (map != null) return FfIds.slug(map);
            final Matcher m = ORACLE_ID.matcher(id);
            return m.find() ? m.group(1) : null;
        }

        /** The map to look the recipe up in, in game: FF's Tank is the Canner. */
        @Nullable
        public String gameMapId() {
            return isPlannerTank() ? "gt.recipe.canner" : mapId();
        }

        /** The map slug to look the recipe up by, in game. */
        @Nullable
        public String gameMapSlug() {
            return isPlannerTank() ? "gt-recipe-canner" : mapSlug();
        }

        /**
         * The programmed circuit the recipe needs: FF's {@code programmedCircuit} when it is a small number, else
         * the number on a non-consumed circuit input; null when the recipe names none.
         */
        @Nullable
        public Integer circuit() {
            if (programmedCircuit != null && CIRCUIT_SETTING.matcher(programmedCircuit)
                .matches()) return Integer.parseInt(programmedCircuit);
            for (final FfSlot in : inputs) {
                if (in.consumed()) continue;
                final Integer c = FfIds.circuitOf(in.id());
                if (c != null) return c;
            }
            return null;
        }
    }

    /** A rate on a card's output (FF's {@code TargetRate}). */
    public record FfTarget(String kind, String resourceId, double perSecond) {}

    /** One more recipe the same machine runs (FF's shared machine section); its handles wear {@code r<n>:}. */
    public record FfSection(String recipeId, Map<Integer, FfSlot> overrides) {}

    /**
     * A card (FF's {@code FactoryNode}). Positions are absolute FF board pixels (boards flattened).
     *
     * @param overrides           FF's {@code recipeInputOverrides}: the slot index's chosen alternative
     * @param energyHatches       0 when FF gave none
     * @param customRatePerSecond custom rate cards only: the dial, when the card remembers one
     */
    public record FfNode(String id, String recipeId, double machineCount, int parallel, String overclockTier,
        int energyHatches, @Nullable String energyHatchType, @Nullable Double powerEuT,
        @Nullable String hatchVoltageTier, @Nullable Double hatchAmps, @Nullable String powerInputMode,
        @Nullable String machineHandlerId, @Nullable String coilTier, Map<String, String> machineConfigTiers,
        Map<Integer, FfSlot> overrides, List<FfSection> extraRecipes, @Nullable Double solvePin,
        @Nullable FfTarget targetOutput, boolean enabled, double x, double y, @Nullable Double customRatePerSecond,
        @Nullable String customRateMode, List<String> hatchSupplies) {}

    /**
     * A wire. Handles look like {@code output:fluid:hydrogen} or
     * {@code r1:input:item:gregtech%3Agt.metaitem.01%402377:1}
     * (see {@link FfHandle}); legacy plans may have none.
     *
     * @param crossForm a loose cell wire (a cell feeding a fluid slot through a hidden canner)
     */
    public record FfEdge(String id, String source, String target, @Nullable String sourceHandle,
        @Nullable String targetHandle, String resourceKind, String resourceId, boolean crossForm) {}

    /** A drawer (FF's {@code FactoryStorage}). Its role comes from its wires; see {@link FfDrawers}. */
    public record FfStorage(String id, String kind, String resourceId, @Nullable String displayName,
        @Nullable String drainMode, @Nullable String bufferMode, @Nullable Double targetPerSecond,
        @Nullable String targetMode, @Nullable String poolTargetMode, @Nullable String poolSide, double x, double y) {}
}

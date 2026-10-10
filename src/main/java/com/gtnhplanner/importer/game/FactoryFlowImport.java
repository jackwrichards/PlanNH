package com.gtnhplanner.importer.game;

import com.gtnhplanner.GtnhPlanner;
import com.gtnhplanner.api.PlanAPI;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Plan;
import com.gtnhplanner.importer.FfConverter;
import com.gtnhplanner.importer.FfImport;
import com.gtnhplanner.importer.FfImportException;

/**
 * Importing a Factory Flow plan in game. Client thread only (NEI). Typical use, from a menu item or a harness
 * endpoint:
 *
 * <pre>
 * {@code
 * try {
 *     FfConverter.Result r = FactoryFlowImport.importAsSlot(GuiScreen.getClipboardString());
 *     // show r.report().summary(), and r.report().entries() on request
 * } catch (FfImportException e) {
 *     // e.getMessage() says, in a player's words, why the text is not a plan
 * }
 * }
 * </pre>
 */
public final class FactoryFlowImport {

    private FactoryFlowImport() {}

    /**
     * Converts pasted text (FF's JSON, a plan code or a plan link) against the game's recipes. Changes nothing: the
     * graph is not on any slot yet.
     *
     * @throws FfImportException when the text is not a Factory Flow plan
     */
    public static FfConverter.Result importFromText(final String text) {
        return FfImport.importFromText(text, new NeiRecipeIndex(), new GameNodeMaker());
    }

    /**
     * Converts pasted text and adds the plan as a new slot named after it, makes that slot the active one and saves.
     * The report is also written to the log.
     *
     * @throws FfImportException when the text is not a Factory Flow plan
     */
    public static FfConverter.Result importAsSlot(final String text) {
        final FfConverter.Result result = importFromText(text);
        addAsSlot(result.graph());
        GtnhPlanner.LOG.info(
            "Factory Flow import of '{}': {}",
            result.graph()
                .getName(),
            result.report());
        return result;
    }

    /** The game's stack for a Factory Flow item id ({@code gregtech:gt.metaitem.01@2377}), or null. */
    @javax.annotation.Nullable
    public static net.minecraft.item.ItemStack item(final String ffId) {
        return GameIds.stackOf(ffId);
    }

    /** The game's fluid for a Factory Flow fluid id (the registry name, lower case), or null. */
    @javax.annotation.Nullable
    public static net.minecraftforge.fluids.FluidStack fluid(final String ffId) {
        final net.minecraftforge.fluids.Fluid f = net.minecraftforge.fluids.FluidRegistry.getFluid(ffId);
        return f == null ? null : new net.minecraftforge.fluids.FluidStack(f, 1000);
    }

    /** A stack's Factory Flow id, as the website writes it ({@code gregtech:gt.metaitem.01@2377}). */
    public static String idOf(final net.minecraft.item.ItemStack stack) {
        return GameIds.itemId(stack);
    }

    /** A fluid's Factory Flow id. */
    public static String idOf(final net.minecraftforge.fluids.FluidStack fluid) {
        return GameIds.fluidId(fluid);
    }

    /** Adds a graph as a new plan slot (its name made unique among the slots), switches to it and saves. */
    public static void addAsSlot(final Graph graph) {
        final Plan plan = Plan.getInstance();
        final String base = graph.getName() == null || graph.getName()
            .isBlank() ? "Imported plan"
                : graph.getName()
                    .trim();
        String name = base;
        for (int n = 2; taken(plan, name); n++) name = base + " " + n;
        graph.setName(name);
        plan.getGraphs()
            .add(graph);
        plan.setActiveIndex(
            plan.getGraphs()
                .size() - 1);
        PlanAPI.save();
    }

    private static boolean taken(final Plan plan, final String name) {
        for (final Graph g : plan.getGraphs()) if (name.equals(g.getName())) return true;
        return false;
    }
}

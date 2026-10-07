package com.sbancuz.plannh.importer;

/**
 * The importer's front door, without the game: pasted text in, a PlanNH graph and a report out. The game's own entry
 * points, which bring NEI's recipes and add the result as a plan slot, are in {@code importer.game.FactoryFlowImport}.
 */
public final class FfImport {

    private FfImport() {}

    /**
     * The plan in pasted text: Factory Flow's JSON download, a plan code ({@code gtnh1....}) or a plan link
     * ({@code https://gtnhplanner.com/#p=gtnh1....}).
     *
     * @throws FfImportException when the text is none of these; the message is for the player
     */
    public static FfPlan read(final String text) {
        return FfPlanParser.parse(FfPlanCode.toJson(text));
    }

    /**
     * Reads and converts a plan against the given recipes and node maker.
     *
     * @throws FfImportException when the text is not a plan
     */
    public static FfConverter.Result importFromText(final String text, final RecipeIndex index, final NodeMaker maker) {
        return FfConverter.convert(read(text), index, maker);
    }
}

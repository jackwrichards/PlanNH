/**
 * The Factory Flow importer's ties to the game: NEI's recipes as a {@link com.gtnhplanner.importer.RecipeIndex},
 * real nodes from a {@link com.gtnhplanner.importer.NodeMaker}, and the entry points that add an import as a plan
 * slot. Client thread only. GregTech classes are touched only in {@code GtRecipeSource}, and only when GregTech is
 * loaded.
 */
@javax.annotation.ParametersAreNonnullByDefault
package com.gtnhplanner.importer.game;

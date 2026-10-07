/**
 * The Factory Flow importer's ties to the game: NEI's recipes as a {@link com.sbancuz.plannh.importer.RecipeIndex},
 * real nodes from a {@link com.sbancuz.plannh.importer.NodeMaker}, and the entry points that add an import as a plan
 * slot. Client thread only. GregTech classes are touched only in {@code GtRecipeSource}, and only when GregTech is
 * loaded.
 */
@javax.annotation.ParametersAreNonnullByDefault
package com.sbancuz.plannh.importer.game;

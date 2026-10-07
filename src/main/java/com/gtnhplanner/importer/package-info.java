/**
 * Importing Factory Flow plans (gtnhplanner.com) into GTNH Planner plans. Everything here is plain Java: the plan model
 * and
 * parser, share codes, the recipe matcher and the converter. The game is reached only through {@link
 * com.gtnhplanner.importer.RecipeIndex} and {@link com.gtnhplanner.importer.NodeMaker}, whose in-game
 * implementations live in {@code importer.game}.
 */
@javax.annotation.ParametersAreNonnullByDefault
package com.gtnhplanner.importer;

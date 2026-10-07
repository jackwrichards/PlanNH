/**
 * Importing Factory Flow plans (gtnhplanner.com) into PlanNH plans. Everything here is plain Java: the plan model and
 * parser, share codes, the recipe matcher and the converter. The game is reached only through {@link
 * com.sbancuz.plannh.importer.RecipeIndex} and {@link com.sbancuz.plannh.importer.NodeMaker}, whose in-game
 * implementations live in {@code importer.game}.
 */
@javax.annotation.ParametersAreNonnullByDefault
package com.sbancuz.plannh.importer;

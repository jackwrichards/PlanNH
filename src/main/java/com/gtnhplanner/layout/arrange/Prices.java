package com.gtnhplanner.layout.arrange;

/**
 * The arrange's dials, as Factory Flow ships them (its router-tuning.ts): what a bend and a crossing cost in px of
 * wire (the proxy and the judge speak this one currency), the air strangers owe each other, and the search's budgets
 * and spacing.
 *
 * @param turn45           a 45-degree bend, in px of wire
 * @param turn90           a right angle
 * @param crossing         crossing another wire once
 * @param islandAir        per px each card pays when its nearest stranger stands closer than six cells
 * @param searchTrials     annealing trials the search may spend (capped by board size)
 * @param finalists        distinct layouts the real router judges at the end of the search
 * @param polishBudget     router questions the polish may ask per layout
 * @param arrangeRowGap    air between stacked cards, in cells
 * @param arrangeColumnGap least corridor between columns, in cells
 * @param arrangeDrawerGap air between a drawer and the machine it rides, in cells
 */
public record Prices(double turn45, double turn90, double crossing, double islandAir, int searchTrials, int finalists,
    int polishBudget, int arrangeRowGap, int arrangeColumnGap, int arrangeDrawerGap) {

    public static final Prices DEFAULT = new Prices(35, 80, 400, 0.5, 20000, 6, 100, 1, 2, 1);
}

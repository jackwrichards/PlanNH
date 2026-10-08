package com.gtnhplanner.power;

/** The picker's columns, in the website's order (registry.ts POWER_GROUPS). */
public enum PowerGroup {

    BURNERS("burners", "Generators", "Singleblocks: one amp of their tier from fuel."),
    ENGINES("engines", "Engines", "The big fuel burners: engines and fuel cells."),
    STEAM("steam", "Boilers and exchangers", "Everything that makes steam."),
    TURBINES("turbines", "Turbines", "Steam, gas and plasma through a rotor."),
    REACTORS("reactors", "Reactors", "Nuclear heat, pebbles and salts."),
    PASSIVE("passive", "Solar", "Power from the sky."),
    ENDGAME("endgame", "Endgame", "Naquadah, fusion, antimatter.");

    /** The website's id, as plans store it. */
    public final String id;
    public final String title;
    public final String blurb;

    PowerGroup(final String id, final String title, final String blurb) {
        this.id = id;
        this.title = title;
        this.blurb = blurb;
    }
}

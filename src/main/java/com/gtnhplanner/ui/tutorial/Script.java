package com.gtnhplanner.ui.tutorial;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;

import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.nei.PlanMenu;
import com.gtnhplanner.ui.BoardScreen;
import com.gtnhplanner.ui.Planner;
import com.gtnhplanner.ui.PlannerSettings;
import com.gtnhplanner.ui.card.CardModel;
import com.gtnhplanner.ui.card.RecipeCard;
import com.gtnhplanner.ui.drawer.DrawerCard;
import com.gtnhplanner.ui.drawer.DrawerModel;
import com.gtnhplanner.ui.note.NoteCard;
import com.gtnhplanner.ui.tutorial.Targets.Target;
import com.gtnhplanner.ui.tutorial.Tour.Beat;
import com.gtnhplanner.ui.tutorial.Tour.Scene;
import com.gtnhplanner.ui.world.Minimap;
import com.gtnhplanner.ui.world.PlannerKeys;

import codechicken.nei.recipe.GuiCraftingRecipe;

/**
 * What the tour shows, in order, from the planner's button to the minimap. Each beat does the clicking first, at a
 * brisk pace, then one note says what it showed. Notes are plain and short: what the thing is, and how to use it.
 * docs/design/tutorial.md has the design.
 */
final class Script {

    private Script() {}

    /** The screen the first beat starts on. */
    static final Scene START = Scene.INVENTORY;

    static List<Beat> beats() {
        final List<Beat> out = new ArrayList<>();
        recipe(out);
        board(out);
        machine(out);
        cards(out);
        overview(out);
        fromNei(out);
        kinds(out);
        power(out);
        tidying(out);
        plans(out);
        minimap(out);
        return out;
    }

    private static Beat beat(final List<Beat> out) {
        final Beat b = new Beat();
        out.add(b);
        return b;
    }

    // region What the beats point at

    private static final Target PLANNER_KEY = Targets.plannerButton();

    private static boolean boardOpen() {
        return Targets.board() != null;
    }

    private static boolean popupOpen() {
        return Targets.popup()
            .rect() != null;
    }

    /** A sticky note is being written on (keys typed now go to it, and Esc only ends the writing). */
    private static boolean writingNote() {
        final BoardScreen b = Targets.board();
        return b != null && b.canvas()
            .notes()
            .values()
            .stream()
            .anyMatch(NoteCard::editing);
    }

    /** A card making {@code what} (a recipe's, not a generator's). */
    private static Predicate<Node> making(final String what) {
        return n -> n.powerSource == null && n.outputs.stream()
            .anyMatch(
                p -> p.getDisplayName()
                    .toLowerCase(Locale.ROOT)
                    .contains(what));
    }

    private static final Predicate<Node> ACID = making("hydrochloric");
    private static final Predicate<Node> BENZENE = making("benzene");
    private static final Predicate<Node> TURBINE = n -> "gas-turbine".equals(n.powerSource);

    /** The acid cards, top left first: the last is the clone (a clone lands below and right of its card). */
    private static List<Node> acids() {
        final List<Node> out = new ArrayList<>();
        final BoardScreen b = Targets.board();
        if (b == null) return out;
        for (final Node n : b.session()
            .graph()
            .getNodes()) if (ACID.test(n)) out.add(n);
        out.sort(Comparator.comparingInt((Node n) -> n.x + n.y));
        return out;
    }

    /** The copy: the acid card with no wires (the one it was cloned from has its drawers). */
    private static final Predicate<Node> CLONE = n -> {
        final BoardScreen b = Targets.board();
        if (b == null || !ACID.test(n) || acids().size() < 2) return false;
        final com.gtnhplanner.data.flowchart.Graph g = b.session()
            .graph();
        for (final com.gtnhplanner.data.flowchart.Edge e : g.getEdges())
            if (e.sourceNodeId.equals(n.id) || e.targetNodeId.equals(n.id)) return false;
        for (final com.gtnhplanner.data.flowchart.Drawer d : g.getDrawers())
            for (final com.gtnhplanner.data.flowchart.Drawer.Link l : d.getLinks()) if (l.nodeId()
                .equals(n.id)) return false;
        return true;
    };

    /** The card the clone was made from: the acid card with its drawers. */
    private static final Predicate<Node> ORIGINAL = n -> ACID.test(n) && !CLONE.test(n);

    /** The acid recipes share one machine. */
    private static boolean merged() {
        final BoardScreen b = Targets.board();
        if (b == null) return false;
        for (final Node n : acids()) if (b.session()
            .sharedOf(n.id) != null) return true;
        return false;
    }

    /** One of NEI's keys as the player has it bound ("gui.recipe": R, "gui.usage": U). */
    private static String neiKey(final String binding, final String fallback) {
        try {
            final String k = codechicken.nei.NEIClientConfig.getKeyName(binding);
            return k == null || k.isEmpty() ? fallback : k;
        } catch (final RuntimeException | LinkageError e) {
            return fallback;
        }
    }

    private static boolean hasCard(final Predicate<Node> which) {
        return Targets.card(which) != null;
    }

    private static CardModel model(final Predicate<Node> which) {
        final RecipeCard c = Targets.card(which);
        return c == null ? null : c.model();
    }

    private static boolean hasDrawer(final String label) {
        return drawer(label) != null;
    }

    private static DrawerModel drawer(final String label) {
        final BoardScreen b = Targets.board();
        if (b == null) return null;
        for (final DrawerModel d : b.session()
            .drawerModels()
            .values())
            if (d.label.toLowerCase(Locale.ROOT)
                .contains(label.toLowerCase(Locale.ROOT))) return d;
        return null;
    }

    private static boolean solved() {
        final BoardScreen b = Targets.board();
        return b != null && !b.session()
            .solving();
    }

    /** NEI's entry for a fluid, once searched for: the fluid itself, else a cell of it. */
    private static Target fluidItem(final String name) {
        return Targets.neiItem(fluid(name), cell(name));
    }

    private static Predicate<ItemStack> fluid(final String name) {
        return s -> TourRecipes.name(s)
            .equals(name);
    }

    private static Predicate<ItemStack> cell(final String name) {
        return s -> TourRecipes.name(s)
            .equals(name + " cell");
    }

    /**
     * Opens NEI's page of what makes a fluid: its own entry in NEI's list, else its cell's. Looked up in NEI's whole
     * list, not the searched one, which a quick search may not have caught up with yet.
     */
    private static void recipesFor(final String name) {
        final List<ItemStack> items = codechicken.nei.ItemList.items;
        if (items == null) return;
        final int i = Targets.neiIndex(items, fluid(name), cell(name));
        if (i < 0) return;
        final ItemStack s = items.get(i)
            .copy();
        if (Planner.isPlanner(Minecraft.getMinecraft().currentScreen)) Planner.lookUp(s, false);
        else GuiCraftingRecipe.openRecipeGui("item", s);
    }

    private static TourRecipes.Found acidRecipe() {
        return TourRecipes.onPage("large chemical", List.of("hydrogen", "chlorine"), "hydrochloric acid");
    }

    private static TourRecipes.Found benzeneRecipe() {
        final TourRecipes.Found f = TourRecipes.onPage("distill", List.of("wood tar"), "benzene");
        return f != null ? f : TourRecipes.onPage("", List.of(), "benzene");
    }

    private static Target planButton(final java.util.function.Supplier<TourRecipes.Found> recipe) {
        return () -> {
            final TourRecipes.Found f = recipe.get();
            return f == null ? null
                : Targets.planButton(f.handler(), f.recipe())
                    .rect();
        };
    }

    private static final Target ACID_PLAN_BUTTON = planButton(Script::acidRecipe);
    private static final Target BENZENE_PLAN_BUTTON = planButton(Script::benzeneRecipe);

    private static Target rail(final String id) {
        return Targets.boardPart("rail:" + id);
    }

    private static Target card(final Predicate<Node> which, final RecipeCard.Part part) {
        return Targets.card(which, part);
    }

    private static Target drawerPart(final String label, final DrawerCard.Part part) {
        return Targets.drawer(label, part);
    }

    /** Sets a number box: opens it, types, and presses Enter. */
    private static Beat setNumber(final Beat b, final Target box, final String value) {
        return b.opens(box)
            .type(Steps::focusedField, value)
            .pause(150)
            .commit();
    }

    // endregion

    // region From NEI to a card

    private static void recipe(final List<Beat> out) {
        beat(out).pause(250)
            .note(PLANNER_KEY, "This button opens *GTNH Planner*.");

        // The reactor's tab is clicked when it shows on the tab strip; the page then turns to the acid's recipe either
        // way (a strip with many tabs may have it on a later page of tabs).
        final Target lcrTab = Targets.recipeTab(
            h -> h.getRecipeName()
                .toLowerCase(Locale.ROOT)
                .contains("large chemical"));
        beat(out).click(Targets.neiSearch())
            .type(Steps::neiSearch, "hydrochloric acid")
            .pause(200)
            .hover(fluidItem("hydrochloric acid"), 200)
            .key(neiKey("gui.recipe", "R"), () -> recipesFor("hydrochloric acid"))
            .until(() -> Targets.recipePage() != null, 4000)
            .pause(200)
            .when(() -> lcrTab.rect() != null, Steps.click(lcrTab))
            .run(() -> TourRecipes.open(acidRecipe()))
            .until(() -> ACID_PLAN_BUTTON.rect() != null, 3000)
            .note(ACID_PLAN_BUTTON, "This button *adds a recipe to a plan*.");

        final Target lcrRow = Targets.planMenuRow("Large Chemical");
        beat(out).click(ACID_PLAN_BUTTON)
            .until(PlanMenu.INSTANCE::isOpen, 2000)
            .hover(Targets.planMenuRow("New plan"), 200)
            .click(Targets.planMenuRow("New plan"))
            .until(() -> lcrRow.rect() != null || boardOpen(), 2000)
            .when(() -> lcrRow.rect() != null, Steps.seq(Steps.hover(lcrRow, 200), Steps.click(lcrRow)))
            .until(() -> hasCard(ACID), 5000)
            .rest()
            .note(
                card(ACID, RecipeCard.Part.BODY),
                "This is a *recipe in a plan*. Right now it isn't working because it isn't wired up.");
    }

    // endregion

    // region Wiring and targets

    private static void board(final List<Beat> out) {
        final Target hydrogen = Targets.port(ACID, false, "Hydrogen"), chlorine = Targets.port(ACID, false, "Chlorine"),
            acid = Targets.port(ACID, true, "Hydrochloric");
        beat(out).wheel(Targets.canvasArea(), -3)
            .pause(150)
            .pan(Targets.emptyBoard(60, 60), 0, 30)
            .drag(hydrogen, Targets.shift(hydrogen, -130, -40))
            .until(() -> hasDrawer("Hydrogen"), 3000)
            .drag(chlorine, Targets.shift(chlorine, -130, 50))
            .until(() -> hasDrawer("Chlorine"), 3000)
            .drag(acid, Targets.shift(acid, 130, 0))
            .until(() -> hasDrawer("Hydrochloric"), 3000)
            .rest()
            .note(
                Targets.around(
                    drawerPart("Hydrogen", DrawerCard.Part.BODY),
                    drawerPart("Chlorine", DrawerCard.Part.BODY),
                    drawerPart("Hydrochloric", DrawerCard.Part.BODY)),
                "Drag out inputs and outputs so things can enter and leave the plan. *Notice nothing runs yet.*");

        final Target count = card(ACID, RecipeCard.Part.MACHINES);
        setNumber(beat(out), count, "1").until(() -> model(ACID) != null && model(ACID).pinned, 3000)
            .until(Script::solved, 3000)
            .rest()
            .note(count, "*Pin a recipe's machine count* so the plan can solve. Everything else is worked out.");

        setNumber(beat(out), count, "").until(() -> model(ACID) != null && !model(ACID).pinned, 3000)
            .opens(drawerPart("Hydrochloric", DrawerCard.Part.RATE))
            .type(Steps::focusedField, "1000")
            .pause(150)
            .commit()
            .until(() -> model(ACID) != null && model(ACID).machines > 0, 4000)
            .until(Script::solved, 3000)
            .rest()
            .note(
                drawerPart("Hydrochloric", DrawerCard.Part.RATE),
                "Or *pin an output*. This plan now solves for 1k hydrochloric acid per second, and the unpinned machine count shows how many machines that takes.");
    }

    // endregion

    // region The machine

    private static void machine(final List<Beat> out) {
        final Target tier = card(ACID, RecipeCard.Part.TIER), amps = card(ACID, RecipeCard.Part.AMPS);
        setNumber(
            beat(out).click(tier)
                .pause(200)
                .click(tier)
                .until(Script::solved, 3000),
            amps,
            "2").until(Script::solved, 3000)
                .rest()
                .note(Targets.around(tier, amps), "Set the *tier* and *amps* here.");

        beat(out).pause(150)
            .note(
                Targets.around(card(ACID, RecipeCard.Part.POWER), card(ACID, RecipeCard.Part.CIRCUIT)),
                "Power use is shown here, but machines *don't need to be wired to power*. The circuit is also shown.");

        beat(out).opens(card(ACID, RecipeCard.Part.SETTINGS))
            .note(
                Targets.popup(),
                "This is the *settings panel*. Pin settings to show them on the card, so you don't have to open this menu.");
    }

    // endregion

    // region Cards

    private static void cards(final List<Beat> out) {
        beat(out).escIf(Script::popupOpen)
            .opens(card(ACID, RecipeCard.Part.ACTIONS))
            .hover(Targets.popupRow("Clone"), 200)
            .click(Targets.popupRow("Clone"))
            .until(() -> acids().size() == 2, 3000)
            .drag(
                Targets.point(card(CLONE, RecipeCard.Part.BODY), 0.96f, 0.95f),
                Targets.emptyBoard(330, 230, 0.96f, 0.95f))
            .rest()
            .note(card(CLONE, RecipeCard.Part.BODY), "You can *clone* machines easily.");

        // A card clicked without moving it is selected; Shift adds the next to it; two that one machine can run
        // offer to combine.
        final Target selection = Targets.boardPart("selection");
        beat(out).click(Targets.point(card(ORIGINAL, RecipeCard.Part.BODY), 0.9f, 0.85f))
            .pause(150)
            .shiftClick(Targets.point(card(CLONE, RecipeCard.Part.BODY), 0.9f, 0.85f))
            .until(() -> selection.rect() != null, 2000)
            .hover(selection, 250)
            .click(selection)
            .until(Script::merged, 3000)
            .rest()
            .note(
                card(ACID, RecipeCard.Part.BODY),
                "Select both and *merge* them. Now two recipes run on one machine.");

        // Undone back to the one card: the merge, the move and the clone.
        beat(out).clickUntil(Targets.topKey("undo"), () -> acids().size() == 1, 5)
            .rest()
            .note(Targets.around(Targets.topKey("undo"), Targets.topKey("redo")), "*Undo* and *redo* are up here.");
    }

    // endregion

    // region The overview

    private static void overview(final List<Beat> out) {
        beat(out).note(
            rail("all"),
            "This is the *overview* of everything in the plan. Click something to go to it, and set pinned rates at a glance.");

        // Each key is turned all the way round, back to where it was.
        final Target rate = Targets.topKey("rate"), power = Targets.topKey("power"), peak = Targets.topKey("peak");
        final Beat units = beat(out);
        for (int i = 0; i < 4; i++) units.click(rate)
            .pause(120);
        units.click(power)
            .pause(250)
            .click(power)
            .pause(120)
            .click(peak)
            .pause(250)
            .click(peak)
            .rest()
            .note(Targets.around(rate, power, peak), "These buttons change *the way numbers are shown*.");
    }

    // endregion

    // region From NEI's list

    private static void fromNei(final List<Beat> out) {
        final Target spot = Targets.emptyBoard(200, 90);
        beat(out).click(Targets.neiSearch())
            .type(Steps::neiSearch, "benzene")
            .pause(200)
            .drag(fluidItem("benzene"), spot)
            .pause(150)
            .when(() -> codechicken.nei.ItemPanels.itemPanel.draggedStack != null, Steps.click(spot))
            .until(Script::popupOpen, 2500)
            .hover(Targets.popupRow("Add as a product"), 200)
            .click(Targets.popupRow("Add as a product"))
            .until(() -> hasDrawer("Benzene"), 3000)
            .rest()
            .note(drawerPart("Benzene", DrawerCard.Part.BODY), "You can also *drag things in from NEI*.");

        final String recipeKey = neiKey("gui.recipe", "R"), usesKey = neiKey("gui.usage", "U");
        beat(out).hover(drawerPart("Benzene", DrawerCard.Part.BODY), 200)
            .key(recipeKey, () -> {
                final DrawerModel d = drawer("Benzene");
                if (d != null) Planner.lookUp(com.gtnhplanner.ui.Resources.lookupStack(d.item, d.fluid), false);
            })
            .until(() -> Targets.recipePage() != null, 4000)
            .pause(200)
            .run(() -> TourRecipes.open(benzeneRecipe()))
            .until(() -> BENZENE_PLAN_BUTTON.rect() != null, 3000)
            .click(BENZENE_PLAN_BUTTON)
            .until(PlanMenu.INSTANCE::isOpen, 2000)
            .hover(Targets.planMenuRow("Plan 2"), 200)
            .click(Targets.planMenuRow("Plan 2"))
            .until(() -> PlanMenu.INSTANCE.isOpen() || hasCard(BENZENE), 2000)
            .when(PlanMenu.INSTANCE::isOpen, Steps.click(Targets.planMenuRow("")))
            .until(() -> hasCard(BENZENE), 5000)
            .drag(Targets.port(BENZENE, true, "Benzene"), drawerPart("Benzene", DrawerCard.Part.BODY))
            .until(() -> drawer("Benzene") != null && drawer("Benzene").linked, 3000)
            .rest()
            .note(
                card(BENZENE, RecipeCard.Part.BODY),
                "Treat this planner *just like NEI*: press " + recipeKey + " or " + usesKey + " on things.");
    }

    // endregion

    // region Byproducts and trash

    /** The benzene recipe's other output the next beats drag out, fixed when they start: its name. */
    private static String side;

    /** The benzene recipe's first output that is neither benzene nor wired yet; null for none. */
    private static String sideOutput() {
        final CardModel m = model(BENZENE);
        if (m == null) return null;
        for (final CardModel.PortView p : m.outputs) if (!p.wired() && !p.name()
            .toLowerCase(Locale.ROOT)
            .contains("benzene")) return p.name();
        return null;
    }

    private static Target sidePart(final DrawerCard.Part part) {
        return () -> side == null ? null
            : Targets.drawer(side, part)
                .rect();
    }

    /** The byproduct drawer on the board, by its name: the side output, for a beat started where the last left it. */
    private static String byproduct() {
        final BoardScreen b = Targets.board();
        if (b == null) return null;
        for (final DrawerModel d : b.session()
            .drawerModels()
            .values()) if (d.kind == com.gtnhplanner.data.flowchart.Drawer.Kind.BYPRODUCT) return d.label;
        return null;
    }

    private static boolean sideIs(final com.gtnhplanner.data.flowchart.Drawer.Kind kind) {
        final DrawerModel d = side == null ? null : drawer(side);
        return d != null && d.kind == kind;
    }

    private static void kinds(final List<Beat> out) {
        final Target port = () -> side == null ? null
            : Targets.port(BENZENE, true, side)
                .rect();
        beat(out).run(() -> side = sideOutput())
            .drag(port, Targets.emptyBoard(130, 70))
            .until(() -> side != null && hasDrawer(side), 3000)
            .pause(200)
            .click(sidePart(DrawerCard.Part.CYCLE))
            .until(() -> sideIs(com.gtnhplanner.data.flowchart.Drawer.Kind.BYPRODUCT), 2000)
            .rest()
            .note(
                sidePart(DrawerCard.Part.CYCLE),
                "This key switches a product drawer to a *byproduct*: surplus, never calculated for.");
        beat(out).run(() -> { if (side == null || !hasDrawer(side)) side = byproduct(); })
            .click(sidePart(DrawerCard.Part.CYCLE))
            .until(() -> sideIs(com.gtnhplanner.data.flowchart.Drawer.Kind.TRASH), 2000)
            .until(Script::solved, 3000)
            .rest()
            .note(sidePart(DrawerCard.Part.BODY), "Or to *trash*: it voids what comes in, and leaves your outputs.");
    }

    // endregion

    // region Power

    private static void power(final List<Beat> out) {
        beat(out).click(Targets.topKey("nonrecipe"))
            .until(
                () -> Targets.boardPart("picker:sheet")
                    .rect() != null,
                2000)
            .note(
                Targets.boardPart("picker:sheet"),
                "This section is for *non-recipe machines*, mostly power generation.");

        // The turbine at HV burns the tower's benzene; everything else the tower takes and makes gets a drawer, and the
        // turbine's power one too, so the whole plan runs. The tower pinned to one says how many turbines that feeds.
        final Target turbineTier = card(TURBINE, RecipeCard.Part.TIER);
        final Beat b = beat(out).hover(Targets.boardPart("picker:Gas Turbine"), 200)
            .click(Targets.boardPart("picker:Gas Turbine"))
            .until(() -> hasCard(TURBINE), 3000)
            .click(turbineTier)
            .pause(200)
            .click(turbineTier)
            .click(drawerPart("Benzene", DrawerCard.Part.DELETE))
            .until(() -> !hasDrawer("Benzene"), 2000)
            .drag(Targets.port(BENZENE, true, "Benzene"), card(TURBINE, RecipeCard.Part.BODY))
            .pause(200);
        drawerFor(b, Targets.port(TURBINE, true, "EU"), true, "EU");
        for (final String made : new String[] { "Creosote", "Phenol", "Toluene", "Dimethylbenzene" })
            drawerFor(b, Targets.port(BENZENE, true, made), true, made);
        drawerFor(b, Targets.port(BENZENE, false, "Wood Tar"), false, "Wood Tar");
        setNumber(b, card(BENZENE, RecipeCard.Part.MACHINES), "1")
            .until(() -> model(BENZENE) != null && model(BENZENE).pinned, 3000)
            .until(Script::solved, 3000)
            .rest()
            .note(
                card(TURBINE, RecipeCard.Part.BODY),
                "You can also *plan power*. This distillation tower makes enough benzene to run this many HV gas turbines.");
    }

    /**
     * Drags a port out to a drawer of its own, on empty board beside it; a port with one already (the output the
     * byproduct and trash beats used) keeps it, as a second would be refused.
     */
    private static void drawerFor(final Beat b, final Target port, final boolean output, final String label) {
        b.when(
            () -> !hasDrawer(label),
            Steps.seq(
                Steps.drag(port, Targets.freeNear(port, output, output ? 70 : -70, 0)),
                Steps.until(() -> hasDrawer(label), 2500)));
    }

    // endregion

    // region Arrange and notes

    private static void tidying(final List<Beat> out) {
        beat(out).click(Targets.topKey("arrange"))
            .until(
                () -> Targets.board() != null && !Targets.board()
                    .canvas()
                    .arranging(),
                20000)
            .pause(200)
            .click(Targets.topKey("fit"))
            .pause(300)
            .rest()
            .note(
                Targets.around(Targets.topKey("arrange"), Targets.topKey("fit")),
                "You can *auto-arrange* a plan and *focus the camera* on it.");

        final Target empty = Targets.emptyBoard(200, 140);
        final Target note = Targets.note(null, NoteCard.Part.BODY);
        final Target grip = Targets.note(null, NoteCard.Part.RESIZE);
        final Target bigger = Targets.note(null, NoteCard.Part.BIGGER);
        beat(out).wheel(empty, 3)
            .pause(150)
            .opensMenu(empty)
            .click(Targets.popupRow("Add a sticky note"))
            .until(() -> note.rect() != null, 2000)
            .until(Script::writingNote, 1500)
            .when(Script::writingNote, Steps.keys("Hydrogen comes from the electrolyzers by the door."))
            .pause(200)
            .escIf(Script::writingNote)
            .pause(150)
            .drag(Targets.point(note, 0.4f, 0.3f), Targets.shift(Targets.point(note, 0.4f, 0.3f), 30, 20))
            .drag(Targets.point(grip, 0.7f, 0.7f), Targets.shift(Targets.point(grip, 0.7f, 0.7f), 40, 10))
            .opensMenu(Targets.point(note, 0.5f, 0.6f))
            .click(Targets.popupRow("Colour"))
            .until(
                () -> Targets.popupRow("Lime")
                    .rect() != null,
                2000)
            .click(Targets.popupRow("Lime"))
            .click(bigger)
            .pause(150)
            .click(bigger)
            .pause(150)
            .click(bigger)
            .rest()
            .note(note, "Annotations are possible with *sticky notes*. Right-click anywhere to make one.");
    }

    // endregion

    // region Plans and the Library

    private static void plans(final List<Beat> out) {
        beat(out).opens(Targets.boardPart("tab:+"))
            .note(Targets.around(Targets.topKey("tabs"), Targets.popup()), "Here's how you make a *new plan*.");

        beat(out).escIf(Script::popupOpen)
            .click(Targets.topKey("library"))
            .until(
                () -> Targets.board() != null && Targets.board()
                    .libraryOpen(),
                2000)
            .click(Targets.boardPart("library:shelf:public"))
            .until(Script::publicLoaded, 8000)
            .click(Targets.boardPart("library:search"))
            .type(() -> text -> {
                final BoardScreen b = Targets.board();
                if (b != null && b.key("libraryView") instanceof final com.gtnhplanner.ui.library.LibraryView v)
                    v.searchField()
                        .setText(text);
            }, "oil")
            .pause(400)
            .rest()
            .note(
                Targets.boardPart("library:all"),
                "The *Library* is where people upload plans. You can browse them here.");
    }

    private static boolean libraryOpen() {
        return Targets.board() != null && Targets.board()
            .libraryOpen();
    }

    private static boolean publicLoaded() {
        final BoardScreen b = Targets.board();
        return b != null && b.key("libraryView") instanceof final com.gtnhplanner.ui.library.LibraryView v
            && v.publicLoaded();
    }

    // endregion

    // region The minimap

    private static void minimap(final List<Beat> out) {
        // The keys as the player has them bound.
        final boolean arrows = PlannerKeys.minimapOnArrows();
        final String move = arrows ? "The arrow keys move it"
            : PlannerKeys.minimapKey("up") + ", "
                + PlannerKeys.minimapKey("left")
                + ", "
                + PlannerKeys.minimapKey("down")
                + " and "
                + PlannerKeys.minimapKey("right")
                + " move it";
        final String in = PlannerKeys.minimapKey("in"), outKey = PlannerKeys.minimapKey("out");
        beat(out).when(Script::libraryOpen, Steps.click(Targets.topKey("library")))
            .until(() -> !libraryOpen(), 2000)
            .opens(Targets.topKey("settings"))
            .click(Targets.settingsSection("Minimap"))
            .clickUntil(Targets.popupRow("Show the minimap"), () -> "On".equals(Targets.setting("Show the minimap")), 2)
            .clickUntil(Targets.settingChoice("Size", "Large"), () -> "Large".equals(Targets.setting("Size")), 2)
            .clickUntil(Targets.settingChoice("Shape", "Square"), () -> "Square".equals(Targets.setting("Shape")), 2)
            .clickUntil(
                Targets.settingChoice("Position", "Top right"),
                () -> "Top right".equals(Targets.setting("Position")),
                2)
            .pause(200)
            .esc()
            .rest()
            .key(
                "Esc",
                () -> Minecraft.getMinecraft()
                    .displayGuiScreen(new TourScreen()))
            .until(() -> Minecraft.getMinecraft().currentScreen instanceof TourScreen, 2000)
            .note(
                Targets.minimap(),
                "The *minimap* shows your plan while you play. Set it up under the gear. " + move
                    + ", "
                    + outKey
                    + " and "
                    + in
                    + " zoom, and "
                    + PlannerKeys.minimapKey("show")
                    + " hides it.")
            .holdKey(arrows ? "→" : PlannerKeys.minimapKey("right"), 450, () -> Minimap.INSTANCE.pan(1, 0))
            .pause(200)
            .holdKey(arrows ? "←" : PlannerKeys.minimapKey("left"), 450, () -> Minimap.INSTANCE.pan(-1, 0))
            .pause(200)
            .key(in, () -> PlannerSettings.setMinimapZoomIndex(PlannerSettings.minimapZoomIndex() + 1))
            .pause(400)
            .key(outKey, () -> PlannerSettings.setMinimapZoomIndex(PlannerSettings.minimapZoomIndex() - 1))
            .pause(200)
            .run(Minimap.INSTANCE::recentre);

        // Back on the board, at the Feedback key.
        beat(out).run(com.gtnhplanner.ui.Planner::open)
            .until(() -> Targets.board() != null, 3000)
            .rest()
            .note(
                Targets.topKey("feedback"),
                "That's everything! Found a bug, or have an idea? *Feedback* sends it from the game. Questions about your own setup do well in GTNH Planner's thread on the *GT New Horizons Discord*.");
    }

    // endregion
}

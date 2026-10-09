package com.gtnhplanner.ui.tutorial;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.inventory.GuiContainer;
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
import com.gtnhplanner.ui.tutorial.Tour.Chapter;
import com.gtnhplanner.ui.tutorial.Tour.Scene;
import com.gtnhplanner.ui.world.Minimap;

import codechicken.nei.recipe.GuiCraftingRecipe;

/**
 * What the tour shows, in order: from opening the planner to the minimap, one chapter per thing a new player needs.
 * Captions are short and plain, as a person says them; each beat's steps act out its caption. The design and the
 * reasons for the order are in docs/design/tutorial.md.
 */
final class Script {

    private Script() {}

    static List<Chapter> chapters() {
        final List<Chapter> out = new ArrayList<>();
        openingThePlanner(out);
        firstRecipe(out);
        readingACard(out);
        wiring(out);
        target(out);
        settings(out);
        actions(out);
        overview(out);
        units(out);
        fromTheList(out);
        power(out);
        arrange(out);
        notes(out);
        plans(out);
        library(out);
        minimap(out);
        return out;
    }

    private static Chapter chapter(final List<Chapter> out, final String title, final Scene scene) {
        final Chapter c = new Chapter(title, scene);
        out.add(c);
        return c;
    }

    // region What the chapters point at

    private static final Target PLANNER_KEY = Targets.plannerButton();

    private static boolean boardOpen() {
        return Targets.board() != null;
    }

    private static boolean inventoryOpen() {
        final net.minecraft.client.gui.GuiScreen s = Minecraft.getMinecraft().currentScreen;
        return s instanceof GuiContainer && !Planner.isPlanner(s) && !(s instanceof codechicken.nei.recipe.GuiRecipe);
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

    // endregion

    // region 1. Opening the planner

    private static void openingThePlanner(final List<Chapter> out) {
        final Chapter c = chapter(out, "Opening the planner", Scene.INVENTORY);
        c.beat("This button opens GTNH Planner. It sits at the bottom left of any inventory, beside NEI's search.")
            .pause(300)
            .ring(PLANNER_KEY)
            .hover(PLANNER_KEY, 1600);
        c.beat("Click it to open the planner. Click it again to go back.")
            .ring(PLANNER_KEY)
            .click(PLANNER_KEY)
            .until(Script::boardOpen, 4000)
            .pause(1600)
            .ring(PLANNER_KEY)
            .click(PLANNER_KEY)
            .until(Script::inventoryOpen, 4000)
            .ring(null);
    }

    // endregion

    // region 2. Your first recipe

    private static void firstRecipe(final List<Chapter> out) {
        final Chapter c = chapter(out, "Your first recipe", Scene.INVENTORY);
        c.beat("Everything starts in NEI. Search for what you want to make: hydrochloric acid.")
            .ring(Targets.neiSearch())
            .click(Targets.neiSearch())
            .type(Steps::neiSearch, "hydrochloric acid")
            .ring(null)
            .pause(500);
        c.beat("Point at it and press R to see the recipes that make it.")
            .hover(fluidItem("hydrochloric acid"), 1100)
            .key("R", () -> recipesFor("hydrochloric acid"))
            .until(() -> Targets.recipePage() != null, 4000)
            .pause(600);
        // The reactor's tab is clicked when it shows on the tab strip; the page then turns to the acid's recipe either
        // way (a strip with many tabs may have it on a later page of tabs, where it shows once chosen).
        final Target lcrTab = Targets.recipeTab(
            h -> h.getRecipeName()
                .toLowerCase(Locale.ROOT)
                .contains("large chemical"));
        c.beat("Pick the Large Chemical Reactor's tab. Hydrogen and chlorine go in, hydrochloric acid comes out.")
            .pause(400)
            .when(() -> lcrTab.rect() != null, Steps.seq(Steps.ring(lcrTab), Steps.click(lcrTab)))
            .run(() -> TourRecipes.open(acidRecipe()))
            .until(() -> ACID_PLAN_BUTTON.rect() != null, 3000)
            .ring(lcrTab)
            .hover(lcrTab, 700)
            .ring(null);
        c.beat("This button adds a recipe to a plan.")
            .ring(ACID_PLAN_BUTTON)
            .hover(ACID_PLAN_BUTTON, 1800);
        c.beat("Click it, then pick a plan: a new one, or one you already have.")
            .click(ACID_PLAN_BUTTON)
            .until(PlanMenu.INSTANCE::isOpen, 2000)
            .ring(Targets.planMenuRow("New plan"))
            .hover(Targets.planMenuRow("New plan"), 800)
            .click(Targets.planMenuRow("New plan"));
        c.beat("Then the machine that runs it.")
            .until(
                () -> Targets.planMenuRow("Large Chemical")
                    .rect() != null || boardOpen(),
                2000)
            .when(
                () -> Targets.planMenuRow("Large Chemical")
                    .rect() != null,
                Steps.seq(
                    Steps.ring(Targets.planMenuRow("Large Chemical")),
                    Steps.hover(Targets.planMenuRow("Large Chemical"), 900),
                    Steps.click(Targets.planMenuRow("Large Chemical"))))
            .until(() -> hasCard(ACID), 5000)
            .ring(null);
        c.beat("There it is: the recipe as a card, in a plan of its own.")
            .until(() -> hasCard(ACID), 3000)
            .rest()
            .spot(card(ACID, RecipeCard.Part.BODY))
            .pause(800);
        c.beat("Shift-click the plan button to skip both menus. It remembers the machine you picked.")
            .spot(null);
    }

    // endregion

    // region 3. Reading a card

    private static void readingACard(final List<Chapter> out) {
        final Chapter c = chapter(out, "Reading a card", Scene.BOARD);
        c.beat("A card is one recipe, running on one kind of machine. This one runs in a Large Chemical Reactor.")
            .rest()
            .spot(card(ACID, RecipeCard.Part.BODY));
        c.beat("What it takes is on the left: hydrogen and chlorine.")
            .spot(Targets.around(Targets.port(ACID, false, "Hydrogen"), Targets.port(ACID, false, "Chlorine")));
        c.beat("What it makes is on the right: hydrochloric acid.")
            .spot(Targets.port(ACID, true, "Hydrochloric"));
        c.beat("This says how many machines it needs. Nothing is wired yet, so it can't tell.")
            .spot(card(ACID, RecipeCard.Part.MACHINES));
    }

    // endregion

    // region 4. Wiring it up

    private static void wiring(final List<Chapter> out) {
        final Chapter c = chapter(out, "Wiring it up", Scene.BOARD);
        final Target hydrogen = Targets.port(ACID, false, "Hydrogen"), chlorine = Targets.port(ACID, false, "Chlorine"),
            acid = Targets.port(ACID, true, "Hydrochloric");
        c.beat("First, some room: scroll to zoom out. Drag empty board to move around.")
            .wheel(Targets.canvasArea(), -3)
            .pause(400)
            .pan(Targets.emptyBoard(60, 60), 0, 30)
            .rest();
        c.beat("Nothing happens until things are wired up. Drag a port out onto empty board to make a drawer.")
            .ring(hydrogen)
            .drag(hydrogen, Targets.shift(hydrogen, -130, -40))
            .until(() -> hasDrawer("Hydrogen"), 3000)
            .ring(null)
            .rest();
        c.beat("That drawer brings in hydrogen. The same for chlorine.")
            .ring(chlorine)
            .drag(chlorine, Targets.shift(chlorine, -130, 50))
            .until(() -> hasDrawer("Chlorine"), 3000)
            .ring(null)
            .rest();
        c.beat("And a drawer on the right takes the acid away.")
            .ring(acid)
            .drag(acid, Targets.shift(acid, 130, 0))
            .until(() -> hasDrawer("Hydrochloric"), 3000)
            .ring(null)
            .rest();
        c.beat("Wired up, and still nothing: the plan has nothing to aim for yet.")
            .rest()
            .spot(card(ACID, RecipeCard.Part.MACHINES))
            .pause(1800)
            .spot(Targets.boardPart("notices"));
    }

    // endregion

    // region 5. Giving it a target

    private static void target(final List<Chapter> out) {
        final Chapter c = chapter(out, "Giving it a target", Scene.BOARD);
        final Target count = card(ACID, RecipeCard.Part.MACHINES);
        c.beat("One way to give it a target: click the machine count and pin it, say to one machine.")
            .ring(count)
            .opens(count)
            .type(Steps::focusedField, "1")
            .pause(300)
            .commit()
            .until(() -> model(ACID) != null && model(ACID).pinned, 3000)
            .ring(null);
        c.beat("Now every number fills in: what it takes and what it makes, all from that one machine.")
            .until(Script::solved, 3000)
            .rest()
            .spot(
                Targets.around(
                    Targets.drawer("Hydrogen", DrawerCard.Part.BODY),
                    Targets.drawer("Chlorine", DrawerCard.Part.BODY),
                    Targets.drawer("Hydrochloric", DrawerCard.Part.BODY)));
        c.beat("The other way: clear the pin, and ask for an amount of product instead.")
            .ring(count)
            .opens(count)
            .type(Steps::focusedField, "")
            .pause(300)
            .commit()
            .until(() -> model(ACID) != null && !model(ACID).pinned, 3000)
            .ring(Targets.drawer("Hydrochloric", DrawerCard.Part.RATE))
            .opens(Targets.drawer("Hydrochloric", DrawerCard.Part.RATE))
            .type(Steps::focusedField, "1000")
            .pause(300)
            .commit()
            .until(() -> model(ACID) != null && model(ACID).machines > 0, 4000)
            .ring(null);
        c.beat("It works out how many machines that takes, and what goes in.")
            .until(Script::solved, 3000)
            .rest()
            .spot(count);
        c.beat("Pin what you know, and the planner solves the rest.")
            .spot(null);
    }

    // endregion

    // region 6. Machine settings

    private static void settings(final List<Chapter> out) {
        final Chapter c = chapter(out, "Machine settings", Scene.BOARD);
        final Target tier = card(ACID, RecipeCard.Part.TIER), count = card(ACID, RecipeCard.Part.MACHINES);
        c.beat(
            "The tier: click to step it up, right-click to step it down. Higher tiers overclock, so fewer machines do the job.")
            .ring(tier)
            .click(tier)
            .pause(900)
            .click(tier)
            .pause(700)
            .until(Script::solved, 3000)
            .ring(count)
            .pause(1200);
        c.beat("The amps are the multiblock's energy hatches. More amps leave room to overclock further.")
            .ring(card(ACID, RecipeCard.Part.AMPS))
            .opens(card(ACID, RecipeCard.Part.AMPS))
            .type(Steps::focusedField, "2")
            .pause(300)
            .commit()
            .ring(null);
        c.beat("Power is shown, never wired: you don't supply it on the board.")
            .ring(card(ACID, RecipeCard.Part.POWER))
            .hover(card(ACID, RecipeCard.Part.POWER), 2200);
        c.beat("This is the programmed circuit the recipe needs. It isn't used up.")
            .ring(card(ACID, RecipeCard.Part.CIRCUIT))
            .hover(card(ACID, RecipeCard.Part.CIRCUIT), 2200);
        c.beat("More settings live under the gear. Pin one there to show it on the card.")
            .ring(card(ACID, RecipeCard.Part.SETTINGS))
            .opens(card(ACID, RecipeCard.Part.SETTINGS))
            .spot(Targets.popup())
            .pause(2600)
            .escIf(Script::popupOpen)
            .spot(null);
    }

    // endregion

    // region 7. Card actions

    private static void actions(final List<Chapter> out) {
        final Chapter c = chapter(out, "Card actions", Scene.BOARD);
        c.beat("This key holds the card's actions. Clone makes a copy, settings and all.")
            .ring(card(ACID, RecipeCard.Part.ACTIONS))
            .opens(card(ACID, RecipeCard.Part.ACTIONS))
            .hover(Targets.popupRow("Clone"), 700)
            .click(Targets.popupRow("Clone"))
            .until(() -> acids().size() == 2, 3000)
            .ring(null);
        c.beat("Drag a card by its body to move it somewhere else.")
            .drag(
                Targets.point(card(CLONE, RecipeCard.Part.BODY), 0.96f, 0.95f),
                Targets.emptyBoard(330, 230, 0.96f, 0.95f))
            .rest();
        c.beat("Delete takes it away again.")
            .ring(card(CLONE, RecipeCard.Part.ACTIONS))
            .opens(card(CLONE, RecipeCard.Part.ACTIONS))
            .hover(Targets.popupRow("Delete"), 700)
            .click(Targets.popupRow("Delete"))
            .until(() -> acids().size() == 1, 3000)
            .ring(null);
        c.beat("Made a mistake? Undo and redo are up here, or Ctrl+Z.")
            .ring(Targets.topKey("undo"))
            .click(Targets.topKey("undo"))
            .until(() -> acids().size() == 2, 2000)
            .pause(700)
            .ring(Targets.topKey("redo"))
            .click(Targets.topKey("redo"))
            .until(() -> acids().size() == 1, 2000)
            .ring(null);
    }

    // endregion

    // region 8. The overview

    private static void overview(final List<Chapter> out) {
        final Chapter c = chapter(out, "The overview", Scene.BOARD);
        c.beat("The overview on the left adds up the whole plan.")
            .rest()
            .spot(rail("all"));
        c.beat("What the plan needs from outside, and what comes out of it.")
            .spot(Targets.around(rail("section:INPUTS"), rail("resource:Hydrochloric")));
        c.beat("Every machine to build, and the power they draw.")
            .spot(Targets.around(rail("section:MACHINES"), rail("group:Large Chemical")));
        c.beat("Click a machine to go to its card.")
            .ring(rail("group:Large Chemical"))
            .click(rail("group:Large Chemical"))
            .pause(900)
            .ring(null);
        c.beat("Point at an item for more. A right-click finds public plans that make it.")
            .ring(rail("resource:Hydrochloric"))
            .hover(rail("resource:Hydrochloric"), 2400);
    }

    // endregion

    // region 9. Units and power

    private static void units(final List<Chapter> out) {
        final Chapter c = chapter(out, "Rates and power", Scene.BOARD);
        final Target rate = Targets.topKey("rate"), power = Targets.topKey("power"), peak = Targets.topKey("peak");
        c.beat("Rates show per tick, second, minute or hour.")
            .ring(rate)
            .click(rate)
            .pause(800)
            .click(rate)
            .pause(800)
            .click(rate)
            .pause(800)
            .click(rate)
            .ring(null);
        c.beat("Power shows in EU/t, or in amps at each machine's tier.")
            .ring(power)
            .click(power)
            .pause(1400)
            .click(power)
            .ring(null);
        c.beat("Average power, or peak: every machine running at once.")
            .ring(peak)
            .click(peak)
            .pause(1400)
            .click(peak)
            .ring(null);
    }

    // endregion

    // region 10. From NEI's list

    private static void fromTheList(final List<Chapter> out) {
        final Chapter c = chapter(out, "Items from NEI's list", Scene.BOARD);
        final Target spot = Targets.emptyBoard(200, 90);
        c.beat("NEI's list works here too. Search for something: benzene.")
            .ring(Targets.neiSearch())
            .click(Targets.neiSearch())
            .type(Steps::neiSearch, "benzene")
            .ring(null)
            .pause(500);
        c.beat("Drag it onto the board, and click where it goes.")
            .drag(fluidItem("benzene"), spot)
            .pause(200)
            .when(() -> codechicken.nei.ItemPanels.itemPanel.draggedStack != null, Steps.click(spot))
            .until(Script::popupOpen, 2500);
        c.beat("Add it as a product, something you want. A source is something you already have.")
            .ring(Targets.popupRow("Add as a product"))
            .hover(Targets.popupRow("Add as a product"), 900)
            .click(Targets.popupRow("Add as a product"))
            .until(() -> hasDrawer("Benzene"), 3000)
            .ring(null);
        c.beat("Point at it and press R to see what makes it.")
            .hover(Targets.drawer("Benzene", DrawerCard.Part.BODY), 900)
            .key("R", () -> {
                final DrawerModel d = drawer("Benzene");
                if (d != null) Planner.lookUp(com.gtnhplanner.ui.Resources.lookupStack(d.item, d.fluid), false);
            })
            .until(() -> Targets.recipePage() != null, 4000)
            .pause(500);
        c.beat("This one distills it out of wood tar. Add it to this plan.")
            .run(() -> TourRecipes.open(benzeneRecipe()))
            .until(() -> BENZENE_PLAN_BUTTON.rect() != null, 3000)
            .ring(BENZENE_PLAN_BUTTON)
            .click(BENZENE_PLAN_BUTTON)
            .until(PlanMenu.INSTANCE::isOpen, 2000)
            .hover(Targets.planMenuRow("Plan 2"), 700)
            .click(Targets.planMenuRow("Plan 2"))
            .until(() -> PlanMenu.INSTANCE.isOpen() || hasCard(BENZENE), 2000)
            .when(PlanMenu.INSTANCE::isOpen, Steps.click(Targets.planMenuRow("")))
            .until(() -> hasCard(BENZENE), 5000)
            .ring(null);
        c.beat("Drag its benzene onto the drawer to wire them.")
            .rest()
            .drag(Targets.port(BENZENE, true, "Benzene"), Targets.drawer("Benzene", DrawerCard.Part.BODY))
            .until(() -> drawer("Benzene") != null && drawer("Benzene").linked, 3000)
            .rest();
    }

    // endregion

    // region 11. Planning power

    private static void power(final List<Chapter> out) {
        final Chapter c = chapter(out, "Planning power", Scene.BOARD);
        c.beat("Generators, turbines, boilers and reactors are under Non-recipe machines.")
            .ring(Targets.topKey("nonrecipe"))
            .click(Targets.topKey("nonrecipe"))
            .until(
                () -> Targets.boardPart("picker:sheet")
                    .rect() != null,
                2000)
            .spot(Targets.boardPart("picker:sheet"))
            .pause(1200);
        c.beat("The gas turbine burns benzene.")
            .ring(Targets.boardPart("picker:Gas Turbine"))
            .hover(Targets.boardPart("picker:Gas Turbine"), 1200)
            .click(Targets.boardPart("picker:Gas Turbine"))
            .until(() -> hasCard(TURBINE), 3000)
            .ring(null);
        c.beat("Set it to HV.")
            .ring(card(TURBINE, RecipeCard.Part.TIER))
            .click(card(TURBINE, RecipeCard.Part.TIER))
            .pause(700)
            .click(card(TURBINE, RecipeCard.Part.TIER))
            .ring(null);
        c.beat("Send the benzene to the turbine instead: delete the drawer, and drag the port onto the turbine.")
            .ring(Targets.drawer("Benzene", DrawerCard.Part.DELETE))
            .click(Targets.drawer("Benzene", DrawerCard.Part.DELETE))
            .until(() -> !hasDrawer("Benzene"), 2000)
            .ring(null)
            .drag(Targets.port(BENZENE, true, "Benzene"), card(TURBINE, RecipeCard.Part.BODY))
            .pause(400);
        c.beat("Pin the benzene machine to one...")
            .ring(card(BENZENE, RecipeCard.Part.MACHINES))
            .opens(card(BENZENE, RecipeCard.Part.MACHINES))
            .type(Steps::focusedField, "1")
            .pause(300)
            .commit()
            .until(() -> model(BENZENE) != null && model(BENZENE).pinned, 3000)
            .ring(null);
        c.beat("...and you can see how many turbines it feeds, and the power they make.")
            .until(Script::solved, 3000)
            .rest()
            .spot(card(TURBINE, RecipeCard.Part.BODY))
            .pause(2000)
            .spot(rail("section:MACHINES"));
    }

    // endregion

    // region 12. Arrange

    private static void arrange(final List<Chapter> out) {
        final Chapter c = chapter(out, "Arrange", Scene.BOARD);
        c.beat("Cards land wherever there's room, and the board gets untidy.")
            .ring(Targets.topKey("fit"))
            .click(Targets.topKey("fit"))
            .pause(700)
            .rest()
            .spot(Targets.canvasArea());
        c.beat("Arrange tidies the whole plan. On a big plan it takes a moment.")
            .ring(Targets.topKey("arrange"))
            .click(Targets.topKey("arrange"))
            .until(
                () -> Targets.board() != null && !Targets.board()
                    .canvas()
                    .arranging(),
                20000)
            .pause(900)
            .ring(null);
        c.beat("Fit shows all of it.")
            .ring(Targets.topKey("fit"))
            .click(Targets.topKey("fit"))
            .pause(800)
            .ring(null);
    }

    // endregion

    // region 13. Sticky notes

    private static void notes(final List<Chapter> out) {
        final Chapter c = chapter(out, "Sticky notes", Scene.BOARD);
        final Target empty = Targets.emptyBoard(200, 140);
        final Target note = Targets.note(null, NoteCard.Part.BODY);
        final Target grip = Targets.note(null, NoteCard.Part.RESIZE);
        c.beat("Leave yourself a note: right-click empty board and add a sticky note.")
            .wheel(Targets.emptyBoard(200, 140), 3)
            .pause(300)
            .opensMenu(empty)
            .click(Targets.popupRow("Add a sticky note"))
            .until(() -> note.rect() != null, 2000);
        c.beat("Type on it. Press Esc or click away when you're done.")
            .until(Script::writingNote, 1500)
            .when(Script::writingNote, Steps.keys("Hydrogen comes from the electrolyzers by the door."))
            .pause(500)
            .escIf(Script::writingNote)
            .pause(300);
        c.beat("Drag it to move it. Drag its folded corner to resize it.")
            .drag(Targets.point(note, 0.4f, 0.3f), Targets.shift(Targets.point(note, 0.4f, 0.3f), 30, 20))
            .pause(300)
            .drag(Targets.point(grip, 0.7f, 0.7f), Targets.shift(Targets.point(grip, 0.7f, 0.7f), 40, 10));
        c.beat("Right-click it for its colour and text size.")
            .opensMenu(Targets.point(note, 0.5f, 0.6f))
            .click(Targets.popupRow("Colour"))
            .until(
                () -> Targets.popupRow("Lime")
                    .rect() != null,
                2000)
            .hover(Targets.popupRow("Lime"), 500)
            .click(Targets.popupRow("Lime"))
            .rest();
        final Target bigger = Targets.note(null, NoteCard.Part.BIGGER);
        c.beat("The + and - keys in its top corner change the text size too.")
            .ring(bigger)
            .click(bigger)
            .pause(350)
            .click(bigger)
            .pause(350)
            .click(bigger)
            .pause(500)
            .ring(null);
    }

    // endregion

    // region 14. Plans

    private static void plans(final List<Chapter> out) {
        final Chapter c = chapter(out, "Plans", Scene.BOARD);
        c.beat("Each tab along the top is an open plan.")
            .rest()
            .spot(Targets.topKey("tabs"));
        c.beat("The + starts a new plan, opens one you closed, or pastes one from Factory Flow.")
            .ring(Targets.boardPart("tab:+"))
            .opens(Targets.boardPart("tab:+"))
            .spot(Targets.popup())
            .pause(2400)
            .escIf(Script::popupOpen)
            .spot(null);
        c.beat(
            "Right-click a tab to rename it, copy its code, post it, or close it. A closed plan is kept in My plans.")
            .opensMenu(Targets.boardPart("tab:Plan 2"))
            .spot(Targets.popup())
            .pause(2600)
            .escIf(Script::popupOpen)
            .spot(null);
    }

    // endregion

    // region 15. The Library

    private static void library(final List<Chapter> out) {
        final Chapter c = chapter(out, "The Library", Scene.BOARD);
        c.beat("The Library holds plans: yours, and public setups from gtnhplanner.com.")
            .ring(Targets.topKey("library"))
            .click(Targets.topKey("library"))
            .until(
                () -> Targets.board() != null && Targets.board()
                    .libraryOpen(),
                2000)
            .ring(Targets.boardPart("library:shelf:public"))
            .click(Targets.boardPart("library:shelf:public"))
            .until(Script::publicLoaded, 8000)
            .ring(null);
        c.beat("Looking for something? Someone has probably built it already.")
            .rest()
            .spot(Targets.boardPart("library:all"))
            .pause(600)
            .ring(Targets.boardPart("library:search"))
            .click(Targets.boardPart("library:search"))
            .type(() -> text -> {
                final BoardScreen b = Targets.board();
                if (b != null && b.key("libraryView") instanceof final com.gtnhplanner.ui.library.LibraryView v)
                    v.searchField()
                        .setText(text);
            }, "oil")
            .pause(1500)
            .ring(null);
        c.beat("Open one, and it becomes a plan of your own. Back to the board.")
            .ring(Targets.topKey("library"))
            .click(Targets.topKey("library"))
            .until(
                () -> Targets.board() != null && !Targets.board()
                    .libraryOpen(),
                2000)
            .ring(null);
    }

    private static boolean publicLoaded() {
        final BoardScreen b = Targets.board();
        return b != null && b.key("libraryView") instanceof final com.gtnhplanner.ui.library.LibraryView v
            && v.publicLoaded();
    }

    // endregion

    // region 16. Settings and the minimap

    private static void minimap(final List<Chapter> out) {
        final Chapter c = chapter(out, "Settings and the minimap", Scene.BOARD);
        c.beat("Bugs and ideas go here, to GTNH Planner's thread on the GT New Horizons Discord.")
            .ring(Targets.topKey("feedback"))
            .hover(Targets.topKey("feedback"), 2200);
        c.beat("The gear holds the settings. The minimap shows a plan while you play.")
            .ring(Targets.topKey("settings"))
            .opens(Targets.topKey("settings"))
            .spot(Targets.popup())
            .clickUntil(
                Targets.popupRow("Show the minimap"),
                () -> "On".equals(Targets.setting("Show the minimap")),
                2);
        c.beat("Make it large and square, in the top right.")
            .clickUntil(Targets.popupRow("Size"), () -> "Large".equals(Targets.setting("Size")), 4)
            .clickUntil(Targets.popupRow("Shape"), () -> "Square".equals(Targets.setting("Shape")), 2)
            .clickUntil(Targets.popupRow("Position"), () -> "Top right".equals(Targets.setting("Position")), 4)
            .pause(600);
        c.beat("Close the planner, and there it is: the plan you just built.")
            .esc()
            .spot(null)
            .rest()
            .key(
                "Esc",
                () -> Minecraft.getMinecraft()
                    .displayGuiScreen(new TourScreen()))
            .until(() -> Minecraft.getMinecraft().currentScreen instanceof TourScreen, 2000)
            .spot(Targets.minimap());
        c.beat("The arrow keys move it, and [ and ] zoom it.")
            .holdKey("→", 450, () -> Minimap.INSTANCE.pan(1, 0))
            .pause(300)
            .holdKey("←", 450, () -> Minimap.INSTANCE.pan(-1, 0))
            .pause(300)
            .key("]", () -> PlannerSettings.setMinimapZoomIndex(PlannerSettings.minimapZoomIndex() + 1))
            .pause(700)
            .key("[", () -> PlannerSettings.setMinimapZoomIndex(PlannerSettings.minimapZoomIndex() - 1))
            .pause(300)
            .run(Minimap.INSTANCE::recentre);
        c.beat("N shows and hides it. Every key can be changed in the game's Controls.")
            .spot(Targets.minimap());
    }

    // endregion
}

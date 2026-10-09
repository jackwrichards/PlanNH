# Build prompt: the GTNH Planner rebuild, start to finish

This is a complete brief for an engineer (human, Claude, or any other LLM agent) to build the new GTNH Planner planner
from this repository. Read all of it before writing code. It is written to be pasted as a prompt.

## Status (2026-10-06)

All six steps below are built and on `main`, each checked in the GregTech dev client through the harness
(`mc.sh part`, `/board`). The old `gui/` package is deleted. After the owner's first look, added: the overview rail
(Factory Flow's resources column, on the left), Factory Flow wires (ranked widths, arrowheads, bridges where they
cross), multiblock pictures (bundled Factory Flow renders, else built in game with BlockRenderer6343), whole-row
port handles, two-line port names, one type scale, selection (click, Shift, box, Ctrl+A; Delete; group moves), eased
camera moves, an animated Arrange, and a help key. A polish pass after that: arrowheads sit mid-run, clear of
ports and corners; whatever is carried or was last moved draws on top; the wire router is about 8x faster (a
turn-aware A* heuristic and an allocation-free heap: 13 ms for 30 wires on 20 cards), so drags stay smooth; drawers
added from the overview line up below their neighbours; an empty plan shows a hint rather than a solver error. Items
dragged out of NEI drop onto the board: NEI carries a dragged item until the next click, which ModularUI gives to the
board, so `BoardScreen.onMousePressed` takes the drop itself.
Wires route after Factory Flow's: eight directions with real 45-degree runs between ends 120 or more apart, straight
runs out of and into ports, crossings priced high and ripped up and routed again within a time budget, heaviest
wires first, and a quick pass (no rip-up) while a card is being dragged. `tools/dev/board-check.sh` checks the main flows end to end. If you are picking this up, the brief below is still
the reference for how things are meant to work; what is left is this list.

Known gaps:
- Dropped on purpose (the owner, 2026-10-06): our own "What makes / uses this?" picker and "Replace the recipe".
  NEI already does the looking up: a port click opens NEI's page (R for an input, U for an output) and + wires the
  pick into that port; P does the same for any item; replacing a recipe is deleting it and adding another.
- Not built: "Add another recipe to this machine", the greyed "recipe missing" card, short-ladder [-] value [+]
  settings on the card (settings live in the actions menu's Machine settings list), Shift-wheel amps steps,
  previewing a machine on hover in the switch list, copy and paste.
- The solver does not flag a GregTech card set below its recipe's tier; the card shows a red ring and TIER!.
- AUTO can still balance a wired port through a gated import or surplus no drawer allows (see the engine notes).
- Undo history is per session (not saved), as before.

---

## Your job

You are rebuilding the user interface of **GTNH Planner**, an in-game production planner for the GT New Horizons modpack
(Minecraft 1.7.10, Forge, client-side only). The new planner is a port of the recipe board from the owner's web
app **GTNH Factory Flow** (gtnhplanner.com, source at `C:\Users\jack\gtnh-factory-flow`), restricted to its
**Solve mode**, dressed in NEI and GregTech's own GUI parts, and driving GTNH Planner's existing solver engine.

Deliver it in the six milestones below. Each milestone ends with: tests green, the feature verified in the running
game with the dev harness (screenshots you have looked at), a commit on `main`, and a short note to the owner.

## Ground rules

- Repo: `C:\Users\jack\GTNH Planner`, branch `main`, remote `origin` = the owner's fork. Commit and push finished work.
- Read `CLAUDE.md` (working rules, build, dev loop) and `docs/design/board-solve-mode.md` (the design spec; it
  is the source of truth for what the board does). The mockups are on the design canvas linked from that spec;
  row 4 "Hybrid" is the chosen look.
- Solve mode only. No Build mode, no "usage %" or "reason" words on cards.
- Everything must work in the game's limits: one pixel font (the vanilla FontRenderer, 9 px line, ~6 px per
  character, only whole-number scales), 16 px item icons, a 960 x 540 GUI at scale 2 that must still work at
  640 x 360, NEI's item list on the right. Names that do not fit are cut, never shrunk.
- Never freeze the render thread: solving and layout run in the background.
- Player-facing text: plain and short; the number, the rule, the consequence. No em dashes.
- Don't do work for the old UI (`gui/`): it is being replaced and is deleted in milestone 6.

## The dev loop (use it constantly)

```bash
PLANNH_GTNH=1 tools/dev/mc.sh start   # launch with GregTech + pack recipes (~30 s), loads a creative test world
tools/dev/mc.sh call open             # open the planner (F8 in game)
tools/dev/mc.sh call widgets          # ModularUI widget tree with GUI-coordinate rectangles
tools/dev/mc.sh call 'click?x=..&y=..'  # also: move, drag, scroll, key, type, cmd, status
tools/dev/mc.sh shot name.png 'x=..&y=..&w=..&h=..'   # screenshot (crop optional), then look at it
tools/dev/mc.sh swap --reopen         # hot-swap changed classes into the running game (~12 s)
tools/dev/mc.sh restart               # when swap exits 2 (new classes), or for mixins/resources/startup code
./gradlew test                        # headless engine tests, no Minecraft
```

Full reference: `docs/dev-harness.md`. The game window is on the owner's desktop: muted, 1920x1080, GUI scale 2.
Don't send synthetic input while the owner is using it.

## What exists and what you keep

Engine (keep, extend): `data/` model (`Graph`, `Node`, `Port`, `Edge`, `Plan`), per-mod recipe providers and
machine profiles (`data/provider`, `MachineProfile`, `SettingDef`, `MachineConfig`), GregTech's real overclock
calculator (`GTOverclockStep`), the ojAlgo balancer (`data/flowchart/balancer`, ~180 tests), `Serializer`,
`PlanAPI` (save, share, clipboard), `AutoLayout` (ELK), `WireRouter` (grid A*; it replaced `ArrowRouter` on 2026-10-08), and the NEI glue in `nei/`
(the "+" overlay on NEI recipe pages, R/U lookup context, NEI layout so its item list sits on the right).

UI (replace): everything in `gui/`. Its mechanics are worth reading (pan/zoom canvas, NEI integration), its look
and structure are not.

Engine facts that matter (from a code survey):
- Recipes come from NEI live: `new Node(IRecipeHandler handler, int recipeIndex, x, y)`; client thread only.
  Programmatic lookup: `GuiCraftingRecipe.getCraftingHandlers("item", stack)` (what makes X) and
  `GuiUsageRecipe.getUsageHandlers("item", stack)` (what uses X); iterate `numRecipes()`.
- "Machines that run this recipe" = NEI's catalysts: `RecipeCatalysts.getRecipeCatalysts(handler)`.
- Settings: `cfg.getProfile().visibleSettings(new RecipeContext(node.properties), cfg.settings)`; values via
  `MachineConfig.getInt/getBoolean/getString` and setters (setters re-extract ports through NEI).
  GregTech "unified" profile keys: voltage (OFF, ULV..MAX), amp, speed, parallels, machines, perfect_oc,
  gt_multiblock, laser_oc, eut_discount, ..., heat_oc, machine_heat, recipe_heat, heat_discount. Coil choices map
  to heat via GregTech's `HeatingCoilLevel` (`getHeat()`, `getName()`).
- Effects after settings and overclocking: `node.machineConfig.computeEffect(node.properties)` ->
  duration ticks, EU/t, throughput factor. Per-craft quantity of a port = amount * chance * multipliers * tf.
- GregTech overclocking itself: `gregtech.api.util.OverclockCalculator` (wrapped by `GTOverclockStep`).

Engine API on `main` (merged from the engine work; tests in `DrawerRulesTest`, `SolveServiceTest` and friends):
- `Drawer extends GraphData` (x, y, id): `Kind` SOURCE / PRODUCT / BYPRODUCT / TRASH (`linksInputs()` only for
  SOURCE, `hasRule()` for SOURCE and PRODUCT, `next()` cycles product -> byproduct -> trash), `Rule` ANY /
  AT_LEAST / EXACTLY / AT_MOST, `setTarget(rule, perSecond)` (fluids in L/s), `effectiveRule()` (ANY for
  byproduct and trash), links `Drawer.Link(nodeId, portIndex)`, `getResourceKey()` ("item:<reg>:<meta>",
  "fluid:<name>"), `getLabel()`.
- `Graph`: `drawers` / `getDrawer` / `addDrawer` / `removeDrawer`, `linkDrawer(drawerId, link)` (a port belongs
  to at most one drawer per direction), `drawerAt(nodeId, port, input)`. `version()` moves on every change,
  `solveVersion()` only on changes that matter to the answer. Call `touch()` after edits the graph can't see
  (settings, pins, drawer setters, rates) and `touchLayout()` after moves.
- Solving off the render thread: `SolveService.request(graph.solveVersion(), () -> SolveInput.of(graph,
  BalanceMode.AUTO, null))` every tick; `latest()` is the newest `Result(version, input, balance, error, ms)`;
  it is current when `latest().version() == graph.solveVersion()`. `BoardSession` already does this.
- `BalanceResult`: `nodeBalances()` -> `NodeBalance(operations = machine count, ...)` with
  `inputPerSecond(i)` / `outputPerSecond(i)`; `drawers()` -> `DrawerReadout(rates, unmet, shortfalls)`, where a
  `Shortfall` names the limiting nodes, drawers and groups ("Show me" frames those). Notes carry
  `SolverMessage` keys (`DRAWER_UNMET`, `DRAWER_NOT_CONNECTED`, `LIMIT_PIN`, `SOLVE_CRASHED`, ...) with lang
  entries in `en_US.lang`.
- Rules: pins are hard; drawers turn soft only when the solve fails, and then the unmet ones are reported with a
  shortfall. AT_LEAST / EXACTLY with a rate anchor AUTO; only AT_MOST drawers leave it idle with a note.
- Undo: `PlanAPI.undoHistory(graph)`; histories live per slot outside the graph, and `undo`/`redo` return the
  graph that takes the slot (put it back into `plan.getGraphs()`).
- Known gap: AUTO may still balance a wired port through a gated import or surplus that no drawer allows;
  Factory Flow would not. Revisit if plans import things the user never asked for.

ModularUI lessons from steps 1-2 (already handled in `ui/popup/Popup` and `RecipeCard`):
- A `ParentWidget` is only hovered when it has a background, hover overlay or tooltip; widgets that draw
  themselves override `canHover()` to return true, or tooltips and hover states never fire.
- Open popups a tick after the click (`Popup.open` queues; the canvas drains it), or the opening click closes them
  as an outside click. Give every popup panel its own name: the panel manager re-opens the old panel, with its
  stale callbacks, when a name comes back.

## Architecture of the new UI

New package `com.gtnhplanner.ui`. One ModularUI2 screen, opened like the old one (F8 and NEI's "FC" button):
`new GuiContainerWrapper(new ModularContainer().constructClientOnly(), BoardScreen.create())`, so NEI keeps its
item list on the right through the existing `FlowchartGuiHandler` / `FlowchartLayoutStyle` (point them at the new
screen).

```
ui/
  BoardScreen        screen + root panel: top bar, totals rail, canvas, notice bar
  BoardSession       the open plan slot: graph, undo, edits API (every edit: undo record + touch + save),
                     solve service hookup, the latest solve result, selection, hover resource
  canvas/            BoardCanvas (pan, zoom at whole steps, world-space children), WireLayer, HoverGlow
  card/              RecipeCard and its parts: CardHead (actions key, machine switch, amps chip, tier chip),
                     PortRail, MachinePicture, SettingsPanel + tiles, CardFooter (power, machines, parallel,
                     circuit); CardModel = view model built from Node + effect + solve result
  drawer/            DrawerWidget (source / product / byproduct / trash), RuleButton, RateBox
  popup/             Menu (card actions), MachineSwitchList, DropdownList (coils etc.), RecipePicker
                     ("what makes / uses this?"), all anchored to the widget that opened them
  chrome/            TopBar (plan tabs, undo/redo, unit key, power key, arrange, help), TotalsRail, NoticeBar
  theme/             Hyb: palette, bevels, slots, text helpers, number formats
```

Rules for the UI code:
- Draw with immediate GL inside widgets (rects, bevels, the vanilla font, `RenderItem` for items, fluid icons
  tinted by `Fluid.getColor()`); use ModularUI2 widgets for input handling, text fields, popups and tooltips.
- Sizes on a 2 px grid in GUI pixels. Card width 320; head 20; port rows 22; settings rows 16; footer ~36.
- NEI slots: grey item slot (#8B8B8B with #373737 top-left and #FFFFFF bottom-right edges), dark fluid slot.
- Colours: Factory Flow's card ramp (frame #3c3e45 with #52545c ring, #5a5c65 highlight, #1d1f23 shadow; tiles
  #36383f; ink #e8e9ee; muted #9a9ca4), GregTech tier colours (LV #00AA00, MV #FFAA00, HV #FFFF55, EV #555555,
  IV #5555FF, LuV #FF55FF, ZPM #55FFFF, UV #00AA00 underlined, ...), selection #22d3ee, hover glow #ffd257,
  pinned gold #ffd257, sources red (#9d6c70 frame, #493539 fill), products green (#45937b frame, #23463e fill).
- Rates use the board's unit key (/t, /s, /min, /hr); fluids add L. Compact numbers (12.5k, 1.2M).
- The view model is rebuilt only when the graph version or the solve result changes, never per frame.
- Every interaction is reachable by click or key, not hover alone.

## Milestones

1. **The card, static.** `BoardScreen` with the canvas and one `RecipeCard` per node of the active graph, drawn
   exactly per the spec: head, rails with NEI slots, names and rates, machine picture (machine block at 4x),
   settings, footer with power and the machine count. Numbers from the effect (after overclocking) and the solve
   result. Verify on the EBF Ilmenite recipe (Ilmenite + Carbon -> Cast Iron + Rutile + Carbon Monoxide) added via
   NEI's "+".
2. **Card controls.** Tier chip (click up, right-click down, wheel), amps chip (multiblocks), machine switch list
   (NEI catalysts; picking a single-block sets its tier, picking a multiblock turns on the multiblock options),
   settings tiles (coil dropdown with filter, short ladders, toggles, typed numbers), actions menu (Clone, Replace
   the recipe, Delete; "Add another recipe" later), machine count click-to-type pin (gold), unpin. Each edit goes
   through `BoardSession` (undo + touch + save) and re-solves.
3. **Drawers, wires, solving.** Drawer widgets with rule button and rate box and the product reading; drag from
   an unwired port to empty board creates a drawer; right-click board menu; wires drawn Factory Flow style
   (resource colour, width by flow, arrowheads, hops, routed with `WireRouter`); auto-wiring on add; solve in the
   background with a "working" indicator; notices with Show me.
4. **Getting recipes in.** NEI "+" (existing overlay, pointed at the new screen), P over any item to start a plan,
   R/U on ports, and the in-planner "what makes / uses this?" picker (machine filter, tier ceiling, add wires only
   that resource into the origin card).
5. **Around the board.** Totals rail (inputs, outputs, power, machine shopping list), plan tabs (rename, new,
   close), undo/redo that works across many steps, save/load/share with drawers, unit and power keys.
6. **Polish and cleanup.** Auto-arrange, hover glow across ports and wires, zoomed-out glance view (big icons
   instead of tiny text), tooltips with gesture hints, delete the old `gui/` package and its references, update
   `CLAUDE.md` and the docs.

## Definition of done (every milestone)

- `./gradlew test` green, `./gradlew spotlessApply` clean.
- `tools/dev/mc.sh smoke` passes (and no GTNH Planner exception in the log).
- You looked at screenshots of every new or changed element in the running game, with GregTech loaded.
- Committed on `main` with a message that says what changed and why; pushed.
- A short note to the owner: what works now, what to try, what is not done.

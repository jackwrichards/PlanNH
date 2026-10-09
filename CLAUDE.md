# GTNH Planner

GTNH Planner, the in-game side of gtnhplanner.com (mod id `gtnhplanner`, package `com.gtnhplanner`). It started as
a fork of sbancuz/PlanNH and was renamed on 2026-10-07 so the two never collide; saves (`plannh/plannh.dat` in a
world's NEI folder) and config files from before the rename are still read, then written under the new names.

In-game, NEI-driven flowchart production planner for GT New Horizons: Minecraft 1.7.10, Forge 10.13.4.1614,
client-side only. The planner UI is ModularUI2 in `ui/` (the Solve-mode board), the balancer is an ojAlgo ILP
solved in the background (`data/flowchart/balancer/`, `SolveService`), auto-layout and wire routing are in
`layout/`, recipe sources per mod are in `data/provider/`, and the NEI glue is in `nei/`.

## Remotes and branches

- `origin` = github.com/jackwrichards/gtnh-planner-mod (ours; it was jackwrichards/PlanNH, a fork, until the rename),
  `upstream` = github.com/sbancuz/PlanNH.
- `main` is our trunk and the fork's default branch; it started from upstream `dev` (a0896df). The other branches
  on the fork are untouched copies of upstream's. The plan is to gut and rebuild the codebase in our own image, so
  upstream's branches, bugs and CI are not our concern; don't spend effort on them.
- Changing the mod is fine when it makes developing or testing easier (hooks, debug output, testability
  refactors). Don't do end-user work yet (UX polish, features, user-facing bug fixes): it is all going to change.
- Dev tooling lives in `src/main/java/com/gtnhplanner/dev/`, `tools/dev/` and `docs/`; it is wired in through
  `DevHarness.initIfDev()` in `ClientProxy#init`.

## The rebuild

- `docs/design/board-solve-mode.md` is the spec for the new planner: Solve mode only, Factory Flow's recipe card
  and drawers with NEI parts, what every control does, what we keep from the engine, and the porting order.
- Mockups live on the design canvas linked from that file (row 4, "Hybrid", is the chosen direction).
- All six porting steps are on `main` (2026-10-06) and the old `gui/` package is gone. `ui/` map: `BoardScreen`
  (top bar, notices), `OverviewRail` (the overview on the left), `BoardSession` (the open plan: every edit, undo,
  slots, solve hookup, notices, totals), `canvas/` (board, wires, port drags, arrange), `card/` (recipe card),
  `drawer/`, `popup/` (menus, number and text boxes; recipes are looked up in NEI itself). Known gaps and next
  steps are at the top of `docs/design/build-prompt.md`.
- `docs/design/card-redesign.md` is the clean card now on the board, the minimap and the world (`CardLayout`,
  `CardPaint`, `CardChips`, `RecipeCard`, `CleanCardView`): what is settled, where every control and setting went,
  and what is left to design (`call cards` pages through a plan's cards).
- `docs/design/ff-card-spec.md` is Factory Flow's card measured from its source (sizes, colours, tooltips, the
  power panel's formulas). Our card is 320 wide to its 380 with chrome kept at 1 px; the clean card replaced its
  look (`docs/design/card-redesign.md`); `ui/card/CardLayout` holds the geometry. Board tooltips are `ui/popup/Tip` panels; a multiblock's power chips show `ui/card/PowerPanel`.
- Wires are routed by `layout/WireRouter`: exact A* on a 10 px grid with planned docks, kept per board so a change
  re-routes only what it touched. Arrange is `layout/arrange/`, a port of the website's arrange (column pass,
  optimiser, free placement, polish) judged by that router, run in the background from the top bar's key.
  `docs/design/routing-and-arrange.md` has both designs and the benchmark they are tuned against.
- `importer/` converts Factory Flow plans (JSON, plan codes, links) to graphs: a pure core plus `importer/game/`,
  the NEI and GregTech side. The "+" plan tab pastes one from the clipboard; `call 'importff?file=<path>'` does it
  from the harness. Fixtures are in `src/test/resources/factory-flow/`.
- Shared machines (one card, several recipes time-sharing one machine; `docs/design/ff-shared-machine-spec.md`) are
  `MachineGroup`s with ordered `sections`: each recipe stays a node with its own ports and wires, the group draws as
  one card, its machine settings are copied to every recipe, and a pinned count is an exact solver pool.
- Saves are backed up to `backups/` beside the save (`gtnhplanner/plans.dat`), once per session and before any save
  that drops a plan.
- Non-recipe machines (the top bar's Non-recipe key, `ui/power/PowerPicker`) are the website's power sources, ported
  to `power/` from its `src/lib/power` (57 sources; the workbook tables, resource map and machine icons are copied
  JSON in `assets/gtnhplanner/power/`, never hand-edited; parity with the website is tested per source). A power card
  is a `Node` with `powerSource` and `powerSettings` instead of an NEI recipe: `power/game/PowerPorts` rebuilds its
  ports from the model at exact per-second rates (`Port.exactAmount`, a craft a second), EU first. EU is a resource
  (`Energy`, key `power:eu`) that only drawers take, shown in EU/t; the overview shows power used, made and net.
  Power cards post to and import from the website as its power cards. `call power` checks every flow against the
  game (unmapped names, items or fluids this pack lacks).
- The custom rate card (the picker's Custom rate key beside its title; `power/CustomRate`, the website's
  custom-rate.ts) is a power card with source `custom-rate`, outside the catalog: a dial (Supply or Request, a rate a
  second, EU/t for EU) and one port. Empty, it shows two sockets; a port dropped on it, or a socket dragged to a port,
  makes it take that resource (`BoardSession.holdAndWire`), and every edit lets a card with nothing wired go of it
  (`releaseCustomRates`). Pinned at one when placed, so the dial is the rate. The overview counts it as the plan's
  edge (what it supplies is an input), never a machine. It posts and imports as the website's own custom rate card.
  The website's crop farm (its Farm key) is not ported: it needs CropsNH (GTNH 2.9), which the dev pack lacks.
- NEI's recipe pages carry a plan button above + and the star (`nei/PlanButton`, `nei/PlanMenu`, added through NEI's
  `UpdateRecipeButtonsEvent`): it asks which plan (most recently open first, `Plan.byRecency`) and which machine
  (`ui/card/MachineChoices`: GT single blocks are one choice at the recipe's tier), then opens the board on that plan
  with the new card centred and selected (`BoardSession.addAndFocus`). Shift-click skips both menus. The machine pick is
  remembered per tab (`MachinePicks`, `config/gtnhplanner-machine-picks.properties`) and NEI's + uses it too.
  `call 'nei?item=<key>&planner=0&tab=<name>'` opens a recipe page from the inventory.
- Plans work as on the website: a tab is an open plan (`Graph.open`); closing one keeps it, in the Library's My
  plans, where every plan is listed, most recently open first (`Plan.byRecency`, `lastOpen`). Deleting asks first.
- Sticky notes (`ui/note/NoteCard`, a `Note` in `graph.notes`): right-click empty board to add one; drag to move,
  drag the folded corner or an edge to resize, double-click to write, right-click for colour, text size and delete;
  hovering shows keys (delete top left, text size top right) at a fixed size on screen whenever they fit on the note.
  A selection outline shows only when several things are selected. Dragged cards and drawers stop against the others
  (`BoardCanvas.clearAt`; past one far enough, they jump through), notes pass over. While the plan has nothing set,
  every unpinned count says "(set count)" and every empty drawer "Set rate", pulsing gold together (`CardPaint.prompt`);
  the count keeps its place, a line kept under it. Scrolling a pinned count below one unpins it. The gear's See-through slider (0 to 100%) fades
  the board, its dots and the overview (`Hyb.seeThrough`) down to nothing; cards and text stay solid. The gear opens
  `ui/popup/SettingsPanel`, a centred box of sections. Drawers are drawn by `ui/drawer/DrawerPaint` (board and
  minimap): the clean card's look in the website's shapes per kind (tank, crate, shield, bin).
  They are Factory Flow's text annotations (`kind: "text"`, its colour tags and font sizes), so they come in with an
  imported plan and go out with a posted one; they take no part in the solve, the wiring or Arrange.
- The tour (`ui/tutorial/`, `docs/design/tutorial.md`): the "?" key's Take the tour, or the first-run offer (`TourOffer`:
  a note over the dimmed board the first time the planner opens, asked once). Twenty-four steps: each acts something out on the real UI, then a note (`Callout`) beside what it did says what it
  is (gold words between asterisks in `Script`) and waits for next; no autoplay, no chapters. It drives the UI with
  a cursor of its own (`Pointer`, through mixins on the mouse position screens and NEI read and on Shift;
  `VirtualInput` delivers presses, drags and keys to the open screen), in plans of its own (`Plan.enterSandbox`,
  never saved; `Sandbox` puts everything back). `Targets` finds what steps point at by name, `Director` plays them
  (Back restores a step's saved start). Text that names a key reads the player's bindings. `call 'tutorial?step=N'`
  starts or jumps to a step, `call tutorial` says where it is; every step's start ships in
  `assets/gtnhplanner/tutorial/starts.json` (`call 'tutorial?export=1'` after a full run rewrites it, needed when a
  script change alters a step's start). Hurrying must never outrun the game: the pointer settles on screen draws, not
  frames (the GUI does not draw every frame, and hover is found as it draws). It plays at a GUI scale with room for it
  (960x540: GTNH ships scale 4, a 480x270 GUI at 1080p where its drags run out of board) and opens NEI's folded item
  groups (the pack folds every fluid into one) as it looks for items; `Sandbox` puts both back. After a script change,
  walk every step and check the log for `[tutorial]` warnings.
- The Library (top bar; the + tab menu; right-click on an overview row: setups that make it) has two shelves: My
  plans, and Factory Flow's public setups from gtnhplanner.com (`library/CommunityApi`, `LibraryFeed`;
  `ui/library/LibraryView`), opened through the importer as a new plan. Signing in uses the website's own username
  and password accounts; the session (never the password) is kept in `config/gtnhplanner-account.properties`
  (`library/Account`). "Post to library..." (a tab's or a plan tile's menu) sends the plan as Factory Flow project
  JSON (`library/PlanExport`, checked against the website's own schema). Test it all against
  `node tools/dev/mock-library.mjs` (a local stand-in; `call 'library?url=http://127.0.0.1:8789'`), never the real site.
- While playing (`ui/world/`, `docs/design/minimap-and-world-links.md`): the minimap of the plan last open
  (`Minimap`, from the board's `PlanSnapshot`), and the plan over the world: a card's place key (or L on a block)
  places it on a spot, the imaginary block in front of the face looked at (`LinkPicker`, `LinkTarget`,
  `WorldLinks`, `Node.worldLinks`, a spot and a facing per machine; breaking blocks never removes one), and
  `PlanOverlay` (Y) draws each placed card over its block as the board does (`ui/card/CleanCardView`), with the plan's
  wires as connectors. Multiblocks show as their whole structure (`ui/gt/StructureGhosts`), drawn every frame from a
  kept fake world: display lists of block rendering come out invisible under Angelica. They are built as the card needs
  them (its coil, a tower's height, a line's length) through GregTech's structure channels. Placed machines are adjusted
  from the world with sneak held (`PlacementKeys`: R turn, G pick up and move, Del remove, U Focus: cards only where you
  look, Y hides the whole overlay). World code never loads the plans (`Plan.loaded()`). The earlier lens of every machine's live
  state is shelved on `shelf/ar-machine-lens`. Settings are the top bar's gear (`PlannerSettings`).
- Sound (`ui/sound/Sfx`, `docs/design/sound.md`): every sound is synthesized (`tools/sound/engine.mjs` the
  material, `sounds.mjs` the recipes, `synth.mjs` renders them with ffmpeg; `sounds.json` names them) and balanced
  there against the click; the lab (`tools/sound/lab`) plays the same recipes live in a browser to pick from; the game plays them at one volume matched
  to the vanilla click, times the gear's Sounds setting. Anything pressed clicks unless the action has a sound of its
  own; wires and drawers sound of what they carry (item clack, fluid bubbles, power sparks). `call sfx` lists what
  played (the dev game is muted).
- Multiblock pictures in `assets/gtnhplanner/textures/structures/` are Factory Flow's renders (`public/power-art`: the
  owner's processing multiblocks and the Power Planner workbook's power plants), scaled to 320 px palette PNGs with
  transparency. `ui/card/StructureArt` maps in-game machine names to them; `call 'gtmachines?art=1'` checks coverage.

## Build and test

- Gradle provisions the JDKs (25 for the build, JetBrains Runtime 25 for `runClient25`). Keep the checkout at a
  short path: deep Windows paths break the clone and the Minecraft dev setup (MAX_PATH).
- `./gradlew test`: 442 headless JUnit tests (power sources against the website, drawers, solve service, routing, layout, serialization),
  many over gtnh-flow YAML charts in `src/test/resources/gtnh-flow/`. About 40s, no Minecraft. `addon.gradle`
  opts `test` out of the configuration cache; without that a clean build reports `:test NO-SOURCE` and silently
  runs nothing, so if you ever see NO-SOURCE, check the count in `build/test-results/test/*.xml`.
- `./gradlew spotlessApply` before committing; CI checks formatting.
- The layout benchmark (`./gradlew test -Pbench=<label> --tests '*LayoutBenchmark*'`, tagged `bench`, left out of the
  normal run) routes and arranges every plan in the corpus, measures it (`layout/RouteMetrics`) and draws it to
  `build/bench/<label>/`; `DragBench`, `RouterTrace` and `RouterProfile` look at dragging, single searches and
  profiles. Usage and the numbers so far: `docs/design/routing-and-arrange.md`.

## Running and seeing the game

Use the dev harness; full reference in `docs/dev-harness.md`.

```bash
tools/dev/mc.sh start      # build, launch, auto-load the creative test world; blocks until ready
tools/dev/mc.sh call open  # open the flowchart
tools/dev/mc.sh shot x.png ['x=..&y=..&w=..&h=..']   # screenshot (optionally cropped), then Read the PNG
tools/dev/mc.sh part 1 TIER     # click card 1's tier chip by name (also: move, scroll 'amount=-1')
tools/dev/mc.sh swap --reopen   # hot-swap code changes into the running game (~12s, no restart)
tools/dev/mc.sh stop
tools/dev/mc.sh smoke      # automated launch/open/screenshot/log-scan check
tools/dev/board-check.sh  # GT runs: end-to-end board check (recipe, port drag, solve, add, undo) in a throwaway plan
```

- `PLANNH_GTNH=1 tools/dev/mc.sh start` loads GregTech and the pack's recipes through the GTNH core mod (72 mods,
  about 30s once the jars are cached) in its own test world, `plannh-dev-gtnh`. Use it whenever real GT recipes,
  machines or items are needed; the plain start (29 mods, no GT) is enough for anything else.
- The target pack is GTNH 2.9 (2.9.0-RC-2 as of 2026-10-09). `dependencies.gradle` pins that pack's versions of NEI,
  GT, MUI2, GTNHLib, the core mod and every mod a recipe provider reads, so what compiles runs there; move them
  together when the pack moves (NEI 2.8.155 changed the recipe button event and broke the plan button). The full
  pack, all of its mods and recipes, is the Prism instance `GTNH_2.9.0-RC-2_planner`: build, swap the
  `gtnhplanner-*.jar` in its `.minecraft/mods`, launch it with `prismlauncher.exe --launch GTNH_2.9.0-RC-2_planner`
  (from `%LOCALAPPDATA%\Programs\PrismLauncher2`). Its JVM arguments turn the harness on and load
  `gtnhplanner-dev`, so `mc.sh call`, `shot` and the rest work against it (screenshots land in the instance's
  `.minecraft/screenshots`); `swap` and `start` do not. It opens at 854x480 whatever Prism's settings say:
  `call 'window?w=1920&h=1080'` (`scale=` sets the GUI scale). It runs Java 21, so it runs the fully downgraded
  classes (`gradle.properties`: the partial downgrade to 21 broke pattern switches), which the dev game on Java 25
  never does; the harness port is the same, so stop one game before starting the other.
- Input endpoints and `shot` crops take GUI coordinates (960x540), not screen pixels. On the board, act on a card
  control by name: `mc.sh part 1 MACHINES` (from `call board`, which also returns each card's state). Elsewhere
  find targets with `call widgets` and check `hovered`/`focused` in `call status` rather than estimating.
- `mc.sh stop` kills the game on purpose: a clean quit with GT loaded hangs on a "really close?" dialog.
- GTNH Planner text fields need a double-click (`click?x&y&count=2`) before `type` works.
- After changing mod code: `tools/dev/mc.sh swap --reopen`. Restart instead (`mc.sh restart`, ~30s) when swap
  exits 2 (new or not-yet-loaded classes), for mixins, resources and startup-only code, and when you add a field
  with an initialiser: widgets already on screen get it as null, and a draw that touches it crashes out of the world.
  Decide before swapping: swap applies every class it can before it exits 2, so swapping code that uses a new class
  or an initialised field on an existing object (an event handler's `INSTANCE` too) crashes the user's game at once.
- The game window opens on the user's desktop at 1920x1080, GUI scale 2, muted (`PLANNH_DEV_SOUND=1.0` for sound,
  `call 'sound?volume=1'` live); keep it that way. Leave it running while the user is iterating on the UI with you;
  stop it when the work is done.
- The user can press F2 in game to screenshot what they see; when they refer to "this" or "my screenshot", Read
  the newest file in `run/client/screenshots/`. Harness screenshots land there too, under the names you gave them.
- Performance: `call 'perf?start=1'`, do something (drag, pan, open a plan), then `call perf` for FPS, frame-time
  percentiles and per-part timings (cards, drawers, wires, overview, routing, solve). Wires route on the router's
  thread (`layout/WireRouter`), incrementally: a drag, a new wire or a new card re-routes only what it touched, and
  the frame waits for a route expected back within 10 ms. They are drawn from a display list; keep per-frame drawing batched (`Hyb.beginBatch`). Dev runs keep
  each plan opened from the Library in `run/client/library-downloads/` for `call 'importff?file=...'`.
- UI feedback loop: change code, `swap --reopen`, check with a cropped `shot`, then tell the user it's live in
  their window. They can interact with the game at the same time; just don't send synthetic input while they are
  mid-action. Gate input and restarts on `tools/dev/mc.sh idle`: it succeeds when the game window is unfocused or
  nothing has come from their mouse or keyboard for 30 s (`idleSeconds` in `call status`). A focused window alone
  means nothing: the game takes focus when it starts and keeps it while they work elsewhere.

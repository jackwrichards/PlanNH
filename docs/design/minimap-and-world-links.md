# Minimap, world links and the AR lens

The planner should be useful while you build, not only in its own screen. Five pieces, each usable on its own, in the
order they are built.

## 1. Settings, and names when zoomed out

- A gear key on the top bar, beside "?", opens the settings (`ui/popup/SettingsPanel`), stored in
  `config/gtnhplanner-settings.properties` (`ui/PlannerSettings`). A row is a setting's name and its value; click for
  the next value, right-click for the one before.
- **Names when zoomed out**: past the glance zoom, every card's machine name and every drawer's resource name, at a GUI
  pixel per font pixel whatever the zoom. They are placed like map labels (`ui/canvas/ZoomedOutLabels`): drawn over
  everything in one pass, a card's above (or below) it, a source drawer's to its left, a product's to its right, each
  where it overlaps no other name and no card, and left out where there is no such place; cards first, then drawers by
  rate. Zooming in makes room for the rest.

## 2. The minimap

- While playing (no screen open, HUD shown), a corner of the screen shows the plan last open in the planner: its
  cards (machine picture, count), drawers (icon, rate), and wires, as the board drew them.
- The board publishes a `PlanSnapshot` (cards, drawers, wire paths, view centre, links) whenever it rebuilds its models
  or routes; the minimap draws the latest one. A plan changed while the planner is closed (NEI's plan button) marks the
  map out of date until the planner opens again.
- Settings: on/off, size (Small, Medium, Large, Huge), shape (square, circle), corner, zoom, and "Centre on the machine
  you look at".
- Keys (Minecraft's Controls, category GTNH Planner): toggle the minimap, zoom in and out, pan (arrow keys), re-centre.
- Square maps clip with the scissor test; round ones mask their corners with the depth buffer.

## 3. Linking cards to blocks

The plan and the world are separate: a link says where a card's machines are built, to lay a build out by its plan
and see the plan over it. It never makes the plan show what the machines are doing.

- A block can be a card's machine only if it runs the card's recipe (every recipe, on a shared machine): one NEI lists
  for it, and for GregTech's single blocks at a tier that can run it; a power card's block is its generator
  (`ui/world/MachineMatch`). Several blocks can be one card's machines, but a block is one card's: linking it to a
  card takes it off any other, in any plan (`WorldLinks.assign`).
- From a card: its menu's **Link to blocks in the world** turns the crosshair into a picker (`ui/world/LinkPicker`):
  left-click a block to link it (again to unlink), right-click or Esc to go back to the planner. A block that cannot
  be the card's machine is refused with the reason.
- From the world: the Link key (L) on a block, as far as the lens reaches, opens the planner in link mode
  (`ui/world/LinkTarget`): a banner says what is being linked, the cards that can take it are lit (the one it is on
  now in gold) and the rest dimmed, in any plan (tabs, Library). Clicking a lit card links the block and goes back to
  the world; clicking its own card unlinks it; Esc cancels.
- **Show in the world** and **Clear world links** on the card's menu; the card says "N IN WORLD". A linked block that
  is broken is unlinked, with a note.
- Links are saved with the plan, never copied with it, and undo like any edit, but are not changes to the plan: nothing
  re-solves and the minimap stays current.

## 4. Seeing links in the world

- **Show in the world**: the card's blocks are outlined through walls, with a beam and their name and distance, for
  30 seconds (`ui/world/WorldView`, drawing in `WorldMarks`).
- Looking at a linked block (up to 64 blocks away, the lens off) outlines it and the rest of its card's, names the card
  and plan under the crosshair, and centres the minimap on that card and rings it (when set).

## 5. The AR lens

The lens shows the machines as they are in the world, not the plan, in the planner's look (`ui/world/ArLens`,
`ArPanel`). Everything is on the panel all the time; looking at a machine only rings its panel and brings it to the
front.

- Every machine within range (GregTech's single blocks and multiblock controllers, and any linked block; only linked
  ones when "AR lens: only machines linked to a plan" is on) gets a small dark panel whose size depends on the machine
  alone, never on its numbers: its name (a small gold star when it is a plan card's machine) and tier chip; what its
  recipe takes and makes in tiles either side of an arrow that fills with the recipe's progress, each tile's recent
  rate under it and the time left under the arrow; two charts of the last hour, a dot each two minutes joined by a
  line, each in its own space and scale (Made: per minute, with the hour's average; Power: EU/t against the most it can
  take, with the figure now), and why it is stopped across the first; and a footer with its state, how much it has made and how busy it has been
  this last hour. The nearest eight and the one looked at get panels, the rest a tile with a state light. Panels fade
  in and out and over the last 8 blocks of the range.
- Panels keep out of each other's way and off the minimap: over the machine when there is room, else the nearest free
  place a few steps up, down (under the machine) or to the side. A panel keeps the place it has unless it must move,
  and the panels that came first keep their claim, so they never swap.
- What it is doing comes from `ui/world/GtMachineStatus`: in single player the integrated server's copy, read on the
  server's own thread for the machines the client asks about (progress, the recipe from `processingLogic.lastRecipe`
  or `mLastRecipe` by reflection, the cycle's outputs, energy hatches' tier, amps and max input, maintenance, a formed
  structure, shutdown reasons, power). `ui/world/MachineStats` samples every machine within 128 blocks twice a
  second into per-second and per-minute buckets (time watched, time running, what it made and used, EU drawn) for this
  session. On a server only the running light reaches the
  client, so panels show state alone.
- Connectors run between linked machines whose cards are wired in the plan last open, block centre to block centre: a
  thin shaded tube in the resource's colour with arrowheads every block and a quarter sliding towards the machine it
  feeds, and only a faint line where something hides it.

## Safety

- Nothing in the world loads the plans (`Plan.loaded()`): loading them while NEI is still loading its recipes can
  fail a slot. A slot that cannot be read is kept as it was (`Graph.unreadable`), written back unchanged while it
  stays empty, and read again when it is opened.

## Order of work

1. Settings and zoomed-out names.
2. Plan snapshot and the minimap, with its keys and settings.
3. World links: the data, the card menu, the picker, linking from the world.
4. Show in the world, and looking at a linked block.
5. The AR lens.

All five are built (2026-10-07). Keys (Controls, GTNH Planner): N minimap, [ and ] zoom, arrows pan, Y AR lens,
L link the machine you look at to a card.

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

## 3. Placing cards in the world

The plan and the world are separate: placing a card on a block says where that part of the plan goes (or is). It
never makes the plan show what the machines are doing.

- Every card has a place key (a map pin, beside its menu key; gold once placed). Left-click: the planner closes and the
  crosshair picks a block, any block; a click places the card on it and opens the planner again (`ui/world/LinkPicker`).
  Right-click: removes it from the world. The card's menu also has **Show in the world** and **Remove from the world**.
- A card is placed on one block and a block holds one card: placing a card on a block takes the block from any other
  card, in any plan (`ui/world/WorldLinks`, `Node.worldLinks`). A placed block that is broken is unlinked, with a note.
- From the world: the Link key (L) on a block opens the planner in link mode (`ui/world/LinkTarget`); clicking a card
  places it there, clicking the card already there removes it, Esc cancels.
- Placements are saved with the plan, never copied with it, and undo like any edit, but are not changes to the plan:
  nothing re-solves and the minimap stays current.

## 4. Seeing placements in the world

- **Show in the world**: the card's block is outlined through walls, with a beam and its name and distance, for 30
  seconds (`ui/world/WorldView`, drawing in `WorldMarks`).
- With the overlay off, looking at a placed block (up to 64 blocks away) outlines it, names its card and plan under the
  crosshair, and centres the minimap on that card (when set).

## 5. The plan over the world (AR)

`ui/world/PlanOverlay`, toggled with Y and in the settings ("Show the plan over the world (AR)", "How far to show it").
Nothing shows until a card of the plan last open is placed.

- Each placed block shows a ghost of the card's machine (its own block model, see-through, over the world; not when
  the machine is built there already), boxed in the machine's colour, and the card floats over it with a stem down to
  the block, drawn as the board draws it (`ui/card/PlanCardView`: name bar and tier chips, ports with the plan's
  rates, the machine's picture, POWER and MACHINES), with nothing to press. A card is two and a half blocks wide in
  the world, so it shrinks with distance and turns into the board's zoomed-out card past the board's glance zoom;
  nearer cards cover farther ones. The card looked at, and its block, are ringed.
- The plan's wires between placed cards run from block centre to block centre as connectors drawn over the world
  (`WorldMarks.connector`): a thin shaded tube in the resource's colour with arrowheads sliding towards the card it
  feeds, and the resource's icon at its middle. The wire nearest the crosshair is lit and labelled as the plan has
  it: what, how much, and from which card to which.

The earlier AR lens (every machine's live state, read from GregTech, with stats and charts) is shelved on the branch
`shelf/ar-machine-lens`.

## Safety

- Nothing in the world loads the plans (`Plan.loaded()`): loading them while NEI is still loading its recipes can
  fail a slot. A slot that cannot be read is kept as it was (`Graph.unreadable`), written back unchanged while it
  stays empty, and read again when it is opened.

## Order of work

1. Settings and zoomed-out names.
2. Plan snapshot and the minimap, with its keys and settings.
3. Placing cards: the data, the place key, the picker, placing from the world.
4. Show in the world, and looking at a placed block.
5. The plan over the world.

All five are built (2026-10-08). Keys (Controls, GTNH Planner): N minimap, [ and ] zoom, arrows pan, Y the plan over
the world, L place a card on the block you look at.

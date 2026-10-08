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

- A card's actions menu: **Link to blocks in the world** (then "Link more blocks"), **Show in the world** and **Clear
  world links**. A card keeps a list of block positions (`Node.worldLinks`: dimension, x, y, z; a card is often
  several machines), saved with the plan, never copied with it. A link edit is an undo step and is saved, but is not a
  change to the plan: nothing re-solves and the minimap stays current (`ui/world/WorldLinks`).
- Linking closes the planner and turns the crosshair into a picker (`ui/world/LinkPicker`): left-click a block to
  link it (again to unlink), right-click or Esc when done, which opens the planner again. A block that is not the
  card's machine (by its pick-block item) is linked with a note saying what it is.
- From the world: the Link key (L) on the block under the crosshair opens `ui/world/LinkChooser`, the cards of the
  plan last open, the same machine first; clicking one links or unlinks the block.
- The card says how many blocks it is linked to ("2 IN WORLD" on its machines tile).

## 4. Seeing links in the world

- **Show in the world**: the card's blocks are outlined through walls, with a beam and their name and distance, for
  30 seconds (`ui/world/WorldView`, drawing in `WorldMarks`).
- Looking at a linked block (up to 64 blocks away) outlines it and the rest of its card's, names the card, its count
  and plan under the crosshair, and centres the minimap on that card and rings it (when set).

## 5. The AR lens

- A key (Y) and a setting show a card over every machine within range (`ui/world/ArLens`): GregTech's single blocks
  and multiblock controllers, and any block linked to a card. Each is drawn in the recipe card's look with nothing to
  press (`ui/card/WorldCard`): the name bar with the tier and amps chips, the running recipe's inputs and outputs per
  second as port tiles, the machine in the middle with the progress under it, a POWER tile (EU/t drawn now, of the
  most it can take) and a STATUS tile (progress and time left, or why it stopped; the linked card's count). The
  nearest eight and the one looked at get a card, the rest the board's zoomed-out tile. Cards are projected from the
  world's camera and drawn flat on the HUD, raised clear of nearer ones; their size is a setting (Medium default).
- What a machine is doing comes from `ui/world/GtMachineStatus`. In single player it reads the integrated server's
  copy: progress, the recipe (`processingLogic.lastRecipe` on multiblocks, `mLastRecipe` on single blocks, by
  reflection) times its parallels over the real duration, the outputs of the running cycle, the energy hatches' tier,
  amps and max input, maintenance, a formed structure, the shutdown reason and why it cannot start. On a server only
  the running light reaches the client.
- Wires between two cards that both have blocks here are drawn between them, in their resource's colour.

## Order of work

1. Settings and zoomed-out names.
2. Plan snapshot and the minimap, with its keys and settings.
3. World links: the data, the card menu, the picker, linking from the world.
4. Show in the world, and looking at a linked block.
5. The AR lens.

All five are built (2026-10-07). Keys (Controls, GTNH Planner): N minimap, [ and ] zoom, arrows pan, Y AR lens,
L link the block you look at.

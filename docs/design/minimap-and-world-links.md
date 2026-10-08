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

- A card's actions menu: **Link to blocks in the world**, **Show in the world** and **Clear world links** (the last
  two once it has links). A card keeps a list of block positions (dimension and x, y, z: a card is often several
  machines), saved with the plan, never copied with it.
- Linking closes the planner and turns the crosshair into a picker: left-click a block to link it (again to unlink),
  right-click or Esc when done, which opens the planner again. A block that is not the card's machine (by its pick-block
  item) is linked with a warning.
- The card says how many blocks it is linked to ("2 IN WORLD" on its machines tile).

## 4. Seeing links in the world

- **Show in the world**: the card's blocks are outlined through walls, with a beam and their name and distance, for a
  while or until another card is shown.
- Looking at a linked block outlines it, puts its card's name, count and plan under the crosshair, and (when set)
  centres the minimap on that card and rings it.

## 5. The AR lens

- A key (and a setting) shows a panel over every linked machine within range: its machine, count, and what goes in and
  comes out per second, with icons, from the last solve. Wires between linked machines are drawn as lines between them,
  in their resource's colour.

## Order of work

1. Settings and zoomed-out names.
2. Plan snapshot and the minimap, with its keys and settings.
3. World links: the data, the card menu, the picker.
4. Show in the world, and looking at a linked block.
5. The AR lens.

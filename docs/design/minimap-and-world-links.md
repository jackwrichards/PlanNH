# Minimap, world links and the AR lens

The planner should be useful while you build, not only in its own screen. Five pieces, each usable on its own, in the
order they are built.

## 1. Settings, and names when zoomed out

- A gear key on the top bar, beside "?", opens the settings (`ui/popup/SettingsPanel`), stored in
  `config/gtnhplanner-settings.properties` (`ui/PlannerSettings`). A row is a setting's name and its value; click for
  the next value, right-click for the one before.
- **Names when zoomed out**: past the glance zoom, every card's machine name and every drawer's resource name, small (a
  screen pixel per font pixel, two at large GUI scales) whatever the zoom, and no wider than what they name (a card's
  width, twice a drawer's; cut short with "..."). They are placed like map labels (`ui/canvas/ZoomedOutLabels`): drawn
  over everything in one pass, a card's above (or below) it, a source drawer's to its left, a product's to its right,
  each where it keeps clear of the other names (with room between two side by side) and of every card and drawer, and
  left out where there is no such place; cards first, then drawers by rate. Zooming in makes room for the rest. The
  minimap places them the same way.

## 2. The minimap

- While playing (no screen open, HUD shown), a corner of the screen shows the plan last open in the planner, as the
  board draws it zoomed out: cards (machine picture, the count in a dark pill), drawers (icon, the rate in a pill),
  wires as the board routed them, and with **Names when zoomed out** the same names, placed the same way.
- What the mouse or crosshair is on is highlighted in one colour everywhere (`Hyb.LIT`, a soft gold, drawn faint):
  the resource under the mouse on the board, the card under the crosshair on the minimap and over the world, and the
  wire under the crosshair over the world and on the minimap. The board's selection keeps its own colour.
- The board publishes a `PlanSnapshot` (cards, drawers, wire paths, view centre, links) whenever it rebuilds its models
  or routes; the minimap draws the latest one. A plan changed while the planner is closed (NEI's plan button) marks the
  map out of date until the planner opens again.
- Settings: on/off, size (Small, Medium, Large, Huge), shape (square, circle), corner, zoom, and "Centre on the machine
  you look at".
- Keys (Minecraft's Controls, category GTNH Planner): toggle the minimap, zoom in and out, pan (arrow keys), re-centre.
- Square maps clip with the scissor test; round ones mask their corners with the depth buffer.

## 3. Placing cards in the world

The plan and the world are separate: placing a card says where that part of the plan goes (or is). It never makes the
plan show what the machines are doing.

- A card goes on a spot: a block's place, usually an imaginary block in front of the face the crosshair is on (like
  placing a block, but nothing is placed and you can walk through it). Breaking or building blocks never moves or
  removes a placement.
- Every card has a place key (a map pin, beside its menu key; gold once placed). Left-click: the planner closes and the
  card's machine shows as a ghost on the spot in front of the face under the crosshair; a click places it there and you
  stay in the world (`ui/world/LinkPicker`). Right-click or Esc cancels. Right-click on the key removes the card from
  the world. The card's menu also has **Show in the world** and **Remove from the world**.
- A card is placed on one spot and a spot holds one card: placing a card on a spot takes it from any other card, in any
  plan (`ui/world/WorldLinks`, `Node.worldLinks`).
- From the world: the Link key (L) opens the planner in link mode (`ui/world/LinkTarget`) for the placed spot under
  the crosshair, or else the spot in front of the face looked at; clicking a card places it there, clicking the card
  already there removes it, Esc cancels.
- Placements are saved with the plan, never copied with it, and undo like any edit, but are not changes to the plan:
  nothing re-solves and the minimap stays current.

## 4. Seeing placements in the world

- **Show in the world**: the card's block is outlined through walls, with a beam and its name and distance, for 30
  seconds (`ui/world/WorldView`, drawing in `WorldMarks`).
- With the overlay off, looking at a placed spot (up to 64 blocks away, through blocks) outlines it, names its card and plan under the
  crosshair, and centres the minimap on that card (when set).

## 5. The plan over the world (AR)

`ui/world/PlanOverlay`, toggled with Y and in the settings ("Show the plan over the world (AR)", "How far to show it").
Nothing shows until a card of the plan last open is placed.

- Each placed spot shows a ghost of the card's machine (its own block model, see-through, over the world; not when the
  machine is built there already), and the card sits on the middle of the spot, over its ghost, drawn as the board
  draws it (`ui/card/PlanCardView`: name bar and tier chips, ports with the plan's rates, the machine's picture, POWER
  and MACHINES), with nothing to press. A card is drawn at six tenths of the board's size within ten blocks of you and
  shrinks with distance beyond (always the whole card).
- Cards move out of each other's way as map labels do (`PlanOverlay.layout`): nearest first, each stays on its spot
  while that is clear of the cards already placed and the wires' tags, else takes the clear place nearest it (aside, up,
  or down, which costs most), with a line from its nearest edge to the middle of its spot, ending in a small pin. A
  card goes home again once that is clear by a good margin, and moves in a quick ease, never a glide; where it was is
  kept, so turning away and back finds the same arrangement.
- The crosshair is on a card when it is over the card itself or meets its spot (its ghost), through blocks: the card is
  highlighted, drawn over the others, its spot outlined, and the minimap highlights it and glides to it.
- The plan's wires between placed cards are drawn flat on the screen as the board draws them, from where they leave
  one spot to where they meet the other (cut short at the camera): a thick line in the resource's colour on a dark
  edge, with many small arrowheads drifting slowly towards the card fed (set out in the world, two fifths of a block apart at two
  thirds of a block a second, so their pace holds as you move), faint where nothing flows yet. Several wires between
  the same two cards run side by side in lanes (a line, its edges and a clear gap apart), so the two directions of a pair part. At its middle a tag says what
  goes along it: the icon, the rate in large type and the name small under it, on a plain soft dark backing, as big as
  the cards' ports there; on the line when the wire runs alone, beside its own lane, on the side away from the other
  wires, when two cards share several.
- The wire the crosshair is on (its tag, or its line within 24 GUI pixels, when no card is under the crosshair) is
  highlighted, and the minimap
  highlights the same wire and (with "Centre on the machine you look at") glides to it.

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

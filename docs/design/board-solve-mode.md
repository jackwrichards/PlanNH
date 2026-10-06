# The board in Solve mode: what goes on it

Design spec for the rebuilt planner. It covers everything a player can place and touch on the board, using
GTNH Factory Flow (FF) as the reference and the game's limits from the mockup canvas
(https://claude.ai/artifact/8eNKRqEGRwWywtK4mvaxkL, row 4 "Hybrid"). Sizes are GUI px (1 GUI px = 2 screen px
at GUI scale 2). Numbers here are starting points; the in-game build is where they get tuned.

There is one mode: **Solve**. The player places recipes and drawers and says what they want; the planner works out
machine counts and rates. There is no Build mode, no usage percentage and no "reason" word on cards.

## 1. What can be on the board

| Object | What it is | How it gets placed |
| --- | --- | --- |
| Recipe card | One recipe on one machine type, times a solved machine count | NEI "+" on a recipe page; P over an item; "what makes / uses this?" from a port |
| Source drawer | Something the player brings in (Oil, Water, a dust from the ME system) | Drag an unwired input into empty space; right-click the board; drag an item from NEI |
| Product drawer | Something the player wants; carries the target rate | Drag an unwired output into empty space; right-click the board; drag an item from NEI |
| Byproduct drawer | Takes whatever surplus arrives, never asks for more | Cycle a product drawer (⟳ key) |
| Trash | Voids what arrives | Cycle a byproduct drawer |
| Wire | One resource flowing between two objects | Made automatically; or drag from a port |

Machines are never placed on their own: a card is always a recipe, and its machine is a choice on the card.

## 2. Recipe card

```
+--------------------------------------------------------------+
| [≡] [v  Electric Blast Furnace          ] [ 4A ][ EV ]       |  head, 20 tall
|--------------------------------------------------------------|
| [slot] Ilmenite Dust   |                | [slot] Cast Iron    |  rails, 22 per port
|        900/hr          |   machine      |        180/hr       |
| [slot] Carbon Dust     |   picture      | [slot] Rutile Dust  |
|        180/hr          |                |        540/hr       |
|                        |                | [slot] Carbon Mon.  |
|                        |                |        180k L/hr    |
|--------------------------------------------------------------|
| COIL   [ (icon) Nichrome            v ]                       |  settings, 16 per row
|--------------------------------------------------------------|
| POWER 6.93k EU/t    | MACHINES x1.00 (pencil)   | [circuit]  |  footer
+--------------------------------------------------------------+
```

Width 320. Height grows with the taller rail and the number of settings; it snaps to a 4 px grid.

### 2.1 Head row

- **Card actions [≡]**: a menu. Items, in FF's order and words:
  - Clone node
  - Replace the recipe (keeps every wire that still fits)
  - Add another recipe to this machine (only when the machine can run several recipes; see open question 2)
  - Delete node
- **Machine switch** (the name bar, chevron on the left): lists "Machines that run this recipe", one row each:
  icon, name, duration and EU/t at the card's tier. The current one is highlighted. Hovering a row previews it on
  the card; the wheel over the bar steps through them. Example: Electric Blast Furnace, Mega Blast Furnace,
  Volcanus. Names that do not fit are cut, never shrunk (the game font has one size).
- **Amps chip** (multiblocks only): "4A". Click to type, wheel ±1, Shift-wheel steps 1/4/16/64, right-click −1.
- **Tier chip**: in GregTech's own tier colours (LV green, MV gold, HV yellow, EV dark grey, IV blue...). Click
  steps up, right-click steps down, wheel steps. Its tooltip tells the overclock story: input tier, overclocks
  applied, resulting duration and EU/t.

### 2.2 Rails (inputs left, outputs right)

- Each port: an NEI slot (grey for items, dark for fluids) holding the 16 px icon, then the name (1 to 2 lines)
  and the rate in the current unit (`900/hr`, `180k L/hr`). Chanced outputs add the chance: `540/hr · 25%`.
- No bars, no plugs, no "current / nameplate" in Solve.
- Ports sit on the rails, in recipe order, not at NEI's slot positions.
- **Gestures on a port**:
  - Hover: NEI's item tooltip plus the rate; every port and wire carrying the same resource glows.
  - R / U: NEI's recipes / uses for that item.
  - Click an unwired input: "What makes this?" picker. Adding from it wires only this resource into this card.
  - Click an unwired output: "What uses this?" picker, same rule.
  - Drag: start a wire. Drop on a card to connect (it snaps to the matching port); drop on empty board to create a
    drawer (source for an input, product for an output).
- **Unwired port**: dashed slot outline, and the board notice counts it (see 5).

### 2.3 Machine picture

The structure render where we have one, otherwise the machine's block drawn at 4x (64 px). Sunken window. Purely
visual; clicking it does nothing.

### 2.4 Settings

Only the settings that matter for this machine, from the machine profile PlanNH already has per mod:

- GregTech: coil (dropdown with a filter box, starting at the recipe's minimum heat: Cupronickel, Kanthal,
  Nichrome, TPV-Alloy, HSS-G, ...), parallels, perfect overclock, other multiblock options.
- Other mods: speed / acceleration, and their own resources (mana, LP, vis) where the provider defines them.
- Short ladders (6 values or fewer) are [−] value [+]; long ones are a dropdown; numbers can be typed. The wheel
  steps any of them.

### 2.5 Footer

- **POWER**: the card's EU/t after overclocking, or "n A tier" when the board's power key says so. Follows the
  board's peak / average switch. When the tier cannot run the recipe it turns red and says `TIER!`.
- **MACHINES**: the solved count, `x1.00` style (3 decimals under 1, 2 under 10, 1 under 100, whole from 100),
  underlined with a small pencil. Click to type a count: that **pins** it (gold). Empty the field to unpin.
  Wheel steps and pins. A zero count is greyed.
- **PARALLEL**: only when the machine runs parallels; shows `x8`.
- **Circuit**: the programmed circuit the recipe needs, or an empty socket.

### 2.6 States

| State | Look |
| --- | --- |
| Selected | 2 px cyan ring outside the card |
| Resource hover | gold glow on every port and wire with that resource |
| Pinned count | MACHINES figure in gold |
| Unwired port | dashed slot; counted in the board notice |
| Can't run | red ring; tooltip says why (tier too low, no power) |
| Recipe missing (pack update) | greyed card that keeps its wires; "Replace the recipe" offered |

## 3. Drawers

120 x 80. Title bar: [−] delete, the resource name, and on products a [⟳] key that cycles
product → byproduct → trash. Body: the slot icon and the signed rate in big digits (`−900 /hr` red for sources,
`+540 /hr` green for products).

- **Source** (red frame, rounded): a rule button and a rate box. Typing a rate on Any switches the rule to Exactly.
- **Product** (green frame, square): a rule button and a rate box, then a reading of what the plan actually makes
  with a bar toward the target. Typing a rate on Any switches the rule to At least.
- **Byproduct / Trash**: no rule; they show what arrives.
- **Rules**: `~ Any`, `≥ At least`, `= Exactly`, `≤ At most`. The rate box takes shorthand (`2.5k`, `1/3`) in the
  board's unit. Empty, it reads `rate?` and pulses when the plan has nothing to solve for.
- A product whose target can't be met shows its box in red, and the notice names the limit.

## 4. Wires

- Made automatically when a card is added (to whatever already makes or uses the same items), and when a port is
  dragged onto a card. One wire per resource between two objects.
- Drawn in the resource's colour, thicker with more flow, arrowheads along the run, hop bumps where two cross,
  routed on the grid with right angles.
- Right-click a wire: Add a drawer here, Delete wire. Redrawing an existing wire deletes it.

## 5. Solving and notices

- The plan re-solves after every change, in the background. While it works the board says so; nothing freezes.
- Inputs to the solver: product and source rules, pinned machine counts, and each card's machine settings.
- Notices along the top of the board, each with **Show me** (frames the cards it is about):
  - Nothing to solve for: "Set a rate on a product, or pin a machine count."
  - Not wired up: N ports.
  - Can't reach a target: which product, and what caps it.
  - Pins that disagree: names the pins to drop.
  - Loops that drain themselves or clog themselves.

## 6. Board-level controls

Plan tabs; undo / redo; unit key (/t, /s, /min, /hr); power key (EU/t or amps at a tier); peak / average;
arrange; fit; help. A totals rail (folded by default) lists inputs, outputs, power and the machine shopping list.

## 7. What we keep from PlanNH, and what is new

| Keep (engine) | New (UI and model) |
| --- | --- |
| NEI hooks: the "+" overlay, R/U lookups, auto-wire context | Card widget and its menus, machine switch, tier and amps chips |
| Per-mod recipe providers and machine profiles (settings) | Drawers with rules, and their place in the save format |
| GregTech's real overclock calculator | Wire renderer (FF look), hover glow, notices |
| The ojAlgo balancer and its 131 tests | "What makes / uses this?" picker |
| Save / share encoding, undo, auto-layout, wire router | Totals rail, board controls |

Engine work the UI needs:

- Drawer rules mapped onto the balancer: products as targets (At least / Exactly / At most), sources as limits.
  Today targets live on a node's output and only mean "exactly"; At most and source limits are new.
- Solving off the render thread (today it runs inside `draw()` and can freeze the screen).
- Machine switch: list the machines that can run a recipe (same recipe map, other controllers).

## 8. Porting order

1. **The card, static**: a real GregTech recipe drawn in the new style with real numbers, in the dev client.
2. **Card controls**: tier and amps chips, machine switch, settings, the actions menu, pinning the count.
3. **Drawers, wires and solving**: rules, auto-wiring, background solve, notices.
4. **Getting recipes in**: NEI "+", P over an item, the port pickers.
5. **Around the board**: totals rail, plan tabs, undo, save and load.
6. **Polish**: arrange, hover glow, zoomed-out glance view, tooltips with gesture hints.

## 9. Open questions

1. Card actions: always the [≡] menu (as drawn), or FF's separate keys (−, clone, replace, +) on single-block
   machines?
2. "Add another recipe to this machine" (shared machines): first version or later?
3. When a card's output has nowhere to go, auto-create a byproduct drawer, or leave it unwired and flag it?
4. Chanced outputs: show the chance on the port, or only in the tooltip?

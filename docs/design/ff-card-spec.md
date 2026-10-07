# Factory Flow's recipe card, measured from its source

What Factory Flow (`C:\Users\jack\gtnh-factory-flow`) actually draws for a recipe card in Solve mode, read from the
code (2026-10-06). Paths are in that repo. Numbers are FF board pixels at zoom 1 on a 380 px card; ours is 320, so
multiply by 0.842. The default font is Inter.

## A trap when reading FF's classes

`src/app/globals.css:460-465` has an unlayered `button, input, select, textarea { font: inherit; }`. Tailwind v4
puts utilities in a layer, and unlayered CSS beats layered, so on every `<button>` the `text-[11px]`, `font-bold`
and `leading-*` classes do nothing. The button takes the card's base: **16 px, weight 400, line-height 24 px**.
That covers the amps and tier chips and the coil well. Below, "effective" means what is really drawn.

## Theme tokens (`globals.css:128-158`)

| Token | | Token | | Token | |
|---|---|---|---|---|---|
| --mc-100 | #5a5c65 | --mc-61 | #2d2f35 | --mc-33 | #1d1f23 |
| --mc-96 | #52545c | --mc-56 | #2a2c31 | --mc-29 | #1a1c20 |
| --mc-93 | #4e5058 | --mc-55 | #292b30 | --mc-25 | #17191d |
| --mc-85 | #4a4c54 | --mc-54 | #282a2f | --mc-15 | #111317 |
| --mc-78 | #3c3e45 | --mc-49 | #26282d | ink | #e8e9ee |
| --mc-71 | #36383f | --mc-47 | #25272c | ink-muted | #9a9ca4 |

Highlights: selection #22d3ee, glow line #ffd257, glow halo rgba(255,202,84,.8), pulse period 1.9 s.

## Card frame

- 380 wide; content inset 8 each side (364). Height is always a whole number of 20 px cells.
- Face #3c3e45. Inset 2 px frame #52545c, then 2 px bevels: light #5a5c65 top and left, dark #1d1f23 right and
  bottom. Square corners.
- Drop shadow on every node: `drop-shadow(6px 8px 7px rgba(0,0,0,.45))`; wires `4px 5px 5px rgba(0,0,0,.35)`.
- Selected: 2 px #22d3ee ring outside the edge.

## Head row (40 tall, items 24 tall)

- Hamburger 24×24: 2 px border #111317, bg #26282d, bevel 2 px #4a4c54 / #17191d, hover #2d2f35. Menu icon 14 px.
- Name bar: 24 tall, 2 px border #1d1f23, bg #2d2f35, bevel 2 px #4a4c54 / #1a1c20. White 13 px regular, centred
  (fits by shrinking to 9 px, then ellipsis). Chevron 12 px at left 2.
- **Amps and tier chips**: two 64×24 buttons, touching. Both in the tier's colours (bg, 2 px border, text, and a
  `1px 1px 0` text shadow in the border colour). Bevel `inset 2px 2px 0 rgba(255,255,255,.55)`,
  `inset -2px -2px 0 rgba(0,0,0,.45)`. Only the tier chip underlines (UV and up). Effective text 16 px.
  Hover brightens 10%. In EU/t mode both go neutral (#4a4c54, border #1d1f23, ink).

Tier colours (`tier-colors.ts`); border and text shadow are the colour at 55%:

| Tier | Bg | Text | Border | | Tier | Bg | Text | Border |
|---|---|---|---|---|---|---|---|---|
| ULV | #FF5555 | #fff | #8C2F2F | | UV | #00AA00 | #fff | #005E00 |
| LV | #00AA00 | #fff | #005E00 | | UHV | #AA0000 | #fff | #5E0000 |
| MV | #FFAA00 | #111 | #8C5E00 | | UEV | #AA00AA | #fff | #5E005E |
| HV | #FFFF55 | #111 | #8C8C2F | | UIV | #0000AA | #fff | #00005E |
| EV | #555555 | #fff | #2F2F2F | | UMV | #FF5555 | #fff | #8C2F2F |
| IV | #5555FF | #fff | #2F2F8C | | UXV | #AA0000 | #fff | #5E0000 |
| LuV | #FF55FF | #fff | #8C2F8C | | MAX | #FFFFFF | #111 | #8C8C8C |
| ZPM | #55FFFF | #111 | #2F8C8C | | | | | |

## Port rails

- One column per side, 112 wide; the picture takes the middle. Rows stack with no gap.
- **Tile** 112×40: bg #36383f, 2 px border #25272c, 1 px bevel #4e5058 / #25272c.
- **Icon**: 28 px box at x 4, bare (no slot), art fitted to 24 px; shadow `drop-shadow(0 2px 3px rgba(0,0,0,.5))`.
  Fluids: a 22 px square.
- **Name**: 9 px bold #e8e9ee, at most 2 lines. **Rate** (Solve): 12 px weight 500 #9a9ca4, `formatCompact` +
  "/hr" or " L/hr". No chance text: the chance is in the rate, and the tooltip says "Average output".
- Wires dock anywhere on the card's perimeter on the 20 px grid (except one cell in from a corner), at a cost of
  0.25/px from the side's centre, facing the far end (`FactoryFlow.tsx:1144-1202`).
- **Unwired** (`globals.css:2128-2180`): tile border dashed #8f9bad on #2f3640, plus a white 2 px dashed overlay
  breathing 0→1→0 over 1.9 s. The card gets a dim 2 px ring rgba(200,210,224,.25) with a breathing lit ring
  rgba(238,242,248,.85) and a 12 px halo.
- **Hovered port**: it and every port at the far end of its wires get `0 0 0 2px #ffd257, 0 0 10px 2px` halo.
- **Dragging a wire**: source ring amber #ffd236, compatible ports cyan #53eafd.

## Picture well

- Fills the space between the rails, at least 80 tall. 2 px border #25272c, padding 4, sunken bevel
  (rgba(0,0,0,.3) top-left, rgba(255,255,255,.04) bottom-right). Bg ≈#26282c (power cards ≈#322b1c).
- Art is fitted and pixelated, shadow `drop-shadow(5px 7px 6px rgba(0,0,0,.6))`. A plain item is 64 px.

## Settings row (COIL)

- Tile spanning the width, 37 tall: 1 px border #25272c, bg #36383f, 1 px bevel #4e5058 / #25272c.
- Caption "COIL" above, 11 px uppercase #9a9ca4, left.
- **Well**: a raised button 20 tall, full width: 1 px border #25272c, bg #4a4c54, bevel #5a5c65 / #282a2f, hover
  #4e5058. Centred: an 18 px coil face, the name without " Coil Block", a 12 px muted chevron.

## Footer

- Hairline 1 px #2a2c31, 6 px gap, then tiles 35 tall with 4 px gaps: POWER (auto) | MACHINES (≥84) | circuit (36).
- Tile: 1 px border #25272c, bg #36383f, 1 px bevel #4e5058 / #25272c. Label 11 px uppercase #9a9ca4; value 14 px
  weight 500.
- POWER: value + "EU/t" 8 px muted. Stalled: red tile, "LOW!" or "TIER!".
- MACHINES: "×0.338" with a dotted underline, a 9 px pencil; gold #ffd257 when pinned, muted at 0.
- **Circuit: icon only, no number**, 36 wide, art at 1.5× with brightness 1.9 / contrast 1.15 / saturate 1.2.
  Empty: sunken #25272c with a faint CPU outline.

## Tooltips

Panel: 2 px border #111317, bg #26282d, bevel 2 px #4a4c54 / #17191d, shadow `2px 3px 6px rgba(0,0,0,.2)`.
Title 16 px semibold #f5f5f5 with a mode tag on the right (Solve #c78bff). Rows: label #a3a3a3 left, value
weight 500 #f5f5f5 right. Actions under a #3a3b40 rule, each a mouse icon and a label.

### POWER INPUT panel (`HatchPowerControls.tsx:119-379`)

Shown when hovering the amps/tier chips or the POWER tile. Sits 6 px above the card, right edges aligned (flips
below if no room). 480 wide. Rows:

1. "Power input" (semibold).
2. "4A × [EV] = 8,192 EU/t" on the left; "2.0 [EV] hatches (2A per hatch)" on the right.
3. Two-column stats between rules: Parallels (if any), Overclocks ("1 perfect, 2 imperfect"), Time / run,
   Runs / second, EU / run, Draw · EU/t.
4. "Next improvement" with "+9.57A [EV]" on the right, then comparison boxes (42 tall, #1d1f23, 1 px #3a3b40):
   label muted, then "2 → 3" with an arrow.
5. Scale: "0A" at the left; a 10 px bar (#1d1f23 well, fill #9a9ca4) from 0 to the next win; markers for
   "13.57A next" (end), "4A supplied" and "3.38A draw", each a label, a 1 px guide and a 3 px ink tick.
6. "Demand for this card" with the value in bold; "Across the required machines." below.

Formulas:

- Pool EU/t = V(tier) × amps. Hatches = amps / (amps == 1 ? 1 : 2), one decimal.
- Affordable overclocks = floorLog4(floor(V × amps / max(ceil(|eut| × heatDiscount × parallels), 32))).
- EBF heat: excess = coilHeat + 100 × max(0, tier − MV) − recipeHeat. Perfect steps = min(affordable,
  floor(excess / 1800)), taken first: ÷4 time, ×4 EU/t. Normal steps: ÷2 time, ×4 EU/t. Heat discount =
  0.95^floor(excess / 900).
- Duration = floor(base / 4^perfect / 2^normal); multiblocks below 1 tick run 1/ceil(1/d) per tick.
- Runs/s = parallels × 20 / duration. EU/run = |eut| × duration. Draw = |eut| × parallels.
- Next win: the smallest EU/t above the pool at which runs, overclocks or parallels change (scan by 2^0.25 from 1,
  then bisect). "+X A" = ceil((next − pool) / V × 100) / 100.
- Demand = per-machine draw × required machines (fractional).

### Gesture guide (`HatchPowerControls.tsx:381-402`)

224 wide, to the right of the power panel (4 px gap, bottoms aligned). Two sections, "Amps" and "Tier", each with a
2 px left bar that lights (#a3a3a3) for the hovered chip.

- Amps: click to type; right-click −1; wheel ±1; Ctrl ±10; Ctrl+Shift ±1000; Shift walks powers of four.
- Tier: click or wheel up steps up; right-click or wheel down steps down. Amps are kept (at least 1A).

### Other card tooltips

- Port: name, "Input"/"Output"; "Consumed"/"Produced"/"Average output"; unwired adds "Unconnected" and "You must
  connect this input."; actions Recipes (left), Uses (right), Drag to connect.
- Machines: "Required machines" (or "Pinned machines"); count; if fractional, whole machines and average
  utilization. Action: Pin count.
- Coil: "Heating Coil", the coil; Time per operation, Draw per machine, Parallel operations.
- Name bar: machine, "Multiblock"/"Machine"; Required machines, Configured tier, Time per operation, Draw per
  machine, Parallel operations.
- Circuit: "Programmed circuit", "Required setting N", "Not consumed."

## Drawer (Solve, `StorageNode.tsx`)

120×80. Source: 12 px rounded; product: 4 px radius. Frame `mix(tint 55%, #262b34)`, fill `mix(tint 24%,
#101318)`; tint source #ffa2a2, product #5ee9b5. Title bar 18 tall, `mix(tint 30%, #0b0d10)`, name 9.5 px bold
fading out at the right. Body: 32 px icon, then a rule row (gold rule button ~/≥/=/≤ and a gold rate box on
#0f1114) and a reading well (signed rate green/red/grey and a 3 px rule bar: #3fae5c met, #cf3333 unreachable).

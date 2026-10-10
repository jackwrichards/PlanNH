# Bacterial Vat and Tree Growth Simulator

Both machines modelled from the game's code (GT5-Unofficial 5.09.54.205, the GTNH 2.9 RC-2 pack's, checked
against the jar's bytecode), on the website and in the mod, with the same settings, the same numbers and the
same worked formulas. The wiki is wrong in several places; the code wins (listed at the end).

## Where it lives

- Website (3.10.0): `src/lib/machines/tree-growth-simulator.ts`, `bacterial-vat.ts`, `formula-lines.ts`, the side files
  in `src/lib/machines/data/`, the machine table's entries (`controlsFor`, the vat's `recipeGate`), the TGS branch of
  `getOverclockedRecipeStats`, `getMachineOutputMultiplier`, the vat's inputs in `applyRecipeInputOverrides`, and
  the cards' Formulas strip (`components/flow/MachineFormulas.tsx`).
- Mod: `machines/` (pure, the same numbers and lines) and `machines/game/` (what GregTech says, the solver's per-port
  multipliers, the radio hatch's input, the gear sheet's settings and formulas). Plans carry the settings both ways
  under the website's keys.
- Before: the website overclocked the TGS's 0 EU recipe (100 ticks at LV, 1 tick from ZPM, at 0 EU) and ran the vat at
  x1 with no glass or sievert check; the mod showed NEI's amounts every 5 s at 0 EU/t and the vat's glass switched on
  the heat settings.

## Tree Growth Simulator (`MTETreeFarm`)

The sapling sits in the controller and is never used up. Every 5 s (100 ticks, fixed: no overclocks, no
parallels, no batch) it makes, for each output mode that has a tool:

    out per run = NEI amount x tierMult(t) x toolMult        NEI amount = product x modeMult (log 5, sapling 5, leaves 2, fruit 1)
    tierMult(t) = 2t^2 - 2t + 5                               LV 5, MV 9, HV 17, EV 29, IV 45, LuV 65, ZPM 89, UV 117, UHV 149, ...
    EU/t        = VP[t] = V[t] x 30/32                        LV 30, MV 120, HV 480, EV 1920, IV 7680, ...
    t           = max(1, tier of V x A rounded up)            one hatch: its tier; two hatches of tier T: T+1

Tools (one per mode, in an input bus; several of a mode do not stack; no tool, no output of that mode):

| Mode | x1 | x2 | x4 |
|---|---|---|---|
| Logs | Saw, Pocket Saw | Buzzsaw (LV/MV/HV) | Chainsaw (LV/MV/HV) |
| Saplings | Branch Cutter, Pocket Branch Cutter | | Grafter (Forestry) |
| Leaves | Shears | Wire Cutter (pocket) | Automatic Snips (electric Wire Cutter LV/MV/HV) |
| Fruit | Knife, Pocket Knife | | |

The Pocket Multitool counts as x1 in every mode. A tool's material never changes the amount, only how long it
lasts: each mode with an output wears its tool once a run (720 uses an hour). GT tools last
floor(durability x tool multiplier) uses (branch cutter 0.25, MV electrics 2, HV electrics 4, pocket tools 4);
electric GT tools take 100 EU of their own charge a use (20 EU/s) and lose durability only 1 use in 25.
Shears last 238 uses, a Forestry grafter 4, a proven grafter 149.

Forestry saplings (key `Forestry:sapling:<species>`) scale by the inserted sapling's genes: logs x height x girth
(height = max(3 x (heightAllele - 1), 0) + 1), saplings x max(1, (int)(n x fertility x 10)), fruit x
max(1, (int)(n x yield x 10)), leaves unscaled. NEI shows the species' default genes.

Worked: Oak at LV (saw, branch cutter, shears, knife) 5 logs, 5 saplings, 2 leaves, 1 apple a second at 30 EU/t;
at IV (chainsaw, grafter, automatic snips, knife) 180, 180, 72 and 9 a second at 7680 EU/t. Giant Sequoia (80
logs in NEI): 80 logs/s at LV with a saw, 2880/s at IV with a chainsaw.

### Card

- One sapling per card, swapped in place (the EEC's mob picker is the pattern). The sapling shows as the card's
  catalyst, never a port.
- Settings: energy tier (and hatch count, for t), a tool per output (None / each tool with its x), and for
  Forestry saplings the height, saplings and yield genes (default: the species').
- Ports: only the outputs that have a tool. EU/t = VP[t]. Duration 100 ticks at every tier.

## Bacterial Vat (`MTEBioVat`)

A recipe needs the culture's petri dish in the controller (never used up), glass of at least the recipe's tier,
and radiation from the one radio hatch: at least the recipe's sieverts, or exactly them when the recipe says so.
Overclocks are GT's normal ones (2x speed for 4x power, one hatch = 1 A, N plain hatches = 2N A; no multi-amp or
laser hatches). The fluids scale with how full the output hatch is kept:

    f      = stored output / output hatch capacity (at the moment the next run is checked)
    M      = 1 when the hatch is empty or holds another fluid; else max(1, ceil(1000 x (1 - (2f - 1)^2))) + 1   (2..1001)
    M_eff  = max(1, min(M, floor(input fluid buffered / recipe's fluid in)))
    fluid in and fluid out x M_eff; items, EU/t and duration unchanged
    fits   = M_eff x fluid out <= capacity - stored   (void protection on: the run waits; off: the rest is voided)

M is 1001 within 1.58% of half full. Capacity: 8000 x 2^tier (HV 64k, IV 256k, LuV 512k), the Giant Output
Hatch 100M; an ME output hatch keeps nothing, so x1.

Radiation: effective Sv = s - ceil(s x shutter% / 100). Material s: a stick's or long stick's proton count
(bismuth 83 and up), Naquadah 130, Enriched 140, Naquadria 150, fuel rods by material, depleted rods a tenth. The
hatch burns material all the time, running or not: one mass unit every decay(s) ticks
(s <= 100: ceil((8000 tanh(-s/20) + 8000) x 1000); above: ceil(8000 tanh(-s/65) + 8000)); a stick is mass 1, a
long stick 2, rods 3/6/12. Uranium 92: a stick lasts 1617 ticks (80.85 s). Radiation lost mid-run voids that
run's fluid output.

Worked: Biomass 100 L -> Fermented Biomass 100 L, 150 ticks at 2 EU/t, HV glass, one HV hatch: 2 overclocks, 32 EU/t,
37 ticks; kept half full in an IV hatch (M 1001): 100,100 L a run, 54,108 L/s in and out.

### Card

- Settings: glass tier (the recipe's minimum by default; below it the card says why it cannot run), the radio
  hatch's material and shutter (only on recipes that need sieverts), the output hatch (tier, Giant, ME) and the
  fill it is kept at (half by default, so x1001), and the usual energy tier and hatches.
- Ports: the recipe's fluids x M, its items, and the radio hatch's material as an input at its burn rate.
- Warnings: glass too low, sieverts too low or not exact, the scaled output does not fit the hatch.

## Formulas

Each card shows its working, as the crop farm's card does on the website: a Formulas strip that folds, one line
per step (label, the working with the card's own settings plugged in, then "= result"), each setting's number in
that setting's colour. In the mod it is a section of the card's gear sheet (readable there, never pinned to the
card).

- TGS: `tier` (V x A -> t), `mult` (2t^2 - 2t + 5), one line per output (NEI x mult x tool / 5 s), `power` (VP[t]).
- Vat: `fill` (f -> M), `fluids` (in and out x M), `time` (overclocks), `glass`, `rad` (s - ceil(s x c/100) vs
  the need), `burn` (the material's rate), `power`.

## Data

The website's dataset lacks what the cards need, and a new export is not deterministic (it re-mints recipe ids).
As with `data/fusion-startups.json`, the missing facts go in side files matched to dataset recipes by content and
generated from the running pack (never hand-edited):

- `tgs-trees.json`: per tree, its sapling, its outputs by mode (base amounts), Forestry species' default genes.
- `bio-vat.json`: per vat recipe, its GLASS and SIEVERT (value, exact) and its culture's name.
- `radio-hatch.json`: the radio hatch's materials (item, sieverts, mass).

The mod reads the same facts straight from the game, and checks them against these files in a dev command.

## Where the wiki is wrong

TGS: multi-amp hatches up to 64 A are accepted (beside at least one plain hatch); two hatches run a tier up and
draw more than 1 A; at least one input and one output bus are required; Oak gives 1 apple, not 2; the Pocket
Multitool works in every mode. Vat: any matching fluid in the output hatch gives at least x2; the input buffer
caps the multiplier; void protection stops a run whose output does not fit; glass gates the recipe's glass value,
not its voltage; a "disabled" radio hatch still burns its loaded material; losing radiation mid-run voids the
output; an ME output hatch gives x1.

# What GregTech's ProcessingSpec does not yet tell PlanNH

PlanNH plans a GregTech multiblock from the `ProcessingSpec` the machine declares
(`GTSpecReader`). A number the spec leaves unset is planned at the plain default: parallel 1,
speed and EU 1, the standard 2/4 overclock. This file lists the machines where that default is
wrong, and what GregTech or PlanNH would need to fix each one. `/plannh_machines` writes the
current numbers of every machine to `plannh-machines.md`.

Numbers a machine only reaches while it runs are declared at their best and marked
`bestCase`: the Industrial Centrifuge at full momentum, the Black Hole Compressor with a stable
black hole, the HIP Compressor before it overheats, the Mass Solidifier at full speed-up. The
table's `assumes` column lists them. They are not gaps and are not repeated below.

## Numbers that depend on an item or upgrade in the machine

The spec reads the energy hatch tier, the mode and the structure. It cannot read an item in a
slot, so each of these needs a GregTech input for the upgrade and a PlanNH row to choose it.

| Machine | Unset in the spec | Depends on |
|---|---|---|
| Latex Factory | parallel (plans at 1) | 8 per voltage tier, doubled with a singularity in the controller |
| Industrial Electromagnetic Separator | everything (no spec) | the magnet in the magnet hatch |
| PCB Factory | everything (no spec) | nanites in the bus, linked upgrade structures, the roughness setting, the cooling tower |
| Spinmatron | parallel, speed, EU, max overclocks | turbines in slots; its own speed slider, which is not `machineMode` |
| Quantum Force Transformer | parallel | catalyst count |
| Industrial Arc Furnace (KubaTech) | parallel, overclock ratios | the electrode: parallel limit, OC factors, the Infinity electrode's ramp |
| Nano Forge | parallel, speed, overclock | magmatter and per-recipe logic |
| Plasma Forge | overclock | convergence, which gives perfect overclocks while it runs |

## Numbers that depend on amperage

The spec reads the energy hatch tier, not how many amps reach the machine.

| Machine | Planned | Machine |
|---|---|---|
| Black Hole Compressor | 8 parallel per hatch tier | 8 per tier of the total input EU/t, so more amps add parallels |

## Numbers that depend on the recipe

The spec's formulas cannot see the recipe. GregTech would need recipe-dependent terms.

| Machine | Unset in the spec | Formula |
|---|---|---|
| Mega Alloy Blast Smelter | EU modifier (plans at 1) | 0.95^(coil tier + 1 - recipe tier) |
| Component Assembly Line | speed | casing tier against the recipe's tier |
| Large Fusion Computer 1-5 | parallel | scales with the recipe's startup energy |
| Naquadah Fuel Refinery, Spinmatron, Nano Forge | max overclocks | per recipe |
| DE Fusion Crafter | heat (no spec) | 1800 x (casing tier - the recipe's casing tier), against recipe heat 0 |

## Structure values GregTech does not declare yet

These are Stage 2 gaps: the machine reads a block its structure check finds, but does not
declare it as a structure parameter, so the spec cannot read it.

| Machine | Undeclared | Drives |
|---|---|---|
| Electric Implosion Compressor | containment block tier | parallel 4^(tier - 1) |
| Entropic Processor | its two casing tiers | machine heat (casing tier + 1) x 1800, parallel 8 or 32 |
| Solar Factory | structure tier, precise casing tier | parallel 8-64 |
| Industrial Arc Furnace (GT++ Legacy) | structure size 3/5/7 | parallel |
| DE Fusion Crafter | casing tier | machine heat |

## Machines that never overclock in ways the spec cannot state

`ProcessingSpec.noOverclock()` runs a recipe at its own voltage, as
`OverclockCalculator.ofNoOverclock` does. These differ:

- **Algae Farm** - recipe EU/t is 90% of the energy hatch's voltage, not the recipe's.
- **BEC I/O Node** - disables overclocks but keeps the hatch voltage as the cap; parallel is a
  GUI setting.
- **Transcendent Plasma Mixer** - wireless power; parallel is a GUI setting.
- **Liquid Fluoride Thorium Reactor** - a generator; power comes from each recipe's metadata.

## Machines with no ProcessingLogic at all

Their own `checkProcessing` does not use `ProcessingLogic`, so a spec would never be applied:
the eleven nanochip assembly modules, the Integrated Ore Factory, the Tree Growth Simulator and
the Steam Water Pump.

**Eye of Harmony.** Its recipe check (`checkProcessing_EM`) builds no overclock calculator, and
its numbers are not overclock numbers:

- Parallel is `2^floor(log(8 x arrays) / log 1.7)`, where `arrays` is the astral arrays it has
  taken from its input bus into an internal counter, capped at 8637. That is an item count,
  which the spec cannot read.
- Duration and output depend on the spacetime compression, time acceleration and stabilisation
  field tiers. GregTech does not declare these as structure parameters.
- The time discount compares the recipe's required spacetime tier with the built one, so it
  also depends on the recipe.

PlanNH keeps its own copy of the parallel formula (`GTProvider`, with the node's astral array
row). It does not model time acceleration or the spacetime discount.

## PlanNH-side gaps

- **Fusion recipes get a perfect overclock by a PlanNH rule** (`GTProvider`) when a node has no
  machine selected. It only approximates GregTech: `FusionOverclockDescriber` overclocks 2/2,
  capped by the recipe's startup energy, and only `AdvancedFusionOverclockDescriber` overclocks
  4/4. A node with a fusion computer selected uses the machine's own describer.

- **Steam multiblocks are planned in EU, not steam.** The spec gives their real speed, energy cost
  and no-overclock, and outranks GregTech's steam describer. That describer is the bronze
  singleblock's (2x duration, 1x EU), which is not what the multiblocks do. The unified GT profile
  still reports EU/t; GregTech burns 2 L of steam per EU. The separate manual "GT Steam" profile
  converts, but it is hand-entered.
- **Steam multiblocks refuse recipes above their recipe voltage** (`getTierRecipes()`), and PlanNH
  does not model that limit: the picker offers them for recipes they will not run. Other
  multiblocks' `validateRecipe` restrictions are not modelled either.
- **The steam structure row is the generic Structure kind** (1 = bronze, 2 = steel). A kind named
  for it in GregTech's `TooltipTier` would read better.
- **The best-case assumption is only noted in `plannh-machines.md`.** A node planned at a best
  case does not say so in the chart.

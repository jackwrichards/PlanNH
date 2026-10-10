# Every GregTech card as the website computes it

The mod's GregTech cards run on generic engine knobs (speed, parallels, perfect overclocks, overclock limits, heat
overrides...) fed to GT's `OverclockCalculator`. The website knows each machine instead: its curated machine table
(`src/lib/machines/machine-table.ts`, 96 machines checked against a reference calculator and the game's code), the
dataset's machine handlers, and modules for the special machines. This port makes the mod compute every GT card exactly
as the website does, offer exactly the website's settings for it (under the website's keys, so plans go both ways
unchanged), and drop every knob the website has no equivalent for. Then every card gets the Tree Growth Simulator's
gear sheet: its setup beside its worked formulas.

Owner's call (2026-10-10): "the website's machine table already knows... drop everything else the website can't do,
then go to this new theme".

## What the website computes, and what it reads

The node math (`throughput.ts:171-214`): `getOverclockedRecipeStats` (overclock.ts) gives the run tier, overclock
steps (normal and perfect), duration (whole ticks; a multiblock banks sub-tick speed as parallels) and EU/t;
`getMachineParallelMultiplier` = min(structural parallels, what the power pays for); `getMachineOutputMultiplier` per
output; `getNodePowerReport` the power state and gate. Branches run in order: power cards, the handler, fusion, the run
tier, EEC, TGS, runtime variants, crops, apiaries, then the generic path (table or scraped coefficients, heat discount,
parallels paid before overclocks, voltage caps for singleblocks, perfect then normal steps, quantising).

- The runtime variants (the game's own calculator, run per recipe at export) agree with the generic path for every
  singleblock recipe (1,622,126 of 1,622,126 recipe/tier pairs); the port uses the generic path only.
- The dataset supplies what the code cannot: per recipe map, the machine handlers (which machines run it, kind,
  minimum tier, a singleblock family's tiers, perfect overclocks, baked duration/EU multipliers), scraped controls
  (coil ladders, solenoids, voltage parallels...) and metadata (EEC mobs, fusion startup EU). Table machines take only
  the control definitions from it; the 31 multiblocks outside the table run on its scraped numbers.

## The port

1. **Data from the website, never hand-edited** (as `assets/gtnhplanner/power/`): `assets/gtnhplanner/machines/`
   - `handlers.json`: per recipe map, its handler templates and recipe-level controls, from the dataset
     (`tools/audits/export-mod-machine-data.mjs` in the website repo).
   - EEC mob data and fusion startups by recipe fingerprint, or read from the game where it has them.
2. **The pure port** `machines/web/` (headless, like `power/` and `machines/`): the website's recipe, handler and
   control shapes; the machine table (entries with their formulas); the generic overclock path, hatches and power
   pools, heat, parallels, duration/EU/output multipliers, power state and gates; the special modules (fusion, EEC,
   HILE, Neutron Activator, Precise Assembler, Utupu-Tanuri, Dangote, Extreme Heat Exchanger; TGS and the vat are
   ported already).
3. **Parity**: the website writes a golden fixture by running its own functions over real dataset recipes (about five
   per machine, every fusion/EEC/NA/PrAss recipe) across tiers, hatches and amps, coils and every option of every
   control; the mod's JUnit reads it and must reproduce every number (ticks exactly, EU/t to a relative 1e-9).
4. **The game side**: a card becomes the website's recipe (its map from `GTProvider.RECIPE_MAP`, its handler from the
   catalyst: a multiblock by display name through the table's names and aliases, a singleblock by its "Machine Type"
   family, the Precise Assembler and Dangote by map). The result feeds `EffectResult` (duration, EU/t, parallels) and
   the per-port multipliers; a gate holds the card.
5. **Settings**: the card's settings are the website's: the tier and hatches (`hatchVoltageTier`, `hatchAmps`, the
   hatch type), the coil (`coilTier`), `machineConfigTiers` (every resolved control) and `machineHandlerId`. The
   generic knobs go: speed, parallels, perfect/laser/no overclock, discounts, per-overclock factors, limits, tier
   skips, heat overrides, astral arrays. Saves migrate (`machine_heat` to the coil key; the rest dropped).
6. **The sheet**: every card's gear sheet is Setup (its controls) beside Formulas (the overclock working: tier from
   V x A, steps, time, power, parallels, the coil's heat bonus, each output's rate; only the lines that apply).
7. **Plans** carry the website's node fields both ways (`FfSettings`, `PlanExport`).

Untouched: the non-GT providers (their own profiles and `TICK_MODIFIER`), power cards, the modelled TGS and vat.
Fixed on the way: steam parallels applied twice (squared).

## Where it lives

`machines/web/` is the pure port, named after the website's files: `Web` (its recipe, handler, control and node shapes,
Gson-ready), `Tiers`, `EnergyHatches`, `Power` (power.ts and power-input-rules.ts), `Heat`, `RecipeRules` (handlers,
handler applied, settings resolved), `MachineEffects` (context, parallels, speed, power, outputs), `Overclock`,
`PowerReport` (and the steam report), `NodeMath` (a card's nameplate as throughput.ts takes it), `MachineTable` with
`MachineTableEntries` and `MachineControls`, the special machines (`Fusion`, `ExtremeEntityCrusher`, `Hile`,
`NeutronActivator`, `PreciseAssembler`), `HandlerData` (handlers.json) and `Js` (JavaScript's number and string
semantics where the maths lean on them). `tools/dev/webcheck.sh <out-dir> [test regex]` compiles and tests the package
with javac alone, beside a running Gradle build.

Left out on purpose: the website's crops (CropsNH, not in the pack) and bees (the mod's own providers), its runtime
variants (they agree with the generic path), recipe input overrides, and the Tree Growth Simulator and Bacterial Vat
(`NodeMath.covers` says no; `machines/game/MachineModels` runs them, already matched to the website).

## A card's settings, as the website's node fields

Stored in the card's settings map under these keys, so plans go both ways unchanged:

| Website field | Setting | Notes |
| --- | --- | --- |
| `overclockTier` (singleblock), `hatchVoltageTier` (multiblock) | `voltage` | the tier, as now |
| `hatchAmps` | `amp` | any amps up to 16,777,216 (the website's MAX_HATCH_AMPS), decimals kept |
| `energyHatchType` | `energy_hatch_type` | "standard" when absent |
| `coilTier` | `coil` | a coil key; old `machine_heat` migrates to the hottest coil at or under it |
| `machineConfigTiers[id]` | `machine:<id>` | every resolved setting, as the TGS and vat already do |
| `machineHandlerId` | the card's machine (`Node.machineName`) | matched to a handler by name: multiblocks by display name through the table's names and aliases, singleblocks by family |

A new multiblock card gets the website's seed (`normalizeHatchInput`): its recipe's tier with whole amps enough for every
structural parallel. Switching between a singleblock and a multiblock carries the voltage (`carryMachineVoltage`).

The engine's `EffectResult` takes the result as the balancer reads it: whole ticks, the machine's draw (EU/t times
parallels) and its parallels; a multiblock's sub-tick duration 1/n runs as one tick with n times the parallels, the
draw unchanged.

## Machine identity

Multiblock handler labels are the NEI catalyst display names: the table's normalised names and aliases match 93 of the
in-game controllers exactly. Singleblocks are found by family ("Machine Type" in the tooltip, never on multiblocks: the
Bricked Blast Furnace says "Blast Furnace"). The Precise Assembler's own map and the Dangote's Distillery mode go by
recipe map. Four entries the website never applies (its maps carry no handlers) need site aliases so both agree:
Industrial 3D Copying Machine ("Industrial Chisel"), IsaMill ("Milling"), L.A.T.E.X. ("Cable Coating"), Flotation Cell
Regulator ("Flotation Cell").

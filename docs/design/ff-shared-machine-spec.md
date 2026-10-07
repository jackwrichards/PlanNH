# Factory Flow's shared machines, and ours

Factory Flow (`C:\Users\jack\gtnh-factory-flow`, `src/lib/model/shared-machine.ts`) lets one recipe card run several
recipes that time-share one machine. Read from its source on 2026-10-07; FF board pixels.

## What FF does

- **Data.** A card has sections: section 0 is the card's recipe, sections 1..n its `extraRecipes`. Ports, wires and
  ore-dict picks are per section; machine count, tier, amps, coil, settings and the pin are the card's.
- **Solve mode.** Sections solve independently, each to its own fractional need. The card's MACHINES is the sum.
  Pinning it adds one equality: the sections' machines add up to the pin. Each section's power is its own draw
  times its share; the card's demand is the sum.
- **Card.** One head row (machine bar, amps and tier chips). Then, per section on each side: a 20 px rule row (10 px
  more above it from the second section on), then the rails, as tall as the section's longer side, so its inputs
  and outputs face each other. One picture spans every section in the middle. The input-side rule shows the
  section's reading (`NN%` and a word); the output-side rule its circuit (22 px) and three 16 px keys: up ("Move
  this recipe up"), down ("Move this recipe down"), and X ("Take this recipe off the machine"). Sections are not
  named or numbered: the ports say what each recipe is. One coil row and one footer for the card; the footer's
  circuit moves onto the section rules.
- **Combine.** With two or more recipe cards selected that share a machine handler, a cyan button appears top
  middle: "Combine N into one machine" (tooltip "One machine runs all of these recipes"), `#0b5563` face, 2 px
  `#22d3ee` border. The first card (in plan order) is the host and keeps its position and every setting; the others'
  recipes become its sections, their wires move onto those sections, and the other cards go. One undo step. No
  split action: undo, or X on a section, which removes that recipe and its wires.
- **Add a recipe to the card.** A "+" key on the card head ("Add another recipe to this machine"), the card menu,
  and the machine menu's last row open the recipe browser pinned to the machine's recipes. A recipe no machine on
  the card runs is refused: "No machine runs both X and what Y already has."
- **Machine menu** on a shared card: "Machines that run every recipe on this card", the machines common to all.
- **Tooltips.** Name bar: the machine, "Recipes: N", "Runs its recipes one at a time; each row says how its share
  goes." Power: Peak (the hungriest recipe), Average (weighted by share). Hatch panel: "Demand for this card",
  "All recipes, across the required machines."

## Ours

- A shared machine is a `MachineGroup` with ordered `sections` (node ids, the host first). Each recipe stays a real
  node with its own ports and wires, so the solver and wiring are unchanged; the group draws as one card.
- The group's count is a cap (`machineCapacity`), or with `pinned` the count itself: `SolveInput.PoolIn.exact`
  makes the pool row an equality, and a pinned group anchors the solve like any pin.
- The machine settings (tier, amps, multiblock, coil heat, parallels) are the host's and copied to every section on
  each change. Per-recipe settings (a recipe's own heat) stay per section.
- Adding a recipe: NEI's uses of the card's machine are exactly the recipes it runs, so the card's "+" opens that
  page and arms a lookup; the recipe picked with NEI's "+" joins the card as a section.

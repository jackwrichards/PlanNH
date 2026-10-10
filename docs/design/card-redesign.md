# The card redesign

The clean card, on the board, the minimap, the plan over the world and the zoomed-out view (2026-10-08): geometry in
`ui/card/CardLayout`, the pieces both kinds draw in `ui/card/CardPaint`, the settings strip's chips in
`ui/card/CardChips`; the board's interactive card is `ui/card/RecipeCard`, the snapshot's (world, minimap)
`ui/card/CleanCardView`. `call 'cards?count=6&scale=0.6&match=<name>'` pages through the plan's cards as the
world draws them (`dev/CardGallery`, arrow keys).

## Settled so far (2026-10-08)

- One surface, no boxes inside it: no port tiles, no name bar box, no POWER and MACHINES footer.
- Header: the machine's name large on the left; the amps and tier chips at the board's size on the right, their labels
  one screen pixel heavier than plain (not the game's bold; laid on the screen's pixels so every chip matches at any
  zoom), as far from the top as from the right edge; a hairline under it.
- Ports: inputs against the left edge, outputs against the right, where their wires meet the card; each its icon at the
  edge, how much in large type with its unit small, the name small under it on one line (smaller when long, then cut).
  Rows start at the top; nothing is centred vertically.
- Power is one more port with a bolt: after the inputs for what the machine draws, after the outputs for what a
  generator makes. Hovering it says, in a few words, that plans do not wire machine power (e.g. "Not wired in plans").
- The machine picture in the middle, as large as the column allows (88), the machine count in plain white on its
  bottom right corner.
- The title level with the chips' labels (the game's capitals are seven of its eight rows).
- The recipe's programmed circuit as its icon, large, on the picture's bottom left corner; or, with the setting
  "Circuit as an input", one more input row (its icon, its number large, "Circuit" small). GregTech's circuit icons
  are drawn clearer everywhere a card shows one (`ui/gt/CircuitIcons`, made by `tools/dev/circuit-icons.mjs`: the
  faint "88" taken off the screen and the lit segments darker, nothing else changed).
- A settings strip along the bottom: a chip for the coil (heat recipes; red when too cold), parallels when more than
  one, and every setting changed from its default (`PlanSnapshot.Setting`, from `SnapshotMaker.settings`); a card left
  at its defaults has no strip.
- Settings: a gear key in the header (beside the actions and place keys; red when a setting stops the recipe) opens
  the settings sheet (`ui/card/SettingsSheet`): "Settings" faint at the top, the settings by section (Machine,
  Overclocking, Heat; a power card's Settings and Readings), two columns when there are more than seven, each row a
  pin, a plain name and its control (a switch, a number with arrows, a list). Pinned settings show on the card as chips
  that change in place (click, right-click back, scroll), on every card of the machine in the plan; the pins are saved
  with the plan (`Graph.settingPins`, `ui/card/SettingPins`), the coil pinned by default on a heat recipe. A setting
  changed from the sheet or a chip is remembered for the machine's next card (`ui/card/SettingMemory`, config
  folder); cards on the board and imported plans keep their own. Posting to the library does not carry the pins yet:
  Factory Flow's project format has no place for them.
- Shadows a fifth lighter everywhere (cards, drawers, icons, structure pictures): done for the whole mod.
- The same look carries to the zoomed-out (glance) card, the minimap and the cards over the world.
- Zoomed out, hovering a card shows the clean card itself at full size beside the pointer (`RecipeCard.drawReveal`,
  drawn by `CleanCardView` from the board's snapshot), in place of Factory Flow's reveal panel.

## Where everything goes

Today's card has these controls and settings (`ui/card/RecipeCard`, `Part`); each needs a home in the new card. The
proposal, to settle before building it on the board:

| Today | Proposed home |
|---|---|
| Actions key (≡): clone, machine settings, show in / remove from the world, delete (adding a recipe to a machine was taken out: merging two cards does it) | A small key at the left of the header, before the name |
| Place key (map pin), gold once placed | Beside the actions key |
| Machine (name bar with chevron): pick the machine; wheel steps machines | The name itself: click opens the machine list, wheel steps; a small chevron after it |
| Amps chip (GT multiblocks): click a number box, right click -1, wheel ±1 / Ctrl ±10 / Shift ×4 | The amps chip, as now |
| Tier chip: click up, right click down, wheel; power cards' tier select | The tier chip, as now |
| Power input panel (hover amps, tier or power on a GT multiblock) | Same panel, on hovering the chips or the power port |
| Machine count (MACHINES tile): click a number box (empty unpins), wheel ±1 pins; gold when pinned | The count on the picture's corner: click a number box, wheel ±1; pinned shown by a small pin mark beside it, not by colour |
| PARALLEL ×n (on MACHINES) | Under the count, small ("4 parallel"), when more than one |
| Coil (heat recipes): pick a coil, wheel steps; red when too cold | A chip in a slim settings strip along the bottom |
| Circuit (display only; empty socket when none) | A badge on the picture's bottom left corner (it is a setting, not a flow: nothing is consumed). The alternative is one more input port with the circuit's icon and no rate |
| Everything else in "Machine settings" (speed, parallels, perfect OC, overclock limits, heat discounts, steam modifiers, other mods' per-tick costs...) | Stays in the menu; any setting changed from its default also shows as a chip in the settings strip, so a card says what is unusual about it at a glance |
| Port slots: click makes / uses, drag a wire, R / U arm; unwired ports dashed and pulsing; glow on resource hover | The same on the new ports; unwired: the icon's outline dashed and pulsing |
| Chanced outputs: chance only in the tooltip | Small "25%" after the name |
| States: selected ring, red ring when the tier is too low, link-mode rings, lifted while carried | Unchanged; the tier chip turns red when too low |

Power (non-recipe) cards: their setting tiles become chips in the settings strip (arrows or wheel step them, click
opens the pick list or number box, as the tiles do now); their readings sit under the strip, small; warnings stay as
amber lines at the bottom. The amber name bar becomes the header's name, amber.

Shared machines (one card, several recipes): each recipe keeps a slim rule row over its ports (its share of the
machine's time, its circuit, up / down / remove keys); the count is the group's; edits are copied to every recipe.

## To design later

- Machine settings as chips: which settings earn a chip by default, how a chip shows a number, an on/off and a choice,
  and how the strip wraps on a narrow card.
- Machines with settings unlike others. Today's power sources already include: turbines (rotor, size, fitting, fuel,
  flow), heat exchangers (hot fluid, rate, circuit), the Large Neutralization Engine (structure, acid, base, arms),
  reactors (pebbles, fuel rods, coolant, core temperature), the Solar Boiler (calcification), the Dyson Swarm and the
  Eye of Harmony. Recipes with special settings: heat recipes (coil, heat discounts), the Eye of Harmony (astral
  arrays), fusion (always perfect overclocks), steam machines, and other mods' costs (mana, vis, LP).
- Crop farms and greenhouses: none exist yet as sources; they will need their own card (seeds, fertiliser, output per
  crop).
- The zoomed-out card and the minimap in the new look.
- Known gaps the redesign can close: the AMP setting is capped at 64 in `data/Settings` while the chip allows 2^24; the
  board spec's [−] value [+] settings ladder exists only on power tiles.

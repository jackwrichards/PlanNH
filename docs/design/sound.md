# Sound

Every action in the planner makes a sound, but most actions share one: a key press is the click, a page or tab is the
page turn. What gets its own voice is what changes the plan, and what is themed is what a resource does: a wire or a
drawer sounds of what it carries (an item snaps in, a fluid blubs, power hums on).

The feel is clicky but smooth: rounded knocks that die away without ringing, soft edges, low short tones. The first set
(2026-10-09) sounded like lab glassware (an inharmonic partial on the click, a metal ring on items, bubbles rising to
1.5 kHz, high pure blips) and was redone; it is kept in the recipes to compare in the lab.

## Where the sounds come from

All of them are synthesized (no samples, nothing borrowed). `tools/sound/engine.mjs` is the material, plain JavaScript
that runs in Node and in a browser; `tools/sound/sounds.mjs` is the recipes, each sound with its level, its variant
count, when it plays and its options (the first is what the game uses unless `pick` names another).
`node tools/sound/synth.mjs` (or `node tools/sound/synth.mjs wire.` for some) renders them to Ogg Vorbis in
`src/main/resources/assets/gtnhplanner/sounds/` and writes `sounds.json`; it needs ffmpeg on the path.
`tools/sound/manifest.json` records the loudness reference so a partial render matches the rest.

**The lab** (`node tools/sound/lab/build.mjs`, then open `build/sound/lab/gtnh-planner-sound-lab.html`) inlines the
same engine and recipes and makes every sound live: a working mock board (ports, drawers, the tier chip, pins, menus),
every sound with its options and the first set to compare, and the variation techniques as switches (variants fixed or
new every play; shuffled, random or in turn; pitch, loudness and brightness spread; musical pitch; layer timing;
ducking; streak climb; stereo). Picks copy out as text, to be set as `pick` in the recipes.

The materials follow the website's board sounds (`src/lib/board-sounds.ts` in gtnh-factory-flow), and its rules:
every envelope ramps in and fully out, fundamentals sit at 200 Hz and up, the mix runs under a soft lowpass and a
memoryless tanh clip. The set's own:

- **tock**: a rounded knock whose pitch drops as it dies away, like a tap on plastic or wood; no ring.
- **snap**: a millisecond or two of soft noise, the edge on a click. A tock with a snap and a little low body under it
  is the click.
- **swish**: noise through a band sliding from one pitch to another: lifts, pages, panels, undo.
- **tone**: a soft, warm sine, low and short, gliding if asked (toggles, the running relay).
- **blob**: a low rounded bubble rising a little (fluids, without the glassware).
- **hum**: a smooth buzz of a few harmonics (power, the tier dial).
- **paper grains**: short noise grains, each its own pitch (sticky notes).

The palette (`FAMILIES` and `GESTURES` in `sounds.mjs`) widens the choice: twelve materials (low click, felt mallet,
wood block, rubber pop, plucked string, soft synth, retro square, warm keys, hollow box, thock, bubble, air), all
pitched low, each able to play every gesture a sound is (a tap, two steps up, set down, rise, fall, a latch...). Every
sound offers its gesture in every material beside its own hand-made versions; the themed fluid and power wires have
eight or more of their own instead. The loudness measure listens through a gentle low cut, so a low thud and a bright
tick come out matched.

Each sound has one to four variants (a seed apiece) and the game picks one; the game also drifts the pitch a few cents.

## Loudness

Each sound has a level against the click in the synth (a card set down is 1.3, a wheel tick 0.55), and the synth scales
each so its loudest 30 ms come out at that level. The game then plays every sound at one volume, `Sfx.BASE`, chosen so
our click is as loud as the vanilla click (which plays at a quarter), times the player's **Sounds** setting (the gear:
Off, Quiet, Normal, Loud), under the game's master volume. So the balance lives in one place, the synth.

## Playing them

`ui/sound/Sfx` plays them. The rules:

- **The click gives way.** Anything pressed clicks (`Hyb.click()`, the top bar's keys), unless the same action makes a
  sound that says more within 80 ms either side (a tier stepping, a menu opening, a card landing): then the click is
  dropped, or stopped if it already started.
- **Repeats duck.** The same sound again within 120 ms plays at 0.6 of the last, down to three steps, so a wheel spun
  is one sound, not a crescendo; within 30 ms it is the same event twice (keys arrive twice) and is dropped.
- **Nothing while the tour hurries** (`Tutorial.hurried()`), so catching up to a step is silent; played at its own
  pace, the tour's actions sound as the player's would.
- **After the planner opens**, sounds wait 3 ticks so the open lands first (the plan button opening the board on a new
  card: unfold, then the card lands).

## What plays where

| Sound | When |
|---|---|
| `ui.click` | any key, row, chip or tab pressed that has nothing more to say; a card or drawer selected (softer, higher) |
| `ui.toggle_on` / `_off` | a setting switched on or off (gear rows, card toggles, the minimap and overlay keys) |
| `ui.open` / `ui.close` | a menu or box opening; dismissed (Esc, a click off it), the non-recipe picker, a tab closed |
| `ui.deny` | refused: a wire dropped where nothing takes it, two cards that cannot share a machine, nothing to undo, a paste with no plan, a placement blocked |
| `ui.tick` | one wheel step on a value (amps, count, a drawer's rate, note text size, section order), pitched up or down |
| `ui.page` | a plan tab switched or added, the Library opened or closed, NEI's recipe pages turned from the board, the tour's Next and Back |
| `board.place` | a card added (from NEI, the picker, custom rate), one card pasted |
| `board.remove` | cards or drawers deleted, a recipe taken off a shared machine |
| `board.lift` / `board.drop` | a card, drawer or note picked up once it moves; set down |
| `board.clone` | a card cloned |
| `board.merge` | cards combined onto one machine, or a recipe added to one ("Add another recipe") |
| `board.sweep` | many things at once: Arrange landing, several pasted, a plan pasted or imported |
| `board.undo` / `board.redo` | undo, redo |
| `board.pin` / `board.unpin` | a machine count pinned or unpinned, a drawer's rate set or cleared |
| `board.adjust` | a setting changed (machine, coil (pitched up its ladder), number, choice, drawer rule or kind (pitched by it), note colour, plan renamed) |
| `board.running` | a solve answer has the plan running where it did not before (not on a tab switch, undo or return from NEI) |
| `wire.grab` | a wire picked up off a port (once it leaves it) |
| `wire.snap` | the wire in hand comes over a card or drawer that would take it |
| `wire.item` / `wire.fluid` / `wire.power` | a wire or drawer link made, or a drawer made for a port, by what it carries |
| `wire.cut` / `wire.fluid_cut` / `wire.power_cut` | a wire or drawer link cut, as what it carried (unlatch, drain, hum off) |
| `dial.tier` | a voltage tier stepped, pitched up the ladder ULV to MAX |
| `screen.open` / `screen.close` | the planner opening and closing (a visit to NEI's pages is a page turn instead) |
| `note.stick` / `note.crumple` | a sticky note added; a note deleted, or a plan deleted |
| `world.place` / `world.remove` | a machine placed on a spot in the world; taken off it |

`call sfx` in the dev harness lists what played lately (the dev game is muted), and `call 'sfx?play=wire.fluid'` plays
one.

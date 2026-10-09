# The tour

A guided tour, not a video: the planner drives itself in your game, a step at a time, and a note beside what it did
says what it is. Placing machines in the world is not in it (that will be a video). Built in `ui/tutorial/`
(2026-10-09); this file is what it does and how.

## How it looks and feels

- **Steps, not a show.** Each step acts something out at a brisk pace (the cursor clicks, drags, types), then stops on
  a note and waits. Nothing moves on until the player says so. Twenty-five steps, about a minute and a half of action
  in all; the rest is the player's reading.
- **The note** (`Callout`): a small dark box with a gold edge beside the thing it is about, a tip pointing at it, and
  the thing framed in gold. Its words are short and plain; the words that matter most are gold (written between
  asterisks in the script). It sits where it covers least of the board's cards, drawers, notes, popups and the
  overview: below the thing if there is room, else above, right or left, slid along that side, and off the tour's
  cursor. The note fades and slides in.
- **The keys** are in the note: × (leave) on the left, ◀ (back) and ▶ (next) on the right. ▶ turns gold when the step
  is done; on the last step it is a tick. The right arrow, Space or Enter is next, the left arrow back, Esc leaves.
  While a step plays, next (or a click anywhere) hurries it to its note. The function keys stay the game's.
- **No chapters, no titles, no counts.** The tour starts at once from the "?" key.
- **The tour's cursor**: a gold arrow (so it never passes for the player's) that glides on gentle arcs, dips and
  leaves a ring when it clicks, and fades when it steps aside. Keys it presses show as key caps beside it (R, Shift,
  Esc, the minimap's keys), a wheel turn as a small arrow. Key names come from the player's own bindings (NEI's R and
  U, the minimap's keys).

## Where it starts

- The "?" key on the top bar: **Take the tour** and **Controls and shortcuts**.
- The first time the planner opens, an offer along the bottom of the board: **Take the tour** or **Not now** (either
  puts it away for good; `tour.offered` in the settings file).
- Dev harness: `call 'tutorial?start=1'`, `call 'tutorial?step=N'` (start or jump there), `call 'tutorial?next=1'`,
  `call 'tutorial?stop=1'`, `call 'tutorial?export=1'`; every call returns where the tour is ("step 5 of 25 waiting:
  ...").

## Safe to run

`Sandbox` keeps the player's things apart:

- **Plans**: the player's are put aside (`Plan.enterSandbox`) for plans of the tour's own, which `PlanAPI.save`
  never writes. Leaving puts the player's back exactly as they were, and a world closing mid-tour does too.
- **What else it touches** is copied first and put back: the machine picked per NEI tab, the settings remembered per
  machine, NEI's search, the minimap's picture of the plan and its view, the minimap's settings.
- **Where the player was**: the planner opens again if it was open, else the tour leaves them in the world.
- **The Library** is only browsed; nothing is signed into, posted or opened into My plans.

## How it drives the game

- **`Pointer`**: while the tour drives, the mouse position every screen is drawn with is the tour's (a mixin on
  `EntityRenderer.updateCameraAndRender`), and so is NEI's own reading of it (a mixin on `GuiDraw.getMousePosition`).
  Hover, highlights and tips all follow the tour's cursor; the player's real cursor is never moved. A third mixin
  makes `GuiScreen.isShiftKeyDown` say yes while the tour Shift-clicks (adding a card to the selection).
- **`VirtualInput`**: presses, drags, releases, wheel turns and keys go to the open screen the way the game sends the
  player's: ModularUI's screen first, then the screen itself and NEI's hooks. Nothing goes through the operating
  system, so it works the same on Java 8 and under lwjgl3ify.
- **Typing**: with lwjgl3ify a key press carries no character (text comes as events of its own), so the tour types
  into text fields by setting their text a letter at a time (NEI's search, the planner's number boxes, the Library's
  search); a sticky note takes typed keys directly.
- **`Targets`**: everything is pointed at by name, looked up afresh each frame: NEI's planner button, its search, an
  item in its list, a recipe tab, a recipe's plan button and its menus; the top bar's keys, a card's parts and ports,
  drawers, notes, overview rows, plan tabs, popup rows, the settings rows, the selection bar, the non-recipe machines,
  the Library's parts, the minimap; and free board beside a port for a new drawer. A board target off the visible
  board pans the board to it first, so the cursor never clicks what cannot be seen.
- **`Director`**: plays a step's actions a frame at a time, then waits. The tour's plans are saved at the start of
  every step that starts on a settled screen; Back puts those back (plans, screen, NEI's search) and plays the step
  again, or hurries from the nearest saved step before it.
- **Hurrying** (next part way through a step, Back to a step whose start was not saved, the harness's jumps) runs the
  actions without their glides, but never faster than the game takes them: the pointer still rests until the screen
  has drawn twice with it there (what is under the mouse is found as the screen draws, and the screen does not draw
  on every frame), on a target that has stopped moving and a board at rest (no zoom easing, no fling gliding, no cards
  being built afresh); a drag still glides (ModularUI takes no drop from a single jump, nor from one let go within
  100 ms); a pause is still a game tick; and after a jump nothing runs for 400 ms while the screen builds itself.
- **Starts**: the plans at the start of every step, from a full run (`call 'tutorial?export=1'` writes
  `assets/gtnhplanner/tutorial/starts.json`), so the harness starts any step at once. Re-export after a script change
  that changes what a step starts from.
- **Recipes** are found the way a player finds them: by name in NEI's list (this pack lists fluids by their cells; a
  cell dropped on the board is its fluid), and on NEI's page by what goes in and comes out, so a pack that moved a
  recipe still finds it, or the step carries on without it.

## The steps (`Script`)

1. The planner's button on the inventory.
2. NEI: hydrochloric acid searched, R, the Large Chemical Reactor's page: the plan button.
3. A new plan, the reactor: the card, not running (nothing wired).
4. Drawers dragged out of its ports: still nothing runs.
5. The machine count pinned to 1: everything else worked out.
6. The pin cleared, 1000 L/s asked of the acid's drawer: the machine count follows.
7. The tier stepped to MV and 2 amps.
8. Power (shown, never wired) and the circuit.
9. The settings panel; pinned settings show on the card.
10. Clone.
11. The card and its clone selected (a click, a Shift-click) and merged: two recipes on one machine.
12. Undo, back to the one card; redo beside it.
13. The overview.
14. The rate, power and average keys turned round: how numbers are shown.
15. Benzene dragged in from NEI's list as a product.
16. R on its drawer, the distillation tower added and wired: the planner works like NEI.
17. Non-recipe machines.
18. A gas turbine at HV on the tower's benzene, every other flow given a drawer (wood tar in, creosote, phenol,
    toluene, dimethylbenzene and the turbine's EU out), the tower pinned to one: how many turbines it runs.
19. Arrange and Fit.
20. A sticky note: added, written on, moved, resized, coloured, its text bigger.
21. The + menu: a new plan.
22. The Library's public setups, searched.
23. The Discord key.
24. The minimap set up large, square, top right; over the world, moved and zoomed with its keys.
25. That's everything.

## Open

- Voice: each step's note could carry a clip (`assets/gtnhplanner/sounds/tutorial/`).
- The world-placement video.

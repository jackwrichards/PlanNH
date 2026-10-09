# The tour

A guided tour you watch, not a video: the planner drives itself in your game while a caption bar says what is
happening. Placing machines in the world is not in it (that is a recorded video, linked from the end card). It is
built (`ui/tutorial/`, 2026-10-09); this file is what it does and how.

## How it looks and feels

- **The tour's cursor** does everything: a gold arrow (so it never passes for the player's) that glides on gentle arcs,
  dips and leaves a ring when it clicks, holds still on a hover so the real tip shows, and fades when it steps aside.
  Keys it presses show as key caps beside it (R, Esc, the arrows), a wheel turn as a small arrow.
- **The spotlight** dims everything but what is being talked about, with a soft edge and a thin gold frame, and glides
  from one thing to the next. Where a tip opens beside the thing, only the frame shows (no dimming), so the tip stays
  bright.
- **The caption bar** sits at the bottom (at the top when what the tour shows is down there): the chapter and its
  number, a dot per beat, the caption coming in, and Chapters, Back, Pause, Next and Leave; a thin gold line along its
  bottom is the time left on the caption. It plays on at reading pace. Right arrow, Space or Enter is Next, Left is
  Back, P pauses, Esc pauses and Esc again leaves. The function keys stay the game's (F2 still screenshots).
- **Hands off while it plays**: the player's mouse and keys work only the bar; a click elsewhere says so. The game
  window losing focus stops the tour's clock.
- **The chapter list** opens first and from the bar's Chapters key: pick any chapter, or start over.
- **The end card**: the world video and the "?" key pointed out, and whether to keep the minimap as the tour set it.

## Where it starts

- The "?" key on the top bar: a small menu with **Take the tour** and **Controls and shortcuts**.
- The first time the planner opens, an offer along the bottom of the board: **Take the tour** or **Not now** (either
  puts it away for good; `tour.offered` in the settings file).
- Dev harness: `call 'tutorial?start=1'`, `call 'tutorial?chapter=N&beat=M'`, `call 'tutorial?next=1'`,
  `call 'tutorial?stop=1'`; every call returns where the tour is.

## Safe to run

`Sandbox` keeps the player's things apart:

- **Plans**: the player's are put aside (`Plan.enterSandbox`) for plans of the tour's own, which `PlanAPI.save`
  never writes. Leaving puts the player's back exactly as they were, and a world closing mid-tour does too.
- **What else it touches** is copied first and put back: the machine picked per NEI tab, the settings remembered per
  machine, NEI's search, the minimap's picture of the plan and its view, the minimap's settings (unless the player
  keeps them at the end).
- **Where the player was**: the planner opens again if it was open, else the tour leaves them in the world.
- **The Library** is only browsed; nothing is signed into, posted or opened into My plans.

## How it drives the game

- **`Pointer`**: while the tour drives, the mouse position every screen is drawn with is the tour's (a mixin on
  `EntityRenderer.updateCameraAndRender`), and so is NEI's own reading of it (a mixin on `GuiDraw.getMousePosition`).
  Hover, highlights and tips all follow the tour's cursor; the player's real cursor is never moved.
- **`VirtualInput`**: presses, drags, releases, wheel turns and keys go to the open screen the way the game sends the
  player's: ModularUI's screen first, then the screen itself and NEI's hooks. Nothing goes through the operating
  system, so it works the same on Java 8 and under lwjgl3ify.
- **Typing**: with lwjgl3ify a key press carries no character (text comes as events of its own), so the tour types
  into text fields by setting their text a letter at a time (NEI's search, the planner's number boxes, the Library's
  search); a sticky note takes typed keys directly.
- **`Targets`**: everything is pointed at by name, looked up afresh each frame: NEI's planner button, its search, an
  item in its list, a recipe tab, a recipe's plan button and its menus; the top bar's keys, a card's parts and ports,
  drawers, notes, overview rows, plan tabs, popup rows, the settings rows, the non-recipe machines, the Library's
  parts, the minimap. A board target off the visible board pans the board to it first, so the cursor never clicks
  what cannot be seen.
- **`Director`**: plays beats a frame at a time (steps: move, click, drag, wheel, key, type, wait for the board or a
  popup), then holds the caption long enough to read. Next hurries the beat to its end. The tour's plans are saved at
  the start of every beat that starts on a settled screen; Back and the chapter list put those back (plans, screen,
  NEI's search) and play on, or hurry through the beats from the nearest saved one before.
- **Hurrying** (Next, Back, a chapter picked) runs a beat's steps without their glides and holds, but never faster
  than the game takes them: the pointer still rests until the screen has drawn twice with it there (what is under the
  mouse is found as the screen draws, and the screen does not draw on every frame), on a target that has stopped
  moving and a board at rest (no zoom easing, no fling gliding, no cards being built afresh); a drag still glides
  (ModularUI takes no drop from a single jump, nor from one let go within 100 ms); a pause is still a game tick; and
  after a jump nothing runs for 400 ms while the screen builds itself. Checked by jumping to every chapter's last beat
  from its start (`call 'tutorial?chapter=N&beat=last'`): all sixteen hurry through with no warnings.
- **Recipes** are found the way a player finds them: by name in NEI's list (this pack lists fluids by their cells; a
  cell dropped on the board is its fluid), and on NEI's page by what goes in and comes out, so a pack that moved a
  recipe still finds it, or the beat carries on without it.

## The script (`Script`)

Sixteen chapters, about seventy beats, seven to eight minutes end to end.

1. **Opening the planner**: the button at the bottom left of any inventory; open, and back.
2. **Your first recipe**: search NEI for hydrochloric acid, R for its recipes, the Large Chemical Reactor's tab, the
   plan button, a new plan, the machine; the card on its new plan. Shift-click skips the menus.
3. **Reading a card**: inputs, outputs, the machine count.
4. **Wiring it up**: zoom out and pan; drag ports out into drawers (hydrogen, chlorine, the acid); still nothing.
5. **Giving it a target**: pin one machine and the numbers fill in; unpin and ask for 1000 L/s instead.
6. **Machine settings**: the tier, the amps, power (shown, never wired), the circuit, the gear's settings.
7. **Card actions**: clone, delete, undo and redo.
8. **The overview**: inputs and outputs, machines and power, a machine row goes to its card, an item's row.
9. **Rates and power**: per tick, second, minute or hour; EU/t or amps; average or peak.
10. **Items from NEI's list**: search benzene, drag it onto the board, add it as a product; R on the drawer, the
    distillation tower's recipe added to this plan, its benzene wired to the drawer.
11. **Planning power**: Non-recipe machines, the gas turbine at HV, the benzene sent to it, the benzene machine
    pinned to one; the turbines it feeds and the power they make.
12. **Arrange** and Fit.
13. **Sticky notes**: add one with a right-click, type on it, move it, resize it, colour it, its text bigger.
14. **Plans**: the tabs, the + menu, a tab's own menu.
15. **The Library**: the public setups, searched.
16. **Settings and the minimap**: feedback, the settings, the minimap large, square, top right; then over the world,
    panned and zoomed with its keys.

## Open

- Voice: every beat's caption could carry a clip (`assets/gtnhplanner/sounds/tutorial/`), the hold then the clip's
  length.
- The world-placement video, and the end card's link to it.

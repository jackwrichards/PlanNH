# Tutorial (draft)

A guided tour you watch, not a video: the planner drives itself in your game while a caption bar says what is
happening. Slick is the bar. Placing machines in the world is not in it (that will be a recorded video, linked at the
end).

## How it looks and feels

- **A ghost cursor** does everything: it glides (eased, slightly curved paths), dips and leaves a small ripple when
  it clicks, and holds still on a hover so the real tooltip shows. It is drawn; the real mouse is never moved.
- **A spotlight** dims everything but the thing being talked about (a soft rounded cut-out, a short arrow when the
  target is small). It moves with the target as the board pans.
- **The caption bar** sits at the bottom centre: one or two short lines, the chapter name and dots for its beats, and
  Back, Next, Pause and Leave. Right arrow or Space is Next, left arrow Back, Esc leaves. It plays on by itself at
  reading pace; Next skips ahead and Back goes one beat back.
- **Hands off while it plays**: clicks and keys outside the caption bar are ignored, so nothing knocks it off script.
- **The board keeps its normal look**: wires routing, the solver filling numbers in, cards gliding on Arrange; every
  beat waits for the solver and the router to settle before it holds.
- **Voice later**: every caption has an id, so a voice clip per id (`assets/gtnhplanner/sounds/tutorial/`) can be
  added later without touching the script; a beat with a clip holds for the clip's length.

## Safe to run

- It works in its own plan, "Tutorial", which is never saved and never listed in My plans. Leaving the tutorial
  removes it and puts back whatever was open (the plan, the screen, the view).
- Settings it shows (the minimap) are changed only for the tour and put back afterwards. At the end it asks whether
  to keep the minimap the way it was shown.
- The Library is only browsed: it never signs in, posts or opens a public plan into My plans. Offline, that chapter
  says so and moves on.
- If the pack lacks a recipe it uses (another GTNH version), that beat says so and the tour carries on.

## Where it starts

- The `?` key on the top bar opens a small menu: **Tutorial** and **Controls** (today's gesture list).
- The first time someone opens the planner, a notice offers it: "New here? Take the tour (6 minutes)".
- The tour opens on a chapter list, so people can watch one part again later.

## The script

Captions are short and plain, as a person would say them. About 15 chapters and 70 beats: roughly six to eight
minutes end to end.

### 1. Opening the planner

1. Inventory open, the GTNH Planner button lit up at the bottom left. "This button opens GTNH Planner."
2. Click: the board opens. Click again (or Esc): it closes. "Open and close it here whenever you like."

### 2. Your first recipe

1. NEI opens Hydrochloric Acid; the cursor finds the Large Chemical Reactor recipe (hydrogen and chlorine in).
   "Everything starts in NEI. Here's hydrochloric acid."
2. Hover the plan button above +. Its tip shows. "This button adds a recipe to a plan."
3. Click: "Which plan to add to?" Click **New plan**. "Add it to a new plan, or one you already have."
4. "Which machine to use?" Click **Large Chemical Reactor**. "Pick the machine that runs it."
5. The board opens on the new card, centred. "There it is."
6. Aside, one line: "Shift-click the button to skip both menus. It remembers the machine you picked."

### 3. Reading a card

1. Spotlight the card. "A card is one recipe running on one kind of machine."
2. Inputs on the left (hydrogen, chlorine), output on the right (hydrochloric acid). "Inputs on the left, outputs on
   the right."
3. The machine count, which says NO WIRES. "The count is how many machines you need. Right now: no idea yet."

### 4. Wiring it up

1. "Nothing happens until things are wired up."
2. Drag the hydrogen port onto empty board: a source drawer appears, wired. "Drag a port out to make a drawer. This
   one supplies hydrogen."
3. Same for chlorine. Then drag the acid port out: a product drawer. "And this one takes the acid away."
4. The count now reads UNUSED. "Wired, and still nothing. The plan needs a target."

### 5. Giving it a target

1. Click the machine count, choose **Pin count**, type 1. Numbers flow into every drawer. "Pin one machine..."
2. Spotlight the drawers' rates. "...and the planner works out everything else."
3. Unpin. Click the acid drawer's rate, type 1000. The count becomes a fraction (x2.4 or so). "Or ask for an amount
   of product. It solves for the machines."
4. "Pin what you know. It solves the rest."

### 6. Machine settings

1. Tier chip: step it up twice. The count drops as it overclocks. "Raise the tier: fewer machines, more power."
2. Amps chip: step it. "Amps work the same way."
3. Power: hover it; the tip shows "Not wired in plans". "Power is shown, not wired. You never have to supply it here."
4. Circuit chip: hover. "The programmed circuit this recipe needs. It isn't used up."
5. The gear: open the machine settings, pin one, close. "More settings live here. Pin one to keep it on the card."

### 7. Card actions

1. Card actions key: **Clone**. A second card appears. "Clone copies a card with its settings."
2. Delete the clone. Then Undo and Redo on the top bar, cursor on each. "Undo and redo are up here, or Ctrl+Z."

### 8. The overview

1. Spotlight the rail on the left. "The overview adds up the whole plan."
2. INPUTS, OUTPUTS, INTERNAL, MACHINES in turn. "What goes in, what comes out, what stays inside, and the machines."
3. MACHINES' power rows (Used, Made, Total) and its Avg/Peak toggle. "Power for the whole plan."
4. Double-click an output row: the board jumps to its card. "Double-click a row to find its cards."
5. Hover a row: the tip lists right-click (public plans that make it). "Right-click to find public plans that make it."

### 9. Units and power on the top bar

1. Click the rate key through /t, /s, /min, /hr; every number changes. "Per tick, second, minute or hour."
2. EU/t to Amps and back. "Power in EU/t, or in amps at each machine's tier."
3. Avg to Peak and back. "Average power, or peak: every machine running at once."

### 10. A second way to add: from the item list

1. Drag Benzene out of NEI's item list onto the board: a drawer. "Drag any item from NEI onto the board."
2. Make it a product. "It can be a product you want, or a source you already have."
3. Click its port: NEI shows what makes benzene; add a recipe with +. It arrives already wired. "Click a port to see
   what makes it. Adding one wires it in."

### 11. Planning power

1. The bolt key (non-recipe machines). The picker opens. "Generators, turbines, boilers and reactors are here."
2. Pick Basic Gas Turbine, set it to HV. "A gas turbine, at HV."
3. Wire the benzene into the turbine. "Burn the benzene."
4. Pin the benzene machine to 1. The turbine count and the power made appear; the overview's Made row fills in.
   "Now you can see how much power one benzene machine gives you."

### 12. Arrange

1. The board is untidy on purpose (the new cards were dropped wherever). "Things pile up as you add them."
2. Click Arrange: it counts up, then the cards glide into place. "Arrange tidies the whole plan."
3. Fit. "Fit shows all of it."

### 13. Plans

1. The tabs along the top. "Each tab is an open plan."
2. Click +: the menu (New plan, Open a plan, Paste plan, Browse library). "Start a new one, open an old one, or paste
   one from Factory Flow."
3. A tab's menu: rename, copy plan code, post to library, close. "Closing a tab keeps the plan. Delete is separate."

### 14. The Library

1. Click Library: the public shelf loads. "Public setups, made by other players."
2. Scroll it slowly; type a search. "Looking for something? Someone has probably made it."
3. Back to the board. "Open one and it becomes a plan of your own."

### 15. Feedback, settings and the minimap

1. The Discord key. "Bugs and ideas go here, on the GTNH Discord."
2. The gear: settings. The minimap section: show it, Size Large, Shape Square, Position Top right. "The minimap shows
   a plan while you play."
3. Close the planner: the minimap is in the corner, on the plan the tour built. "Here it is."
4. Pan it with the arrow keys, zoom with [ and ]. "Arrow keys move it. [ and ] zoom. N hides it."
5. Ask: keep this minimap, or put it back as it was?

### End

"That's the planner. Placing a plan in your world has its own video." The chapter list again, the `?` key's Controls
list pointed out, and Leave.

## How it is built

- `ui/tutorial/`: the script (chapters of beats), the director (runs beats on the client tick, Back and Next, the
  hold times), the ghost cursor, the spotlight, the caption bar, and targets.
- **Beats are small and declarative**: say a caption, point at a target, move to it, hover it, click it, drag from one
  target to another, type a number, wait until the solver and router are idle. A click runs the same code the real
  control runs (the session's own edits, the menu entry's action), at the moment the ghost cursor lands. No synthetic
  OS input: the harness's needs lwjgl3ify, and players may be on Java 8.
- **Targets by name**, resolved fresh each frame to a rect: top-bar keys, a card's parts (as `mc.sh part` finds
  them), ports, drawers, overview rows, popup and menu entries, NEI's plan button and recipe slots, the minimap. A
  hover shows the control's real tip.
- **Back** restores a snapshot taken at the start of each beat (the tour plan, the view, which screen is open), then
  replays nothing: the beat just starts again.
- **NEI**: opening a recipe page goes the way `call 'nei?item=...'` does; the recipe is found by its output and
  machine, not by page position.
- **Testing**: a harness endpoint jumps to any beat and runs the tour at speed, with a screenshot per beat, so the
  whole thing can be checked by eye after a change.

## Open

- Which benzene recipe: one simple enough to read at a glance (decide when building chapter 10).
- Whether the first-run notice should also show in NEI (the GTNH Planner button glowing once).
- The world-placement video: where it lives and how the end screen links to it.

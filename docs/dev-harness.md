# Dev harness: driving the client from a shell

The dev harness lets an agent (or a script) run the PlanNH client without anyone at the keyboard: launch it,
wait until it is in a world, open the flowchart, click/drag/scroll/type, read the widget tree, take screenshots,
and shut it down. It exists so UI work can be verified the same way logic is verified by `./gradlew test`.

It is dev-only: `DevHarness.initIfDev()` does nothing outside a deobfuscated dev environment (force with
`-Dplannh.dev=true|false`), so it never runs in a shipped jar.

## Quick start

```bash
tools/dev/mc.sh start            # build + launch runClient25, block until the test world is loaded (~20s warm)
tools/dev/mc.sh call open        # open the PlanNH flowchart
tools/dev/mc.sh shot look.png    # -> run/client/screenshots/look.png
tools/dev/mc.sh part 1 TIER      # click card 1's tier chip, found by name via /board (also: move, scroll 'amount=-1', 'button=1');
                                 # d0 RATE = drawer 0's rate box; ports are IN0, OUT1... (drag them with /drag)
tools/dev/mc.sh stop             # kills the game (a clean quit can hang on a confirm dialog)
tools/dev/mc.sh smoke            # all of the above as a pass/fail check
```

After changing mod code, `tools/dev/mc.sh swap --reopen` pushes it into the running game (about 12s, see
[Hot swap](#hot-swap)); `tools/dev/mc.sh restart` rebuilds and relaunches (about 30s) for what swapping can't do.

`mc.sh start` writes these into `run/client/options.txt` before launch (env var overrides in brackets):
window 1920x1080 (`PLANNH_DEV_WIDTH`/`PLANNH_DEV_HEIGHT`), GUI scale 2 (`PLANNH_DEV_GUI_SCALE`), master volume 0
(`PLANNH_DEV_SOUND`). The harness also mutes sound at runtime unless `-Dplannh.dev.sound=true`, and turns off
pause-on-lost-focus so an unfocused window keeps running.

`PLANNH_GTNH=1` adds `-PgtnhRecipes`: the GTNH core mod, GregTech and the pack's recipes (72 mods instead of 29;
about 30s to the world once the jars are cached). It uses its own world, `plannh-dev-gtnh`, because opening a world
across mod sets stops on EndlessIDs' "convert this world?" prompt, which the harness cannot get past.

On first launch it creates a creative superflat world `plannh-dev` (`-Dplannh.dev.world=name`, empty to stay on
the main menu) and loads it automatically. Plans are saved per world, so notes and nodes persist across runs;
with the game stopped, delete `run/client/saves/plannh-dev` (the world) and
`run/client/saves/NEI/local/plannh-dev` (the plan) for a clean slate.

## Endpoints

HTTP on `127.0.0.1:25599` (`-Dplannh.dev.port`), GET with query parameters, JSON replies. `mc.sh call 'path?args'`
is a thin curl wrapper. Every request waits until the game has processed it, so calls can be chained.

| Endpoint | What it does |
| --- | --- |
| `/status` | `ready`, `inWorld`, `screen`, `muiScreen`, `hovered` and `focused` widgets, display size, `guiWidth`/`guiHeight`/`guiScale`, mouse position |
| `/open`, `/close` | open the flowchart (same as F8) / close the current screen |
| `/screenshot?name=x.png[&x&y&w&h]` | save the next fully drawn frame, optionally cropped to a GUI-coordinate rectangle; returns the path |
| `/widgets` | ModularUI widget tree of the current screen: type, name, x/y/w/h in GUI coordinates, children |
| `/move?x&y` | move the mouse (hover) |
| `/click?x&y[&button=0][&count=1]` | button 0 left, 1 right, 2 middle; `count=2` double-clicks |
| `/drag?x1&y1&x2&y2[&steps=10][&button=0]` | press, move in steps (one per tick), release |
| `/scroll?x&y[&amount=1]` | wheel; positive is up |
| `/key?code[&char][&mods=ctrl,shift]` | press + release an LWJGL2 key code (modifiers held around it) (`Keyboard.KEY_*`: 1 Esc, 28 Enter, 14 Backspace, 20 T, 66 F8) |
| `/type?text` | type text into the focused field |
| `/cmd?c=/time%20set%20day` | run a command as the player (client commands first, then chat) |
| `/board` | the open board as data: zoom/pan, per card its state (tier, amps, coil, machines, pinned, ports) and the GUI rect of each control and port (`parts.TIER`, `parts.OUT1`, with `cx/cy`), per drawer its kind, rule, target, rate, unmet flag and parts, the edge count, `solving`, and the notices |
| `/view?zoom&panX&panY` | set the board view (defaults 1, 0, 0) so tests start from a known place |
| `/gtmachines?q&all=1&art=1` | GregTech multiblocks (all=1: every machine) by meta and in-game name; art=1 adds the bundled picture each resolves to |
| `/recipeinfo?output&handler&input` | what NEI and PlanNH see in a recipe (ingredient, result and other stacks with registry names, catalysts, the ports a card would get), read-only |
| `/frame` | run one ModularUI frame update and report what is hovered and below the mouse (hover debugging) |
| `/quit` | ask the game to quit (with GT this can stop on a "really close?" dialog; `mc.sh stop` kills instead) |

All coordinates are GUI-scaled (what `GuiScreen` sees as `mouseX`/`mouseY`), not window pixels. Find targets with
`/widgets` and confirm with `/status` (`hovered`) instead of estimating from screenshots.

## Tips for UI work

- PlanNH text fields (note bodies, headers) only take focus on a double-click: `click?x&y&count=2`, check
  `focused` in `/status`, then `type`. Esc (`key?code=1`) ends the edit.
- The canvas zooms with the wheel around the cursor; left-drag moves nodes and notes (see the in-game Help panel).
- Cropped screenshots are cheap to inspect and keep native resolution:
  `mc.sh shot detail.png 'x=30&y=50&w=150&h=90'`.
- `mc.sh smoke` fails on a crash, on any exception with a `com.sbancuz.plannh` frame, or if the flowchart does not
  open, and lists other logged errors (other mods, ModularUI layout warnings) without failing.
- Logs: `build/dev-client/client.log` is the full output of the current run (all mods; rewritten on each start).

## Hot swap

`mc.sh start` runs the game on JetBrains Runtime 25 with HotswapAgent and a JDWP port on `127.0.0.1:5005`
(`PLANNH_HOTSWAP=0` turns this off, `PLANNH_JDWP_PORT` moves it). Then:

```bash
tools/dev/mc.sh swap            # recompile (~10s) and redefine every class whose bytes changed (~1s)
tools/dev/mc.sh swap --reopen   # same, then close and reopen the flowchart so widget-building code reruns
```

Works for method bodies and for structural changes to loaded classes (adding methods was verified; JBR's
enhanced redefinition also allows fields and signature changes). Code that draws every frame updates at once;
code that builds widgets needs `--reopen`.

Needs `mc.sh restart` instead:
- new classes, or changed classes the game hasn't loaded yet: swap lists them and exits 2;
- mixins, resources (lang files, textures), and anything that only runs at startup (static initializers, event and
  keybind registration, config loading).

How it works: the game loads PlanNH from the dev jar (`shadowJar` then `downgradeJar`), which is locked while the
game runs, but its PlanNH classes are byte-identical to `build/tmp/downgradeMainClasses/main` (checked 435 of 435),
where the game uses the `META-INF/versions/21` copies on Java 25. `start` builds both in one Gradle run and, once the
client is ready, records each class's SHA-1 in `build/dev-client/hotswap.sums`; `swap` rebuilds that folder and
sends only classes whose hash differs, through JDI `redefineClasses` (`tools/dev/Hotswap.java`). Hashes, not
timestamps: the Mixin and Lombok annotation processors force full recompiles, and redefining all ~260 classes in one
batch crashed JBR (`EXCEPTION_ACCESS_VIOLATION` in `VM_EnhancedRedefineClasses::do_topological_class_sorting`).

## How it works

- **Input** goes into lwjgl3ify's emulated LWJGL2 queues (`org.lwjglx.input.Mouse.addMoveEvent` / `addButtonEvent` /
  `addWheelEvent`, `Keyboard.addRawKeyEvent`), the same entry points its SDL event loop uses for real input, so the
  game cannot tell the difference and the user's real cursor is never touched. Typing mirrors
  `Lwjgl3ifyEventLoop#handleTextEvent`: an `InputEvents` text event (vanilla text fields) plus one char event per
  character (`GuiScreen#keyTyped`, ModularUI). `Mouse.isButtonDown` reads `sdlMouseButtonFlags`, which the harness
  keeps in step for drags. All of this is reflection because lwjgl3ify and LWJGL's SDL bindings are only on the
  Java 17+ run classpath, so synthetic input needs `runClient25` (or 17/21), not the Java 8 `runClient`.
- **Timing**: input steps run one per client tick (at `ClientTickEvent` START, before the game polls input);
  screenshots run at the end of a render tick after skipping one frame, so earlier requests are visible.
- **Screenshots** use `ScreenShotHelper` on the main framebuffer, which includes all GUI layers.
- **Process control**: killing the Gradle run task does not stop the forked game, so the harness writes its PID to
  `run/client/plannh-dev.pid` and `mc.sh stop` kills that process (only if it is still a java process). It never
  asks the game to quit: with GT loaded that can stop on a "really close?" dialog. Plans save on every edit.

## Troubleshooting

- `a client is already answering on port 25599`: run `tools/dev/mc.sh stop`.
- A game window that no script controls (e.g. started from an IDE): close it, or `taskkill //PID <pid> //F`.
- The project must live at a short path on Windows: Gradle and Minecraft dev hit MAX_PATH under deep folders.

# PlanNH (jackwrichards fork)

In-game, NEI-driven flowchart production planner for GT New Horizons: Minecraft 1.7.10, Forge 10.13.4.1614,
client-side only. UI is ModularUI2 (`gui/`), the balancer is an ojAlgo ILP (`data/flowchart/balancer/`), auto-layout
is ELK (`layout/`), recipe sources per mod are in `data/provider/`.

## Remotes and branches

- `origin` = github.com/jackwrichards/PlanNH (our fork), `upstream` = github.com/sbancuz/PlanNH.
- `main` is our trunk and the fork's default branch; it started from upstream `dev` (a0896df). The other branches
  on the fork are untouched copies of upstream's. The plan is to gut and rebuild the codebase in our own image, so
  upstream's branches, bugs and CI are not our concern; don't spend effort on them.
- Changing the mod is fine when it makes developing or testing easier (hooks, debug output, testability
  refactors). Don't do end-user work yet (UX polish, features, user-facing bug fixes): it is all going to change.
- Dev tooling lives in `src/main/java/com/sbancuz/plannh/dev/`, `tools/dev/` and `docs/`; it is wired in through
  `DevHarness.initIfDev()` in `ClientProxy#init`.

## The rebuild

- `docs/design/board-solve-mode.md` is the spec for the new planner: Solve mode only, Factory Flow's recipe card
  and drawers with NEI parts, what every control does, what we keep from the engine, and the porting order.
- Mockups live on the design canvas linked from that file (row 4, "Hybrid", is the chosen direction).

## Build and test

- Gradle provisions the JDKs (25 for the build, JetBrains Runtime 25 for `runClient25`). Keep the checkout at a
  short path: deep Windows paths break the clone and the Minecraft dev setup (MAX_PATH).
- `./gradlew test`: 131 headless JUnit tests (balancer, routing, layout, serialization) over gtnh-flow YAML charts
  in `src/test/resources/gtnh-flow/`. About 40s, no Minecraft. `addon.gradle` opts `test` out of the configuration
  cache; without that a clean build reports `:test NO-SOURCE` and silently runs nothing, so if you ever see
  NO-SOURCE, check the count in `build/test-results/test/*.xml`.
- `./gradlew spotlessApply` before committing; CI checks formatting.

## Running and seeing the game

Use the dev harness; full reference in `docs/dev-harness.md`.

```bash
tools/dev/mc.sh start      # build, launch, auto-load the creative test world; blocks until ready
tools/dev/mc.sh call open  # open the flowchart
tools/dev/mc.sh shot x.png ['x=..&y=..&w=..&h=..']   # screenshot (optionally cropped), then Read the PNG
tools/dev/mc.sh swap --reopen   # hot-swap code changes into the running game (~12s, no restart)
tools/dev/mc.sh stop
tools/dev/mc.sh smoke      # automated launch/open/screenshot/log-scan check
```

- `PLANNH_GTNH=1 tools/dev/mc.sh start` loads GregTech and the pack's recipes through the GTNH core mod (72 mods,
  about 30s once the jars are cached) in its own test world, `plannh-dev-gtnh`. Use it whenever real GT recipes,
  machines or items are needed; the plain start (29 mods, no GT) is enough for anything else.
- Input endpoints take GUI coordinates. Find targets with `call widgets` and check `hovered`/`focused` in
  `call status` rather than estimating from screenshots.
- PlanNH text fields need a double-click (`click?x&y&count=2`) before `type` works.
- After changing mod code: `tools/dev/mc.sh swap --reopen`. Restart instead (`mc.sh restart`, ~30s) when swap
  exits 2 (new or not-yet-loaded classes) or for mixins, resources and startup-only code.
- The game window opens on the user's desktop. It is muted and 1920x1080 at GUI scale 2 by design; keep it that
  way. Leave it running while the user is iterating on the UI with you; stop it when the work is done.
- The user can press F2 in game to screenshot what they see; when they refer to "this" or "my screenshot", Read
  the newest file in `run/client/screenshots/`. Harness screenshots land there too, under the names you gave them.
- UI feedback loop: change code, `swap --reopen`, check with a cropped `shot`, then tell the user it's live in
  their window. They can interact with the game at the same time; just don't send synthetic input while they are
  mid-action.

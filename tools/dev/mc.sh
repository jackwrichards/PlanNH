#!/usr/bin/env bash
# Drive the PlanNH dev client from a shell, headless-agent friendly. See docs/dev-harness.md.
#
#   tools/dev/mc.sh start [timeout_s]   build + launch the client, block until the test world is loaded
#   tools/dev/mc.sh stop                kill the client (a graceful quit can hang on a confirm dialog)
#   tools/dev/mc.sh restart             stop + start
#   tools/dev/mc.sh status              harness status JSON (exit 1 if the client is not up)
#   tools/dev/mc.sh shot [name.png] [x=..&y=..&w=..&h=..]   screenshot (optional GUI-coord crop), prints the path
#   tools/dev/mc.sh call 'click?x=10&y=20'   any harness endpoint, prints the JSON reply
#   tools/dev/mc.sh part 1 TIER [click|move|scroll] ['button=1']   act on card 1's tier chip (names from /board)
#   tools/dev/mc.sh swap [--reopen]     recompile and hot-swap changed classes into the running client
#                                       (exit 2: some changed classes weren't loaded yet, restart for those)
#   tools/dev/mc.sh smoke               start, open the flowchart, screenshot, stop; non-zero on failure
#
# Env: PLANNH_DEV_PORT (25599), PLANNH_RUN_TASK (runClient25), PLANNH_DEV_WIDTH/HEIGHT (1920x1080),
#      PLANNH_DEV_GUI_SCALE (2), PLANNH_DEV_SOUND (master volume, 0.0 = muted),
#      PLANNH_GTNH=1 (load GregTech and the pack's recipes; slow first start).
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PORT="${PLANNH_DEV_PORT:-25599}"
BASE="http://127.0.0.1:$PORT"
TASK="${PLANNH_RUN_TASK:-runClient25}"
STATE="$ROOT/build/dev-client"
LOG="$STATE/client.log"
PIDFILE="$STATE/gradle.pid"
CRASH_DIR="$ROOT/run/client/crash-reports"
SHOT_DIR="$ROOT/run/client/screenshots"
GAME_PIDFILE="$ROOT/run/client/plannh-dev.pid"
WIDTH="${PLANNH_DEV_WIDTH:-1920}"
HEIGHT="${PLANNH_DEV_HEIGHT:-1080}"
GUI_SCALE="${PLANNH_DEV_GUI_SCALE:-2}"
SOUND="${PLANNH_DEV_SOUND:-0.0}"
HOTSWAP="${PLANNH_HOTSWAP:-1}"
JDWP_PORT="${PLANNH_JDWP_PORT:-5005}"
GTNH="${PLANNH_GTNH:-0}"
SWAP_MANIFEST="$STATE/hotswap.sums"
CLASSES="$ROOT/build/tmp/downgradeMainClasses/main"  # what the jar (and so the game) is built from
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"  # any JDK 17+: runs tools/dev/Hotswap.java from source

die() { echo "mc.sh: $*" >&2; exit 1; }

call() { curl -sS --max-time 45 "$BASE/$1"; }

is_up() { curl -s --max-time 2 "$BASE/status" >/dev/null 2>&1; }

gradle_alive() { [ -f "$PIDFILE" ] && kill -0 "$(cat "$PIDFILE")" 2>/dev/null; }

newest_crash() { ls -t "$CRASH_DIR"/crash-*.txt 2>/dev/null | head -1; }

set_option() {
    local file="$1" key="$2" value="$3"
    if grep -q "^$key:" "$file"; then
        sed -i "s|^$key:.*|$key:$value|" "$file"
    else
        echo "$key:$value" >>"$file"
    fi
}

# Window size, GUI scale and sound live in options.txt, which must be set before the window exists.
configure_client() {
    local opts="$ROOT/run/client/options.txt"
    mkdir -p "$(dirname "$opts")"
    touch "$opts"
    set_option "$opts" overrideWidth "$WIDTH"
    set_option "$opts" overrideHeight "$HEIGHT"
    set_option "$opts" guiScale "$GUI_SCALE"
    set_option "$opts" fullscreen false
    set_option "$opts" soundCategory_master "$SOUND"
    # ModularUI2 turns its debug overlay on in every dev environment; players never see it.
    local mui="$ROOT/run/client/config/modularui2.cfg"
    [ -f "$mui" ] && sed -i 's/B:guiDebugMode=true/B:guiDebugMode=false/' "$mui"
}

start() {
    local timeout="${1:-600}"
    is_up && die "a client is already answering on port $PORT (run: $0 stop)"
    mkdir -p "$STATE"
    local crash_before
    crash_before="$(newest_crash)"
    configure_client
    rm -f "$GAME_PIDFILE"
    # downgradeMainClasses is built alongside so the swap baseline matches the jar the game runs.
    local run_args=(downgradeMainClasses "$TASK" --console=plain)
    # The GTNH core mod pulls in GregTech and the pack's recipes: ~180 dependencies, a much slower start.
    # Its own world too: a world saved with GregTech asks about missing blocks when opened without it.
    [ "$GTNH" = 1 ] && run_args=(-PgtnhRecipes "${run_args[@]}" "--mcJvmArgs=-Dplannh.dev.world=plannh-dev-gtnh")
    # JetBrains Runtime + HotswapAgent + a JDWP port so `mc.sh swap` can push recompiled classes into the game.
    [ "$HOTSWAP" = 1 ] && run_args+=(--hotswap
        "--mcJvmArgs=-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:$JDWP_PORT")
    (cd "$ROOT" && exec nohup ./gradlew "${run_args[@]}" >"$LOG" 2>&1 </dev/null) &
    disown
    echo $! >"$PIDFILE"
    echo "launching ($TASK), log: $LOG"
    local deadline=$((SECONDS + timeout))
    while true; do
        if call status 2>/dev/null | grep -q '"ready": true'; then
            echo "ready after $((SECONDS - deadline + timeout))s"
            # Baseline for `swap`: hashes of the classes the game was launched with.
            [ "$HOTSWAP" = 1 ] && "$JAVA" "$ROOT/tools/dev/Hotswap.java" baseline "$CLASSES" "$SWAP_MANIFEST"
            return 0
        fi
        local crash
        crash="$(newest_crash)"
        if [ -n "$crash" ] && [ "$crash" != "$crash_before" ]; then
            echo "client crashed: $crash" >&2
            sed -n '1,40p' "$crash" >&2
            stop >/dev/null 2>&1
            return 1
        fi
        if ! gradle_alive; then
            echo "gradle exited before the client was ready; tail of $LOG:" >&2
            tail -40 "$LOG" >&2
            return 1
        fi
        [ $SECONDS -ge $deadline ] && { echo "timed out after ${timeout}s" >&2; stop >/dev/null 2>&1; return 1; }
        sleep 2
    done
}

# Force-kills the game. A graceful quit can raise a "really close?" dialog (GTNH) that blocks the client until
# someone clicks it; plans save on every edit and the dev worlds are disposable, so nothing is lost by killing.
stop() {
    if [ -f "$GAME_PIDFILE" ]; then
        local pid
        pid="$(cat "$GAME_PIDFILE")"
        # Only a java process: after a crash the PID file is stale and the number may belong to something else.
        if tasklist //FI "PID eq $pid" 2>/dev/null | grep -qi java; then
            taskkill //PID "$pid" //F >/dev/null 2>&1 || kill -9 "$pid" 2>/dev/null
        fi
        rm -f "$GAME_PIDFILE"
    fi
    gradle_alive && kill "$(cat "$PIDFILE")" 2>/dev/null
    for _ in $(seq 1 15); do
        is_up || gradle_alive || break
        sleep 1
    done
    rm -f "$PIDFILE"
    is_up && die "something still answers on port $PORT"
    echo "stopped"
}

shot() {
    local name="${1:-shot-$(date +%Y%m%d-%H%M%S).png}"
    local reply
    reply="$(call "screenshot?name=$name${2:+&$2}")" || die "screenshot request failed"
    echo "$reply" | grep -q '"ok": true' || die "screenshot failed: $reply"
    echo "$SHOT_DIR/$name"
}

swap() {
    is_up || die "no client running (run: $0 start)"
    [ "$HOTSWAP" = 1 ] || die "hotswap is off (PLANNH_HOTSWAP=0)"
    (cd "$ROOT" && ./gradlew downgradeMainClasses --console=plain -q >"$STATE/swap-build.log" 2>&1) ||
        { grep -E "error:|.java:[0-9]+" "$STATE/swap-build.log" >&2; die "build failed, see $STATE/swap-build.log"; }
    "$JAVA" "$ROOT/tools/dev/Hotswap.java" push "$CLASSES" "$SWAP_MANIFEST" "$JDWP_PORT"
    local rc=$?
    if [ "${1:-}" = "--reopen" ] && [ $rc -ne 1 ]; then
        # Widgets are built when the screen opens, so construction changes need a fresh screen.
        call close >/dev/null && call open >/dev/null && echo "reopened the flowchart"
    fi
    return $rc
}

smoke() {
    start || return 1
    # client.log is the run's full stdout (all mods), rewritten by every start.
    local fail=0 game_log="$LOG"
    call open | grep -q '"ok": true' || { echo "open failed" >&2; fail=1; }
    sleep 1
    call status | grep -q '"muiScreen": "com.sbancuz.plannh.gui.FlowchartScreen"' ||
        { echo "flowchart screen not showing" >&2; fail=1; }
    shot smoke-flowchart.png || fail=1
    stop
    # Exceptions from PlanNH code fail the run; other logged errors (other mods, layout warnings) are reported.
    if grep -qE "^\s+at com\.sbancuz\.plannh" "$game_log"; then
        echo "PlanNH exception logged:" >&2
        grep -nE "Exception|^\s+at com\.sbancuz\.plannh" "$game_log" | head -20 >&2
        fail=1
    fi
    local errors
    errors="$(grep -E '/ERROR\]' "$game_log" | sed -E 's/^\[[0-9:]+\] //' | cut -c1-200 | sort | uniq -c | sort -rn | head -10)"
    [ -n "$errors" ] && { echo "logged errors (not failing the run):"; echo "$errors"; }
    [ $fail -eq 0 ] && echo "smoke OK" || echo "smoke FAILED" >&2
    return $fail
}

# Acts on a card control by name, using /board for its GUI centre: part <card index> <PART> [click|move|scroll] [extra query].
part() {
    [ $# -ge 2 ] || die "usage: $0 part <card> <ACTIONS|MACHINE|AMPS|TIER|COIL|MACHINES|BODY> [click|move|scroll] ['button=1&count=2' | 'amount=-1']"
    local card="$1" name="$2" action="${3:-click}" extra="${4:-}" xy
    xy="$(call board | node -e '
        let s = ""; process.stdin.on("data", d => s += d).on("end", () => {
            const b = JSON.parse(s), c = (b.cards || [])[+process.argv[1]], p = c && c.parts && c.parts[process.argv[2]];
            if (!p) { console.error(b.error || "no such card or part"); process.exit(1); }
            console.log("x=" + p.cx + "&y=" + p.cy);
        });' "$card" "$name")" || die "part $card $name not found"
    call "$action?$xy${extra:+&$extra}"
}
cmd="${1:-}"
shift || true
case "$cmd" in
    start) start "$@" ;;
    stop) stop ;;
    restart) stop; start "$@" ;;
    status) call status || exit 1 ;;
    shot) shot "$@" ;;
    call) [ $# -ge 1 ] || die "usage: $0 call 'endpoint?args'"; call "$1" ;;
    swap) swap "$@" ;;
    smoke) smoke ;;
    part) part "$@" ;;
    *) awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"; exit 2 ;;
esac

#!/usr/bin/env bash
# Records the AR lens demo (GT run, the "Iron line (demo)" plan and the demo row of machines in place): pans over
# the running machines, links each to its plan card from the world (L, then the lit card), shows the connector,
# the cards' "IN WORLD", Show in the world, and a linked machine looked at with the lens off.
#   tools/dev/ar-demo.sh [name]   -> run/client/recordings/<name>/ frames, then ffmpeg makes the video
set -u
cd "$(dirname "$0")/../.."
export MSYS_NO_PATHCONV=1
M=tools/dev/mc.sh
NAME="${1:-ar-demo}"
call() { $M call "$1" >/dev/null; }
cmd() { call "cmd?c=$(node -e 'console.log(encodeURIComponent(process.argv[1]))' "$1")"; }
# look_to yaw pitch [steps]: turn smoothly from the last view.
YAW=0; PITCH=14
look_to() {
    local steps="${3:-24}"
    for i in $(seq 1 "$steps"); do
        local yp
        yp=$(awk -v i="$i" -v n="$steps" -v y0="$YAW" -v y1="$1" -v p0="$PITCH" -v p1="$2" 'BEGIN {
            t = i / n; e = t < 0.5 ? 2 * t * t : 1 - (-2 * t + 2) ^ 2 / 2
            printf "yaw=%.2f&pitch=%.2f", y0 + (y1 - y0) * e, p0 + (p1 - p0) * e }')
        call "look?$yp"
        sleep 0.03
    done
    YAW=$1; PITCH=$2
}
# Mouse in steps to a GUI point, so the recorded cursor travels there.
MX=480; MY=270
mouse_to() {
    for i in $(seq 1 14); do
        call "move?x=$(( MX + ($1 - MX) * i / 14 ))&y=$(( MY + ($2 - MY) * i / 14 ))"
        sleep 0.025
    done
    MX=$1; MY=$2
}
# The centre of a card on the open board, by its machine's name.
card_xy() {
    $M call board | node -e "let s='';process.stdin.on('data',d=>s+=d).on('end',()=>{const b=JSON.parse(s);const c=(b.cards||[]).find(c=>c.machine===process.argv[1]);if(!c){process.exit(1)}console.log(c.parts.BODY.cx+' '+c.parts.BODY.cy)})" "$1"
}
part_xy() {
    $M call board | node -e "let s='';process.stdin.on('data',d=>s+=d).on('end',()=>{const b=JSON.parse(s);const c=(b.cards||[]).find(c=>c.machine===process.argv[1]);if(!c){process.exit(1)}const p=c.parts[process.argv[2]];console.log(p.cx+' '+p.cy)})" "$1" "$2"
}
# link_from_world yaw pitch machine: look at it, press L, click its card on the board, back to the world.
link_from_world() {
    look_to "$1" "$2" 18
    sleep 1.4
    call "key?code=38"
    sleep 1.6
    MX=480; MY=270
    read -r cx cy < <(card_xy "$3")
    mouse_to "$cx" "$cy"
    sleep 0.6
    call "click?x=$cx&y=$cy"
    sleep 2.6
}

# Both cards in view, clear of the overview rail and NEI's item list.
call "open"
sleep 1.2
call "view?zoom=0.6&panX=60&panY=0"
sleep 0.4
call "close"
cmd "/tp @p 517.5 4 14.5"
call "look?yaw=0&pitch=14"
sleep 1
call "record?start=$NAME&fps=24&width=1280"
sleep 0.5
# 1. The machines running, a slow look across.
look_to 12 12 30
look_to -12 12 50
look_to 0 14 30
sleep 1.5
# 2. Each machine linked to its plan card from the world.
link_from_world 16.7 8.8 "Basic Electric Furnace"
link_from_world 31.0 7.9 "Basic Electric Furnace"
link_from_world -16.7 8.8 "Basic Bending Machine"
link_from_world -31.0 7.9 "Basic Bending Machine"
look_to 0 14 30
sleep 2
# 3. From above: the connector from the furnaces to the benders, arrows towards the benders.
cmd "/tp @p 517.5 10 11.5"
YAW=0; PITCH=26
call "look?yaw=0&pitch=26"
sleep 3.5
look_to 14 28 40
sleep 1.5
look_to -10 26 50
sleep 2
cmd "/tp @p 517.5 4 14.5"
call "look?yaw=0&pitch=14"
YAW=0; PITCH=14
sleep 1
# 4. The planner: each card says how many machines it has in the world; Show in the world.
call "open"
sleep 2.5
read -r ax ay < <(part_xy "Basic Electric Furnace" ACTIONS)
MX=480; MY=270
mouse_to "$ax" "$ay"
call "click?x=$ax&y=$ay"
sleep 1.2
# The menu's fifth row: Show in the world.
read -r sx sy < <($M call widgets | node -e "let s='';process.stdin.on('data',d=>s+=d).on('end',()=>{const p=(JSON.parse(s).popups||[]).find(p=>p.name.startsWith('gtnhplanner_actions'));console.log((p.x+40)+' '+(p.y+4+4*20+10))})")
mouse_to "$sx" "$sy"
sleep 0.6
call "click?x=$sx&y=$sy"
sleep 1
look_to 20 10 30
sleep 3
look_to 0 14 30
# 5. The lens off: a linked machine looked at names its card, and the minimap follows it.
call "key?code=21"
sleep 1
look_to -16.7 8.8 24
sleep 3
look_to 16.7 8.8 30
sleep 3
call "key?code=21"
look_to 0 14 30
sleep 2
# 6. Close on a linked machine: its panel names the plan card.
look_to 16.7 8.8 24
sleep 4
call "record?stop=1"
echo "frames in run/client/recordings/$NAME"

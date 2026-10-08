#!/usr/bin/env bash
# End-to-end check of the planner board in the running GregTech dev client (PLANNH_GTNH=1 tools/dev/mc.sh start).
# Works in a throwaway plan slot it creates and deletes, so the owner's plans are untouched. Prints PASS/FAIL per
# step and exits non-zero on any failure. Refuses to run while the game window has focus (the owner may be using
# it); FORCE=1 overrides.
#
#   tools/dev/board-check.sh
set -u
cd "$(dirname "$0")/../.."
M=tools/dev/mc.sh
fails=0

pass() { echo "PASS $*"; }
fail() { echo "FAIL $*"; fails=$((fails + 1)); }
board() { $M call board; }
# board | js 'expression over b' -> prints the value
js() { node -e "let s='';process.stdin.on('data',d=>s+=d).on('end',()=>{const b=JSON.parse(s);console.log(($1))})"; }

if [ "${FORCE:-0}" != 1 ] && $M call status | grep -q '"windowActive": true'; then
    echo "the game window has focus (the owner may be using it); run again later or FORCE=1" >&2
    exit 2
fi

$M call open >/dev/null
sleep 1
start_slot=$($M call slots | js 'b.active')
$M call "slots?add=board-check" >/dev/null
sleep 1
$M call "view?zoom=1&panX=0&panY=0" >/dev/null

# 1. A real GregTech recipe lands, with its defaults.
$M call "addrecipe?output=dustRutile&handler=blast&input=ilmenite" >/dev/null
sleep 1.5
tier=$(board | js 'b.cards.length===1 ? b.cards[0].tier+" "+b.cards[0].coil : "cards="+b.cards.length')
[ "$tier" = "HV Cupronickel" ] && pass "EBF card at HV with Cupronickel" || fail "EBF card defaults: $tier"

# 2. Dragging an output's name onto empty board makes a product drawer.
# Dropped below the card, so it stays on the board whatever the window size (NEI's list covers the right).
read -r x y cx bottom < <(board | js 'b.cards[0].parts.OUT1.cx+40+" "+b.cards[0].parts.OUT1.cy+" "+b.cards[0].parts.BODY.cx+" "+(b.cards[0].parts.BODY.y+b.cards[0].parts.BODY.h)')
$M call "drag?x1=$x&y1=$y&x2=$cx&y2=$((bottom + 50))&steps=10" >/dev/null
sleep 1
drawer=$(board | js 'b.drawers.length===1 ? b.drawers[0].kind+" "+b.drawers[0].label : "drawers="+b.drawers.length')
[ "$drawer" = "PRODUCT Rutile Dust" ] && pass "product drawer from a port drag" || fail "port drag: $drawer"

# 3. A rate on the drawer solves the plan: 1 rutile/s at HV is 53.33 EBFs.
$M part d0 RATE >/dev/null
sleep 0.6
$M call "type?text=1" >/dev/null
$M call "key?code=28" >/dev/null
sleep 2
machines=$(board | js 'b.cards[0].machines.toFixed(2)')
[ "$machines" = "53.33" ] && pass "solved 53.33 EBFs for 1 rutile/s" || fail "solve: machines=$machines"

# 4. A new card is placed, not wired: adding a recipe that makes carbon dust leaves it for the player to wire.
# The packager recipe that makes carbon dust from small piles (NEI also lists the reverse one).
$M call "addrecipe?output=dustCarbon&handler=Packager&input=small%20pile" >/dev/null
sleep 2
wired=$(board | js 'b.edges+" "+b.cards.length')
[ "$wired" = "0 2" ] && pass "packager added, not wired" || fail "add: $wired"

# 5. Undo takes the packager away again; redo brings it back.
$M call "key?code=44&mods=ctrl" >/dev/null
sleep 1.5
after_undo=$(board | js 'b.cards.length')
$M call "key?code=44&mods=ctrl,shift" >/dev/null
sleep 1.5
after_redo=$(board | js 'b.cards.length')
[ "$after_undo/$after_redo" = "1/2" ] && pass "undo and redo" || fail "undo/redo: $after_undo/$after_redo"

# 6. No solver errors on the board.
errors=$(board | js 'b.notices.filter(n=>n.startsWith("ERROR")).length')
[ "$errors" = "0" ] && pass "no solver errors" || fail "notices: $(board | js 'b.notices.join(" | ")')"

# 7. A screenshot for a look.
$M shot board-check.png >/dev/null && pass "screenshot run/client/screenshots/board-check.png"

# Clean up: drop the check's slot and go back.
slot=$($M call slots | js 'b.slots.indexOf("board-check")')
[ "$slot" -ge 0 ] && $M call "slots?delete=$slot" >/dev/null
$M call "slots?switch=$start_slot" >/dev/null
$M call close >/dev/null
$M call open >/dev/null

[ $fails -eq 0 ] && echo "board check OK" || echo "board check: $fails failed" >&2
exit $fails

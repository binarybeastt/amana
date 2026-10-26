#!/usr/bin/env bash
# Records the demo's phone flows with real UI taps (docs/DEMO.md): Ada = the real phone,
# Tunde = emulator-5556, a stranger = emulator-5558. Clips to demo/raw/r-*.mp4, taps to
# demo/raw/taps.jsonl, full-resolution stills to demo/raw/r-*.png.
#   ./shoot.sh <phone-serial> [amount=0.01]
set -uo pipefail
source "$(dirname "$0")/demo_lib.sh"
P=$1; AMT=${2:-0.01}; T=emulator-5556; R=emulator-5558; X=emulator-5554
say() { echo "$(date +%T) $*"; }
wake() { "$ADB" -s "$P" shell input keyevent KEYCODE_WAKEUP; }
: >"$RAW/taps.jsonl"

say "prep: sync Ada and Tunde, then take Tunde and the stranger offline"
wake; launch $P; phone_airplane $P off; launch $T
wait_synced $P 600 || { say "FAIL: phone not synced"; exit 1; }
{ wait_synced $T 600 && sleep 15 && wait_synced $T 300; } || { say "FAIL: Tunde not synced"; exit 1; }
ADA=$(state $P "s['address']")
for s in $T $R $X; do net_off $s; bt $s on; done
hook $P RESET_RECEIVE; hook $T CLEAR_PAY; home $T; home $R; wake; home $P; sleep 2

say "scene: pay"
rec_start $P r-ada-pay; rec_start $T r-tunde-pay; sleep 2
phone_airplane $P on; sleep 3
tap_rec $T nav_pay r-tunde-pay; sleep 1.5
# The address: the emulator's clipboard doesn't follow the Mac's, and long `input text` drops
# characters, so type it in short chunks and check it arrived whole.
tap_rec $T pay_to r-tunde-pay; sleep 0.8
for ((i=0; i<${#ADA}; i+=24)); do "$ADB" -s $T shell input text "${ADA:i:24}"; sleep 0.4; done
hide_keyboard $T; sleep 1
"$ADB" -s $T shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
got=$("$ADB" -s $T exec-out cat /sdcard/ui.xml | python3 -c "
import re, sys; x = sys.stdin.read(); m = re.search(r'text=\"([^\"]*)\"[^>]*resource-id=\"pay_to\"', x); print(m.group(1) if m else '')")
say "address typed: $([ "$got" = "$ADA" ] && echo ok || echo "MISMATCH (${#got} chars)")"
keys_rec $T "$AMT" r-tunde-pay; sleep 1
if [ "$got" = "$ADA" ]; then
  tap_rec $T pay_go r-tunde-pay
else
  say "paying through the hook"; hook $T PAY --es to "$ADA" --es zats "$(python3 -c "print(round($AMT*1e8))")"
fi
wait_state $T "s['pay']['state'] in ('ready', 'failed')" 600
TX=$(state $T "s['pay'].get('txid')"); say "tunde: $(state $T "s['pay']['state']") $TX $(state $T "s['pay'].get('error')")"
[ -n "$TX" ] && [ "$TX" != None ] || { rec_stop $P r-ada-pay; rec_stop $T r-tunde-pay; say "FAIL: no payment"; exit 1; }
echo "$TX" >"$RAW/r-txid.txt"
sleep 2; wake
tap_rec $P nav_receive r-ada-pay; sleep 2.5
tap_rec $P recv_tab_scan r-ada-pay; sleep 2
relay_frames $T $P; say "ada: $(state $P "s['receive']" | cut -c1-160)"
sleep 4; shot $P r-ada-check; shot $T r-tunde-qr
"$ADB" -s $P shell input keyevent KEYCODE_BACK; sleep 3; shot $P r-ada-home
rec_stop $P r-ada-pay; rec_stop $T r-tunde-pay

say "scene: relay"
rec_start $R r-relay; sleep 1; tap_rec $R nav_payments r-relay
wait_state $R "any(e['txid'] == '$TX' for e in s['mesh']['queue'])" 120 && say "relay holds it" || say "relay never got it"
sleep 3; net_on $R
wait_state $R "any(e['txid'] == '$TX' and e.get('state') in ('sent', 'settled') for e in s['mesh']['queue'])" 120 && say "broadcast"
sleep 5; shot $R r-relay-sent; rec_stop $R r-relay

say "scene: settle (waiting for the block)"
wait_state $R "any(e['txid'] == '$TX' and e.get('state') == 'settled' for e in s['mesh']['queue'])" 1200 && say "mined"
wake; home $P; sleep 2
rec_start $P r-ada-settle; sleep 2; phone_airplane $P off
wait_state $P "any(r['txid'] == '$TX' and r.get('settled') for r in s.get('received', []))" 300 && say "ada settled"
sleep 4; shot $P r-ada-settled; rec_stop $P r-ada-settle
net_on $T; say "done"

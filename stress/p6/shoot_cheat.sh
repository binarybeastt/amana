#!/usr/bin/env bash
# Records the double spend for the demo video (docs/DEMO.md, scene 4.1): the same steps as
# stress/p4/p4g2t1_conflict.sh, with all three screens recorded from just before the second spend.
# A holds tx1, B spends the same note again (tx2), C is the phone nearby that raises the alarm.
# Run right after a fresh emulator boot. SETTLE=<s> waits that long after B's mesh starts. Clips: demo/raw/cheat-{a,b,c}.mp4; timings: cheat.env.
#   ./shoot_cheat.sh <A> <B> <C> [pair-dir=stress/p4/logs/p4g2-pair]
set -uo pipefail
source "$(dirname "$0")/demo_lib.sh"
A=$1; B=$2; C=$3; PAIR=${4:-$ROOT/stress/p4/logs/p4g2-pair}
source "$PAIR/pair.env"
say() { echo "$(date +%T) $*"; }
has_alert() { [ "$(state "$1" "any(set(['$TX1', '$TX2']) <= set(c['txids']) for c in s.get('conflicts', []))")" = True ]; }
cleanup() { for s in "$A" "$B" "$C"; do hook "$s" RELAY_CLEAR; hook "$s" CONFLICTS_CLEAR; hook "$s" MESH_START; done; }
trap cleanup EXIT

for s in "$A" "$B" "$C"; do
  launch "$s"; net_off "$s"; bt "$s" on
  hook "$s" RELAY_CLEAR; hook "$s" CONFLICTS_CLEAR
done
for s in "$A" "$B" "$C"; do
  wait_state "$s" "s.get('wallet') == 'ready'" 240 || { say "FAIL: $s wallet not ready"; exit 1; }
  wait_mesh "$s" "m['ble_running']" 60 || { say "FAIL: $s mesh not running"; exit 1; }
done

# 1. A → C, B away.
hook "$B" MESH_STOP
inject "$A" "$PAIR/tx1.hex" 1 "$TX1" >/dev/null
wait_state "$C" "any(e['txid'] == '$TX1' for e in s['mesh']['queue'])" 180 || { say "FAIL: tx1 never reached C"; exit 1; }
say "C holds tx1"
hook "$A" MESH_STOP
for s in "$A" "$B" "$C"; do home "$s"; done
sleep 5

# 2. Record, then B (with tx2) meets C.
hook "$B" MESH_START
wait_mesh "$B" "m['ble_running']" 60 || { say "FAIL: B mesh did not restart"; exit 1; }
sleep "${SETTLE:-2}"  # let B find its neighbours before the second spend
for s in "$A" "$B" "$C"; do rec_start "$s" "cheat-${s##*-}"; done
sleep 3
t_inject=$(now_ms)
inject "$B" "$PAIR/tx2.hex" 1 "$TX2" >/dev/null
end=$((SECONDS + 120)); tc=""
while [ $SECONDS -lt $end ]; do has_alert "$C" && { tc=$(now_ms); break; }; sleep 0.3; done
[ -n "$tc" ] && say "C alert $(( (tc - t_inject) )) ms after the inject (host clock, state poll)" || say "C: no alert in 120 s"
sleep 8
for s in "$A" "$B" "$C"; do rec_stop "$s" "cheat-${s##*-}"; done
shot "$C" cheat-c-alert
{
  echo "T_INJECT=$t_inject"; echo "T_ALERT=$tc"
  for s in "$A" "$B" "$C"; do echo "T0_${s##*-}=$(cat "$RAW/cheat-${s##*-}.t0")"; done
} >"$RAW/cheat.env"
cat "$RAW/cheat.env"

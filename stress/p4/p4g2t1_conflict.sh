#!/usr/bin/env bash
# P4.G2.T1, emulator form: two conflicting txs on two phones; a third phone meets both.
# Pass: conflict alert on the third phone within 60 s of it meeting the second phone.
# Also reported (not part of the pass line): when the two holders learn of it.
#
# All three offline (Wi-Fi + data off) so neither tx is broadcast. The mesh is stopped and
# started with the app's hooks (no radio cycles: netsim degrades). Order:
#   1. A holds tx1 (injected), B's mesh stopped. A meets C: C gets tx1. A's mesh stopped.
#   2. B's mesh started, tx2 injected on B (clock starts once it is stored). C meets B.
#   3. Wait for C's alert (≤ 60 s), and B's; then start A's mesh and wait for A's.
# Ends with every queue and alert cleared, so the pair is never carried online.
#   ./p4g2t1_conflict.sh <A> <B> <C> [pair-dir=stress/p4/logs/p4g2-pair]   (p4g2_make_pair.sh first)
set -uo pipefail
source "$(dirname "$0")/../p3/lib.sh"
A=$1; B=$2; C=$3; PAIR=${4:-$ROOT/stress/p4/logs/p4g2-pair}
source "$PAIR/pair.env"
LOG="$ROOT/stress/p4/logs/p4g2t1.log"; : >"$LOG"
say() { echo "$(date -u +%T) $*" | tee -a "$LOG"; }
conflicts() { state "$1" "len(s.get('conflicts', []))"; }
has_alert() { [ "$(state "$1" "any(set(['$TX1', '$TX2']) <= set(c['txids']) for c in s.get('conflicts', []))")" = True ]; }
# wait_alert <serial> <timeout-s>: prints seconds from $t0 to the alert in state.json
wait_alert() {
  local end=$((SECONDS + $2))
  while [ $SECONDS -lt $end ]; do
    has_alert "$1" && { echo $(( ($(now_ms) - t0) / 1000 )); return 0; }
    sleep 1
  done
  return 1
}
cleanup() { for s in "$A" "$B" "$C"; do hook "$s" RELAY_CLEAR; hook "$s" CONFLICTS_CLEAR; hook "$s" MESH_START; done; }
trap cleanup EXIT

say "pair: tx1=${TX1:0:16}… tx2=${TX2:0:16}… nullifier ${SHARED_NF:0:16}…"
for s in "$A" "$B" "$C"; do
  launch "$s"; net_off "$s"; bt "$s" on
  hook "$s" RELAY_CLEAR; hook "$s" CONFLICTS_CLEAR; "$ADB" -s "$s" logcat -c
done
for s in "$A" "$B" "$C"; do
  wait_state "$s" "s.get('wallet') == 'ready'" 240 || { say "FAIL: $s wallet not ready"; exit 1; }
  wait_mesh "$s" "m['ble_running']" 60 || { say "FAIL: $s mesh not running"; exit 1; }
  [ "$(conflicts "$s")" = 0 ] || { say "FAIL: $s has alerts before the test"; exit 1; }
done
CN=$(node_id "$C")

# 1. A → C, B away.
hook "$B" MESH_STOP
r=$(inject "$A" "$PAIR/tx1.hex" 1 "$TX1"); say "A inject tx1: $r"
echo "$r" | grep -c '"stored"' >/dev/null || { say "FAIL: A did not store tx1"; exit 1; }
wait_state "$C" "any(e['txid'] == '$TX1' for e in s['mesh']['queue'])" 180 || { say "FAIL: tx1 never reached C"; exit 1; }
say "C holds tx1; no alert anywhere yet: A=$(conflicts "$A") C=$(conflicts "$C")"
hook "$A" MESH_STOP
sleep 5

# 2. B (with tx2) meets C.
hook "$B" MESH_START
wait_mesh "$B" "m['ble_running']" 60 || { say "FAIL: B mesh did not restart"; exit 1; }
r=$(inject "$B" "$PAIR/tx2.hex" 1 "$TX2"); say "B inject tx2: $r"
echo "$r" | grep -c '"stored"' >/dev/null || { say "FAIL: B did not store tx2"; exit 1; }
t0=$(now_ms)

# 3. Alerts.
ok=True
if tc=$(wait_alert "$C" 120); then say "C alert after ${tc} s"; else tc=999; say "C: no alert in 120 s"; ok=False; fi
[ "$tc" -le 60 ] || ok=False
if tb=$(wait_alert "$B" 120); then say "B alert after ${tb} s"; else say "B: no alert in 120 s (extra check)"; fi
hook "$A" MESH_START
if ta=$(wait_alert "$A" 180); then say "A alert after ${ta} s (A's mesh restarted after B's alert)"; else say "A: no alert in 180 s (extra check)"; fi
say "C conflict: $(state "$C" "s['conflicts']")"
log_has "$C" "CONFLICT nullifier=$SHARED_NF" && say "C log: $(log_line "$C" "CONFLICT nullifier=")" || say "C log: no CONFLICT line"
# The banner must be on C's home screen (uiautomator).
home "$C"; sleep 4
"$ADB" -s "$C" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
banner=$("$ADB" -s "$C" exec-out cat /sdcard/ui.xml | grep -c 'resource-id="conflict_alert"' || true)
say "C banner on screen: $banner"; [ "$banner" -gt 0 ] || ok=False
[ $ok = True ] && say "PASS (C alert in ${tc} s ≤ 60 s)" || { say FAIL; exit 1; }

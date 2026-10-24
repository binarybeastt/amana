#!/usr/bin/env bash
# P4.G2.T2, emulator form: N honest payments through the mesh → zero false conflict alerts.
# Payers take turns among the phones with offline-ready notes (PAY hook, same code path as
# the Pay button), each paying the next phone. All phones offline (Wi-Fi + data off),
# Bluetooth on, so every payment spreads only by the mesh; each must reach every phone's
# queue, and after each one every phone must still show 0 conflicts.
# If every phone runs out of offline-ready notes before N payments the script stops with
# exit 2 (incomplete, not a pass): settle (net_on), PREPARE, and re-run with the remainder.
#   ./p4g2t2_honest.sh <A> <B> <C> [n=100] [zats=10000]
set -uo pipefail
source "$(dirname "$0")/../p3/lib.sh"
PHONES=("$1" "$2" "$3"); N=${4:-100}; ZATS=${5:-10000}
LOG="$ROOT/stress/p4/logs/p4g2t2.log"; : >"$LOG"
say() { echo "$(date -u +%T) $*" | tee -a "$LOG"; }
conflicts() { state "$1" "len(s.get('conflicts', []))"; }
ready_notes() { state "$1" "sum(1 for n in s.get('prepare', {}).get('notes', []) if n['status'] == 'available')"; }

for s in "${PHONES[@]}"; do
  launch "$s"; net_off "$s"; bt "$s" on; hook "$s" CONFLICTS_CLEAR; "$ADB" -s "$s" logcat -c
done
ADDR=()
for i in 0 1 2; do
  s=${PHONES[$i]}
  wait_state "$s" "s.get('wallet') == 'ready'" 240 || { say "FAIL: $s wallet not ready"; exit 1; }
  wait_mesh "$s" "m['ble_running']" 60 || { say "FAIL: $s mesh not running"; exit 1; }
  ADDR[$i]=$(state "$s" "s['address']")
done
say "start: notes ready ${PHONES[0]}=$(ready_notes "${PHONES[0]}") ${PHONES[1]}=$(ready_notes "${PHONES[1]}") ${PHONES[2]}=$(ready_notes "${PHONES[2]}")"

done_n=0; false_alerts=0; missing=0; turn=0; last=""
while [ $done_n -lt "$N" ]; do
  payer=""; pi=-1
  for k in 0 1 2; do
    i=$(( (turn + k) % 3 ))
    [ "$(ready_notes "${PHONES[$i]}")" -gt 0 ] 2>/dev/null && { pi=$i; payer=${PHONES[$i]}; turn=$(( (i + 1) % 3 )); break; }
  done
  [ -n "$payer" ] || { say "INCOMPLETE: no phone has offline-ready notes after $done_n payments"; exit 2; }
  ti=$(( (pi + 1) % 3 )); to=${PHONES[$ti]}
  # The payer's own previous txid too: a stale 'ready' from its last payment is not a new one.
  prev=$(state "$payer" "s['pay'].get('txid') or ''")
  hook "$payer" CLEAR_PAY; sleep 4
  hook "$payer" PAY --es to "${ADDR[$ti]}" --es zats "$ZATS"
  wait_state "$payer" "s['pay']['state'] in ('ready', 'failed') and s['pay'].get('txid') not in ('$last', '$prev')" 900 \
    || { say "FAIL: payment $((done_n + 1)) from $payer never finished"; exit 1; }
  if [ "$(state "$payer" "s['pay']['state']")" != ready ]; then
    say "payer $payer: $(state "$payer" "s['pay']['error']") (skipping this phone this turn)"; continue
  fi
  tx=$(state "$payer" "s['pay']['txid']"); last=$tx; done_n=$((done_n + 1))
  for s in "${PHONES[@]}"; do
    wait_state "$s" "any(e['txid'] == '$tx' for e in s['mesh']['queue'])" 300 || { missing=$((missing + 1)); say "  ${tx:0:12}… never reached $s"; }
  done
  c="$(conflicts "${PHONES[0]}") $(conflicts "${PHONES[1]}") $(conflicts "${PHONES[2]}")"
  [ "$c" = "0 0 0" ] || { false_alerts=$((false_alerts + 1)); say "  FALSE ALERT after ${tx:0:12}…: $c"; }
  say "payment $done_n/$N ${payer}→${to} ${tx:0:16}… conflicts: $c"
done
for s in "${PHONES[@]}"; do
  n=$(mesh_log "$s" | grep -c "CONFLICT nullifier=" || true)
  say "$s: alerts $(conflicts "$s"), CONFLICT log lines $n, queue $(mesh "$s" "len(m['queue'])"), gossip set $(mesh "$s" "m.get('nullifiers')")"
  [ "$n" = 0 ] || false_alerts=$((false_alerts + n))
done
say "payments $done_n, not relayed everywhere $missing, false alerts $false_alerts"
[ "$false_alerts" = 0 ] && [ "$missing" = 0 ] && say PASS || { say FAIL; exit 1; }

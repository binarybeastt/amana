#!/usr/bin/env bash
# Gate P3, emulator form: three emulators on netsim's shared Bluetooth.
#   1. A, B, C online and synced; then all three go offline (Wi-Fi and mobile data off; no
#      default network), A and B with Bluetooth on, C with Bluetooth off (not in range yet).
#   2. A pays B through the UI. The payment reaches B over Bluetooth only; B verifies it
#      offline and shows "received, not yet settled".
#   3. A leaves (Bluetooth off). C comes into range (Bluetooth on) and picks the payment up
#      from B (B is the only phone C can hear).
#   4. C "leaves range": Bluetooth off, then networking on. C broadcasts; A and B are still
#      offline, so C is the only phone that can. The tx must be mined.
#   5. B and A come online: B shows "settled", A's pending payment clears.
# N runs (default 5).
#   ./gate_p3.sh <A> <B> <C> [runs=5] [zec=0.01] [runs-to-do, e.g. "2 4"]
set -euo pipefail
source "$(dirname "$0")/lib.sh"
A=$1; B=$2; C=$3; N=${4:-5}; AMT=${5:-0.01}; ONLY=${6:-}
ZATS=$(python3 -c "from decimal import Decimal; print(int(Decimal('$AMT') * 10**8))")
LOG="$P3/logs/gate-p3.log"
if [ -z "$ONLY" ]; then : >"$LOG"; fi
say() { echo "$(date -u +%T) $*" | tee -a "$LOG"; }
say "Gate P3: $N runs, $AMT ZEC, A=$A B=$B C=$C${ONLY:+, runs $ONLY}"
for s in "$A" "$B" "$C"; do "$ADB" -s "$s" shell settings put secure stylus_handwriting_enabled 0; done
fail() { say "run $k: FAIL $*"; }
is_offline() { net_is_off "$1"; }
# wait_log <serial> <regex> <timeout-s>: until a MESH log line matches
wait_log() {
  local end=$((SECONDS + $3))
  while [ $SECONDS -lt $end ]; do log_has "$1" "$2" && return 0; sleep 2; done
  return 1
}
# Preflight: start from a freshly powered Bluetooth radio on each phone (earlier tests toggled
# the radios many times; see P3.G5.T2 attempt 1).
for s in "$A" "$B" "$C"; do bt "$s" off; done; sleep 4; for s in "$A" "$B" "$C"; do bt "$s" on; done; sleep 5
if [ -z "$ONLY" ]; then
  # Relay queues start empty so runs only carry their own payment (earlier tests left entries).
  for s in "$A" "$B" "$C"; do launch "$s"; hook "$s" RELAY_CLEAR; done
fi
AN=$(node_id "$A"); BN=$(node_id "$B"); CN=$(node_id "$C")
say "nodes: A=$AN B=$BN C=$CN"
passed=0
for k in ${ONLY:-$(seq 1 "$N")}; do
  # 1. Everyone online and at the tip (sync right before going offline).
  for s in "$A" "$B" "$C"; do net_on "$s"; bt_is_on "$s" || bt "$s" on; launch "$s"; done
  for s in "$A" "$B" "$C"; do wait_synced "$s" 900 || { fail "$s not synced"; continue 2; }; done
  TO=$(state "$B" "s['address']")
  for s in "$A" "$B" "$C"; do net_off "$s"; done
  bt "$C" off
  sleep 5
  # A must hear B before paying (else this is an emulator radio problem, not a payment one).
  wait_mesh "$A" "any(p['node'] == '$BN' and p['seen_ms_ago'] < 10000 for p in m['peers'])" 60 || { fail "A cannot hear B"; continue; }
  for s in "$A" "$B" "$C"; do is_offline "$s" || { fail "$s still online"; exit 1; }; done
  bt_is_on "$C" && { fail "C's Bluetooth still on"; continue; }
  for s in "$A" "$B" "$C"; do "$ADB" -s "$s" logcat -c; done
  # 2. A pays B offline; it goes over Bluetooth.
  t0=$SECONDS
  TXID=$(pay_via_ui "$A" "$TO" "$AMT") || { fail "pay failed"; continue; }
  t_pay=$((SECONDS - t0)); t1=$SECONDS
  if ! wait_state "$B" "any(r['txid'] == '$TXID' for r in s['received'])" 300; then fail "B never received $TXID over Bluetooth"; continue; fi
  t_ab=$((SECONDS - t1))
  label=$(state "$B" "next(r['label'] for r in s['received'] if r['txid'] == '$TXID')")
  amount=$(state "$B" "next(r['amount'] for r in s['received'] if r['txid'] == '$TXID')")
  from_b=$(entry "$B" "$TXID" from)
  if [ "$amount" != "$ZATS" ] || [ "$label" != "received, not yet settled" ] || [ "$from_b" != "$AN" ]; then
    fail "B: amount=$amount label=$label from=$from_b"; continue
  fi
  # 3. A leaves; C comes into range and gets it from B.
  bt "$A" off; sleep 3; bt "$C" on
  t2=$SECONDS
  if ! wait_mesh "$C" "any(e['txid'] == '$TXID' for e in m['queue'])" 300; then fail "C never got $TXID"; continue; fi
  t_bc=$((SECONDS - t2))
  from_c=$(entry "$C" "$TXID" from); hops_c=$(entry "$C" "$TXID" hops)
  [ "$from_c" = "$BN" ] && [ "$hops_c" = 2 ] || { fail "C got it from $from_c with $hops_c hops (want B, 2)"; continue; }
  # 4. C leaves range: Bluetooth off, then networking on. A and B stay offline.
  bt "$C" off; sleep 2; net_on "$C"
  t3=$SECONDS
  for s in "$A" "$B"; do is_offline "$s" || { fail "$s came online"; continue 2; }; done
  if ! wait_log "$C" "BROADCAST $TXID (sent|already known)" 300; then fail "C did not broadcast"; continue; fi
  t_bcast=$((SECONDS - t3))
  bline=$(log_line "$C" "BROADCAST $TXID" | sed 's/.*BROADCAST/BROADCAST/')
  if ! wait_mesh "$C" "any(e['txid'] == '$TXID' and e['state'] == 'settled' for e in m['queue'])" 1800; then fail "not mined 30 min after C broadcast"; continue; fi
  t_mined=$((SECONDS - t3))
  mined=$(entry "$C" "$TXID" mined_height)
  for s in "$A" "$B"; do is_offline "$s" || { fail "$s came online before the tx was mined"; continue 2; }; done
  # 5. B and A come online and agree.
  bt "$A" on; bt "$C" on
  net_on "$B"; net_on "$A"; launch "$B"; launch "$A"
  ok=True
  wait_state "$B" "any(r['txid'] == '$TXID' and r['settled'] and r['mined_height'] == $mined for r in s['received'])" 900 || ok=False
  wait_state "$A" "'$TXID' not in s['pending_sent'] and s['sync']['status'] == 'SYNCED'" 900 || ok=False
  # Keep every phone's log for this run, and check C's send came before any other phone's.
  for s in "$A" "$B" "$C"; do
    "$ADB" -s "$s" logcat -d | grep -E " (MESH|Settler|AppController|offline-zec|AndroidRuntime) *:" \
      | grep -vE "offline-zec.*(Compact|Scan|Download|Enhanc|Process|Sync)" >"$P3/logs/gate-p3-run$k-$s.logcat" || true
  done
  first_other=$(grep -hE "BROADCAST $TXID|submit transaction $TXID" "$P3/logs/gate-p3-run$k-$A.logcat" "$P3/logs/gate-p3-run$k-$B.logcat" | head -1 | cut -c1-18 || true)
  first_c=$(grep -hE "BROADCAST $TXID" "$P3/logs/gate-p3-run$k-$C.logcat" | head -1 | cut -c1-18 || true)
  settled_c=$(grep -hE "SETTLED $TXID" "$P3/logs/gate-p3-run$k-$C.logcat" | head -1 | cut -c1-18 || true)
  if [ -n "$first_other" ] && [[ "$first_other" < "$settled_c" ]]; then ok=False; say "run $k: A or B tried to broadcast ($first_other) before it was mined ($settled_c)"; fi
  if [ $ok = True ]; then passed=$((passed + 1)); r=pass; else r=FAIL; fi
  say "run $k: $r txid $TXID; pay ${t_pay}s; A→B over Bluetooth ${t_ab}s (B: \"$label\", $amount zats); B→C ${t_bc}s (hops 2); C online → $bline ${t_bcast}s; mined at $mined ${t_mined}s after C came online (A, B offline throughout); B shows settled, A's pending cleared"
done
for s in "$A" "$B" "$C"; do net_on "$s"; bt_is_on "$s" || bt "$s" on; done
passed=$(python3 - "$LOG" "$N" <<'PY'
import re, sys
last = {}
for line in open(sys.argv[1]):
    m = re.search(r"run (\d+): (pass|FAIL)", line)
    if m: last[int(m.group(1))] = m.group(2)
print(sum(1 for k in range(1, int(sys.argv[2]) + 1) if last.get(k) == "pass"))
PY
)
say "passed $passed/$N"
[ "$passed" = "$N" ] && say PASS || { say FAIL; exit 1; }

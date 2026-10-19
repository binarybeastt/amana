#!/usr/bin/env bash
# Gate P1: the CLI syncs, goes offline, pays, waits, broadcasts, and the payment confirms.
#   ./e2e.sh [runs=3] [wait_blocks=5] [zats=1000000]
# Pass = every run confirms on testnet, in a row.
set -euo pipefail
source "$(dirname "$0")/../../spikes/zcash/env.sh"
RUNS_N=${1:-3}
WAIT=${2:-5}
ZATS=${3:-1000000}
CLI="$ROOT/offline-core/target/release/offline-cli"
OUT="$RUNS/gate-p1"
LOG="$ROOT/stress/p1/logs/gate-p1.log"
mkdir -p "$OUT"
echo "$(date -u +%FT%TZ) gate P1: $RUNS_N runs, wait $WAIT blocks, $ZATS zats A -> D" | tee "$LOG"

passed=0
for k in $(seq 1 "$RUNS_N"); do
  # 1. Online: sync.
  "$DT" wallet -w "$W" sync >/dev/null 2>&1
  build_tip=$(tip)
  # 2. Offline: pay (build, prove, sign, store) with networking denied.
  if ! nonet "$CLI" -w "$W" pay --account "$ACCT_A" --to "$ADDR_D" --amount "$ZATS" -i "$ID" \
       --raw-out "$OUT/run$k.raw" >"$OUT/run$k.json" 2>"$OUT/run$k.err"; then
    echo "run=$k FAIL pay: $(tail -1 "$OUT/run$k.err")" | tee -a "$LOG"; break
  fi
  txid=$(python3 -c "import json,sys; print(json.load(open(sys.argv[1]))['txid'])" "$OUT/run$k.json")
  # 3. Wait.
  until [ "$(tip)" -ge $((build_tip + WAIT)) ]; do sleep 30; done
  # 4. Broadcast the wallet's stored bytes.
  sent=$("$DT" pczt -w "$W" send-raw --txid "$txid" 2>&1 | grep -v INFO | tail -1)
  # 5. Confirm.
  mined=""
  for i in $(seq 1 40); do
    "$DT" wallet -w "$W" sync >/dev/null 2>&1
    mined=$("$DT" wallet -w "$W" list-tx 2>/dev/null | grep -A1 "^$txid" | awk '/Mined:/{print $2}' | head -1)
    [ -n "$mined" ] && break
    sleep 40
  done
  if [ -n "$mined" ]; then
    passed=$((passed + 1))
    echo "run=$k PASS txid=$txid built_at=$build_tip broadcast=\"$sent\" mined=$mined" | tee -a "$LOG"
  else
    echo "run=$k FAIL not mined: txid=$txid broadcast=\"$sent\"" | tee -a "$LOG"; break
  fi
done
echo "gate P1: $passed/$RUNS_N in a row" | tee -a "$LOG"
[ "$passed" = "$RUNS_N" ]

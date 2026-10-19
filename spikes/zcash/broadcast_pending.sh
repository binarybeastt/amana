#!/usr/bin/env bash
# P1.G5.T5: once the chain is <min_blocks> past <build_tip>, broadcast every payment in
# spikes/runs/<dir>/pay*.json from the wallet's stored bytes, then wait until all are mined.
#   ./broadcast_pending.sh <dir> <build_tip> <min_blocks>
set -euo pipefail
source "$(dirname "$0")/env.sh"
DIR="$RUNS/${1:?dir}"; BUILD=${2:?build tip}; MIN=${3:?min blocks}
TARGET=$((BUILD + MIN))
LOG="$DIR/broadcast.log"
until [ "$(tip 2>/dev/null || echo 0)" -ge "$TARGET" ]; do
  echo "$(date -u +%FT%TZ) tip=$(tip 2>/dev/null || echo ?) waiting for $TARGET" >>"$LOG"
  sleep 300
done
T=$(tip)
echo "$(date -u +%FT%TZ) broadcasting at tip $T (age $((T - BUILD)) blocks)" >>"$LOG"
TXIDS=()
for f in "$DIR"/pay*.json; do
  case "$f" in *.inspect.json) continue;; esac
  txid=$(python3 -c "import json,sys; print(json.load(open(sys.argv[1]))['txid'])" "$f")
  TXIDS+=("$txid")
  set +e
  out=$("$DT" pczt -w "$W" send-raw --txid "$txid" 2>&1 | grep -v INFO | tail -1)
  set -e
  echo "send $txid: $out" >>"$LOG"
done
# Wait for all to be mined (up to ~1 h).
for i in $(seq 1 80); do
  "$DT" wallet -w "$W" sync >/dev/null 2>&1 || true
  mined=0
  for txid in "${TXIDS[@]}"; do
    internal=$(python3 -c "import sys; print(bytes.fromhex(sys.argv[1])[::-1].hex())" "$txid")
    h=$(sqlite3 "$W/data.sqlite" "SELECT IFNULL(mined_height,'') FROM transactions WHERE lower(hex(txid)) = '$internal'")
    [ -n "$h" ] && mined=$((mined + 1))
  done
  echo "$(date -u +%FT%TZ) mined $mined/${#TXIDS[@]}" >>"$LOG"
  [ "$mined" = "${#TXIDS[@]}" ] && break
  sleep 45
done
echo "RESULT mined $mined/${#TXIDS[@]} (broadcast at age $((T - BUILD)) blocks)" >>"$LOG"

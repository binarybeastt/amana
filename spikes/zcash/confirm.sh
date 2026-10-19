#!/usr/bin/env bash
# Sync and report whether a spike's tx is mined.   ./confirm.sh <label>
set -euo pipefail
source "$(dirname "$0")/env.sh"
OUT="$RUNS/${1:?label}"
TXID=$(cat "$OUT/txid.txt")
"$DT" wallet -w "$W" sync >/dev/null 2>&1
if "$DT" wallet -w "$W" list-tx 2>/dev/null | grep -A1 "^$TXID" | grep 'Mined:' >/dev/null; then
  M=$("$DT" wallet -w "$W" list-tx 2>/dev/null | grep -A1 "^$TXID" | grep 'Mined:' | awk '{print $2}' | head -1)
  echo "mined txid=$TXID height=$M"
  grep -q '^mined_height=' "$OUT/meta.txt" || echo "mined_height=$M" >>"$OUT/meta.txt"
else
  echo "not mined yet: txid=$TXID"
  exit 1
fi

#!/usr/bin/env bash
# Wait until the chain is <min_blocks> past the build tip, then broadcast twice (P0.G3.T2-T4).
#   ./broadcast.sh <label> <min_blocks>
# The second send of the same bytes checks that a duplicate broadcast is harmless.
set -euo pipefail
source "$(dirname "$0")/env.sh"
LABEL=${1:?label}; MIN=${2:?min blocks}
OUT="$RUNS/$LABEL"
H=$(grep '^build_tip=' "$OUT/meta.txt" | cut -d= -f2)
TARGET=$((H + MIN))
while true; do
  T=$(tip 2>/dev/null || echo 0)
  [ "${T:-0}" -ge "$TARGET" ] && break
  echo "$(date -u +%FT%TZ) tip=$T waiting for $TARGET"
  sleep 300
done
echo "broadcast_tip=$T" >>"$OUT/meta.txt"
echo "broadcast_time=$(date -u +%FT%TZ)" >>"$OUT/meta.txt"
set +e
"$DT" pczt -w "$W" send-without-storing "$OUT/combined.pczt" >"$OUT/send1.txt" 2>&1
echo "send1_exit=$?" >>"$OUT/meta.txt"
sleep 10
"$DT" pczt -w "$W" send-without-storing "$OUT/combined.pczt" >"$OUT/send2.txt" 2>&1
echo "send2_exit=$?" >>"$OUT/meta.txt"
echo "broadcast done at tip $T (build tip $H, age $((T - H)) blocks)"

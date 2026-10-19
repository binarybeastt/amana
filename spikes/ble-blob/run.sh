#!/usr/bin/env bash
# Run the P0.G4 blob test between devices (emulators or phones) over adb.
#
#   ./run.sh <sender> <sink> [count] [size_bytes] [relay]
#
# One hop:  sender A -> sink B.   Two hops (with relay): A -> R -> B.
# Serials come from `adb devices`. Logs land in spikes/logs/p0g4-<timestamp>/.
# Pass line (PLAN.md): >= 95% delivered within 60 s, 100% checksum match on delivered.
set -euo pipefail

SENDER=${1:?sender serial}
SINK=${2:?sink serial}
COUNT=${3:-100}
SIZE=${4:-20480}
RELAY=${5:-}

HERE="$(cd "$(dirname "$0")" && pwd)"
ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
APK="$HERE/app/build/outputs/apk/debug/app-debug.apk"
PKG=dev.offlinezec.bleblob
OUT="$HERE/../logs/p0g4-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$OUT"
[ -f "$APK" ] || { echo "build first: ./gradlew assembleDebug" >&2; exit 1; }

DEVICES=("$SENDER" "$SINK")
[ -n "$RELAY" ] && DEVICES+=("$RELAY")

for d in "${DEVICES[@]}"; do
  "$ADB" -s "$d" install -r -g "$APK" >/dev/null
  "$ADB" -s "$d" shell cmd bluetooth_manager enable >/dev/null 2>&1 || true
  "$ADB" -s "$d" shell am force-stop $PKG
  "$ADB" -s "$d" logcat -c
  "$ADB" -s "$d" logcat -v time -s BLEBLOB:V >"$OUT/$d.log" 2>/dev/null &
done
trap 'kill $(jobs -p) 2>/dev/null || true' EXIT

svc() { "$ADB" -s "$1" shell am start-foreground-service -n $PKG/.BlobService "${@:2}" >/dev/null; }

svc "$SINK" --es role sink --es node B
FIRST_HOP=B
if [ -n "$RELAY" ]; then
  svc "$RELAY" --es role relay --es node R --es target B
  FIRST_HOP=R
fi
sleep 3
svc "$SENDER" --es role send --es node A --es target "$FIRST_HOP" --ei count "$COUNT" --ei size "$SIZE"
echo "running: $COUNT x $SIZE bytes, A -> ${RELAY:+R -> }B. Logs: $OUT"

deadline=$((SECONDS + COUNT * 65 + 120))
until grep -q SUMMARY "$OUT/$SENDER.log" 2>/dev/null; do
  [ $SECONDS -lt $deadline ] || { echo "timed out waiting for SUMMARY" >&2; break; }
  sleep 2
done
sleep 5  # let the last relay hop land

echo
grep SUMMARY "$OUT/$SENDER.log" | sed 's/^.*SUMMARY/sender  SUMMARY/' || true
sent_ok=$(grep -c 'RESULT.*ok=true' "$OUT/$SENDER.log" || true)
within=$(awk '/RESULT/ && /ok=true/ { for (i=1;i<=NF;i++) if ($i ~ /^ms=/) { split($i,a,"="); if (a[2] <= 60000) n++ } } END { print n+0 }' "$OUT/$SENDER.log")
recv_ok=$(grep -c 'RECV.*ok=true' "$OUT/$SINK.log" || true)
recv_bad=$(grep -c 'RECV.*ok=false' "$OUT/$SINK.log" || true)
echo "sender  acked ok: $sent_ok / $COUNT   (within 60 s: $within)"
echo "sink    received ok: $recv_ok   checksum failures: $recv_bad"
if [ -n "$RELAY" ]; then
  echo "relay   forwarded ok: $(grep -c 'FWD.*ok=true' "$OUT/$RELAY.log" || true)"
  delivered=$recv_ok
else
  delivered=$within
fi
pct=$(( delivered * 100 / COUNT ))
echo "delivered end to end: $delivered / $COUNT = $pct%"
{ echo "count=$COUNT size=$SIZE relay=${RELAY:-none} delivered=$delivered pct=$pct checksum_failures=$recv_bad"; } >"$OUT/summary.txt"

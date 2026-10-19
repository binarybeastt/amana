#!/usr/bin/env bash
# P1.G7.T2-T4: build a payment ON an Android device (emulator or phone) in airplane mode.
#   T2 signed bytes are produced; T3 time per build (10 runs on the slowest phone); T4 peak
#   memory (VmHWM). Each run starts from the same wallet copy, so run 1's bytes are the ones
#   kept for broadcasting later (T5).
#   ./p1g7_device_pay.sh <adb-serial> [runs] [account] [to] [zats]
# Testnet only: pushes a copy of the testnet wallet and its age identity to the device.
set -euo pipefail
source "$(dirname "$0")/../../spikes/zcash/env.sh"
SERIAL=${1:?adb serial}
RUNS_N=${2:-1}
ACCT=${3:-$ACCT_B}
TO=${4:-$ADDR_A}
ZATS=${5:-5000000}
ADB="$HOME/Library/Android/sdk/platform-tools/adb -s $SERIAL"
BIN="$ROOT/offline-core/target/aarch64-linux-android/release/offline-cli"
OUT="$RUNS/p1g7/$SERIAL"
LOG="$ROOT/stress/p1/logs/p1g7-$SERIAL.log"
D=/data/local/tmp/ocw
mkdir -p "$OUT"
[ -f "$BIN" ] || { echo "build first: cargo ndk -t arm64-v8a build --release" >&2; exit 1; }

"$DT" wallet -w "$W" sync >/dev/null 2>&1     # fresh witnesses before going offline
$ADB shell "mkdir -p $D"
$ADB push "$BIN" $D/offline-cli >/dev/null
$ADB push "$W/keys.toml" $D/keys.toml >/dev/null
$ADB push "$ID" $D/identity.age >/dev/null
$ADB shell "chmod 755 $D/offline-cli"

model=$($ADB shell getprop ro.product.model | tr -d '\r')
abi=$($ADB shell getprop ro.product.cpu.abi | tr -d '\r')
$ADB shell cmd connectivity airplane-mode enable
sleep 3
if $ADB shell "ping -c 1 -W 2 8.8.8.8" >/dev/null 2>&1; then
  echo "network still reachable in airplane mode; aborting" | tee "$LOG"; exit 1
fi
echo "$(date -u +%FT%TZ) device=$SERIAL model=$model abi=$abi airplane=on (ping fails) runs=$RUNS_N" | tee "$LOG"

for k in $(seq 1 "$RUNS_N"); do
  $ADB push "$W/data.sqlite" $D/data.sqlite >/dev/null
  $ADB shell "rm -f $D/data.sqlite-wal $D/data.sqlite-shm $D/out.raw"
  # Run in the background and sample peak memory until it exits.
  res=$($ADB shell "cd $D && s=\$(date +%s%N); ./offline-cli -w $D pay --account $ACCT --to $TO --amount $ZATS -i $D/identity.age --raw-out $D/out.raw > $D/out.json 2> $D/err.txt & pid=\$!; hwm=0; while kill -0 \$pid 2>/dev/null; do v=\$(grep VmHWM /proc/\$pid/status 2>/dev/null | tr -dc 0-9); [ -n \"\$v\" ] && [ \$v -gt \$hwm ] && hwm=\$v; sleep 0.1; done; wait \$pid; rc=\$?; e=\$(date +%s%N); echo \$rc \$s \$e \$hwm" | tr -d '\r')
  # The device shell's arithmetic is 32-bit, so subtract the nanosecond timestamps here.
  read -r rc t0 t1 hwm <<<"$res"
  ms=$(( (t1 - t0) / 1000000 ))
  if [ "$rc" = 0 ]; then
    $ADB pull $D/out.raw "$OUT/run$k.raw" >/dev/null
    $ADB pull $D/out.json "$OUT/run$k.json" >/dev/null
    txid=$(python3 -c "import json,sys; print(json.load(open(sys.argv[1]))['txid'])" "$OUT/run$k.json")
    echo "run=$k ok ms=$ms peak_kb=$hwm txid=$txid" | tee -a "$LOG"
  else
    echo "run=$k FAILED rc=$rc ms=$ms: $($ADB shell cat $D/err.txt | tail -1)" | tee -a "$LOG"
  fi
done

$ADB shell cmd connectivity airplane-mode disable
python3 - "$LOG" <<'EOF' | tee -a "$LOG"
import re, statistics, sys
rows = [l for l in open(sys.argv[1]) if l.startswith("run=") and " ok " in l]
ms = sorted(int(re.search(r"ms=(\d+)", l).group(1)) for l in rows)
kb = [int(re.search(r"peak_kb=(\d+)", l).group(1)) for l in rows]
if ms:
    print(f"summary: {len(rows)} ok, median {statistics.median(ms)/1000:.1f} s, worst {ms[-1]/1000:.1f} s, peak memory {max(kb)/1024:.0f} MB")
EOF

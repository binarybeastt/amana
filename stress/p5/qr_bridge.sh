#!/usr/bin/env bash
# Stands in for a camera between two emulators (an emulator's camera can't see another's
# screen): whenever one shows a new payment QR, its frames are fed to the other's scanner,
# both ways, until stopped. Debug builds only (uses the DUMP_FRAMES / FEED_FRAMES hooks).
#   ./qr_bridge.sh emulator-5556 emulator-5558
# On the receiving emulator, open Receive -> Scan a payment before the payer taps Pay.
set -uo pipefail
source "$(dirname "$0")/../p2/lib.sh"
A=$1; B=$2
done_tx=" "   # txids already handed over (macOS bash 3.2: no associative arrays)
echo "bridging payment QRs between $A and $B (Ctrl-C to stop)"
while true; do
  for pair in "$A $B" "$B $A"; do
    read -r from to <<<"$pair"
    st=$(state "$from" "s['pay']['state']" 2>/dev/null) || continue
    [ "$st" = ready ] || continue
    tx=$(state "$from" "s['pay']['txid']" 2>/dev/null) || continue
    [ -n "$tx" ] || continue
    case "$done_tx" in *" $tx "*) continue;; esac
    done_tx="$done_tx$tx "
    echo "$(date +%T) $from shows ${tx:0:12}… -> feeding $to's scanner"
    if relay_frames "$from" "$to" >/dev/null 2>&1; then
      echo "$(date +%T) $to: $(state "$to" "s['receive']['state']")"
    else
      echo "$(date +%T) $to did not accept it (is it on Receive -> Scan a payment?)"
      done_tx=${done_tx/ $tx / }
    fi
  done
  sleep 2
done

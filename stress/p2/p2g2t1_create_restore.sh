#!/usr/bin/env bash
# P2.G2.T1: a wallet created on phone A, restored from its seed on phone B: same address and
# same balance on both. Wipes the app on B first.
#   ./p2g2t1_create_restore.sh <serial-A> <serial-B>
set -euo pipefail
source "$(dirname "$0")/lib.sh"
A=$1; B=$2
LOG="$ROOT/stress/p2/logs/p2g2t1.log"
echo "$(date -u +%FT%TZ) create on $A, restore on $B" | tee "$LOG"
launch "$A"
if [ "$(state "$A" "s.get('wallet')")" != ready ]; then hook "$A" CREATE; fi
wait_synced "$A" 600
hook "$A" DUMP_SEED; sleep 2
WORDS=$(app_file "$A" mnemonic.txt); "$ADB" -s "$A" shell run-as "$PKG" rm -f files/mnemonic.txt
BDAY=$(state "$A" "s['birthday']")
[ "$(echo "$WORDS" | wc -w | tr -d ' ')" = 24 ] || { echo "no seed from $A" | tee -a "$LOG"; exit 1; }
echo "A birthday $BDAY" | tee -a "$LOG"
clear_app "$B"; launch "$B"; sleep 3
t0=$SECONDS
hook "$B" RESTORE --es words "'$WORDS'" --es birthday "$BDAY"
wait_state "$B" "s.get('wallet') == 'ready'" 300
wait_synced "$B" 7200 || { echo "B did not reach tip" | tee -a "$LOG"; exit 1; }
echo "B restored and synced in $((SECONDS - t0))s" | tee -a "$LOG"
wait_synced "$A" 600
for s in "$A" "$B"; do
  echo "$s address=$(state "$s" "s['address']") total=$(sdk_total "$s") tip=$(state "$s" "s['sync']['scanned']")" | tee -a "$LOG"
done
if [ "$(state "$A" "s['address']")" = "$(state "$B" "s['address']")" ] && [ "$(sdk_total "$A")" = "$(sdk_total "$B")" ]; then
  echo PASS | tee -a "$LOG"
else
  echo FAIL | tee -a "$LOG"; exit 1
fi

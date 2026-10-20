#!/usr/bin/env bash
# P2.G4.T3 + T4 on emulators: <sender> pays <receiver> through the UI; QR frames are relayed to
# the receiver's scanner path (with some frames skipped, as a camera would miss them).
#   T3: when the payment QR is first on screen, the payment's notes are already spent-offline
#   T4: the receiver shows "received, not yet settled" with the right amount
#   ./p2g4_pay_receive.sh <sender> <receiver> [zec=0.01]
set -euo pipefail
source "$(dirname "$0")/lib.sh"
SND=$1; RCV=$2; AMT=${3:-0.01}
ZATS=$(python3 -c "from decimal import Decimal; print(int(Decimal('$AMT') * 10**8))")
LOG="$ROOT/stress/p2/logs/p2g4-$SND-$RCV.log"
echo "$(date -u +%FT%TZ) $SND pays $RCV $AMT ZEC" | tee "$LOG"
for s in "$SND" "$RCV"; do launch "$s"; wait_synced "$s" 900; done
TO=$(state "$RCV" "s['address']")
home "$RCV"; tap_id "$RCV" nav_receive; hook "$RCV" RESET_RECEIVE
t0=$SECONDS
TXID=$(pay_via_ui "$SND" "$TO" "$AMT") || { echo "pay failed" | tee -a "$LOG"; exit 1; }
echo "paid: txid $TXID in $((SECONDS - t0))s" | tee -a "$LOG"
T3=$(state "$SND" "' '.join(n['note'] + '=' + n['status'] for n in s['notes_at_qr']['spent_notes'])")
echo "T3 notes when the QR was first shown: $T3" | tee -a "$LOG"
t3=FAIL; [ -n "$T3" ] && ! echo "$T3" | tr ' ' '\n' | grep -qv '=spent-offline$' && t3=pass
echo "T3 $t3" | tee -a "$LOG"
relay_frames "$SND" "$RCV" "1,4,9" || true
r_state=$(state "$RCV" "s['receive']['state']"); r_amt=$(state "$RCV" "s['receive'].get('amount')")
sleep 2; shown=$(ui_text "$RCV" received_status); shown_amt=$(ui_text "$RCV" received_amount)
echo "T4 receiver: state=$r_state amount=$r_amt txid=$(state "$RCV" "s['receive'].get('txid')") screen='$shown_amt' '$shown' $(state "$RCV" "s['receive'].get('reason','')")" | tee -a "$LOG"
t4=FAIL; [ "$r_state" = accepted ] && [ "$r_amt" = "$ZATS" ] && [ "$shown" = "received, not yet settled" ] && [ "$(state "$RCV" "s['receive']['txid']")" = "$TXID" ] && t4=pass
echo "T4 $t4" | tee -a "$LOG"
[ $t3 = pass ] && [ $t4 = pass ] && echo PASS | tee -a "$LOG" || { echo FAIL | tee -a "$LOG"; exit 1; }

#!/usr/bin/env bash
# Gate P2, emulator form: both phones in airplane mode pay each other by QR (frames relayed to
# the receiver's scanner path); then ONE phone comes online and broadcasts; the payment must
# settle on testnet. N runs per direction (default 5), alternating directions; the phone that
# broadcasts alternates between receiver and sender.
#   ./gate_p2.sh <phone-A> <phone-B> [runs-per-direction=5] [zec=0.01]
# Both phones need a prepared ("ready for offline") funded wallet.
set -euo pipefail
source "$(dirname "$0")/lib.sh"
A=$1; B=$2; N=${3:-5}; AMT=${4:-0.01}
ZATS=$(python3 -c "from decimal import Decimal; print(int(Decimal('$AMT') * 10**8))")
LOG="$ROOT/stress/p2/logs/gate-p2.log"
echo "$(date -u +%FT%TZ) Gate P2: $N runs per direction, $AMT ZEC, phones $A $B" | tee "$LOG"
is_offline() { [ "$("$ADB" -s "$1" shell settings get global airplane_mode_on | tr -d '\r')" = 1 ] && ! "$ADB" -s "$1" shell ping -c 1 -W 2 8.8.8.8 >/dev/null 2>&1; }
passed=0; total=$((2 * N))
for k in $(seq 1 "$total"); do
  if [ $((k % 2)) = 1 ]; then SND=$A; RCV=$B; else SND=$B; RCV=$A; fi
  # Runs 1,2 / 5,6 / 9,10: receiver broadcasts; 3,4 / 7,8: sender broadcasts.
  if [ $(( (k - 1) / 2 % 2 )) = 0 ]; then BC=$RCV; who=receiver; else BC=$SND; who=sender; fi
  OTHER=$([ "$BC" = "$SND" ] && echo "$RCV" || echo "$SND")
  # 1. Both online and at the tip (sync right before going offline).
  for s in "$SND" "$RCV"; do airplane "$s" off; launch "$s"; done
  for s in "$SND" "$RCV"; do wait_synced "$s" 900 || { echo "run $k: $s not synced" | tee -a "$LOG"; continue 2; }; done
  TO=$(state "$RCV" "s['address']")
  # 2. Both offline.
  for s in "$SND" "$RCV"; do airplane "$s" on; done
  sleep 5
  for s in "$SND" "$RCV"; do is_offline "$s" || { echo "run $k: $s still online" | tee -a "$LOG"; exit 1; }; done
  home "$RCV"; tap_id "$RCV" nav_receive; hook "$RCV" RESET_RECEIVE
  # 3. Pay offline; QR to the receiver.
  t0=$SECONDS
  TXID=$(pay_via_ui "$SND" "$TO" "$AMT") || { echo "run $k: pay failed" | tee -a "$LOG"; continue; }
  t_pay=$((SECONDS - t0))
  spent=$(state "$SND" "all(n['status'] == 'spent-offline' for n in s['notes_at_qr']['spent_notes'])")
  relay_frames "$SND" "$RCV" "$((k % 5 + 1)),$((k % 7 + 3))" || true
  rs=$(state "$RCV" "s['receive']['state']"); ra=$(state "$RCV" "s['receive'].get('amount')")
  if [ "$rs" != accepted ] || [ "$ra" != "$ZATS" ] || [ "$spent" != True ]; then
    echo "run $k: FAIL receive=$rs amount=$ra spent_offline_at_qr=$spent $(state "$RCV" "s['receive'].get('reason','')")" | tee -a "$LOG"; continue
  fi
  # 4. One phone comes online and broadcasts (its 30 s loop, or Broadcast now).
  airplane "$BC" off; launch "$BC"
  t1=$SECONDS
  hook "$BC" BROADCAST
  if [ "$BC" = "$RCV" ]; then cond="any(r['txid'] == '$TXID' and r['settled'] for r in s['received'])"
  else cond="'$TXID' not in s['pending_sent'] and s['sync']['status'] == 'SYNCED'"; fi
  if ! wait_state "$BC" "$cond" 1800; then echo "run $k: FAIL not mined 30 min after $who came online" | tee -a "$LOG"; continue; fi
  t_settle=$((SECONDS - t1))
  # 5. The other phone comes online and agrees.
  airplane "$OTHER" off; launch "$OTHER"
  ok=True
  wait_state "$RCV" "any(r['txid'] == '$TXID' and r['settled'] for r in s['received'])" 900 || ok=False
  wait_state "$SND" "'$TXID' not in s['pending_sent']" 900 || ok=False
  mined=$(state "$RCV" "next(r['mined_height'] for r in s['received'] if r['txid'] == '$TXID')")
  if [ $ok = True ]; then passed=$((passed + 1)); r=pass; else r=FAIL; fi
  echo "run $k: $r $SND -> $RCV txid $TXID; pay+QR ${t_pay}s; $who broadcast; mined at $mined, ${t_settle}s after it came online; receiver shows settled" | tee -a "$LOG"
done
for s in "$A" "$B"; do airplane "$s" off; done
echo "passed $passed/$total" | tee -a "$LOG"
[ "$passed" = "$total" ] && echo PASS | tee -a "$LOG" || { echo FAIL | tee -a "$LOG"; exit 1; }

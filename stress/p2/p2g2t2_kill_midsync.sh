#!/usr/bin/env bash
# P2.G2.T2: kill the app (SIGKILL, both processes) mid-sync, N times (default 5). Each restart
# must resume (progress not lost) and the wallet must reach the tip. Uses a restore of phone
# <seed-from>'s seed on <serial> so the sync is long.
#   ./p2g2t2_kill_midsync.sh <serial> <seed-from-serial> [kills] [birthday]
# Default birthday: 200,000 blocks before the seed's own, so there is a long sync to interrupt.
set -euo pipefail
source "$(dirname "$0")/lib.sh"
S=$1; FROM=$2; N=${3:-5}
LOG="$ROOT/stress/p2/logs/p2g2t2.log"
hook "$FROM" DUMP_SEED; sleep 2
WORDS=$(app_file "$FROM" mnemonic.txt); "$ADB" -s "$FROM" shell run-as "$PKG" rm -f files/mnemonic.txt
BDAY=${4:-$(( $(state "$FROM" "s['birthday']") - 200000 ))}
clear_app "$S"; launch "$S"; sleep 3
hook "$S" RESTORE --es words "'$WORDS'" --es birthday "$BDAY"
wait_state "$S" "s.get('wallet') == 'ready'" 300
echo "$(date -u +%FT%TZ) $N kills mid-sync on $S (restore from birthday $BDAY)" | tee "$LOG"
fail=0
for k in $(seq 1 "$N"); do
  # Let it make some progress, then kill both processes.
  sleep $((20 + RANDOM % 40))
  before=$(state "$S" "s['sync']['progress']"); st=$(state "$S" "s['sync']['status']")
  scanned=$(state "$S" "s['sync']['scanned']")
  kill9 "$S"; sleep 2
  launch "$S"
  wait_state "$S" "s.get('wallet') == 'ready' and s['sync']['status'] in ('SYNCING', 'SYNCED')" 300 || { echo "kill $k: did not reopen" | tee -a "$LOG"; fail=1; continue; }
  sleep 15
  after=$(state "$S" "s['sync']['progress']")
  ok=$(python3 -c "print('ok' if float('$after' or 0) >= float('$before' or 0) - 0.02 else 'LOST')")
  [ "$ok" = ok ] || fail=1
  echo "kill $k: status=$st progress before=$before after-restart=$after scanned=$scanned -> $ok" | tee -a "$LOG"
done
t0=$SECONDS
if wait_synced "$S" 10800; then
  echo "reached tip $(state "$S" "s['sync']['scanned']") $((SECONDS - t0))s after the last kill" | tee -a "$LOG"
else
  echo "did not reach tip" | tee -a "$LOG"; fail=1
fi
[ $fail = 0 ] && echo PASS | tee -a "$LOG" || { echo FAIL | tee -a "$LOG"; exit 1; }

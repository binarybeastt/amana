#!/usr/bin/env bash
# P2.G3 "Prepare for offline", driven through the UI (uiautomator taps on the real button).
#   ./p2g3_prepare.sh t1 <serial>   tap Prepare -> split mined -> screen shows the denomination
#                                    set; it must equal offline-core's available notes
#   ./p2g3_prepare.sh t2 <serial>   kill the app while waiting for mining; restart resumes
#                                    waiting for the same tx; no second split tx
#   ./p2g3_prepare.sh t3 <serial>   double-tap Prepare: exactly one split tx
# Needs a funded wallet synced to the tip.
set -euo pipefail
source "$(dirname "$0")/lib.sh"
T=$1; S=$2
LOG="$ROOT/stress/p2/logs/p2g3$T-$S.log"
launch "$S"; wait_synced "$S" 900
# The button stays disabled until every note is spendable (SDK confirmation rule).
wait_state "$S" "s['sync']['ironwood_available'] > 0 and s['sync']['ironwood_available'] == s['sync']['ironwood_total'] and s['prepare']['state'] != 'waiting'" 3600 \
  || { echo "funds never all spendable" | tee "$LOG"; exit 1; }
[ "$(state "$S" "s['prepare']['state']")" != waiting ] || { echo "a split is already in flight" | tee "$LOG"; exit 1; }
BEFORE=$(note_txids "$S")
echo "$(date -u +%FT%TZ) P2.G3.$T on $S; total $(sdk_total "$S") zats" | tee "$LOG"
home "$S"; sleep 1; tap_id "$S" nav_prepare; sleep 2
case $T in
  t3)
    # Two taps 80 ms apart on the real button, plus a third request through the hook.
    "$ADB" -s "$S" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    xy=$("$ADB" -s "$S" exec-out cat /sdcard/ui.xml | python3 -c "
import re, sys
m = re.search(r'resource-id=\"prepare_button\"[^>]*bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', sys.stdin.read())
a, b, c, d = map(int, m.groups()); print((a + c) // 2, (b + d) // 2)")
    "$ADB" -s "$S" shell "input tap $xy; sleep 0.08; input tap $xy"
    hook "$S" PREPARE
    ;;
  *) tap_id "$S" prepare_button ;;
esac
wait_state "$S" "s['prepare']['state'] == 'waiting'" 180 || { echo "no split went out" | tee -a "$LOG"; exit 1; }
TXID=$(state "$S" "s['prepare']['txid']")
echo "split $TXID in flight" | tee -a "$LOG"
if [ "$T" = t2 ]; then
  sleep 3; kill9 "$S"; echo "killed while waiting" | tee -a "$LOG"; sleep 2; launch "$S"
  wait_state "$S" "s.get('wallet') == 'ready' and s.get('prepare') is not None" 300
  now=$(state "$S" "s['prepare'].get('txid', s['prepare']['state'])")
  echo "after restart: prepare=$(state "$S" "s['prepare']['state']") $now" | tee -a "$LOG"
  tap_id "$S" nav_prepare || true; sleep 2; tap_id "$S" prepare_button 2>/dev/null || true  # disabled while waiting
fi
wait_state "$S" "s['prepare']['state'] != 'waiting'" 1800 || { echo "split not mined in 30 min" | tee -a "$LOG"; exit 1; }
AFTER=$(note_txids "$S")
NEW=$(comm -13 <(echo "$BEFORE") <(echo "$AFTER"))
echo "new note-creating txs: $(echo "$NEW" | grep -c . || true) ($NEW)" | tee -a "$LOG"
fail=0
[ "$(echo "$NEW" | grep -c .)" = 1 ] && [ "$NEW" = "$TXID" ] || { echo "expected exactly one split tx ($TXID)" | tee -a "$LOG"; fail=1; }
if [ "$T" = t1 ]; then
  wait_state "$S" "s['prepare']['state'] == 'ready'" 1800 || { echo "notes not confirmed" | tee -a "$LOG"; fail=1; }
  home "$S"; sleep 1; tap_id "$S" nav_prepare; sleep 3
  # Read every row: dump, scroll down, dump again (a small screen cuts the list off).
  ROWS=$(mktemp)
  read -r W H < <("$ADB" -s "$S" shell wm size | awk -F'[ x]' '/Physical/{print $3, $4}')
  for pass in 1 2 3; do
    "$ADB" -s "$S" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    "$ADB" -s "$S" exec-out cat /sdcard/ui.xml | python3 -c "
import re, sys
for v, n in re.findall(r'text=\"([0-9.]+) ZEC × (\d+)[^\"]*\"[^>]*resource-id=\"denomination\"', sys.stdin.read()):
    print(v, n)" >>"$ROWS"
    "$ADB" -s "$S" shell input swipe $((W / 2)) $((H * 7 / 10)) $((W / 2)) $((H * 3 / 10)) 300
    sleep 1
  done
  SHOWN=$(sort -u "$ROWS" | python3 -c "
import sys
vals = []
for line in sys.stdin:
    v, n = line.split(); vals += [v] * int(n)
print(' '.join(sorted(vals)))")
  rm -f "$ROWS"
  ACCT=$(state "$S" "s['account']")
  STORE=$(core_notes "$S" | python3 -c "
import json, sys
from decimal import Decimal
ns = [n for n in json.load(sys.stdin) if n['status'] == 'available' and n['pool'] == 'ironwood']
def z(v):
    s = f'{Decimal(v) / Decimal(10**8):.8f}'.rstrip('0')
    return s + '0' if s.endswith('.') else s
print(' '.join(sorted(z(n['value']) for n in ns)))")
  echo "screen: $SHOWN" | tee -a "$LOG"; echo "store:  $STORE" | tee -a "$LOG"
  [ -n "$SHOWN" ] && [ "$SHOWN" = "$STORE" ] || { echo "screen differs from the note store" | tee -a "$LOG"; fail=1; }
  echo "split outputs present: $(echo "$STORE" | wc -w | tr -d ' ') notes" | tee -a "$LOG"
fi
[ $fail = 0 ] && echo PASS | tee -a "$LOG" || { echo FAIL | tee -a "$LOG"; exit 1; }

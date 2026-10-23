#!/usr/bin/env bash
# P5.G1.T2 on one emulator: payments unsettled for 24 h still show correctly, no stuck spinner.
# Uses the phone's real unsettled payments (own, offline, not mined). If the oldest is younger
# than 24 h (the youngest of them), the debug hook CLOCK_SKEW moves the clock the screens use forward so it is 24 h 5 min
# old (the wallet, relay and broadcaster keep real time). Then, on Payments (Sent filter) and Home:
#   - every unsettled own payment has a row, a "pending"/"relayed" chip and an age of "1 d …"
#   - "Show QR to the receiver again" is there for each
#   - no progress bar anywhere (nothing spins), and the screen is the same 10 s later
#   ./p5g1t2_day_old.sh <serial>
set -euo pipefail
source "$(dirname "$0")/../p2/lib.sh"
S=$1; LOG="$ROOT/stress/p5/logs/p5g1t2-$S.log"; UI=$(mktemp -d)
say() { echo "$*" | tee -a "$LOG"; }
echo "$(date -u +%FT%TZ) P5.G1.T2 on $S" | tee "$LOG"
launch "$S"; wait_state "$S" "s.get('wallet') == 'ready'" 180
hook "$S" CLOCK_SKEW --es ms 0; sleep 4
read -r SKEW TXS < <(state "$S" "(lambda q, p, now: (lambda old: '%d %s' % (max(0, 24*3600000 + 300000 - (now - old)), ','.join(t for t in p)))(max(e['first_seen'] for e in q if e['txid'] in p)))([e for e in s['mesh']['queue']], set(s['pending_sent']) - set(s['conflicted_sent']), s['updated_at'])")
[ -n "$TXS" ] || { say "FAIL: no unsettled own payments on $S"; exit 1; }
say "unsettled own payments: ${TXS//,/ }"
say "clock skew: $SKEW ms ($(python3 -c "print(round($SKEW/3.6e6, 2))") h)"
hook "$S" CLOCK_SKEW --es ms "$SKEW"; sleep 4
ok=True
dump() { "$ADB" -s "$S" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; "$ADB" -s "$S" exec-out cat /sdcard/ui.xml >"$UI/$1.xml"; }
check_rows() { python3 - "$UI" "$TXS" <<'PY'
import glob, sys
import xml.etree.ElementTree as ET
ui, txs = sys.argv[1], sys.argv[2].split(',')
nodes = [n for f in sorted(glob.glob(ui + '/pay*.xml')) for n in ET.parse(f).iter('node')]
def find(rid):
    # A row can be cut by the screen edge in one dump: prefer a copy that shows its text.
    found = [n for n in nodes if n.get('resource-id') == rid]
    return max(found, key=lambda n: len(text(n)), default=None)
def text(n):
    return ' '.join(t for t in (m.get('text') for m in n.iter('node')) if t) if n is not None else None
bad = 0
for t in txs:
    k = t[:16]
    a, c, show = text(find('age_' + k)), text(find('status_' + k)), find('pending_show_' + k) is not None
    good = a is not None and a.startswith('1 d') and c in ('pending', 'relayed') and show
    print('%s… age=%r chip=%r show_qr=%s %s' % (t[:12], a, c, show, 'ok' if good else 'BAD'))
    bad += not good
print('ROWS', 'ok' if bad == 0 else 'BAD')
PY
}
home "$S"; sleep 3; dump home1; sleep 10; dump home2
tap_id "$S" nav_payments; sleep 2; tap_id "$S" filter_sent; sleep 2
read -r W H < <("$ADB" -s "$S" shell wm size | sed -n 's/.*: \([0-9]*\)x\([0-9]*\).*/\1 \2/p' | tail -1)
for i in 1 2 3 4 5; do dump "pay$i"; "$ADB" -s "$S" shell input swipe $((W / 2)) $((H * 3 / 4)) $((W / 2)) $((H / 3)) 400; sleep 1; done
"$ADB" -s "$S" exec-out screencap -p >"$ROOT/docs/design/after/payments_day_old.png"
OUT=$(check_rows); echo "$OUT" | tee -a "$LOG"
case "$OUT" in *"ROWS ok"*) ;; *) ok=False;; esac
bars=$(python3 -c "import glob; print(sum(open(f).read().count('class=\"android.widget.ProgressBar\"') for f in glob.glob('$UI/home*.xml') + glob.glob('$UI/pay*.xml')))")
say "progress bars on Home/Payments: $bars"
[ "$bars" = 0 ] || ok=False
# Same Home 10 s apart (the sync line's block number aside): nothing is animating or waiting.
same=$(python3 -c "
import re,sys
# The sync line's block number moves on an online phone; that is progress, not a spinner.
a,b=[re.sub(r'text=\"[^\"]*\"( resource-id=\"sync_status\")', r'\1', re.sub(r' (bounds|focused|selected)=\"[^\"]*\"','',open(f).read())) for f in sys.argv[1:]]
print(a==b)" "$UI/home1.xml" "$UI/home2.xml")
say "home unchanged after 10 s: $same"
[ "$same" = True ] || ok=False
hook "$S" CLOCK_SKEW --es ms 0
[ "$ok" = True ] && say PASS || { say FAIL; exit 1; }

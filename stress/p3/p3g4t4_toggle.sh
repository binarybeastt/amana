#!/usr/bin/env bash
# P3.G4.T4: toggle connectivity (airplane mode) 20 times quickly with queued txs: no crash
# (same app process, no FATAL / ANR in the log) and no duplicate state (each txid once in the
# queue, once on disk, received payments once, entry count unchanged).
#   ./p3g4t4_toggle.sh <phone> <source-phone-with-received-payments> [toggles=20] [gap-s=1]
set -euo pipefail
source "$(dirname "$0")/lib.sh"
S=$1; SRC=$2; N=${3:-20}; GAP=${4:-1}
LOG="$P3/logs/p3g4t4.log"; : >"$LOG"
say() { echo "$(date -u +%T) $*" | tee -a "$LOG"; }
TMP=$(mktemp -d)
offline "$S"; launch "$S"; hook "$S" RELAY_CLEAR; sleep 3
for i in 1 2 3 4 5; do received_raw "$SRC" "$i" >"$TMP/$i.hex"; say "queue $i: $(inject "$S" "$TMP/$i.hex" 1 | tr -d '\n')"; done
sleep 4
PID=$("$ADB" -s "$S" shell pidof "$PKG" | tr -d '\r')
c0=$(mesh "$S" "m['connectivity_changes']")
"$ADB" -s "$S" logcat -c
say "pid $PID; toggling airplane mode $N times, ${GAP}s apart"
for i in $(seq 1 "$N"); do
  airplane "$S" off; sleep "$GAP"; airplane "$S" on; sleep "$GAP"
done
airplane "$S" off   # end online so the queue can settle
sleep 20
PID2=$("$ADB" -s "$S" shell pidof "$PKG" | tr -d '\r')
fatal=$("$ADB" -s "$S" logcat -d | grep -E "FATAL EXCEPTION|ANR in $PKG|Process $PKG.*has died" || true)
c1=$(mesh "$S" "m['connectivity_changes']")
q=$(mesh "$S" "len(m['queue'])"); qu=$(mesh "$S" "len(set(e['txid'] for e in m['queue']))")
files=$("$ADB" -s "$S" shell run-as "$PKG" ls files/relay | tr -d '\r' | grep -c '\.json$' || true)
tmpf=$("$ADB" -s "$S" shell run-as "$PKG" ls files/relay | tr -d '\r' | grep -c '\.tmp$' || true)
rec=$(state "$S" "len(s['received'])"); recu=$(state "$S" "len(set(r['txid'] for r in s['received']))")
states=$(mesh "$S" "sorted(e['state'] for e in m['queue'])")
bc=$(mesh_log "$S" | grep -c 'BROADCAST ' || true); bo=$(mesh_log "$S" | grep -c 'BACKOFF' || true)
say "pid after: $PID2; connectivity changes seen: $((c1 - c0)); queue $q entries, $qu distinct, $files files, $tmpf temp files; received $rec ($recu distinct); states $states; $bc broadcast attempts, $bo back-offs"
ok=True
[ "$PID" = "$PID2" ] || { say "app process changed"; ok=False; }
[ -z "$fatal" ] || { say "crash: $fatal"; ok=False; }
[ "$q" = 5 ] && [ "$qu" = 5 ] && [ "$files" = 5 ] && [ "$tmpf" = 0 ] && [ "$rec" = "$recu" ] || ok=False
offline "$S"
rm -rf "$TMP"
[ $ok = True ] && say PASS || { say FAIL; exit 1; }

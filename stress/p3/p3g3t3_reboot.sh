#!/usr/bin/env bash
# P3.G3.T3: reboot a phone with 5 queued txs; all 5 must still be queued afterwards. Also
# checks the mesh came back on its own (boot receiver → foreground service), before anyone
# opens the app. The phone stays in airplane mode (Bluetooth on) so nothing gets broadcast.
#   ./p3g3t3_reboot.sh <phone> <source-phone-with-5-received-payments>
set -euo pipefail
source "$(dirname "$0")/lib.sh"
S=$1; SRC=$2
LOG="$P3/logs/p3g3t3.log"; : >"$LOG"
say() { echo "$(date -u +%T) $*" | tee -a "$LOG"; }
TMP=$(mktemp -d)
offline "$S"; launch "$S"; hook "$S" RELAY_CLEAR; sleep 3
TXIDS=()
for i in 1 2 3 4 5; do
  received_raw "$SRC" "$i" >"$TMP/$i.hex"
  r=$(inject "$S" "$TMP/$i.hex" 1); say "inject $i: $r"
  TXIDS+=("$(echo "$r" | python3 -c "import json,sys; print(json.load(sys.stdin)['txid'])")")
done
sleep 4
before=$(mesh "$S" "sorted(e['txid'] for e in m['queue'] if e['state'] in ('queued', 'sent'))")
say "queued before reboot: $(mesh "$S" "len(m['queue'])") entries"
"$ADB" -s "$S" reboot
sleep 10
"$ADB" -s "$S" wait-for-device
until [ "$("$ADB" -s "$S" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do sleep 3; done
say "booted; internet=$(has_internet "$S" && echo yes || echo no) bluetooth=$(bt_is_on "$S" && echo on || echo off)"
# Do not open the app: the boot receiver must bring the mesh back by itself. Only a state file
# written after this boot counts (the old one survives the reboot).
BOOT_MS=$(( $("$ADB" -s "$S" shell date +%s | tr -d '\r') * 1000 - $("$ADB" -s "$S" shell cut -d. -f1 /proc/uptime | tr -d '\r') * 1000 ))
wait_state "$S" "s['updated_at'] > $BOOT_MS and s['mesh']['ble_running']" 300 || say "mesh did not come back by itself"
say "app pid after boot: $("$ADB" -s "$S" shell pidof "$PKG" | tr -d '\r'); state written $(( ($(state "$S" "s['updated_at']") - BOOT_MS) / 1000 )) s after boot"
say "$(mesh_log "$S" | grep -E 'BOOT|FOREGROUND started|START node' | head -3 | tr '\n' ' ')"
after=$(mesh "$S" "sorted(e['txid'] for e in m['queue'] if e['state'] in ('queued', 'sent'))")
n=$(mesh "$S" "len([e for e in m['queue'] if e['state'] in ('queued', 'sent')])")
[ "$(state "$S" "s['updated_at'] > $BOOT_MS")" = True ] || { say "no state written since boot"; n=stale; }
say "queued after reboot: $n; same set: $([ "$before" = "$after" ] && echo yes || echo no); app opened by test: no"
ok=True
[ "$n" = 5 ] && [ "$before" = "$after" ] || ok=False
for t in "${TXIDS[@]}"; do queue_has "$S" "$t" || { say "missing $t"; ok=False; }; done
[ "$(mesh "$S" "m['ble_running']")" = True ] || ok=False
rm -rf "$TMP"
[ $ok = True ] && say PASS || { say FAIL; exit 1; }

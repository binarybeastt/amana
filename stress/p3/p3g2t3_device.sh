#!/usr/bin/env bash
# P3.G2.T3 on devices (extra to the JVM test): phone X speaks protocol v2, phone Y v1. Y holds
# a queued tx, so it connects to X; both must refuse with the version reason and Y must not
# hand X the tx. Then X goes back to v1 and the tx goes through.
#   ./p3g2t3_device.sh <X> <Y> <raw-hex-file>
set -euo pipefail
source "$(dirname "$0")/lib.sh"
X=$1; Y=$2; RAW=$3
LOG="$P3/logs/p3g2t3-device.log"; : >"$LOG"
say() { echo "$(date -u +%T) $*" | tee -a "$LOG"; }
for s in "$X" "$Y"; do offline "$s"; launch "$s"; hook "$s" RELAY_CLEAR; done
sleep 3
for s in "$X" "$Y"; do "$ADB" -s "$s" logcat -c; done
hook "$X" MESH_VERSION --es v 2
sleep 3
r=$(inject "$Y" "$RAW" 1); say "inject on Y: $r"
TXID=$(echo "$r" | python3 -c "import json,sys; print(json.load(sys.stdin)['txid'])")
sleep 30
xl=$(mesh_log "$X" | grep REFUSED || true); yl=$(mesh_log "$Y" | grep REFUSED || true)
say "X: ${xl:-no REFUSED line}"; say "Y: ${yl:-no REFUSED line}"
ok=True
echo "$xl" | grep -q 'protocol version mismatch: peer speaks v1, this phone v2' || ok=False
echo "$yl" | grep -q 'protocol version mismatch: peer speaks v2, this phone v1' || ok=False
queue_has "$X" "$TXID" && { say "X got the tx despite the refusal"; ok=False; }
# Back to v1: the next attempt goes through (Y retries a refused peer after 5 min; poke it
# by restarting Y's mesh).
hook "$X" MESH_VERSION --es v 1; sleep 2; hook "$Y" MESH_STOP; sleep 1; hook "$Y" MESH_START
wait_mesh "$X" "any(e['txid'] == '$TXID' for e in m['queue'])" 90 || { say "tx not delivered at v1"; ok=False; }
say "after both on v1: X holds tx = $(queue_has "$X" "$TXID" && echo yes || echo no)"
[ $ok = True ] && say PASS || { say FAIL; exit 1; }

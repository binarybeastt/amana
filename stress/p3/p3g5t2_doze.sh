#!/usr/bin/env bash
# P3.G5.T2: a phone forced into Doze (screen off, unplugged, deviceidle force-idle) still
# relays: it takes a tx from one peer and passes it to another, both while in deep idle.
#   ./p3g5t2_doze.sh <dozing-relay X> <source Y> <sink Z> <raw-hex-file>
# Y holds the tx and pushes it to X (Z's Bluetooth off so Y cannot reach it); then Y's
# Bluetooth goes off and Z's comes on: only X can deliver it to Z.
set -euo pipefail
source "$(dirname "$0")/lib.sh"
X=$1; Y=$2; Z=$3; RAW=$4
LOG="$P3/logs/p3g5t2.log"; : >"$LOG"
say() { echo "$(date -u +%T) $*" | tee -a "$LOG"; }
idle() { "$ADB" -s "$X" shell dumpsys deviceidle get deep | tr -d '\r'; }
for s in "$X" "$Y" "$Z"; do offline "$s"; launch "$s"; hook "$s" RELAY_CLEAR; done
bt "$Z" off
sleep 5
XN=$(node_id "$X")
# X: screen off, on battery, deep idle.
"$ADB" -s "$X" shell input keyevent KEYCODE_SLEEP
"$ADB" -s "$X" shell dumpsys battery unplug
"$ADB" -s "$X" shell dumpsys deviceidle force-idle >/dev/null
sleep 5
say "X ($XN) deep idle state: $(idle); screen: $("$ADB" -s "$X" shell dumpsys power | grep -oE 'mWakefulness=\w+' | head -1)"
"$ADB" -s "$X" logcat -c; "$ADB" -s "$Z" logcat -c
r=$(inject "$Y" "$RAW" 1); say "Y queued: $r"
TXID=$(echo "$r" | python3 -c "import json,sys; print(json.load(sys.stdin)['txid'])")
t0=$SECONDS
until log_has "$X" "RECV txid=$TXID .*status=stored"; do
  [ $((SECONDS - t0)) -lt 180 ] || break; sleep 2
done
say "X received while '$(idle)': $(log_line "$X" "RECV txid=$TXID")"
bt "$Y" off; sleep 3; bt "$Z" on
t1=$SECONDS
until log_has "$Z" "RECV txid=$TXID from=$XN .*status=stored"; do
  [ $((SECONDS - t1)) -lt 180 ] || break; sleep 2
done
zl=$(log_line "$Z" "RECV txid=$TXID")
st=$(idle)
say "Z: ${zl:-nothing} ($((SECONDS - t1)) s after Z's Bluetooth came on); X still '$st'"
ok=True
log_has "$X" "RECV txid=$TXID .*status=stored" || ok=False
[[ "$zl" == *"from=$XN "*"status=stored"* ]] || ok=False
[ "$st" = IDLE ] || ok=False
"$ADB" -s "$X" shell dumpsys deviceidle unforce >/dev/null; "$ADB" -s "$X" shell dumpsys battery reset
"$ADB" -s "$X" shell input keyevent KEYCODE_WAKEUP
bt "$Y" on
[ $ok = True ] && say PASS || { say FAIL; exit 1; }

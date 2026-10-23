#!/usr/bin/env bash
# P4.G4.T2, emulator form on ONE phone (no Bluetooth): a conflicted payment on the receiver is
# clearly labelled and excluded from every balance shown.
#
#   1. Desktop: sync wallets/testnet-a, then a REAL double spend from account D
#      (p4g2_make_pair.sh): tx1 pays this phone, tx2 a desktop account; never broadcast.
#   2. Phone: online just long enough to sync past tx1's anchor, then Wi-Fi + data off.
#   3. tx1 then tx2 enter as if pushed by peers (RELAY_INJECT, verified by :core): tx1 is
#      filed as "received, not yet settled" and counted as incoming; the P4.G2 alert shows.
#   4. Chain fact, simulated: lightwalletd says tx2 was mined (RELAY_SETTLE, applied exactly as
#      the broadcaster applies a real one). Reconciliation must turn tx1 `conflicted`.
# Pass: state.json has tx1 conflicted with a "conflicted … not counted" label, the incoming sum
# back to what it was before tx1, the wallet total unchanged, the amount under
# conflicted_not_counted; on screen the red "Conflicted, not counted" line on Home and the
# conflicted label on Payments, and tx1's amount in no balance line.
# Clean-up removes both txs from the phone so the pair is never carried online.
#   ./p4g4t2_conflicted_receiver.sh <serial> [pair-dir=stress/p4/logs/p4g4-pair] [--reuse-pair]
set -uo pipefail
source "$(dirname "$0")/../p3/lib.sh"
S=$1; PAIR=${2:-$ROOT/stress/p4/logs/p4g4-pair}; REUSE=${3:-}
LOG="$ROOT/stress/p4/logs/p4g4t2.log"; : >"$LOG"
UI="$ROOT/stress/p4/logs/p4g4t2-ui"; mkdir -p "$UI"
say() { echo "$(date -u +%T) $*" | tee -a "$LOG"; }
dump_ui() { "$ADB" -s "$S" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; "$ADB" -s "$S" exec-out cat /sdcard/ui.xml >"$UI/$1.xml"; }
ui_has() { python3 - "$UI/$1.xml" "$2" <<'PY'
import html, re, sys
x = html.unescape(open(sys.argv[1]).read())
print(re.search(sys.argv[2], x) is not None)
PY
}
dismiss_anr() {
  "$ADB" -s "$S" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  local b; b=$("$ADB" -s "$S" exec-out cat /sdcard/ui.xml | python3 -c "
import re, sys
x = sys.stdin.read()
if \"isn't responding\" in x:
    m = re.search(r'text=\"Wait\"[^>]*bounds=\"\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]\"', x)
    if m: a, b, c, d = map(int, m.groups()); print((a + c) // 2, (b + d) // 2)")
  [ -n "$b" ] && { say "dismissed a system 'isn't responding' dialog"; "$ADB" -s "$S" shell input tap $b; sleep 1; }
  return 0
}

launch "$S"
wait_state "$S" "s.get('wallet') == 'ready'" 240 || { say "FAIL: wallet not ready"; exit 1; }
ADDR=$(state "$S" "s['address']")

# 1. The pair.
if [ "$REUSE" != --reuse-pair ] || [ ! -f "$PAIR/pair.env" ]; then
  say "desktop: syncing wallets/testnet-a"
  bash -c "source '$ROOT/spikes/zcash/env.sh'; sync_wallet" || { say "FAIL: desktop sync"; exit 1; }
  TO1="$ADDR" bash "$ROOT/stress/p4/p4g2_make_pair.sh" "$PAIR" >>"$LOG" 2>&1 || { say "FAIL: make_pair"; exit 1; }
fi
source "$PAIR/pair.env"
say "pair: tx1=${TX1:0:16}… (pays this phone) tx2=${TX2:0:16}… nullifier ${SHARED_NF:0:16}…"
DESK=$(bash -c "source '$ROOT/spikes/zcash/env.sh'; nonet '$ROOT/offline-core/target/release/offline-cli' -w \"\$W\" status" | sed -n 's/Synced to height //p')

# 2. Sync past the anchor, then offline.
net_on "$S"
wait_state "$S" "s['sync']['status'] == 'SYNCED' and (s['sync']['scanned'] or 0) >= $DESK and s['sync']['scanned'] == s['sync']['network']" 900 \
  || { say "FAIL: phone did not sync past $DESK"; net_off "$S"; exit 1; }
net_off "$S"
for i in $(seq 1 30); do net_is_off "$S" && break; sleep 2; done
wait_mesh "$S" "not m['online']" 60 || { say "FAIL: still online"; exit 1; }
say "phone synced to $(state "$S" "s['sync']['scanned']") (desktop $DESK), now offline"

cleanup() {
  for t in "$TX1" "$TX2"; do hook "$S" RELAY_REMOVE --es txid "$t"; done
  hook "$S" RECEIVED_DELETE --es txid "$TX1"; hook "$S" CONFLICTS_CLEAR
  sleep 2; force_stop "$S"; launch "$S"   # rebuilds the nullifier set from what is left
  say "clean-up: tx1/tx2 removed (queue has tx1: $(mesh "$S" "any(e['txid'] == '$TX1' for e in m['queue'])"))"
}
trap cleanup EXIT
hook "$S" CONFLICTS_CLEAR; "$ADB" -s "$S" logcat -c
sleep 4
INC0=$(state "$S" "s['balance']['incoming_unsettled']"); LOST0=$(state "$S" "s['balance']['conflicted_not_counted']")
TOT0=$(state "$S" "s['balance']['total']")
say "before: total=$TOT0 incoming_unsettled=$INC0 conflicted_not_counted=$LOST0"

# 3. tx1 (to us) then tx2 (a stranger's) arrive.
r=$(inject "$S" "$PAIR/tx1.hex" 1 "$TX1"); say "inject tx1: $r"
echo "$r" | grep -c '"stored"' >/dev/null || { say "FAIL: tx1 not stored"; exit 1; }
wait_state "$S" "any(x['txid'] == '$TX1' and x['label'] == 'received, not yet settled' for x in s['received'])" 60 \
  || { say "FAIL: tx1 not filed as received"; exit 1; }
AMT=$(state "$S" "next(x['amount'] for x in s['received'] if x['txid'] == '$TX1')")
INC1=$(state "$S" "s['balance']['incoming_unsettled']")
say "tx1 received: $AMT zats, incoming_unsettled $INC0 → $INC1"
[ "$INC1" = $((INC0 + AMT)) ] || { say "FAIL: tx1 not counted as incoming while pending"; exit 1; }
r=$(inject "$S" "$PAIR/tx2.hex" 1 "$TX2"); say "inject tx2: $r"
echo "$r" | grep -c '"stored"' >/dev/null || { say "FAIL: tx2 not stored"; exit 1; }
wait_state "$S" "any('$TX1' in c['txids'] and '$TX2' in c['txids'] for c in s['conflicts'])" 60 || { say "FAIL: no P4.G2 alert"; exit 1; }
say "P4.G2 alert up; tx1 still $(entry "$S" "$TX1" state) (nothing mined yet)"

# 4. Chain fact: tx2 mined.
H=$(( $(state "$S" "s['sync']['scanned']") + 1 ))
hook "$S" RELAY_SETTLE --es txid "$TX2" --es height "$H"
t0=$(now_ms)
wait_state "$S" "any(x['txid'] == '$TX1' and x['conflicted'] for x in s['received'])" 60 || { say "FAIL: tx1 never conflicted"; exit 1; }
say "tx1 conflicted after $(( ($(now_ms) - t0) / 1000 )) s: queue state $(entry "$S" "$TX1" state), $(entry "$S" "$TX1" last_result)"
LABEL=$(state "$S" "next(x['label'] for x in s['received'] if x['txid'] == '$TX1')")
INC2=$(state "$S" "s['balance']['incoming_unsettled']"); LOST2=$(state "$S" "s['balance']['conflicted_not_counted']")
TOT2=$(state "$S" "s['balance']['total']")
say "after: label='$LABEL' total=$TOT2 incoming_unsettled=$INC2 conflicted_not_counted=$LOST2"
"$ADB" -s "$S" logcat -d -s 'AppController:*' | grep RECONCILE | tail -3 | tee -a "$LOG"
ok=True
case "$LABEL" in conflicted*"not counted"*) ;; *) say "FAIL: label"; ok=False;; esac
[ "$INC2" = "$INC0" ] || { say "FAIL: incoming still counts tx1"; ok=False; }
[ "$TOT2" = "$TOT0" ] || { say "FAIL: wallet total changed"; ok=False; }
[ "$LOST2" = $((LOST0 + AMT)) ] || { say "FAIL: conflicted_not_counted"; ok=False; }

# The screen: Home, then Payments.
ZEC=$(python3 -c "z=$AMT; s='%d.%08d' % (z // 10**8, z % 10**8); s=s.rstrip('0'); print(s + '0' if s.endswith('.') else s)")
home "$S"; sleep 5; dismiss_anr; dump_ui home
say "home: $(ui_text "$S" balance_total) | $(ui_text "$S" balance_incoming) | $(ui_text "$S" balance_conflicted)"
[ "$(ui_has home 'resource-id="balance_conflicted"')" = True ] || { say "FAIL: no conflicted line on Home"; ok=False; }
[ "$(ui_has home "text=\"Conflicted, not counted: [^\"]*\"")" = True ] || { say "FAIL: conflicted line text"; ok=False; }
INCTXT=$(ui_text "$S" balance_incoming)
EXP_INC=$(python3 -c "z=$INC2; s='%d.%08d' % (z // 10**8, z % 10**8); s=s.rstrip('0'); print(s + '0' if s.endswith('.') else s)")
if [ "$INC2" -gt 0 ]; then [ "$INCTXT" = "Received offline, not yet settled: $EXP_INC" ] || { say "FAIL: incoming line '$INCTXT' != $EXP_INC"; ok=False; }
else [ -z "$INCTXT" ] || { say "FAIL: incoming line shown for 0"; ok=False; }; fi
tap_id "$S" nav_payments; sleep 3; dismiss_anr
read -r W H < <("$ADB" -s "$S" shell wm size | sed -n 's/.*: \([0-9]*\)x\([0-9]*\).*/\1 \2/p' | tail -1)
for i in $(seq 1 15); do
  dump_ui payments
  [ "$(ui_has payments "resource-id=\"received_label_${TX1:0:16}\"")" = True ] && break
  "$ADB" -s "$S" shell input swipe $((W / 2)) $((H * 3 / 4)) $((W / 2)) $((H / 3)) 400; sleep 1
done
LBL=$(python3 - "$UI/payments.xml" "${TX1:0:16}" <<'PY'
import html, re, sys
x = open(sys.argv[1]).read()
m = re.search(r'text="([^"]*)"[^>]*resource-id="received_label_%s"' % sys.argv[2], x)
print(html.unescape(m.group(1)) if m else '')
PY
)
say "payments screen, tx1: '$LBL'"
case "$LBL" in "$ZEC ZEC · conflicted"*"not counted"*) ;; *) say "FAIL: Payments label"; ok=False;; esac
"$ADB" -s "$S" exec-out screencap -p >"$UI/payments.png"
home "$S"; sleep 3; "$ADB" -s "$S" exec-out screencap -p >"$UI/home.png"
[ $ok = True ] && say "PASS" || { say FAIL; exit 1; }

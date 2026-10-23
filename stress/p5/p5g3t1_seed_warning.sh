#!/usr/bin/env bash
# P5.G3.T1 on one emulator: restore a seed, then go offline. The seed-on-two-devices warning
# must be shown both times and must be acknowledged (Back does not dismiss it; the app behind
# it can't be used until "I understand").
# Wipes <serial>'s app and restores the SAME seed (so no funds move): refuses while that phone
# has unsettled own payments. Before the wipe, the app data is tarred to wallets/emu-backup/;
# after the test the received-payment and relay records are put back.
#   ./p5g3t1_seed_warning.sh <serial>
#   RECORDS_TAR=<older backup> ./p5g3t1_seed_warning.sh <serial>   put records back from that tar
set -euo pipefail
source "$(dirname "$0")/../p2/lib.sh"
source "$ROOT/stress/p3/lib.sh"   # net_off / net_on
S=$1; LOG="$ROOT/stress/p5/logs/p5g3t1-$S.log"
say() { echo "$*" | tee -a "$LOG"; }
echo "$(date -u +%FT%TZ) P5.G3.T1 on $S" | tee "$LOG"
launch "$S"; wait_state "$S" "s.get('wallet') == 'ready'" 180
[ "$(state "$S" "len(s['pending_sent'])")" = 0 ] || { say "refusing: $S has unsettled own payments (a wipe would lose them)"; exit 1; }
ADDR=$(state "$S" "s['address']"); BDAY=$(state "$S" "s['birthday']")
AVD=$("$ADB" -s "$S" emu avd name 2>/dev/null | head -1 | tr -d '\r')
TAR="$ROOT/wallets/emu-backup/emu-$AVD-$(date +%Y%m%d-%H%M)-pre-p5g3.tar"; mkdir -p "$(dirname "$TAR")"
"$ADB" -s "$S" exec-out run-as "$PKG" tar cf - . >"$TAR"
say "app data backed up: $TAR ($(du -h "$TAR" | cut -f1))"
"$ADB" -s "$S" shell run-as "$PKG" rm -f files/mnemonic.txt
hook "$S" DUMP_SEED
for _ in $(seq 1 20); do [ "$(app_file "$S" mnemonic.txt | wc -w | tr -d ' ')" -ge 12 ] && break; sleep 1; done
WORDS=$(app_file "$S" mnemonic.txt); "$ADB" -s "$S" shell run-as "$PKG" rm -f files/mnemonic.txt
[ "$(echo "$WORDS" | wc -w | tr -d ' ')" = 24 ] || { say "no seed from $S"; exit 1; }

UI=$(mktemp -d)
dump() { "$ADB" -s "$S" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; "$ADB" -s "$S" exec-out cat /sdcard/ui.xml >"$UI/$1.xml"; }
has() { python3 -c "import sys; print('resource-id=\"$2\"' in open(sys.argv[1]).read())" "$UI/$1.xml"; }
title() { python3 -c "
import re,sys,html; m=re.search(r'text=\"([^\"]*)\"[^>]*resource-id=\"seed_warning_title\"', open(sys.argv[1]).read()); print(html.unescape(m.group(1)) if m else '')" "$UI/$1.xml"; }
# RESTORED / OFFLINE / none ('' only when state.json can't be read)
showing() { state "$S" "s['seed_warning']['showing'] or 'none'"; }
ok=True
# One warning: on screen, survives Back, blocks the app, goes only with "I understand".
check_warning() { # <name> <expected SeedWarning>
  local n=$1 want=$2
  wait_state "$S" "s.get('seed_warning', {}).get('showing') == '$want'" 300 || { say "FAIL: $n: state never showed $want"; ok=False; return; }
  # The dialog is on screen within a few seconds of the state saying so.
  local i; for i in $(seq 1 10); do sleep 1; dump "$n"; [ "$(has "$n" seed_warning)" = True ] && break; done
  say "$n: warning on screen=$(has "$n" seed_warning) title='$(title "$n")'"
  [ "$(has "$n" seed_warning)" = True ] || ok=False
  "$ADB" -s "$S" exec-out screencap -p >"$ROOT/docs/design/after/seed_warning_$n.png"
  "$ADB" -s "$S" shell input keyevent KEYCODE_BACK; sleep 2; dump "$n-after-back"
  say "$n: after Back still shown=$(has "$n-after-back" seed_warning) app reachable=$(has "$n-after-back" nav_pay)"
  [ "$(has "$n-after-back" seed_warning)" = True ] && [ "$(has "$n-after-back" nav_pay)" = False ] || ok=False
  [ "$(showing)" = "$want" ] || ok=False
  tap_id "$S" seed_ack; sleep 4; dump "$n-acked"
  say "$n: after 'I understand' shown=$(has "$n-acked" seed_warning) state=$(showing)"
  [ "$(has "$n-acked" seed_warning)" = False ] || ok=False
}

clear_app "$S"
# A wiped app asks for Bluetooth and notifications first; that system prompt would sit on top
# of the warning. Grant them as a user who said yes before would have.
for p in BLUETOOTH_SCAN BLUETOOTH_CONNECT BLUETOOTH_ADVERTISE POST_NOTIFICATIONS; do "$ADB" -s "$S" shell pm grant "$PKG" "android.permission.$p" 2>/dev/null || true; done
launch "$S"; sleep 3
hook "$S" RESTORE --es words "'$WORDS'" --es birthday "$BDAY"; unset WORDS
say "restoring from birthday $BDAY"
check_warning restore RESTORED
wait_state "$S" "s.get('wallet') == 'ready'" 600
NEWADDR=$(state "$S" "s['address']"); say "same address after restore: $([ "$NEWADDR" = "$ADDR" ] && echo yes || echo NO)"
[ "$NEWADDR" = "$ADDR" ] || ok=False
# Online and acknowledged once: no second warning yet.
sleep 5; [ "$(showing)" = none ] || { say "FAIL: warning while online after the first ack: $(showing)"; ok=False; }
net_off "$S"; say "network off"
check_warning offline OFFLINE
net_on "$S"; sleep 10
[ "$(showing)" = none ] || ok=False
net_off "$S"; sleep 10; say "offline again after both acks: warning=$(showing)"
[ "$(showing)" = none ] || ok=False
net_on "$S"
say "flags: $(state "$S" "s['seed_warning']")"
# Put back the payment records (received payments, relay queue, conflicts); the wallet itself is the restored one.
"$ADB" -s "$S" shell am force-stop "$PKG"
RT=$(cd "$(dirname "${RECORDS_TAR:-$TAR}")" && pwd)/$(basename "${RECORDS_TAR:-$TAR}")
D=$(mktemp -d)
if ( cd "$D" && tar xf "$RT" ./files/received ./files/relay ./files/conflicts.json 2>/dev/null; COPYFILE_DISABLE=1 tar cf "$D/rec.tar" files ) &&
  "$ADB" -s "$S" push "$D/rec.tar" /data/local/tmp/p5g3-rec.tar >/dev/null &&
  "$ADB" -s "$S" shell "run-as $PKG sh -c 'tar xf /data/local/tmp/p5g3-rec.tar -C /data/user/0/$PKG'"; then
  say "payment records put back from $RT"
else say "note: could not put back payment records"; fi
"$ADB" -s "$S" shell rm -f /data/local/tmp/p5g3-rec.tar
launch "$S"
[ "$ok" = True ] && say PASS || { say FAIL; exit 1; }

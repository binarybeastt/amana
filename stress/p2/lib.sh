# Shared helpers for Phase 2 emulator tests. Source from bash.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ADB="$SDK/platform-tools/adb"
PKG=dev.offlinezec.wallet
APK="$ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
export JAVA_HOME="${JAVA_HOME_21:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"

now_ms() { python3 -c 'import time; print(int(time.time() * 1000))'; }

# hook <serial> <ACTION> [--es key value ...]
hook() { local s=$1 a=$2; shift 2; "$ADB" -s "$s" shell am broadcast -n "$PKG/.debug.TestHooks" -a "$a" "$@" >/dev/null; }

# app_file <serial> <name>: cat a file from the app's files dir
app_file() { "$ADB" -s "$1" exec-out run-as "$PKG" cat "files/$2" 2>/dev/null; }

# push_app_file <serial> <name> <local-file>
push_app_file() { "$ADB" -s "$1" exec-in run-as "$PKG" sh -c "cat > files/$2" <"$3"; }

# state <serial> <python expr over s>: evaluate against state.json, e.g. state X "s['sync']['status']"
state() {
  app_file "$1" state.json | python3 -c "
import json, sys
try:
    s = json.load(sys.stdin)
    v = eval(sys.argv[1])
    print('' if v is None else v)
except Exception:
    print('')" "$2"
}

# wait_state <serial> <python bool expr> <timeout-s>
wait_state() {
  local s=$1 expr=$2 t=${3:-300} end=$((SECONDS + ${3:-300}))
  while [ $SECONDS -lt $end ]; do
    [ "$(state "$s" "bool($expr)")" = True ] && return 0
    sleep 3
  done
  return 1
}

launch() { "$ADB" -s "$1" shell am start -W -n "$PKG/.ui.MainActivity" >/dev/null; }
force_stop() { "$ADB" -s "$1" shell am force-stop "$PKG"; }
# kill9 <serial>: SIGKILL every process of the app (main and :core), as a crash would
kill9() {
  local pids; pids=$("$ADB" -s "$1" shell pidof "$PKG" "$PKG:core" 2>/dev/null | tr -d '\r')
  [ -n "$pids" ] && "$ADB" -s "$1" shell run-as "$PKG" kill -9 $pids 2>/dev/null
  "$ADB" -s "$1" shell am force-stop "$PKG"   # also clears any restart
}

airplane() {  # airplane <serial> on|off
  if [ "$2" = on ]; then
    "$ADB" -s "$1" shell cmd connectivity airplane-mode enable
  else
    "$ADB" -s "$1" shell cmd connectivity airplane-mode disable
  fi
}

# tap_id <serial> <resource-id>: tap the centre of the view with this Compose testTag
tap_id() {
  local s=$1 id=$2
  "$ADB" -s "$s" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  local b; b=$("$ADB" -s "$s" exec-out cat /sdcard/ui.xml | python3 -c "
import re, sys
x = sys.stdin.read()
m = re.search(r'resource-id=\"$id\"[^>]*bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', x)
if m:
    a, b, c, d = map(int, m.groups()); print((a + c) // 2, (b + d) // 2)")
  [ -n "$b" ] || { echo "no view $id on screen" >&2; return 1; }
  "$ADB" -s "$s" shell input tap $b
}

# wallet_ready <serial> <timeout>: wallet open and synced to the network tip
wait_synced() { wait_state "$1" "s.get('wallet') == 'ready' and s['sync']['status'] == 'SYNCED' and s['sync']['scanned'] == s['sync']['network']" "${2:-600}"; }
# shielded total the SDK shows
sdk_total() { state "$1" "s['sync']['ironwood_total'] + s['sync']['orchard_total'] + s['sync']['sapling_total']"; }
clear_app() { "$ADB" -s "$1" shell pm clear "$PKG" >/dev/null; "$ADB" -s "$1" shell pm grant "$PKG" android.permission.CAMERA 2>/dev/null || true; }

# core_notes <serial>: offline-core's view of this wallet's notes (JSON array), fresh
core_notes() {
  "$ADB" -s "$1" shell run-as "$PKG" rm -f files/core_notes.json
  hook "$1" CORE_STATUS
  local i; for i in $(seq 1 30); do app_file "$1" core_notes.json 2>/dev/null | grep -q . && break; sleep 1; done
  app_file "$1" core_notes.json
}
# note_txids <serial>: distinct txids that created this wallet's notes, one per line
note_txids() { core_notes "$1" | python3 -c "import json,sys; print('\n'.join(sorted({n['note'].split(':')[0] for n in json.load(sys.stdin)})))"; }
# home <serial>: back to the home screen
home() { "$ADB" -s "$1" shell input keyevent KEYCODE_BACK; "$ADB" -s "$1" shell input keyevent KEYCODE_BACK; launch "$1"; }

# type_into <serial> <resource-id> <text>: focus a field and type (no spaces in text).
# pay_amount is not a text field (Phase 5): the amount is typed on the Pay screen's keypad.
type_into() {
  if [ "$2" = pay_amount ]; then tap_keys "$1" "$3"; return; fi
  tap_id "$1" "$2"; sleep 0.5; "$ADB" -s "$1" shell input text "$3"; sleep 0.5
  hide_keyboard "$1"
}
# tap_keys <serial> <digits-and-dots>: clears the Pay keypad's amount, then taps each key
# (testTags key_0..key_9, key_dot, key_back).
tap_keys() {
  local s=$1 txt=$2
  hide_keyboard "$s"
  "$ADB" -s "$s" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  local taps; taps=$("$ADB" -s "$s" exec-out cat /sdcard/ui.xml | python3 -c "
import re, sys
x = sys.stdin.read()
pos = {}
for k, a, b, c, d in re.findall(r'resource-id=\"key_(\w+)\"[^>]*bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', x):
    pos[k] = ((int(a) + int(c)) // 2, (int(b) + int(d)) // 2)
amt = re.search(r'text=\"([^\"]*)\"[^>]*resource-id=\"pay_amount\"', x)
keys = ['back'] * (len(amt.group(1)) if amt and amt.group(1) != '0' else 0)
keys += ['dot' if ch == '.' else ch for ch in '$txt']
if all(k in pos for k in keys):
    print('\n'.join('%d %d' % pos[k] for k in keys))")
  [ -n "$taps" ] || { echo "no keypad on screen" >&2; return 1; }
  # </dev/null: adb shell would otherwise swallow the remaining lines
  while read -r x y; do "$ADB" -s "$s" shell input tap "$x" "$y" </dev/null; done <<<"$taps"
  sleep 0.5
}
# hide_keyboard <serial>: Back closes the on-screen keyboard, but only press it while one shows
# (otherwise Back leaves the screen). A covering keyboard swallows the next tap.
hide_keyboard() {
  if [ "$("$ADB" -s "$1" shell dumpsys input_method | grep -c "mInputShown=true")" -gt 0 ]; then
    "$ADB" -s "$1" shell input keyevent KEYCODE_BACK; sleep 0.5
  fi
}

# ui_text <serial> <resource-id>: text of that view on screen ('' if absent)
ui_text() {
  "$ADB" -s "$1" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  "$ADB" -s "$1" exec-out cat /sdcard/ui.xml | python3 -c "
import re, sys, html
m = re.search(r'text=\"([^\"]*)\"[^>]*resource-id=\"$2\"', sys.stdin.read())
print(html.unescape(m.group(1)) if m else '')"
}

# pay_via_ui <sender> <to-address> <zec-amount>: Pay screen -> fields -> "Pay offline";
# waits until the payment QR is on screen. Prints the txid.
pay_via_ui() {
  local s=$1 to=$2 amt=$3
  hook "$s" CLEAR_PAY; home "$s"; sleep 1
  tap_id "$s" nav_pay; sleep 1
  type_into "$s" pay_to "$to"
  type_into "$s" pay_amount "$amt"
  tap_id "$s" pay_go
  # 900 s: on 2 GB emulators deep in swap, proving occasionally takes minutes
  wait_state "$s" "s['pay']['state'] in ('ready', 'failed')" 900 || return 1
  [ "$(state "$s" "s['pay']['state']")" = ready ] || { state "$s" "s['pay']['error']" >&2; return 1; }
  wait_state "$s" "s.get('notes_at_qr', {}).get('txid') == s['pay']['txid']" 60 || return 1
  state "$s" "s['pay']['txid']"
}

# relay_frames <sender> <receiver> [skip-list]: the sender's QR frames, fed to the receiver's
# scanner path (the emulator stand-in for pointing a camera). Receiver must be on Receive.
relay_frames() {
  local s=$1 r=$2 skip=${3:-}
  local tmp; tmp=$(mktemp)
  "$ADB" -s "$s" shell run-as "$PKG" rm -f files/qr_frames.txt
  hook "$s" DUMP_FRAMES --es count 80
  # exec-out returns the device's error text on stdout, so wait for real frame lines.
  local i; for i in $(seq 1 30); do
    app_file "$s" qr_frames.txt >"$tmp" 2>/dev/null
    [ "$(grep -c '^UR:BYTES/' "$tmp")" -ge 2 ] && break; sleep 1
  done
  [ "$(grep -c '^UR:BYTES/' "$tmp")" -ge 2 ] || { echo "no frames from $s" >&2; rm -f "$tmp"; return 1; }
  # Write under a temp name, rename into place, and check every line arrived: exec-in can
  # return before the device has finished writing.
  local want; want=$(grep -c '^UR:BYTES/' "$tmp")
  push_app_file "$r" in_frames.tmp "$tmp"; rm -f "$tmp"
  for i in $(seq 1 20); do
    [ "$(app_file "$r" in_frames.tmp | grep -c '^UR:BYTES/')" = "$want" ] && break; sleep 1
  done
  "$ADB" -s "$r" shell run-as "$PKG" mv files/in_frames.tmp files/in_frames.txt
  hook "$r" FEED_FRAMES --es skip "$skip"
  wait_state "$r" "s['receive']['state'] in ('accepted', 'rejected')" 600  # slow emulators: ~2 frames/s
}

#!/usr/bin/env bash
# Helpers for recording the demo video (docs/DEMO.md): screen recording on any device over adb,
# plus the stress helpers. Source from bash. Footage goes to demo/raw/ (git-ignored).
#   rec_start <serial> <clip>   start recording (max 180 s, Android's limit)
#   rec_stop <serial> <clip>    stop, pull to demo/raw/<clip>.mp4
source "$(dirname "${BASH_SOURCE[0]}")/../p3/lib.sh"
RAW="$ROOT/demo/raw"; mkdir -p "$RAW"

rec_start() {
  "$ADB" -s "$1" shell rm -f "/sdcard/$2.mp4"
  "$ADB" -s "$1" shell screenrecord --bit-rate 8000000 --time-limit 180 "/sdcard/$2.mp4" &
  rec_mark "$1" "$2"
  sleep 1
}

rec_stop() {
  "$ADB" -s "$1" shell pkill -INT screenrecord
  sleep 3
  "$ADB" -s "$1" pull "/sdcard/$2.mp4" "$RAW/$2.mp4" >/dev/null && "$ADB" -s "$1" shell rm -f "/sdcard/$2.mp4"
  echo "$RAW/$2.mp4"
}

# shot <serial> <name>: screenshot to demo/raw/<name>.png
shot() { "$ADB" -s "$1" exec-out screencap -p >"$RAW/$2.png"; }

# clear_keypad <serial> <amount-testTag>: tap the keypad's back key until the amount is empty
clear_keypad() {
  local s=$1 id=$2
  "$ADB" -s "$s" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  "$ADB" -s "$s" exec-out cat /sdcard/ui.xml | python3 -c "
import re, sys
x = sys.stdin.read()
k = re.search(r'resource-id=\"key_back\"[^>]*bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', x)
a = re.search(r'text=\"([^\"]*)\"[^>]*resource-id=\"$id\"', x)
if k and a:
    p = ((int(k[1]) + int(k[3])) // 2, (int(k[2]) + int(k[4])) // 2)
    n = len(re.sub(r'[^0-9.]', '', a[1]))
    print('\n'.join('%d %d' % p for _ in range(n)))" | while read -r x y; do
    "$ADB" -s "$s" shell input tap "$x" "$y" </dev/null; done
}

# keys <serial> <text>: tap digits and dots on the on-screen keypad
keys() {
  local s=$1 txt=$2
  "$ADB" -s "$s" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  "$ADB" -s "$s" exec-out cat /sdcard/ui.xml | python3 -c "
import re, sys
x = sys.stdin.read()
pos = {k: ((int(a) + int(c)) // 2, (int(b) + int(d)) // 2)
       for k, a, b, c, d in re.findall(r'resource-id=\"key_(\w+)\"[^>]*bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', x)}
for ch in '$txt':
    print('%d %d' % pos['dot' if ch == '.' else ch])" | while read -r x y; do
    "$ADB" -s "$s" shell input tap "$x" "$y" </dev/null; sleep 0.3; done
}

# airplane on|off on the real phone (shows in its status bar on the recording)
phone_airplane() { "$ADB" -s "$1" shell cmd connectivity airplane-mode "$([ "$2" = on ] && echo enable || echo disable)"; }

# Tap logging for the edit (touch ripples): rec_start also notes when a clip started, and
# tap_rec taps a view by testTag and appends {clip, t, x, y} to demo/raw/taps.jsonl.
rec_mark() { python3 -c 'import time; print(int(time.time()*1000))' >"$RAW/$2.t0"; }
tap_rec() {
  local s=$1 id=$2 clip=$3
  "$ADB" -s "$s" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  local b; b=$("$ADB" -s "$s" exec-out cat /sdcard/ui.xml | python3 -c "
import re, sys
x = sys.stdin.read()
m = re.search(r'resource-id=\"$id\"[^>]*bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', x)
if m:
    a, b, c, d = map(int, m.groups()); print((a + c) // 2, (b + d) // 2)")
  [ -n "$b" ] || { echo "no view $id on screen" >&2; return 1; }
  "$ADB" -s "$s" shell input tap $b
  python3 -c "
import json, time; t0 = int(open('$RAW/$clip.t0').read())
x, y = map(int, '$b'.split())
open('$RAW/taps.jsonl', 'a').write(json.dumps({'clip': '$clip', 't': (time.time()*1000 - t0) / 1000, 'x': x, 'y': y}) + '\n')"
}
# keys_rec <serial> <text> <clip>: keypad taps, logged
keys_rec() {
  local s=$1 txt=$2 clip=$3
  "$ADB" -s "$s" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  "$ADB" -s "$s" exec-out cat /sdcard/ui.xml | python3 -c "
import re, sys
x = sys.stdin.read()
pos = {k: ((int(a) + int(c)) // 2, (int(b) + int(d)) // 2)
       for k, a, b, c, d in re.findall(r'resource-id=\"key_(\w+)\"[^>]*bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', x)}
for ch in '$txt':
    print('%d %d' % pos['dot' if ch == '.' else ch])" | while read -r x y; do
    "$ADB" -s "$s" shell input tap "$x" "$y" </dev/null
    python3 -c "
import json, time; t0 = int(open('$RAW/$clip.t0').read())
open('$RAW/taps.jsonl', 'a').write(json.dumps({'clip': '$clip', 't': (time.time()*1000 - t0) / 1000, 'x': $x, 'y': $y}) + '\n')"
    sleep 0.45; done
}

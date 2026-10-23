#!/usr/bin/env bash
# Screenshots of every screen into docs/design/after/ (Phase 5 look). No payment is made.
#   ./screens.sh <serial>
set -euo pipefail
source "$(dirname "$0")/../p2/lib.sh"
S=$1; OUT="$ROOT/docs/design/after"; mkdir -p "$OUT"
shot() { sleep "${2:-2}"; "$ADB" -s "$S" exec-out screencap -p >"$OUT/$1.png"; echo "$OUT/$1.png"; }
back() { "$ADB" -s "$S" shell input keyevent KEYCODE_BACK; sleep 1; }
hook "$S" CLEAR_PAY; launch "$S"; sleep 3
shot home
tap_id "$S" nav_pay; tap_keys "$S" 0.01; shot pay 1; back
tap_id "$S" nav_receive; shot receive; tap_id "$S" recv_tab_scan; shot receive_scan 3; back
tap_id "$S" nav_payments; shot payments; back
tap_id "$S" nav_prepare; shot prepare; back
tap_id "$S" nav_limits; shot limits; back

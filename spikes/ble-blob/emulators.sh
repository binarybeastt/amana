#!/usr/bin/env bash
# Boot headless Android emulators that share netsim's virtual Bluetooth.
#   ./emulators.sh start Medium_Phone Small_Phone    boot these AVDs, wait until ready
#   ./emulators.sh stop                              shut down every running emulator
set -euo pipefail

SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ADB="$SDK/platform-tools/adb"
EMU="$SDK/emulator/emulator"

case "${1:-}" in
  start)
    shift
    [ $# -gt 0 ] || { echo "usage: $0 start <avd>..." >&2; exit 2; }
    for avd in "$@"; do
      "$EMU" -avd "$avd" -no-window -no-audio -no-boot-anim -no-snapshot-save >"/tmp/emu-$avd.log" 2>&1 &
      echo "booting $avd (log /tmp/emu-$avd.log)"
      sleep 5
    done
    deadline=$((SECONDS + 300))
    until [ "$("$ADB" devices | grep -c '^emulator-')" -ge $# ]; do
      [ $SECONDS -lt $deadline ] || { echo "timeout waiting for emulators to appear" >&2; exit 1; }
      sleep 3
    done
    for serial in $("$ADB" devices | awk '/^emulator-/{print $1}'); do
      until [ "$("$ADB" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
        [ $SECONDS -lt $deadline ] || { echo "timeout waiting for $serial" >&2; exit 1; }
        sleep 3
      done
      "$ADB" -s "$serial" shell cmd bluetooth_manager enable >/dev/null 2>&1 || true
      echo "ready: $serial ($("$ADB" -s "$serial" emu avd name 2>/dev/null | head -1 | tr -d '\r'))"
    done
    ;;
  stop)
    for serial in $("$ADB" devices | awk '/^emulator-/{print $1}'); do
      "$ADB" -s "$serial" emu kill >/dev/null 2>&1 || true
      echo "stopped $serial"
    done
    ;;
  *)
    echo "usage: $0 start <avd>... | stop" >&2
    exit 2
    ;;
esac

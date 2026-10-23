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
    netsimds() { pgrep -f 'emulator/netsimd' | wc -l | tr -d ' '; }
    [ "$(netsimds)" = 0 ] || { echo "netsimd already running: stop every emulator and netsimd first" >&2; exit 1; }
    first=1
    for avd in "$@"; do
      # -no-snapshot: cold boot from the AVD's disk and keep every write. (With a quick-boot
      # snapshot plus -no-snapshot-save, stopping the emulator throws away everything since the
      # snapshot, wallets included: lost 5554's and 5556's Phase 2 wallets on 2026-10-21.)
      "$EMU" -avd "$avd" -no-window -no-audio -no-boot-anim -no-snapshot >"/tmp/emu-$avd.log" 2>&1 &
      echo "booting $avd (log /tmp/emu-$avd.log)"
      if [ $first = 1 ]; then
        # The first emulator starts netsimd; the others must join it. Booted too close together,
        # each starts its own netsimd and they land on separate Bluetooth networks (2026-10-23).
        for _ in $(seq 1 60); do [ "$(netsimds)" -ge 1 ] && break; sleep 1; done
        sleep 10
        first=0
      else
        sleep 5
      fi
    done
    deadline=$((SECONDS + 900))   # cold boots took ~7.5 min on 2026-10-23
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
    [ "$(netsimds)" = 1 ] || { echo "WARNING: $(netsimds) netsimd daemons: emulators are on separate Bluetooth networks" >&2; exit 1; }
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

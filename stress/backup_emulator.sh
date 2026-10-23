#!/usr/bin/env bash
# Backs up an emulator's wallet before the emulator is stopped or wiped.
#   1. Seed: restored into a desktop devtool wallet wallets/emu-<avd>-<date> (seed encrypted to
#      a new age identity next to it; view and sync only). Checked: same address as the app.
#   2. App data: tarball of the app's data dir, wallets/emu-backup/<avd>-<date>.tar (wallet DB,
#      relay queue, received payments; the app's own seed copy there is Keystore-bound, so the
#      devtool wallet is the seed backup).
# The mnemonic never touches the Mac's disk in clear and is not printed.
#   ./backup_emulator.sh <serial>
set -euo pipefail
source "$(dirname "$0")/p2/lib.sh"
source "$ROOT/spikes/zcash/env.sh"   # DT
S=$1
AVD=$("$ADB" -s "$S" emu avd name 2>/dev/null | head -1 | tr -d '\r')
TAG="emu-$AVD-$(date +%Y%m%d)"
WD="$ROOT/wallets/$TAG"; IDF="$ROOT/wallets/$TAG.age"; TAR="$ROOT/wallets/emu-backup/$TAG.tar"
mkdir -p "$ROOT/wallets/emu-backup"
launch "$S"
wait_state "$S" "s.get('wallet') == 'ready'" 180
ADDR=$(state "$S" "s['address']"); BDAY=$(state "$S" "s['birthday']")
if [ ! -f "$WD/data.sqlite" ]; then
  "$ADB" -s "$S" shell run-as "$PKG" rm -f files/mnemonic.txt
  hook "$S" DUMP_SEED
  for _ in $(seq 1 20); do [ "$(app_file "$S" mnemonic.txt | wc -w | tr -d ' ')" -ge 12 ] && break; sleep 1; done
  WORDS=$(app_file "$S" mnemonic.txt); "$ADB" -s "$S" shell run-as "$PKG" rm -f files/mnemonic.txt
  [ "$(echo "$WORDS" | wc -w | tr -d ' ')" -ge 12 ] || { echo "$S: no seed from the app" >&2; exit 1; }
  mkdir -p "$WD"
  echo "$WORDS" | "$DT" wallet -w "$WD" restore-mnemonic --name "$TAG" -i "$IDF" --birthday "$BDAY" -n test >/dev/null 2>&1
  unset WORDS
fi
DEVADDR=$("$DT" wallet -w "$WD" list-addresses 2>/dev/null | grep -oE 'utest1[0-9a-z]+' | head -1)
"$ADB" -s "$S" exec-out run-as "$PKG" tar cf - . >"$TAR"
echo "$S ($AVD): seed -> $WD (birthday $BDAY), app data -> $TAR ($(du -h "$TAR" | cut -f1))"
if [ "$DEVADDR" = "$ADDR" ]; then echo "  address matches the app: ${ADDR:0:24}…"; else echo "  ADDRESS MISMATCH: app ${ADDR:0:24}… devtool ${DEVADDR:0:24}…" >&2; exit 1; fi

#!/usr/bin/env bash
# P2.G2.T3: the app's shielded balance equals zcash-devtool's for the same seed, to the
# zatoshi. Restores the phone's seed into a desktop devtool wallet (wallets/emu-<serial>,
# git-ignored; view and sync only, it never spends), syncs both to the tip and compares.
#   ./p2g2t3_vs_devtool.sh <serial>
set -euo pipefail
source "$(dirname "$0")/lib.sh"
source "$ROOT/spikes/zcash/env.sh"   # DT
S=$1
LOG="$ROOT/stress/p2/logs/p2g2t3-$S.log"
WD="$ROOT/wallets/emu-$S"; IDF="$ROOT/wallets/emu-$S.age"
launch "$S"; wait_synced "$S" 900
if [ ! -f "$WD/data.sqlite" ]; then
  hook "$S" DUMP_SEED; sleep 2
  WORDS=$(app_file "$S" mnemonic.txt); "$ADB" -s "$S" shell run-as "$PKG" rm -f files/mnemonic.txt
  BDAY=$(state "$S" "s['birthday']")
  mkdir -p "$WD"
  echo "$WORDS" | "$DT" wallet -w "$WD" restore-mnemonic --name "emu-$S" -i "$IDF" --birthday "$BDAY" -n test >/dev/null 2>&1
fi
ACCT=$("$DT" wallet -w "$WD" list-accounts 2>/dev/null | awk '/^Account/{print $2}' | head -1)
# Compare at the same tip: sync both until their heights agree.
for _ in $(seq 1 10); do
  W="$WD" sync_wallet
  wait_synced "$S" 900
  DEV=$("$DT" wallet -w "$WD" balance "$ACCT" --json 2>/dev/null | tail -1)
  [ "$(echo "$DEV" | python3 -c 'import json,sys; print(json.load(sys.stdin)["chain_tip_height"])')" = "$(state "$S" "s['sync']['scanned']")" ] && break
  sleep 10
done
python3 - "$DEV" "$(app_file "$S" state.json)" <<'PY' | tee "$LOG"
import json, sys
dev, st = json.loads(sys.argv[1]), json.loads(sys.argv[2])
sync = st["sync"]
app_total = sync["ironwood_total"] + sync["orchard_total"] + sync["sapling_total"]
print(f"tip: devtool {dev['chain_tip_height']}, app {sync['scanned']}")
print(f"shielded total: devtool {dev['total'] - dev['transparent_spendable']}, app {app_total}")
print(f"ironwood spendable: devtool {dev['ironwood_spendable']}, app {sync['ironwood_available']}")
ok = dev["total"] - dev["transparent_spendable"] == app_total and app_total > 0
print("PASS" if ok else "FAIL")
sys.exit(0 if ok else 1)
PY

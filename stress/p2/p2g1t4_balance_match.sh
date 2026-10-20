#!/usr/bin/env bash
# P2.G1.T4: offline-core opens the SDK's wallet DB (from the :core process) and reads the same
# balance the SDK shows, to the zatoshi. Needs a funded wallet on <serial>.
#   ./p2g1t4_balance_match.sh <serial>
set -euo pipefail
source "$(dirname "$0")/lib.sh"
S=$1
LOG="$ROOT/stress/p2/logs/p2g1t4-$S.log"
launch "$S"
wait_synced "$S" 900 || { echo "not synced" | tee "$LOG"; exit 1; }
"$ADB" -s "$S" shell run-as "$PKG" rm -f files/core_status.json
hook "$S" CORE_STATUS
for _ in $(seq 1 30); do app_file "$S" core_status.json >/dev/null 2>&1 && break; sleep 1; done
CORE=$(app_file "$S" core_status.json)
STATE=$(app_file "$S" state.json)
python3 - "$CORE" "$STATE" <<'PY' | tee "$LOG"
import json, sys
core, st = json.loads(sys.argv[1]), json.loads(sys.argv[2])
acct = next(a for a in core["accounts"] if a["account_uuid"] == st["account"])
sync = st["sync"]
sdk_total = sync["ironwood_total"] + sync["orchard_total"] + sync["sapling_total"]
print(f"tip core={core['chain_tip_height']} sdk={sync['scanned']}")
print(f"total     core={acct['total']} sdk={sdk_total}")
print(f"ironwood spendable core={acct['ironwood_spendable']} sdk={sync['ironwood_available']}")
ok = acct["total"] == sdk_total and acct["total"] > 0 and acct["ironwood_spendable"] == sync["ironwood_available"]
print("PASS" if ok else "FAIL")
sys.exit(0 if ok else 1)
PY

#!/usr/bin/env bash
# P1.G2.T3: for every account, available + pending notes sum to the wallet balance, and every
# available note is mined with a commitment-tree position (so a witness can be built for it).
# Pending = change from a stored payment that is not mined yet (spendable once mined).
# Runs offline-cli with networking denied.
#   ./p1g2t3_notes_match_balance.sh [wallet-dir]
set -euo pipefail
source "$(dirname "$0")/../../spikes/zcash/env.sh"
WALLET=${1:-$W}
CLI="$ROOT/offline-core/target/release/offline-cli"

status=$(nonet "$CLI" -w "$WALLET" status --json)
notes=$(nonet "$CLI" -w "$WALLET" notes --json)
python3 - "$status" "$notes" <<'EOF'
import json, sys
status, notes = json.loads(sys.argv[1]), json.loads(sys.argv[2])
fail = False
for a in status["accounts"]:
    mine = [n for n in notes if n["account_uuid"] == a["account_uuid"]]
    avail = [n for n in mine if n["status"] == "available"]
    pending = [n for n in mine if n["status"] == "pending"]
    total = sum(n["value"] for n in avail + pending)
    no_witness = [n for n in avail if not (n["has_tree_position"] and n["mined_height"])]
    ok = total == a["total"] and not no_witness
    fail |= not ok
    print(f"{'MATCH' if ok else 'DIFF '} {a['name'] or '-':<18} available={len(avail)} pending={len(pending)} sum={total} balance={a['total']}"
          + (f" missing_witness={len(no_witness)}" if no_witness else ""))
print("PASS" if not fail else "FAIL")
sys.exit(1 if fail else 0)
EOF

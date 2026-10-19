#!/usr/bin/env bash
# P1.G1.T2: offline-cli status must equal zcash-devtool's balance for every account, to the zatoshi.
# offline-cli runs inside the no-network sandbox; devtool syncs first so both read the same DB state.
#   ./p1g1t2_status_matches_devtool.sh [wallet-dir]
set -euo pipefail
source "$(dirname "$0")/../../spikes/zcash/env.sh"
WALLET=${1:-$W}
CLI="$ROOT/offline-core/target/debug/offline-cli"
[ -x "$CLI" ] || { echo "build offline-core first" >&2; exit 1; }

"$DT" wallet -w "$WALLET" sync >/dev/null 2>&1
ours=$(nonet "$CLI" -w "$WALLET" -n test status --json)

fail=0
checked=0
for acct in $(echo "$ours" | python3 -c 'import sys, json; [print(a["account_uuid"]) for a in json.load(sys.stdin)["accounts"]]'); do
  theirs=$("$DT" wallet -w "$WALLET" balance "$acct" --json 2>/dev/null | tail -1)
  result=$(python3 - "$acct" "$ours" "$theirs" <<'EOF'
import json, sys
acct, ours, theirs = sys.argv[1], json.loads(sys.argv[2]), json.loads(sys.argv[3])
a = next(x for x in ours["accounts"] if x["account_uuid"] == acct)
fields = ["total", "sapling_spendable", "orchard_spendable", "ironwood_spendable", "transparent_spendable"]
diffs = [f"{f}: ours={a[f]} devtool={theirs[f]}" for f in fields if a[f] != theirs[f]]
if ours["chain_tip_height"] != theirs["chain_tip_height"]:
    diffs.append(f"tip: ours={ours['chain_tip_height']} devtool={theirs['chain_tip_height']}")
name = a["name"] or "-"
print(("MATCH " if not diffs else "DIFF  ") + f"{name:<18} total={a['total']} ironwood={a['ironwood_spendable']}" + ("" if not diffs else "  " + "; ".join(diffs)))
EOF
)
  echo "$result"
  checked=$((checked + 1))
  [[ $result == MATCH* ]] || fail=1
done

[ "$checked" -gt 0 ] || { echo "FAIL: no accounts found"; exit 1; }
if [ $fail = 0 ]; then echo "PASS: $checked accounts match"; else echo "FAIL"; exit 1; fi

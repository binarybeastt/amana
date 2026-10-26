#!/usr/bin/env bash
# "Prepare for offline" on the desktop testnet wallet: split an account's available notes into
# denominations + fee-sized notes (offline-cli plan-split) and send that self-payment online
# with zcash-devtool. Waits until it is mined and the new notes have 3 confirmations (MIN_CONFIRMATIONS).
#   ./prepare_account.sh <account-uuid> <own-address>
set -euo pipefail
source "$(dirname "$0")/../../spikes/zcash/env.sh"
ACCT=$1; ADDR=$2
CLI="$ROOT/offline-core/target/release/offline-cli"
"$DT" wallet -w "$W" sync >/dev/null 2>&1         # sync right before an online build (expiry)
URI=$(nonet "$CLI" -w "$W" plan-split --account "$ACCT" --address "$ADDR" | grep '^zcash:' | tail -1)
[ -n "$URI" ] || { nonet "$CLI" -w "$W" plan-split --account "$ACCT" --address "$ADDR"; exit 1; }
echo "split request: ${URI:0:120}..."
echo y | "$DT" wallet -w "$W" pay -i "$ID" --payment-uri "$URI" "$ACCT" 2>&1 | grep -v INFO | tail -3
start=$(tip)
until nonet "$CLI" -w "$W" notes --json | python3 -c "
import json, sys
notes = [n for n in json.load(sys.stdin) if n['account_uuid'] == '$ACCT']
pend = [n for n in notes if n['status'] == 'pending' or n['mined_height'] is None and n['status'] != 'spent']
sys.exit(1 if pend else 0)"; do
  sleep 30; "$DT" wallet -w "$W" sync >/dev/null 2>&1
done
mined_tip=$(tip)
until [ "$(tip)" -ge $((mined_tip + 2)) ]; do sleep 30; done   # mined_tip itself is the first
"$DT" wallet -w "$W" sync >/dev/null 2>&1
echo "prepared at tip $(tip) (sent at $start)"
nonet "$CLI" -w "$W" notes | awk -v a="${ACCT:0:8}" '$1 == a && $4 == "Available"'

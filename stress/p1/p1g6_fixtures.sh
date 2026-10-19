#!/usr/bin/env bash
# Builds P1.G6 fixtures offline on a COPY of the live wallet (nothing is stored live):
#   memo.raw   A -> D, 0.02 ZEC, memo "bread for Ada"  (T1, T2, T3, T4, T6)
#   anchor.raw A -> the sync-kill wallet, which stopped syncing ~1,800 blocks ago  (T5)
# Writes spikes/runs/p1g6/fixtures.env for tests/receive_live.rs.
set -euo pipefail
source "$(dirname "$0")/../../spikes/zcash/env.sh"
CLI="$ROOT/offline-core/target/release/offline-cli"
OUT="$RUNS/p1g6"
mkdir -p "$OUT/sender"
cp "$W/data.sqlite" "$W/keys.toml" "$OUT/sender/"
SK="$ROOT/wallets/sync-kill"
SK_ADDR=$("$DT" wallet -w "$SK" list-addresses 2>/dev/null | awk '/Default Address/{print $3}' | head -1)
SK_ACCT=$("$DT" wallet -w "$SK" list-accounts 2>/dev/null | awk '/^Account/{print $2}' | head -1)

nonet "$CLI" -w "$OUT/sender" pay --account "$ACCT_A" --to "$ADDR_D" --amount 2000000 \
  --memo "bread for Ada" -i "$ID" --raw-out "$OUT/memo.raw" >"$OUT/memo.json"
nonet "$CLI" -w "$OUT/sender" pay --account "$ACCT_A" --to "$SK_ADDR" --amount 1000000 \
  -i "$ID" --raw-out "$OUT/anchor.raw" >"$OUT/anchor.json"

MEMO_TXID=$(python3 -c "import json; print(json.load(open('$OUT/memo.json'))['txid'])")
# Nullifiers the sender recorded as spent by the memo payment.
SENDER_NFS=$(nonet "$CLI" -w "$OUT/sender" notes --json | python3 -c "
import json, sys
print(','.join(n['nullifier'] for n in json.load(sys.stdin) if n['spent_by'] == '$MEMO_TXID'))")

cat >"$OUT/fixtures.env" <<EOF
RECEIVE_WALLET=$W
RECEIVE_RAW=$OUT/memo.raw
RECEIVE_ACCOUNT=$ACCT_D
RECEIVE_AMOUNT=2000000
RECEIVE_MEMO="bread for Ada"
OTHER_ACCOUNT=$ACCT_B
SENDER_NULLIFIERS=$SENDER_NFS
STALE_WALLET=$SK
STALE_RAW=$OUT/anchor.raw
STALE_ACCOUNT=$SK_ACCT
EOF
cat "$OUT/fixtures.env"

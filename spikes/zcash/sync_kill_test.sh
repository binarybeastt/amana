#!/usr/bin/env bash
# P0.G1.T3: kill -9 a sync midway, restart, and check it reaches the tip with the right balance.
# Uses a throwaway wallet (wallets/sync-kill) with an early birthday so the sync is long enough
# to interrupt, funded with 0.01 TAZ from account A.
set -euo pipefail
source "$(dirname "$0")/env.sh"
SK="$ROOT/wallets/sync-kill"
OUT="$RUNS/sync-kill"
mkdir -p "$OUT"
BIRTHDAY=${BIRTHDAY:-4450000}

if [ ! -f "$SK/data.sqlite" ]; then
  "$DT" wallet -w "$SK" init --name sync-kill -i "$SK.age" -n test --birthday "$BIRTHDAY" >/dev/null 2>&1
fi
# Capture first, then parse: an early-exiting awk would SIGPIPE devtool and trip pipefail.
ADDRS=$("$DT" wallet -w "$SK" list-addresses 2>/dev/null)
ACCTS=$("$DT" wallet -w "$SK" list-accounts 2>/dev/null)
ADDR=$(awk '/Default Address/{print $3}' <<<"$ADDRS" | head -1)
ACCT=$(awk '/^Account/{print $2}' <<<"$ACCTS" | head -1)
echo "throwaway wallet $ACCT address ${ADDR:0:20}…"

# Fund it (online, normal expiry) and wait until mined.
# Reuse an earlier funding tx if this script already sent one.
if ! grep -qE '^[0-9a-f]{64}$' "$OUT/fund.txt" 2>/dev/null; then
  echo y | "$DT" wallet -w "$W" pay "$ACCT_A" -i "$ID" --payment-uri "zcash:$ADDR?amount=0.01" >"$OUT/fund.txt" 2>&1
fi
FUND_TXID=$(grep -E '^[0-9a-f]{64}$' "$OUT/fund.txt" | tail -1)
echo "funding tx $FUND_TXID"
until "$DT" wallet -w "$W" sync >/dev/null 2>&1 && "$DT" wallet -w "$W" list-tx 2>/dev/null | grep -A1 "^$FUND_TXID" | grep Mined >/dev/null; do sleep 30; done
echo "funding mined"

# Interrupt the first sync partway, three times, then let it finish.
for i in 1 2 3; do
  "$DT" wallet -w "$SK" sync >"$OUT/sync-$i.txt" 2>&1 &
  pid=$!
  sleep $((10 + i * 5))
  kill -9 "$pid" 2>/dev/null && echo "killed sync $i (pid $pid)" || echo "sync $i finished before kill"
  wait "$pid" 2>/dev/null || true
done
"$DT" wallet -w "$SK" sync >"$OUT/sync-final.txt" 2>&1
echo "final sync exit=$?"

TIP=$(tip)
BAL=$("$DT" wallet -w "$SK" balance --json 2>/dev/null | tail -1)
echo "chain tip $TIP; wallet: $BAL" | tee "$OUT/result.txt"
python3 - "$TIP" "$BAL" <<'EOF' | tee -a "$OUT/result.txt"
import json, sys
tip, bal = int(sys.argv[1]), json.loads(sys.argv[2])
ok = abs(bal["chain_tip_height"] - tip) <= 2 and bal["total"] == 1_000_000
print(("PASS" if ok else "FAIL") + f": synced={bal['chain_tip_height']} tip={tip} total={bal['total']} (want 1000000)")
EOF

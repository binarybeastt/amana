#!/usr/bin/env bash
# P1.G5.T1-T3: ten payments built back to back in one offline session (networking denied).
#   T1 every build succeeds with no network; T2 every tx has expiry 0 and the ZIP 317 fee for
#   its shape; T3 no note is spent by two of the ten.
# Raw bytes stay stored in the wallet for P1.G5.T5 (broadcast >= 1 h later).
#   ./p1g5_ten_payments.sh [account] [to-address]
set -euo pipefail
source "$(dirname "$0")/../../spikes/zcash/env.sh"
ACCT=${1:-$ACCT_A}
TO=${2:-$ADDR_D}
CLI="$ROOT/offline-core/target/release/offline-cli"
OUT="$RUNS/p1g5"
LOG="$ROOT/stress/p1/logs/p1g5.log"
mkdir -p "$OUT"
# Round amounts in zatoshis: 0.05 0.01 0.1 0.02 0.03 0.07 0.05 0.02 0.01 0.1 ZEC
AMOUNTS=(5000000 1000000 10000000 2000000 3000000 7000000 5000000 2000000 1000000 10000000)

"$DT" wallet -w "$W" sync >/dev/null 2>&1   # online, before the offline session
echo "$(date -u +%FT%TZ) synced at $(tip); offline session starts" | tee "$LOG"
ok=0
for i in "${!AMOUNTS[@]}"; do
  n=$((i + 1))
  if nonet "$CLI" -w "$W" pay --account "$ACCT" --to "$TO" --amount "${AMOUNTS[$i]}" -i "$ID" \
       --raw-out "$OUT/pay$n.raw" >"$OUT/pay$n.json" 2>"$OUT/pay$n.err"; then
    nonet "$CLI" -w "$W" inspect-raw "$OUT/pay$n.raw" >"$OUT/pay$n.inspect.json"
    python3 - "$n" "$OUT/pay$n.json" "$OUT/pay$n.inspect.json" <<'EOF' | tee -a "$LOG"
import json, sys
n, built, info = sys.argv[1], json.load(open(sys.argv[2])), json.load(open(sys.argv[3]))
exact = "exact" if built["change"] == 0 else f"change {built['change']}"
t2 = info["expiry_height"] == 0 and info["fee"] == info["zip317_fee"] == built["fee"]
print(f"pay{n}: {built['amount']} zats, {len(built['spent_notes'])} notes, fee {built['fee']}, {exact}, "
      f"{info['raw_len'] if 'raw_len' in info else built['raw_len']} bytes, expiry {info['expiry_height']} -> T2 {'ok' if t2 else 'FAIL'}")
EOF
    ok=$((ok + 1))
  else
    echo "pay$n: FAILED $(tail -1 "$OUT/pay$n.err")" | tee -a "$LOG"
  fi
done

python3 - "$OUT" <<'EOF' | tee -a "$LOG"
import json, glob, sys
spent = []
fails = 0
for f in sorted(glob.glob(f"{sys.argv[1]}/pay*.json")):
    if f.endswith(".inspect.json"):
        continue
    spent += json.load(open(f))["spent_notes"]
    i = json.load(open(f.replace(".json", ".inspect.json")))
    fails += not (i["expiry_height"] == 0 and i["fee"] == i["zip317_fee"])
dupes = {n for n in spent if spent.count(n) > 1}
print(f"notes spent: {len(spent)}, distinct: {len(set(spent))}, reused: {sorted(dupes) or 'none'}; T2 failures: {fails}")
EOF
echo "built $ok/${#AMOUNTS[@]} offline" | tee -a "$LOG"
nonet "$CLI" -w "$W" pending | tee -a "$LOG"

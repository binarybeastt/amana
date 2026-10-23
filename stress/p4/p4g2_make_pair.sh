#!/usr/bin/env bash
# P4.G2 fixture: a REAL double spend. Two payments from account D (env.sh: "deliberate double
# spend") built offline on two separate copies of the desktop wallet, so offline-core's
# refuse-to-sign-twice guard never sees both: same note selected, same nullifier, different
# txids (different recipient and amount). Nothing is stored in the live wallet and nothing is
# broadcast. Do not run while another job syncs or sends from wallets/testnet-a.
#   ./p4g2_make_pair.sh [out-dir=stress/p4/logs/p4g2-pair]
# Writes tx1.hex / tx2.hex (raw tx as hex, for `inject`) and pair.env (TX1, TX2, SHARED_NF).
set -euo pipefail
source "$(dirname "$0")/../../spikes/zcash/env.sh"
CLI="$ROOT/offline-core/target/release/offline-cli"
OUT="${1:-$ROOT/stress/p4/logs/p4g2-pair}"
rm -rf "$OUT"; mkdir -p "$OUT/w1" "$OUT/w2"
for d in w1 w2; do cp "$W/data.sqlite" "$W/keys.toml" "$OUT/$d/"; done

nonet "$CLI" -w "$OUT/w1" pay --account "$ACCT_D" --to "$ADDR_A" --amount 100000 -i "$ID" --raw-out "$OUT/tx1.raw" >"$OUT/tx1.json"
nonet "$CLI" -w "$OUT/w2" pay --account "$ACCT_D" --to "$ADDR_B" --amount 110000 -i "$ID" --raw-out "$OUT/tx2.raw" >"$OUT/tx2.json"
for t in tx1 tx2; do python3 -c "import sys; print(open(sys.argv[1], 'rb').read().hex())" "$OUT/$t.raw" >"$OUT/$t.hex"; done

TX1=$(python3 -c "import json; print(json.load(open('$OUT/tx1.json'))['txid'])")
TX2=$(python3 -c "import json; print(json.load(open('$OUT/tx2.json'))['txid'])")
nfs() { nonet "$CLI" -w "$OUT/$1" notes --json | python3 -c "
import json, sys
print('\n'.join(sorted(n['nullifier'] for n in json.load(sys.stdin) if n['spent_by'] == '$2')))"; }
SHARED=$(comm -12 <(nfs w1 "$TX1") <(nfs w2 "$TX2") | sed -n 1p)
[ "$TX1" != "$TX2" ] || { echo "FAIL: same txid twice ($TX1)"; exit 1; }
[ -n "$SHARED" ] || { echo "FAIL: the two payments share no note; account D needs one note for both"; exit 1; }
rm -rf "$OUT/w1" "$OUT/w2"   # wallet copies hold keys: never leave them lying around
printf 'TX1=%s\nTX2=%s\nSHARED_NF=%s\n' "$TX1" "$TX2" "$SHARED" | tee "$OUT/pair.env"

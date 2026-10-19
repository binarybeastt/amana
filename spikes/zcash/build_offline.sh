#!/usr/bin/env bash
# Build, prove, sign and combine a payment with networking denied (P0.G3.T1).
#   ./build_offline.sh <label> <account-uuid> <to-address> <zatoshis>
# Syncs first (online) so witnesses are fresh, then does everything else offline.
# Output: spikes/runs/<label>/{created,proven,signed,combined}.pczt, tx.raw, inspect.txt, meta.txt
set -euo pipefail
source "$(dirname "$0")/env.sh"
LABEL=${1:?label}; ACCT=${2:?account}; TO=${3:?address}; ZATS=${4:?zatoshis}
OUT="$RUNS/$LABEL"
mkdir -p "$OUT"

"$DT" wallet -w "$W" sync >/dev/null 2>&1
H=$(tip)
{ echo "label=$LABEL"; echo "account=$ACCT"; echo "value_zats=$ZATS"; echo "build_tip=$H"; echo "build_time=$(date -u +%FT%TZ)"; } >"$OUT/meta.txt"
echo "synced at tip $H; going offline"

nonet "$DT" pczt -w "$W" create "$ACCT" --address "$TO" --value "$ZATS" \
  --expiry-height 0 --ironwood-change --output "$OUT/created.pczt" 2>"$OUT/create.err"
nonet "$DT" pczt -w "$W" inspect <"$OUT/created.pczt" >"$OUT/inspect.txt" 2>&1
nonet "$DT" pczt -w "$W" prove "$OUT/created.pczt" --output "$OUT/proven.pczt" 2>"$OUT/prove.err"
nonet "$DT" pczt -w "$W" sign -i "$ID" "$OUT/created.pczt" --output "$OUT/signed.pczt" 2>"$OUT/sign.err"
nonet "$DT" pczt combine -i "$OUT/signed.pczt" -i "$OUT/proven.pczt" -o "$OUT/combined.pczt" 2>"$OUT/combine.err"
nonet "$DT" pczt extract --output "$OUT/tx.raw" <"$OUT/combined.pczt" >"$OUT/txid.txt" 2>"$OUT/extract.err"
grep -E '^(expiry_height|raw_tx_bytes)=' "$OUT/extract.err" >>"$OUT/meta.txt"

echo "offline_build=ok" >>"$OUT/meta.txt"
echo "txid=$(cat "$OUT/txid.txt")" >>"$OUT/meta.txt"
echo "built $LABEL offline: txid $(cat "$OUT/txid.txt")"
grep -E '^(expiry_height|raw_tx_bytes)=' "$OUT/meta.txt"

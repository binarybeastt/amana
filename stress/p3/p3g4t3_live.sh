#!/usr/bin/env bash
# P3.G4.T3 against the real lightwalletd (extra to the JVM test): a phone holds txs that were
# mined long ago; when it comes online, each broadcast answer ("already in state") must count
# as success: entry settled, at the height the receiving wallet recorded.
#   ./p3g4t3_live.sh <phone> <source-phone-with-settled-received-payments> [count=3]
set -euo pipefail
source "$(dirname "$0")/lib.sh"
S=$1; SRC=$2; N=${3:-3}
LOG="$P3/logs/p3g4t3-live.log"; : >"$LOG"
say() { echo "$(date -u +%T) $*" | tee -a "$LOG"; }
TMP=$(mktemp -d)
net_off "$S"; launch "$S"; hook "$S" RELAY_CLEAR; sleep 3
TXS=(); HS=()  # bash 3.2 (macOS): no associative arrays
for i in $(seq 1 "$N"); do
  f=$("$ADB" -s "$SRC" shell run-as "$PKG" ls files/received | tr -d '\r' | grep '\.json$' | sed -n "${i}p")
  app_file "$SRC" "received/$f" >"$TMP/r.json"
  python3 -c "import json; print(json.load(open('$TMP/r.json'))['raw_hex'])" >"$TMP/$i.hex"
  t=$(python3 -c "import json; print(json.load(open('$TMP/r.json'))['txid'])")
  h=$(python3 -c "import json; print(json.load(open('$TMP/r.json'))['mined_height'])")
  TXS+=("$t"); HS+=("$h")
  say "inject $t (mined at $h per $SRC's wallet): $(inject "$S" "$TMP/$i.hex" 1 | tr -d '\n')"
done
t0=$SECONDS
net_on "$S"
wait_mesh "$S" "len(m['queue']) == $N and all(e['state'] == 'settled' for e in m['queue'])" 120 || true
say "online for $((SECONDS - t0)) s"
ok=True
for k in "${!TXS[@]}"; do
  t=${TXS[$k]}; want=${HS[$k]}
  st=$(entry "$S" "$t" state); h=$(entry "$S" "$t" mined_height); lr=$(entry "$S" "$t" last_result)
  say "$t: state=$st mined_height=$h expected=$want last_result=\"$lr\""
  [ "$st" = settled ] && [ "$h" = "$want" ] && { [[ "$lr" == *"already in state"* ]] || [[ "$lr" == *"committed to the best chain"* ]]; } || ok=False
done
net_off "$S"
rm -rf "$TMP"
[ $ok = True ] && say PASS || { say FAIL; exit 1; }

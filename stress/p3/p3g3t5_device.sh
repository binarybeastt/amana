#!/usr/bin/env bash
# P3.G3.T5 on a device (extra to the JVM test): real tx bytes with one bit flipped go through
# the relay's real verifier (offline-core in :core). Every one must be rejected and never
# stored; a peer in range must never get it. Then the untouched tx is accepted.
#   ./p3g3t5_device.sh <phone> <peer> <raw-hex-file> [flips=20]
set -euo pipefail
source "$(dirname "$0")/lib.sh"
S=$1; PEER=$2; RAW=$3; N=${4:-20}
LOG="$P3/logs/p3g3t5-device.log"; : >"$LOG"
say() { echo "$(date -u +%T) $*" | tee -a "$LOG"; }
TMP=$(mktemp -d)
for s in "$S" "$PEER"; do offline "$s"; launch "$s"; hook "$s" RELAY_CLEAR; done
sleep 3
TXID=""
rejected=0
for i in $(seq 1 "$N"); do
  python3 - "$RAW" "$TMP/bad.hex" "$i" <<'PY'
import sys, random
raw = bytearray(bytes.fromhex(open(sys.argv[1]).read().strip()))
r = random.Random(int(sys.argv[3]))
# Spread over the whole tx: header, actions, proofs, signatures.
bit = r.randrange(len(raw) * 8)
raw[bit // 8] ^= 1 << (bit % 8)
open(sys.argv[2], "w").write(raw.hex())
print(bit // 8, file=sys.stderr)
PY
  r=$(inject "$S" "$TMP/bad.hex" 1)
  st=$(echo "$r" | python3 -c "import json,sys; print(json.load(sys.stdin)['status'])")
  [ "$st" = rejected ] && rejected=$((rejected + 1))
  say "flip $i: $(echo "$r" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d['status'], '-', (d['reason'] or '')[:90])")"
done
sleep 10
n=$(mesh "$S" "len(m['queue'])"); np=$(mesh "$PEER" "len(m['queue'])")
say "rejected $rejected/$N; queue on phone: $n; on peer: $np"
r=$(inject "$S" "$RAW" 1); say "untampered: $r"
ok=True
[ "$rejected" = "$N" ] && [ "$n" = 0 ] && [ "$np" = 0 ] || ok=False
echo "$r" | grep -q '"stored"' || ok=False
rm -rf "$TMP"
[ $ok = True ] && say PASS || { say FAIL; exit 1; }

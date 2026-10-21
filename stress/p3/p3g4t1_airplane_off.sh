#!/usr/bin/env bash
# P3.G4.T1: a phone in airplane mode holds 5 queued, never-broadcast txs; turn airplane mode
# off: all 5 must be broadcast within 30 s (timed from the airplane-off command to the
# phone's "BROADCAST <txid> sent" log line). Then waits for them to be mined.
# The 5 txs are fresh payments built offline on the desktop wallet (offline-cli under the
# no-network sandbox) to <pay-to-phone>'s address, and enter the phone's relay like any
# peer's (proofs verified first).
#   ./p3g4t1_airplane_off.sh <phone> <pay-to-phone> [zats=990000] [accounts: A A A D D]
set -euo pipefail
source "$(dirname "$0")/lib.sh"
source "$ROOT/spikes/zcash/env.sh"
S=$1; TOPH=$2; ZATS=${3:-990000}; shift 3 2>/dev/null || shift $#
ACCTS=("${@:-}"); [ -n "${ACCTS[0]:-}" ] || ACCTS=(A A A D D)
CLI="$ROOT/offline-core/target/release/offline-cli"
LOG="$P3/logs/p3g4t1.log"; : >"$LOG"
RUN="$P3/logs/p3g4t1-txs"; rm -rf "$RUN"; mkdir -p "$RUN"
say() { echo "$(date -u +%T) $*" | tee -a "$LOG"; }
TO=$(state "$TOPH" "s['address']")
say "building ${#ACCTS[@]} payments of $ZATS zats to $TOPH ($TO)"
sync_wallet
TXIDS=()
k=0
for a in "${ACCTS[@]}"; do
  k=$((k + 1)); eval "acct=\$ACCT_$a"
  out=$(nonet "$CLI" -w "$W" pay --account "$acct" --to "$TO" --amount "$ZATS" -i "$ID" --raw-out "$RUN/$k.raw" 2>&1) || { say "pay $k failed: $out"; exit 1; }
  xxd -p "$RUN/$k.raw" | tr -d '\n' >"$RUN/$k.hex"
  t=$(echo "$out" | grep -oE '[0-9a-f]{64}' | head -1)
  TXIDS+=("$t"); say "built $k from account $a: $t ($(wc -c <"$RUN/$k.raw" | tr -d ' ') bytes)"
done
offline "$S"; launch "$S"; hook "$S" RELAY_CLEAR; sleep 3
has_internet "$S" && { say "$S still online"; exit 1; }
for k in $(seq 1 ${#ACCTS[@]}); do say "queue $k: $(inject "$S" "$RUN/$k.hex" 1 | tr -d '\n')"; done
sleep 4
n=$(mesh "$S" "len([e for e in m['queue'] if e['state'] == 'queued'])"); say "queued on $S: $n; mesh sees online=$(mesh "$S" "m['online']")"
[ "$(mesh "$S" "m['online']")" = False ] || { say "app thinks it is online in airplane mode"; exit 1; }
"$ADB" -s "$S" logcat -c
t0=$(now_ms)
online "$S"
seen=""
while :; do
  el=$(( $(now_ms) - t0 ))
  # (|| true: under pipefail a grep with no match yet would end the script)
  seen=$(mesh_log "$S" | grep -oE 'BROADCAST [0-9a-f]{64} (sent|already known)' | awk '{print $2}' | sort -u || true)
  cnt=$(echo "$seen" | grep -c . || true)
  [ "$cnt" -ge ${#TXIDS[@]} ] && break
  [ $el -gt 120000 ] && break
  sleep 0.5
done
el=$(( $(now_ms) - t0 ))
say "broadcast $cnt/${#TXIDS[@]} within $el ms of airplane mode off"
mesh_log "$S" | grep -E 'CONNECTIVITY|BROADCAST|BACKOFF' | tee -a "$LOG"
ok=True
for t in "${TXIDS[@]}"; do echo "$seen" | grep -q "$t" || { say "not broadcast: $t"; ok=False; }; done
[ "$el" -le 30000 ] || ok=False
# Mined? (not part of the pass line; it shows they were real)
wait_mesh "$S" "all(e['state'] == 'settled' for e in m['queue'])" 1800 && \
  say "all mined: $(mesh "$S" "sorted(e['mined_height'] for e in m['queue'])")" || say "not all mined within 30 min"
offline "$S"
[ $ok = True ] && say PASS || { say FAIL; exit 1; }

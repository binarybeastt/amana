#!/usr/bin/env bash
# P1.G5.T4: kill -9 `offline-cli pay` at random points, N runs (default 100), each on a fresh
# copy of the wallet. After each kill the copy must be in exactly one clean state:
#   not stored: the note is available and no new pending payment exists, or
#   stored:     the note is spent-offline and the payment's raw bytes are recoverable
#               (`pending --raw-dir`) with expiry 0.
# Selection locks left by the kill must be fully released by `release-locks`.
#   ./p1g5t4_pay_crash.sh [runs]
set -euo pipefail
source "$(dirname "$0")/../../spikes/zcash/env.sh"
RUNS_N=${1:-100}
CLI="$ROOT/offline-core/target/release/offline-cli"
LOG="$ROOT/stress/p1/logs/p1g5t4.log"
OWNER=6f66666c696e652d636f72653a73656c656374696f6e2d6c6f636b3a76310000
NOTE_TXID=efe6362d4361917323098b416a15328222409d40dc9522d7725962f2eedd8d71   # B's 0.0999 note
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

note_status() {
  "$CLI" -w "$WORK" notes --json | python3 -c "
import json, sys
print(next(n['status'] for n in json.load(sys.stdin) if n['txid'] == '$NOTE_TXID' and n['output_index'] == 0))"
}

cp "$W/data.sqlite" "$W/keys.toml" "$WORK/"
BASE_PENDING=$("$CLI" -w "$WORK" pending | wc -l | tr -d ' ')
echo "$(date -u +%FT%TZ) runs=$RUNS_N base_pending=$BASE_PENDING note_status=$(note_status)" >"$LOG"

stored=0; not_stored=0; locks_seen=0; fail=0
for i in $(seq 1 "$RUNS_N"); do
  rm -f "$WORK"/data.sqlite* "$WORK"/*.raw; rm -rf "$WORK/raw"
  cp "$W/data.sqlite" "$WORK/data.sqlite"
  # Every 10th run aims at the brief window while selection locks are held; the rest at the
  # end of the build, where the payment is stored (a full pay takes ~3.7 s).
  if [ $((i % 10)) = 1 ]; then lo=0.02; hi=0.4; else lo=2.5; hi=5.5; fi
  delay=$(python3 -c "import random; print(f'{random.uniform($lo, $hi):.3f}')")
  # Call sandbox-exec directly, not the `nonet` shell function: backgrounding a function forks
  # a subshell, so $! would be the subshell and kill -9 would leave the real pay running.
  # sandbox-exec execs offline-cli, so $! is the process that does the work.
  sandbox-exec -p '(version 1)(allow default)(deny network*)' \
    "$CLI" -w "$WORK" pay --account "$ACCT_B" --to "$ADDR_A" --amount 5000000 -i "$ID" \
    --raw-out "$WORK/out.raw" >/dev/null 2>&1 &
  pid=$!
  sleep "$delay"
  kill -9 "$pid" 2>/dev/null || true
  wait "$pid" 2>/dev/null || true
  if pgrep -f "offline-cli -w $WORK pay" >/dev/null; then
    echo "run=$i a pay process survived the kill; test harness is broken" | tee -a "$LOG"
    exit 2
  fi

  verdict=ok
  integrity=$(sqlite3 "$WORK/data.sqlite" "PRAGMA integrity_check" 2>&1)
  [ "$integrity" = ok ] || verdict="integrity:$integrity"
  locks=$(sqlite3 "$WORK/data.sqlite" "SELECT COUNT(*) FROM ironwood_received_notes WHERE lower(hex(lock_owner)) = '$OWNER'")
  [ "$locks" -gt 0 ] && locks_seen=$((locks_seen + 1))
  "$CLI" -w "$WORK" release-locks >/dev/null
  left=$(sqlite3 "$WORK/data.sqlite" "SELECT COUNT(*) FROM ironwood_received_notes WHERE lower(hex(lock_owner)) = '$OWNER' AND lock_expiry_height IS NOT NULL")
  [ "$left" = 0 ] || verdict="locks-left:$left"

  status=$(note_status)
  pending=$("$CLI" -w "$WORK" pending --raw-dir "$WORK/raw" | wc -l | tr -d ' ')
  if [ "$status" = available ] && [ "$pending" = "$BASE_PENDING" ]; then
    not_stored=$((not_stored + 1))
  elif [ "$status" = spent-offline ] && [ "$pending" = $((BASE_PENDING + 1)) ]; then
    stored=$((stored + 1))
    # The new payment's bytes must be recoverable and well formed.
    newest=$(ls -t "$WORK"/raw/*.raw | head -1)
    expiry=$("$CLI" -w "$WORK" inspect-raw "$newest" | python3 -c "import json,sys; print(json.load(sys.stdin)['expiry_height'])")
    [ "$expiry" = 0 ] || verdict="recovered-expiry:$expiry"
  else
    verdict="inconsistent status=$status pending=$pending"
  fi
  if [ "$verdict" != ok ]; then
    fail=$((fail + 1))
    # Keep the evidence: the wallet copy and what the extra pending payment spends.
    keep="$ROOT/stress/p1/failures/p1g5t4-run$i"
    mkdir -p "$keep"
    cp "$WORK"/data.sqlite* "$keep/" 2>/dev/null || true
    "$CLI" -w "$WORK" notes >"$keep/notes.txt" 2>&1 || true
    "$CLI" -w "$WORK" pending --raw-dir "$keep/raw" >"$keep/pending.txt" 2>&1 || true
  fi
  echo "run=$i delay=$delay locks_after_kill=$locks status=$status pending=$pending verdict=$verdict" >>"$LOG"
  if [ $((i % 20)) = 0 ]; then echo "$i/$RUNS_N stored=$stored not_stored=$not_stored locks_seen=$locks_seen failures=$fail"; fi
done
summary="runs=$RUNS_N stored=$stored not_stored=$not_stored kills_that_left_locks=$locks_seen failures=$fail"
echo "$summary" | tee -a "$LOG"
if [ $fail = 0 ] && [ $stored -gt 0 ] && [ $not_stored -gt 0 ]; then echo PASS; else echo FAIL; exit 1; fi

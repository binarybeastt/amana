#!/usr/bin/env bash
# P1.G2.T2: kill -9 the writer (store-pczt) at random points, N runs (default 1000).
# Each run uses a fresh copy of the wallet DB. After every kill the DB must open, pass
# PRAGMA integrity_check, hold exactly the notes of the before- or after-store state, and the
# store must be all-or-nothing:
# the payment's tx row and its note-spend row exist together or not at all.
#   ./p1g2t2_store_crash.sh [runs] [pczt]
set -euo pipefail
source "$(dirname "$0")/../../spikes/zcash/env.sh"
RUNS_N=${1:-1000}
PCZT=${2:-$RUNS/C-24h/combined.pczt}
CLI="$ROOT/offline-core/target/release/offline-cli"
SRC="$W/data.sqlite"
LOG="$ROOT/stress/p1/logs/p1g2t2.log"
[ -x "$CLI" ] || { echo "build offline-core --release first" >&2; exit 1; }

TXID=$(nonet "$DT" pczt extract <"$PCZT" 2>/dev/null)
TXID_INTERNAL=$(python3 -c "import sys; print(bytes.fromhex(sys.argv[1])[::-1].hex())" "$TXID")
BASE_NOTES=$(sqlite3 "$SRC" "SELECT COUNT(*) FROM ironwood_received_notes")
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

# Reference: one uninterrupted store. A committed store legitimately adds the payment's own
# change note, so "after" has its own expected note count.
cp "$SRC" "$WORK/data.sqlite"
"$CLI" -w "$WORK" store-pczt "$PCZT" >/dev/null
AFTER_NOTES=$(sqlite3 "$WORK/data.sqlite" "SELECT COUNT(*) FROM ironwood_received_notes")

committed=0; not_committed=0; fail=0
echo "$(date -u +%FT%TZ) runs=$RUNS_N txid=$TXID notes_before=$BASE_NOTES notes_after_store=$AFTER_NOTES" >"$LOG"
for i in $(seq 1 "$RUNS_N"); do
  rm -f "$WORK"/data.sqlite*
  cp "$SRC" "$WORK/data.sqlite"
  delay=$(python3 -c "import random; print(f'{random.uniform(0.5, 1.4):.3f}')")
  "$CLI" -w "$WORK" store-pczt "$PCZT" >/dev/null 2>&1 &
  pid=$!
  sleep "$delay"
  kill -9 "$pid" 2>/dev/null || true
  wait "$pid" 2>/dev/null || true

  integrity=$(sqlite3 "$WORK/data.sqlite" "PRAGMA integrity_check" 2>&1)
  notes=$(sqlite3 "$WORK/data.sqlite" "SELECT COUNT(*) FROM ironwood_received_notes")
  tx=$(sqlite3 "$WORK/data.sqlite" "SELECT COUNT(*) FROM transactions WHERE txid = X'$TXID_INTERNAL'")
  spend=$(sqlite3 "$WORK/data.sqlite" "SELECT COUNT(*) FROM ironwood_received_note_spends s JOIN transactions t ON t.id_tx = s.transaction_id WHERE t.txid = X'$TXID_INTERNAL'")
  opens=ok; "$CLI" -w "$WORK" notes >/dev/null 2>&1 || opens=FAIL

  verdict=ok
  [ "$integrity" = ok ] || verdict="integrity:$integrity"
  if [ "$tx" = 1 ]; then want=$AFTER_NOTES; else want=$BASE_NOTES; fi
  [ "$notes" = "$want" ] || verdict="notes:$notes(want $want)"
  [ "$opens" = ok ] || verdict="open-failed"
  if [ "$tx" = 1 ] && [ "$spend" = 1 ]; then committed=$((committed + 1))
  elif [ "$tx" = 0 ] && [ "$spend" = 0 ]; then not_committed=$((not_committed + 1))
  else verdict="partial-store tx=$tx spend=$spend"; fi

  if [ "$verdict" != ok ]; then fail=$((fail + 1)); fi
  echo "run=$i delay=$delay tx=$tx spend=$spend verdict=$verdict" >>"$LOG"
  if [ $((i % 100)) = 0 ]; then echo "$i/$RUNS_N: committed=$committed not_committed=$not_committed failures=$fail"; fi
done

summary="runs=$RUNS_N committed=$committed not_committed=$not_committed failures=$fail"
echo "$summary" | tee -a "$LOG"
# Both outcomes must occur, or the kills were not landing inside the write.
if [ $fail = 0 ] && [ $committed -gt 0 ] && [ $not_committed -gt 0 ]; then echo PASS; else echo FAIL; exit 1; fi

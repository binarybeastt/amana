#!/usr/bin/env bash
# Re-runs every Phase 1 Auto test in order (a regression after changing offline-core's
# dependencies). Steps that write to a wallet use copies, except Gate P1 (real broadcasts).
#   ./regress.sh [tag]      logs go to stress/p1/logs/regress-<tag>/
# Run with the live wallet idle: nothing else may sync it meanwhile.
set -euo pipefail
source "$(dirname "$0")/../../spikes/zcash/env.sh"
TAG=${1:-$(date +%Y%m%d-%H%M)}
OUT="$ROOT/stress/p1/logs/regress-$TAG"
mkdir -p "$OUT"
SUMMARY="$OUT/summary.txt"
P1="$ROOT/stress/p1"
step() {  # step <name> <command...>: run, keep its log, record pass/fail
  local name=$1; shift
  local t0=$SECONDS
  if "$@" >"$OUT/$name.out" 2>&1; then r=pass; else r="FAIL($?)"; fi
  echo "$(date -u +%FT%TZ) $name $r $((SECONDS - t0))s" | tee -a "$SUMMARY"
}
echo "$(date -u +%FT%TZ) Phase 1 regression '$TAG' (offline-core $(cd "$ROOT/offline-core" && git rev-parse --short HEAD)+worktree)" | tee "$SUMMARY"

step build      bash -c "cd '$ROOT/offline-core' && cargo build --release"
step unit-tests bash -c "cd '$ROOT/offline-core' && cargo test --release"     # P1.G2.T1, G3.T1-T2, G4.T1-T4, G1
step clippy     bash -c "cd '$ROOT/offline-core' && cargo clippy --all-targets -- -D warnings"
step p1g1t2     bash "$P1/p1g1t2_status_matches_devtool.sh"                   # syncs the live wallet first
step p1g2t3     bash "$P1/p1g2t3_notes_match_balance.sh"
step p1g6-fix   bash "$P1/p1g6_fixtures.sh"
step p1g6       bash -c "set -a; source '$RUNS/p1g6/fixtures.env'; set +a; cd '$ROOT/offline-core' && cargo test --release --test receive_live -- --ignored --nocapture"
# P1.G5.T1-T3 on a copy, so the ten payments do not lock live notes.
# Account D must be prepared first (prepare_account.sh). Amounts: Phase 1's seven round amounts,
# then three fee-note-sized ones (as Phase 1 did once pending change had locked the rest).
COPY="$OUT/wallet-copy"; rm -rf "$COPY"; cp -R "$W" "$COPY"
step p1g5       env WALLET_DIR="$COPY" bash "$P1/p1g5_ten_payments.sh" "$ACCT_D" "$ADDR_A" \
                "5000000 1000000 10000000 2000000 3000000 7000000 5000000 10000 20000 25000"
rm -rf "$COPY"
step p1g2t2     bash "$P1/p1g2t2_store_crash.sh" "${STORE_RUNS:-1000}"
step p1g5t4     bash "$P1/p1g5t4_pay_crash.sh" "${PAY_RUNS:-100}"
step gate-p1    bash "$P1/e2e.sh" 3 5 1000000
# The scripts write their own logs into stress/p1/logs; keep this run's copies together.
for f in p1g1t2 p1g2t2 p1g2t3 p1g5 p1g5t4 p1g6 gate-p1; do
  [ -f "$ROOT/stress/p1/logs/$f.log" ] && cp "$ROOT/stress/p1/logs/$f.log" "$OUT/" || true
done
grep -c " pass " "$SUMMARY" | xargs echo "passed steps:" | tee -a "$SUMMARY"
! grep -q "FAIL" "$SUMMARY"

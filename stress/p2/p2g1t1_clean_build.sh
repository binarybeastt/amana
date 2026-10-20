#!/usr/bin/env bash
# P2.G1.T1: `./gradlew assembleDebug` from a clean clone builds.
# Clones the repo's committed HEAD (plus, with --worktree, uncommitted changes) into an empty
# temp dir and builds there. Needs Rust + cargo-ndk + an Android NDK, and a JDK 17-21.
#   ./p2g1t1_clean_build.sh [--worktree]
set -euo pipefail
source "$(dirname "$0")/lib.sh"
LOG="$ROOT/stress/p2/logs/p2g1t1.log"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
git clone -q "$ROOT" "$WORK/repo"
if [ "${1:-}" = --worktree ]; then
  # Uncommitted work too (tracked changes and new files, minus ignored ones).
  (cd "$ROOT" && git ls-files -mo --exclude-standard -z) | (cd "$ROOT" && xargs -0 -I{} rsync -R {} "$WORK/repo/")
fi
echo "sdk.dir=$SDK" >"$WORK/repo/android/local.properties"
t0=$SECONDS
echo "$(date -u +%FT%TZ) clean build in $WORK/repo (${1:-HEAD})" | tee "$LOG"
if (cd "$WORK/repo/android" && ./gradlew --no-daemon -q assembleDebug) >>"$LOG" 2>&1; then
  apk="$WORK/repo/android/app/build/outputs/apk/debug/app-debug.apk"
  so=$(unzip -l "$apk" | grep -c "lib/arm64-v8a/liboffline_core.so" || true)
  echo "BUILD OK in $((SECONDS - t0))s: $(du -h "$apk" | cut -f1) apk, liboffline_core.so in apk: $so" | tee -a "$LOG"
  [ "$so" = 1 ] && echo PASS | tee -a "$LOG"
else
  echo "BUILD FAILED after $((SECONDS - t0))s" | tee -a "$LOG"; tail -30 "$LOG"; echo FAIL; exit 1
fi

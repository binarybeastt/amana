#!/usr/bin/env bash
# P2.G1.T2 + T3: instrumented tests calling offline-core across the bridge (version string;
# Rust error, Rust panic, core-process death and bad input all arrive as exceptions; the app
# keeps running). Runs on every serial given.
#   ./p2g1_bridge.sh emulator-5554 [emulator-5556 ...]
set -euo pipefail
source "$(dirname "$0")/lib.sh"
LOG="$ROOT/stress/p2/logs/p2g1-bridge.log"
echo "$(date -u +%FT%TZ) bridge tests on $*" | tee "$LOG"
fail=0
for s in "$@"; do
  if (cd "$ROOT/android" && ANDROID_SERIAL=$s ./gradlew -q connectedDebugAndroidTest \
        -Pandroid.testInstrumentationRunnerArguments.class=dev.offlinezec.wallet.CoreBridgeTest) >>"$LOG" 2>&1; then r=pass; else r=FAIL; fail=1; fi
  python3 - "$ROOT/android/app/build/outputs/androidTest-results/connected/debug" "$s" "$r" <<'PY' | tee -a "$LOG"
import glob, re, sys
x = "".join(open(f).read() for f in glob.glob(sys.argv[1] + "/*.xml"))
cases = re.findall(r'<testcase name="([^"]+)"[^>]*time="([^"]+)"', x)
fails = re.findall(r'<testcase name="([^"]+)"[^>]*>\s*<failure', x)
print(f"{sys.argv[2]}: {sys.argv[3]} ({len(cases)} tests, {len(fails)} failed)")
for n, t in cases:
    print(f"  {'FAIL' if n in fails else 'ok  '} {n} {t}s")
PY
done
[ $fail = 0 ] && echo PASS | tee -a "$LOG" || { echo FAIL | tee -a "$LOG"; exit 1; }

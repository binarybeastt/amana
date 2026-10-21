#!/usr/bin/env bash
# Phase 3 Auto tests on the JVM (no devices): P3.G1.T1 (WireTest), P3.G2.T3 (ProtoTest),
# P3.G3.T1/T2/T4/T5 (RelayTest), P3.G4.T2/T3 (BroadcasterTest). Re-runnable.
#   ./unit.sh            log in stress/p3/logs/unit.log
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
export JAVA_HOME="${JAVA_HOME_21:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
LOG="$ROOT/stress/p3/logs/unit.log"
cd "$ROOT/android"
{
  echo "$(date -u +%FT%TZ) Phase 3 unit tests"
  ./gradlew --console=plain testDebugUnitTest --tests 'dev.offlinezec.wallet.mesh.*' --rerun-tasks -q 2>&1 | grep -v '^w: ' || true
  python3 - <<'PY'
import glob, re, sys
bad = 0
for f in sorted(glob.glob('app/build/test-results/testDebugUnitTest/TEST-dev.offlinezec.wallet.mesh.*.xml')):
    x = open(f).read()
    suite = re.search(r'<testsuite name="([^"]+)" tests="(\d+)" skipped="\d+" failures="(\d+)" errors="(\d+)"', x)
    print(f"{suite.group(1)}: {suite.group(2)} tests, {suite.group(3)} failures, {suite.group(4)} errors")
    for c in re.finditer(r'<testcase name="([^"]+)"[^>]*time="([^"]+)"(/?)>', x):
        failed = c.group(3) == '' and '<failure' in x[c.end():x.find('</testcase>', c.end())]
        print(f"  {'FAIL' if failed else 'ok  '} {c.group(1)} ({c.group(2)} s)")
    bad += int(suite.group(3)) + int(suite.group(4))
print("PASS" if bad == 0 else f"FAIL ({bad})")
sys.exit(1 if bad else 0)
PY
} 2>&1 | tee "$LOG"

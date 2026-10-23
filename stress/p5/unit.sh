#!/usr/bin/env bash
# Phase 5 Auto tests on the JVM (no devices): P5.G1.T1 every payment state renders and none is
# unreachable, P5.G2.T1 available + locked + pending = wallet total in each state
# (PaymentModelTest), P5.G3 warning logic (SeedWarningTest); all other JVM tests as regression.
#   ./unit.sh            log in stress/p5/logs/unit.log
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
export JAVA_HOME="${JAVA_HOME_21:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
LOG="$ROOT/stress/p5/logs/unit.log"
cd "$ROOT/android"
{
  echo "$(date -u +%FT%TZ) Phase 5 unit tests"
  ./gradlew --console=plain testDebugUnitTest --rerun-tasks -q 2>&1 | grep -v '^w: ' || true
  python3 - <<'PY'
import glob, re, sys
bad = 0
files = glob.glob('app/build/test-results/testDebugUnitTest/TEST-*.xml')
for need in ('PaymentModelTest', 'SeedWarningTest'):
    if not any(need in f for f in files):
        print(f"missing {need} results"); bad += 1
total = 0
for f in sorted(files):
    x = open(f).read()
    m = re.search(r'<testsuite name="([^"]+)" tests="(\d+)" skipped="(\d+)" failures="(\d+)" errors="(\d+)"', x)
    name, t, s, fl, e = m.group(1), *map(int, m.groups()[1:])
    total += t; bad += fl + e
    print(f"{name.split('.')[-1]}: {t - fl - e}/{t}")
print(f"{'PASS' if bad == 0 else 'FAIL'} ({total} tests)")
sys.exit(1 if bad else 0)
PY
} 2>&1 | tee "$LOG"

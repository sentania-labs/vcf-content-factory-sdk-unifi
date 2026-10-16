#!/usr/bin/env bash
# Compiles and runs the adapter's plain-main Java tests (no test framework,
# no external deps beyond a JDK), then the Python checks (stdlib only).
# Exits non-zero on any compile error or failed assertion. Run from
# anywhere:
#   ci/run_java_tests.sh
# The tested classes have no VCF Ops SDK or framework-jar dependency, so
# neither jar is needed. Added in build 14; the tag workflow does not run
# it yet (CI wiring is fork issue #28).
set -euo pipefail
cd "$(dirname "$0")/.."
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
javac -d "$out" -sourcepath src:tests \
    tests/com/vcfcf/adapters/unifi/*.java
# Build 15: every *Test.java and every tests/test_*.py runs, so a new test
# cannot be added and silently skipped.
for f in tests/com/vcfcf/adapters/unifi/*Test.java; do
    t="$(basename "$f" .java)"
    java -cp "$out" "com.vcfcf.adapters.unifi.$t"
done
for p in tests/test_*.py; do
    python3 "$p"
done

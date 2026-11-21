#!/usr/bin/env bash
# Builds FRM together with the real CCM and runs the FRM-CCM integration checks for PRD
# scenarios 6, 7, and 9 (CcmIntegrationTests). SM is FRM's MockStorageManager.
# Exit code 0 only if every check passes.
set -euo pipefail

FRM_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if command -v cygpath >/dev/null 2>&1; then
    FRM_DIR="$(cygpath -m "$FRM_DIR")" # Git Bash: javac reads D:/... paths, not /d/...
fi
ROOT="$(dirname "$FRM_DIR")"
OUT="$FRM_DIR/out-integration"

rm -rf "$OUT"
mkdir -p "$OUT"
# Quote every path in the javac argument file, since the repository path may contain spaces.
find "$ROOT/shared/src/main/java" "$ROOT/concurrency-control/src/main/java" \
    "$FRM_DIR/src/main/java" "$FRM_DIR/src/integration/java" -name '*.java' \
    | sed 's/.*/"&"/' > "$OUT/sources.txt"
javac -encoding UTF-8 --release 21 -d "$OUT" @"$OUT/sources.txt"

java -cp "$OUT" sqlike.frm.integration.CcmIntegrationTests

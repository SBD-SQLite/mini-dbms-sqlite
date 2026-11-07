#!/usr/bin/env bash
# Builds FRM against the root shared types and runs the checkpoint, point-in-time recovery, and
# maintenance service checks (MaintenanceTests). Exit code 0 only if every check passes.
set -euo pipefail

FRM_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(dirname "$FRM_DIR")"
OUT="${OUT:-$FRM_DIR/out}"

rm -rf "$OUT"
mkdir -p "$OUT"
find "$ROOT/shared/src/main/java" "$FRM_DIR/src/main/java" "$FRM_DIR/src/test/java" -name '*.java' > "$OUT/sources.txt"
javac -encoding UTF-8 --release 17 -d "$OUT" @"$OUT/sources.txt"

java -cp "$OUT" sqlike.frm.MaintenanceTests

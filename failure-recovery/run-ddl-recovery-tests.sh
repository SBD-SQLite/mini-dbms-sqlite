#!/usr/bin/env bash
# Builds FRM against the root shared types and runs the DDL crash recovery, DDL PITR, and PITR resume checks.
set -euo pipefail

FRM_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(dirname "$FRM_DIR")"
OUT="${OUT:-$FRM_DIR/out}"

rm -rf "$OUT"
mkdir -p "$OUT"
find "$ROOT/shared/src/main/java" "$FRM_DIR/src/main/java" "$FRM_DIR/src/test/java" -name '*.java' > "$OUT/sources.txt"
javac -encoding UTF-8 --release 17 -d "$OUT" @"$OUT/sources.txt"

java -cp "$OUT" sqlike.frm.DdlRecoveryTests

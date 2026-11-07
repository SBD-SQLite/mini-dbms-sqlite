#!/usr/bin/env bash
# Builds FRM against the root shared types and runs the FRM driver (FR-09).
# Usage: failure-recovery/run-driver.sh [--list | --show-log SCENARIO [TXID] | scenario ...]
set -euo pipefail

FRM_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(dirname "$FRM_DIR")"
OUT="$FRM_DIR/out"

rm -rf "$OUT"
mkdir -p "$OUT"
find "$ROOT/shared/src/main/java" "$FRM_DIR/src/main/java" -name '*.java' > "$OUT/sources.txt"
javac -encoding UTF-8 -d "$OUT" @"$OUT/sources.txt"

java -cp "$OUT" sqlike.frm.driver.FRMDriver --data-dir "$FRM_DIR/frm-data" "$@"

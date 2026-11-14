#!/usr/bin/env bash
# Jalankan dengan JDK 21 sesuai pom.xml root.
set -euo pipefail

frm_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
build_dir="$(mktemp -d "${TMPDIR:-/tmp}/sqlike-fr2-build.XXXXXX")"

trap 'rm -rf "$build_dir"' EXIT

sources=()

while IFS= read -r -d '' source; do
    sources+=("$source")
done < <(
    find \
        "$frm_dir/../shared/src/main/java" \
        "$frm_dir/src/main/java" \
        "$frm_dir/src/test/java/sqlike/frm/log" \
        -name '*.java' -print0
)

javac -encoding UTF-8 \
    -d "$build_dir/classes" \
    "${sources[@]}"

java -cp "$build_dir/classes" sqlike.frm.log.WalTests "$@"
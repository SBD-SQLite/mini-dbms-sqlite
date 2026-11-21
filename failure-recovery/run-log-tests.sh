#!/usr/bin/env bash
# Jalankan dengan JDK 21 sesuai pom.xml root.
set -euo pipefail

frm_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
build_dir="$(mktemp -d "${TMPDIR:-/tmp}/sqlike-fr2-build.XXXXXX")"

trap 'rm -rf "$build_dir"' EXIT

test_dir="$frm_dir/src/test/java"
javac --release 21 -encoding UTF-8 \
    -sourcepath "$frm_dir/../shared/src/main/java:$frm_dir/src/main/java:$test_dir" \
    -d "$build_dir/classes" \
    "$test_dir/sqlike/frm/log/WalTests.java"

java -cp "$build_dir/classes" sqlike.frm.log.WalTests "$@"

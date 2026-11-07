#!/usr/bin/env bash
set -euo pipefail

ccm_root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ccm_build="$(mktemp -d "${TMPDIR:-/tmp}/sqlike-ccm-tests.XXXXXX")"
trap 'rm -rf "$ccm_build"' EXIT

sources=()
while IFS= read -r -d '' source; do
    sources+=("$source")
done < <(find "$ccm_root/../shared/src/main/java" "$ccm_root/src/main/java" "$ccm_root/src/test/java" -name '*.java' -print0)

javac --release 17 -d "$ccm_build/classes" "${sources[@]}"
for test_file in "$ccm_root"/src/test/java/sqlike/ccm/*Test.java; do
    java -cp "$ccm_build/classes" "sqlike.ccm.$(basename "$test_file" .java)"
done
java -cp "$ccm_build/classes" sqlike.ccm.CCMDriver

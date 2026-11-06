#!/usr/bin/env bash
set -euo pipefail

ccm_root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ccm_build="$(mktemp -d "${TMPDIR:-/tmp}/sqlike-ccm-tests.XXXXXX")"
trap 'rm -rf "$ccm_build"' EXIT

sources=()
while IFS= read -r -d '' source; do
    sources+=("$source")
done < <(find "$ccm_root/src/main/java" "$ccm_root/src/test/java" -name '*.java' -print0)

javac --release 17 -d "$ccm_build/classes" "${sources[@]}"
java -cp "$ccm_build/classes" sqlike.ccm.LockBasedProtocolTest
java -cp "$ccm_build/classes" sqlike.ccm.CCMDriver

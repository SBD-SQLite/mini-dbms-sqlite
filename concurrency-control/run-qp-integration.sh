#!/usr/bin/env bash
# Integration driver: real QP ConcurrencyAdapter on top of the real CCM and the real FRM.
# FRM's own MockStorageManager is the data store; no component is mocked except SM.
#
# Every module ships its own copy of sqlike.contract (and FRM also of sqlike.storage and
# sqlike.shared.SchemaChange/SchemaImage). They cannot be compiled together, so the script takes
# the QP copy of sqlike.contract and sqlike.storage and the shared module for the rest.
set -euo pipefail

ccm_root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$ccm_root/.." && pwd)"
build="$(mktemp -d "${TMPDIR:-/tmp}/sqlike-ccm-qp-XXXXXX")"
trap 'rm -rf "$build"' EXIT

sources=()
while IFS= read -r -d '' source; do
    sources+=("$source")
done < <(
    {
        find "$repo_root/shared/src/main/java" "$repo_root/query-processor/src/main/java" -name '*.java' -print0
        find "$repo_root/query-optimizer/src/main/java" "$ccm_root/src/main/java" \
            -name '*.java' -not -path '*/sqlike/contract/*' -print0
        find "$repo_root/failure-recovery/src/main/java" -name '*.java' \
            -not -path '*/sqlike/contract/*' -not -path '*/sqlike/storage/*' -not -path '*/sqlike/shared/*' -print0
        find "$ccm_root/src/integration/java" -name '*.java' -print0
    }
)

javac --release 21 -encoding UTF-8 -d "$build" "${sources[@]}"
java -cp "$build" sqlike.ccm.QpIntegrationDriver

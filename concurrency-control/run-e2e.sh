#!/usr/bin/env bash
# End-to-end test of CCM through the real server: QP, QO, CCM, SM, and FRM run as one process, and every
# client is a real CLI process over TCP. Covers kill -9 recovery, deadlocks of two and three clients, upgrade
# deadlocks, timestamp violations, disconnects, DDL rollback, and random transfers with a crash in the middle,
# under both protocols. Needs JDK 21 and python3 (standard library only).
# Usage: ./concurrency-control/run-e2e.sh [name filter, such as EE-02]
set -euo pipefail

ccm_root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$ccm_root/.." && pwd)"
build="$(mktemp -d "${TMPDIR:-/tmp}/sqlike-e2e-build-XXXXXX")"
trap 'rm -rf "$build"' EXIT

{
    find "$repo_root/shared/src/main/java" -name '*.java'
    find "$repo_root/storage-manager/src/main/java" "$repo_root/query-optimizer/src/main/java/sqlike/qo" \
        "$ccm_root/src/main/java/sqlike/ccm" "$repo_root/failure-recovery/src/main/java/sqlike/frm" \
        "$repo_root/query-processor/src/main/java" -name '*.java' \
        ! -path '*/sqlike/contract/*' ! -name 'RollbackDDLTests.java' ! -name 'RollbackE2EIntegrationTests.java'
} > "$build/sources.txt"

javac --release 21 -encoding UTF-8 -d "$build/classes" @"$build/sources.txt"
SQLIKE_CLASSES="$build/classes" python3 "$ccm_root/e2e/cases.py" "$@"

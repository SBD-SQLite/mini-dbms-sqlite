#!/usr/bin/env bash
# Integration driver: real QP ConcurrencyAdapter on top of the real CCM and the real FRM.
# FRM's own MockStorageManager is the data store; no component is mocked except SM.
#
# Every module ships its own copy of sqlike.contract (and FRM also of sqlike.storage and
# sqlike.shared.SchemaChange/SchemaImage), so the modules cannot be compiled as one source set.
# javac compiles only the driver and the classes it reaches, looked up on the source path in order:
# the shared module first, then QP (its copy of sqlike.contract and sqlike.storage wins), CCM, FRM.
# Sources the driver never reaches, such as the query optimizer, are not compiled.
set -euo pipefail

ccm_root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$ccm_root/.." && pwd)"
build="$(mktemp -d "${TMPDIR:-/tmp}/sqlike-ccm-qp-XXXXXX")"
trap 'rm -rf "$build"' EXIT

source_path="$repo_root/shared/src/main/java"
source_path+=":$repo_root/query-processor/src/main/java"
source_path+=":$ccm_root/src/main/java"
source_path+=":$repo_root/failure-recovery/src/main/java"
source_path+=":$ccm_root/src/integration/java"

javac --release 21 -encoding UTF-8 -implicit:class -sourcepath "$source_path" -d "$build" \
    "$ccm_root/src/integration/java/sqlike/ccm/QpIntegrationDriver.java"
java -cp "$build" sqlike.ccm.QpIntegrationDriver

#!/usr/bin/env bash
# Integration driver with real SM data: the real Storage Manager, the real FRM and the real CCM.
#
# Every module ships its own copy of sqlike.contract and sqlike.storage, so the modules cannot be
# compiled as one source set. javac compiles only the driver and the classes it reaches, looked up
# on the source path in order: the shared module first, then SM (its copy of sqlike.contract and
# sqlike.storage wins, since it is the real implementation), CCM, FRM.
set -euo pipefail

ccm_root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$ccm_root/.." && pwd)"
build="$(mktemp -d "${TMPDIR:-/tmp}/sqlike-ccm-sm-XXXXXX")"
trap 'rm -rf "$build"' EXIT

source_path="$repo_root/shared/src/main/java"
source_path+=":$repo_root/storage-manager/src/main/java"
source_path+=":$ccm_root/src/main/java"
source_path+=":$repo_root/failure-recovery/src/main/java"
source_path+=":$ccm_root/src/integration/java"

javac --release 21 -encoding UTF-8 -implicit:class -sourcepath "$source_path" -d "$build" \
    "$ccm_root/src/integration/java/sqlike/ccm/SmIntegrationDriver.java"
java -cp "$build" sqlike.ccm.SmIntegrationDriver

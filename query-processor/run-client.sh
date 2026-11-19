#!/usr/bin/env bash
# ==============================================================================
# SQLike mDBMS - Interactive CLI Client Launcher
# Usage: ./query-processor/run-client.sh [host] [port] | [--host H] [--port P] [-f FILE]
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

BUILD_DIR="$(mktemp -d /tmp/sqlike-client-build-XXXXXX)"
trap 'rm -rf "${BUILD_DIR}"' EXIT

SOURCES=()
while IFS= read -r f; do SOURCES+=("$f"); done < <(find "${ROOT_DIR}/shared/src/main/java" -type f -name "*.java")
while IFS= read -r f; do SOURCES+=("$f"); done < <(find "${SCRIPT_DIR}/src/main/java" -type f -name "*.java")

javac --release 21 -encoding UTF-8 -d "${BUILD_DIR}" "${SOURCES[@]}"
exec java -cp "${BUILD_DIR}" sqlike.qp.CliClient "$@"


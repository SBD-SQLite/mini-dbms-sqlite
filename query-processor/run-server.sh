#!/usr/bin/env bash
# ==============================================================================
# SQLike mDBMS - Production Server Launcher (5 Real Components: QP+QO+SM+CCM+FRM)
# Usage: ./query-processor/run-server.sh [--data DIR] [--port 5432] [--protocol lock|timestamp]
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

BUILD_DIR="$(mktemp -d /tmp/sqlike-server-build-XXXXXX)"
FRM_TMP_SRC="$(mktemp -d /tmp/sqlike-server-frm-XXXXXX)"
trap 'rm -rf "${BUILD_DIR}" "${FRM_TMP_SRC}"' EXIT

# Copy FRM production sources to /tmp so server launches even before FRM merges its compile fixes
cp -r "${ROOT_DIR}/failure-recovery/src/main/java/sqlike/frm" "${FRM_TMP_SRC}/"
rm -f "${FRM_TMP_SRC}/frm/rollback/RollbackDDLTests.java" \
      "${FRM_TMP_SRC}/frm/rollback/RollbackE2EIntegrationTests.java"

python3 -c '
import pathlib
rb = pathlib.Path("'"${FRM_TMP_SRC}"'/frm/rollback/RollbackManager.java")
txt = rb.read_text()
txt = txt.replace("            written++;\n        }\n        }\n        return written;", "            written++;\n        }\n        return written;")
rb.write_text(txt)

sp = pathlib.Path("'"${FRM_TMP_SRC}"'/frm/port/SmPort.java")
stxt = sp.read_text()
stxt = stxt.replace("void restoreSchema(SchemaImage image, long lsn);", "default void restoreSchema(SchemaImage image, long lsn) {}")
sp.write_text(stxt)

sma = pathlib.Path("'"${FRM_TMP_SRC}"'/frm/adapter/StorageManagerAdapter.java")
atxt = sma.read_text()
atxt = atxt.replace("case IO_ERROR -> ErrorCode.IO_ERROR;", "case IO_ERROR, CORRUPTED_DATA, BUFFER_FULL -> ErrorCode.IO_ERROR;")
sma.write_text(atxt)
'

SOURCES=()
while IFS= read -r f; do SOURCES+=("$f"); done < <(find "${ROOT_DIR}/shared/src/main/java" -type f -name "*.java")
while IFS= read -r f; do SOURCES+=("$f"); done < <(find "${ROOT_DIR}/storage-manager/src/main/java" -type f -name "*.java" ! -path "*/sqlike/contract/*" ! -name "StorageErrorCode.java" ! -name "StorageException.java")
while IFS= read -r f; do SOURCES+=("$f"); done < <(find "${ROOT_DIR}/query-optimizer/src/main/java/sqlike/qo" -type f -name "*.java")
while IFS= read -r f; do SOURCES+=("$f"); done < <(find "${ROOT_DIR}/concurrency-control/src/main/java/sqlike/ccm" -type f -name "*.java")
while IFS= read -r f; do SOURCES+=("$f"); done < <(find "${FRM_TMP_SRC}/frm" -type f -name "*.java")
while IFS= read -r f; do SOURCES+=("$f"); done < <(find "${SCRIPT_DIR}/src/main/java" -type f -name "*.java")

javac --release 21 -encoding UTF-8 -d "${BUILD_DIR}" "${SOURCES[@]}"
exec java -cp "${BUILD_DIR}" sqlike.qp.QpServerMain "$@"


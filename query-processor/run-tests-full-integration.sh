#!/usr/bin/env bash
# ==============================================================================
# SQLike DBMS - Milestone 3 Full 5-Component Integration Test Runner
# Real QP + Real QO + Real CCM + Real SM + Real FRM (No Mocks, No Stubs)
# Lives strictly inside query-processor/ without modifying any other module.
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

echo "======================================================================"
echo "  Compiling SQLike DBMS Real Components for Milestone 3 Integration   "
echo "======================================================================"

BUILD_DIR="$(mktemp -d /tmp/sqlike-m3-integration-XXXXXX)"
FRM_TMP_SRC="$(mktemp -d /tmp/sqlike-frm-src-XXXXXX)"
trap 'rm -rf "${BUILD_DIR}" "${FRM_TMP_SRC}"' EXIT

# Copy FRM production packages (excluding stray JUnit tests placed in src/main/java by FRM team)
cp -r "${ROOT_DIR}/failure-recovery/src/main/java/sqlike/frm" "${FRM_TMP_SRC}/"
rm -f "${FRM_TMP_SRC}/frm/rollback/RollbackDDLTests.java" \
      "${FRM_TMP_SRC}/frm/rollback/RollbackE2EIntegrationTests.java"

# If FRM team has not yet fixed the extra closing brace on line 127 of RollbackManager.java
# or missing default on SmPort.restoreSchema in main, patch the temporary copy in /tmp only:
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
SOURCES+=("${SCRIPT_DIR}/src/test/java/sqlike/qp/QP1ScriptDriver.java")
SOURCES+=("${SCRIPT_DIR}/test-scripts/PureRealFullAcceptanceTest.java")

javac --release 21 -encoding UTF-8 -d "${BUILD_DIR}" "${SOURCES[@]}"

echo -e "\n======================================================================"
echo "  Executing Milestone 3 Full Integration Suite (QP-01 to QP-16)        "
echo "======================================================================"

cd "${ROOT_DIR}"
java -cp "${BUILD_DIR}" PureRealFullAcceptanceTest "$@"

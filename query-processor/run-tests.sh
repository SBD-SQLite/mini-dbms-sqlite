#!/usr/bin/env bash
# ==============================================================================
# SQLike Query Processor - Universal Test Runner (Bash)
# ==============================================================================
# Usage:
#   ./query-processor/run-tests.sh         # Runs all test drivers
#   ./query-processor/run-tests.sh qp2     # Runs QP2 unit tests only
#   ./query-processor/run-tests.sh qp1     # Runs QP1 test scenarios only
# ==============================================================================

set -euo pipefail

# ANSI Color codes
BOLD="\033[1m"
GREEN="\033[0;32m"
RED="\033[0;31m"
YELLOW="\033[0;33m"
CYAN="\033[0;36m"
RESET="\033[0m"

# Find repository root
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

echo -e "${BOLD}${CYAN}====================================================${RESET}"
echo -e "${BOLD}${CYAN}  SQLike Query Processor - Test Suite Runner       ${RESET}"
echo -e "${BOLD}${CYAN}====================================================${RESET}"

# Check Java compiler and runtime
JAVAC_CMD="${JAVA_HOME:-}/bin/javac"
if [ ! -x "${JAVAC_CMD}" ]; then
    JAVAC_CMD="$(command -v javac || true)"
fi

JAVA_CMD="${JAVA_HOME:-}/bin/java"
if [ ! -x "${JAVA_CMD}" ]; then
    JAVA_CMD="$(command -v java || true)"
fi

if [ -z "${JAVAC_CMD}" ] || [ -z "${JAVA_CMD}" ]; then
    echo -e "${RED}[ERROR] JDK 17+ (javac/java) is not installed or not in PATH.${RESET}"
    exit 1
fi

JAVA_VER="$("${JAVA_CMD}" -version 2>&1 | awk -F '"' '/version/ {print $2}' | cut -d'.' -f1)"
echo -e "Detected Java Version: ${BOLD}${JAVA_VER}${RESET} (${JAVA_CMD})"

# Prepare output directory
BUILD_DIR="$(mktemp -d /tmp/sqlike-qp-build-XXXXXX)"
trap 'rm -rf "${BUILD_DIR}"' EXIT

echo -e "\n${BOLD}[1/2] Compiling sources...${RESET}"

# Collect source files
SOURCE_DIRS=(
    "${REPO_ROOT}/concurrency-control/src/main/java"
    "${REPO_ROOT}/query-processor/src/main/java"
    "${REPO_ROOT}/query-processor/src/test/java"
)

# Include stubs if directory exists
if [ -d "${REPO_ROOT}/query-processor/src/stubs/java" ]; then
    SOURCE_DIRS+=("${REPO_ROOT}/query-processor/src/stubs/java")
fi

SOURCES=()
for sdir in "${SOURCE_DIRS[@]}"; do
    if [ -d "${sdir}" ]; then
        while IFS= read -r f; do
            SOURCES+=("$f")
        done < <(find "${sdir}" -type f -name "*.java")
    fi
done

if [ ${#SOURCES[@]} -eq 0 ]; then
    echo -e "${RED}[ERROR] No Java sources found to compile.${RESET}"
    exit 1
fi

echo -e "Compiling ${#SOURCES[@]} Java files into ${BUILD_DIR}..."
if ! "${JAVAC_CMD}" --release 17 -encoding UTF-8 -d "${BUILD_DIR}" "${SOURCES[@]}"; then
    echo -e "${RED}[FAIL] Compilation failed! Please check syntax and type errors.${RESET}"
    exit 1
fi
echo -e "${GREEN}[OK] Compilation successful (0 errors).${RESET}"

echo -e "\n${BOLD}[2/2] Running Test Suites...${RESET}"

TARGET="${1:-all}"
FAILED=0

run_test() {
    local name="$1"
    local class_name="$2"
    shift 2
    local extra_args=("$@")

    echo -e "\n${BOLD}${CYAN}--- Running ${name} (${class_name}) ---${RESET}"
    if "${JAVA_CMD}" -cp "${BUILD_DIR}" "${class_name}" "${extra_args[@]}"; then
        echo -e "${GREEN}[PASS] ${name} completed successfully.${RESET}"
    else
        echo -e "${RED}[FAIL] ${name} encountered errors.${RESET}"
        FAILED=$((FAILED + 1))
    fi
}

case "${TARGET}" in
    qp1)
        run_test "QP1 Driver Scenarios" "sqlike.qp.QP1Driver" "--test"
        ;;
    qp2)
        run_test "QP2 Unit Tests" "sqlike.qp.QP2TestDriver"
        ;;
    qp3)
        run_test "QP3 Test Driver" "sqlike.qp.QP3TestDriver"
        ;;
    qp4)
        run_test "QP4 Test Driver" "sqlike.qp.QP4TestDriver"
        ;;
    qp5)
        run_test "QP5 Acceptance Test" "sqlike.qp.Qp5AcceptanceTest"
        ;;
    all)
        run_test "QP2 Unit Tests" "sqlike.qp.QP2TestDriver"
        run_test "QP1 Driver Scenarios" "sqlike.qp.QP1Driver" "--test"
        if [ -f "${REPO_ROOT}/query-processor/src/test/java/sqlike/qp/QP4TestDriver.java" ]; then
            run_test "QP4 Test Driver" "sqlike.qp.QP4TestDriver"
        fi
        if [ -f "${REPO_ROOT}/query-processor/src/test/java/sqlike/qp/QP3TestDriver.java" ]; then
            run_test "QP3 Test Driver" "sqlike.qp.QP3TestDriver"
        fi
        if [ -f "${REPO_ROOT}/query-processor/src/test/java/sqlike/qp/Qp5AcceptanceTest.java" ]; then
            run_test "QP5 Acceptance Test" "sqlike.qp.Qp5AcceptanceTest"
        fi
        ;;
    *)
        echo -e "${RED}Unknown target: ${TARGET}.${RESET}"
        echo "Valid options: all, qp1, qp2, qp3, qp4, qp5"
        exit 1
        ;;
esac

echo -e "\n${BOLD}====================================================${RESET}"
if [ ${FAILED} -eq 0 ]; then
    echo -e "${BOLD}${GREEN}  ALL REQUESTED TESTS PASSED!                      ${RESET}"
    echo -e "${BOLD}====================================================${RESET}"
    exit 0
else
    echo -e "${BOLD}${RED}  SOME TESTS FAILED (${FAILED} suite(s) with errors). ${RESET}"
    echo -e "${BOLD}====================================================${RESET}"
    exit 1
fi

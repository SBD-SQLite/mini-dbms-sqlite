#!/usr/bin/env bash
# Starts the full mDBMS server (QP + QO + SM + CCM + FRM).
# Usage: ./run-server.sh [--data DIR] [--port N] [--protocol lock|timestamp] [--no-sample] ...
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=";" ;; *) SEP=":" ;; esac
[ -d query-processor/target/classes ] || mvn -q compile
CP="shared/target/classes${SEP}storage-manager/target/classes${SEP}query-optimizer/target/classes${SEP}concurrency-control/target/classes${SEP}failure-recovery/target/classes${SEP}query-processor/target/classes"
exec java -cp "$CP" sqlike.qp.QpServerMain "$@"

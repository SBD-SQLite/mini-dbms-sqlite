#!/usr/bin/env bash
# Starts a CLI client. Usage: ./run-client.sh [--host H] [--port N] [-f script.sql]
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=";" ;; *) SEP=":" ;; esac
[ -d query-processor/target/classes ] || mvn -q compile
exec java -cp "shared/target/classes${SEP}query-processor/target/classes" sqlike.qp.CliClient "$@"

#!/usr/bin/env bash
# Compile shared + Storage Manager sources and run the standalone snapshot unit test.
set -euo pipefail

sm_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_dir="$(dirname "$sm_dir")"
classes_dir="$sm_dir/target/snapshot-test-classes"

if ! command -v javac >/dev/null 2>&1 || ! command -v java >/dev/null 2>&1; then
    echo "Java JDK 21 is required (javac and java must be on PATH)." >&2
    exit 1
fi

mkdir -p "$classes_dir"
sources=()
while IFS= read -r -d '' source; do
    sources+=("$source")
done < <(find "$repo_dir/shared/src/main/java" "$sm_dir/src/main/java" "$sm_dir/src/test/java" -name '*.java' -print0)

javac --release 21 -encoding UTF-8 -d "$classes_dir" "${sources[@]}"
java -cp "$classes_dir" sqlike.storage.SnapshotManagerTest

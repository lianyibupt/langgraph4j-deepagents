#!/bin/bash
set -euo pipefail

PROJECT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
BUILD_DIR="$(/usr/bin/mktemp -d "${TMPDIR:-/tmp}/personal-agent-test.XXXXXX")"
trap '/bin/rm -rf -- "$BUILD_DIR"' EXIT

SOURCES=()
while IFS= read -r -d '' SOURCE; do
  SOURCES+=("$SOURCE")
done < <(/usr/bin/find "$PROJECT_DIR/src/main/java" "$PROJECT_DIR/src/test/java" -name '*.java' -type f -print0)
/usr/bin/javac -encoding UTF-8 --release 21 -g -Xlint:all -d "$BUILD_DIR" "${SOURCES[@]}"
/usr/bin/java -cp "$BUILD_DIR" personal.agent.AgentRuntimeTest

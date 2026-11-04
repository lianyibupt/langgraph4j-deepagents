#!/bin/bash
set -euo pipefail

PROJECT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
BUILD_DIR="$(/usr/bin/mktemp -d "${TMPDIR:-/tmp}/personal-agent-test.XXXXXX")"
trap '/bin/rm -rf -- "$BUILD_DIR"' EXIT
DEPS_CP="$(/bin/bash "$PROJECT_DIR/prepare-deps.sh")"

SOURCES=()
while IFS= read -r -d '' SOURCE; do
  SOURCES+=("$SOURCE")
done < <(/usr/bin/find "$PROJECT_DIR/src/main/java" "$PROJECT_DIR/src/test/java" -name '*.java' -type f -print0)
/usr/bin/javac -encoding UTF-8 --release 21 -g -Xlint:all -cp "$DEPS_CP" -d "$BUILD_DIR" "${SOURCES[@]}"
TEST_OPTIONS=('-Dagent.test.http=false')
if [[ "${1:-}" == '--http' && "$#" == 1 ]]; then
  TEST_OPTIONS=('-Dagent.test.http=true')
elif [[ "$#" != 0 ]]; then
  printf 'Usage: bash test.sh [--http]\n' >&2
  exit 2
fi
/usr/bin/java "${TEST_OPTIONS[@]}" -cp "$BUILD_DIR:$DEPS_CP" personal.agent.AllTests

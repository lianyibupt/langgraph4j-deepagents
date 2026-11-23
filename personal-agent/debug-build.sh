#!/bin/bash
set -euo pipefail
PROJECT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
DEPS_CP="$(/bin/bash "$PROJECT_DIR/prepare-deps.sh")"
BUILD_DIR="$PROJECT_DIR/.debug-classes"
/bin/mkdir -p "$BUILD_DIR"
SOURCES=()
while IFS= read -r -d '' SOURCE; do
  SOURCES+=("$SOURCE")
done < <(/usr/bin/find "$PROJECT_DIR/src/main/java" -path '*/personal/agent/web' -prune -o -name '*.java' -type f -print0)
/usr/bin/javac -encoding UTF-8 --release 21 -g -Xlint:all -cp "$DEPS_CP" -d "$BUILD_DIR" "${SOURCES[@]}"
printf 'Debug classes ready: %s\n' "$BUILD_DIR"

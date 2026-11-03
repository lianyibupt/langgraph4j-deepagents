#!/bin/bash
set -euo pipefail

PROJECT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
BUILD_DIR="$(/usr/bin/mktemp -d "${TMPDIR:-/tmp}/personal-agent-run.XXXXXX")"
trap '/bin/rm -rf -- "$BUILD_DIR"' EXIT

SOURCES=()
while IFS= read -r -d '' SOURCE; do
  SOURCES+=("$SOURCE")
done < <(/usr/bin/find "$PROJECT_DIR/src/main/java" -name '*.java' -type f -print0)
/usr/bin/javac -encoding UTF-8 --release 21 -g -Xlint:all -d "$BUILD_DIR" "${SOURCES[@]}"

JAVA_OPTIONS=("-Dpersonal.agent.workspace=$PROJECT_DIR/examples")
APP_ARGUMENTS=()
for ARGUMENT in "$@"; do
  if [[ "$ARGUMENT" == '--debug' ]]; then
    JAVA_OPTIONS+=('-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:5005')
  else
    APP_ARGUMENTS+=("$ARGUMENT")
  fi
done
/usr/bin/java "${JAVA_OPTIONS[@]}" -cp "$BUILD_DIR" personal.agent.Main "${APP_ARGUMENTS[@]}"

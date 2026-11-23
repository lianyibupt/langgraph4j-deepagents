#!/bin/bash
set -euo pipefail

PROJECT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
BUILD_DIR="$(/usr/bin/mktemp -d "${TMPDIR:-/tmp}/personal-agent-run.XXXXXX")"
trap '/bin/rm -rf -- "$BUILD_DIR"' EXIT
DEPS_CP="$(/bin/bash "$PROJECT_DIR/prepare-deps.sh")"

SOURCES=()
while IFS= read -r -d '' SOURCE; do
  SOURCES+=("$SOURCE")
done < <(/usr/bin/find "$PROJECT_DIR/src/main/java" -path '*/personal/agent/web' -prune -o -name '*.java' -type f -print0)
/usr/bin/javac -encoding UTF-8 --release 21 -g -Xlint:all -cp "$DEPS_CP" -d "$BUILD_DIR" "${SOURCES[@]}"

JAVA_OPTIONS=("-Dpersonal.agent.workspace=$PROJECT_DIR/examples" "-Dpersonal.agent.env=$PROJECT_DIR/.env")
APP_ARGUMENTS=(personal.agent.Main)
for ARGUMENT in "$@"; do
  if [[ "$ARGUMENT" == '--debug' ]]; then
    JAVA_OPTIONS+=('-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:5005')
  else
    APP_ARGUMENTS+=("$ARGUMENT")
  fi
done
/usr/bin/java "${JAVA_OPTIONS[@]}" -cp "$BUILD_DIR:$DEPS_CP" "${APP_ARGUMENTS[@]}"

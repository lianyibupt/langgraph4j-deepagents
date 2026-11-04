#!/bin/bash
set -euo pipefail
PROJECT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
JACKSON_VERSION=2.18.2
DEPS_DIR="$PROJECT_DIR/.deps"
/bin/mkdir -p "$DEPS_DIR"
AGENT_CLASSPATH=''
for ARTIFACT in jackson-databind jackson-core jackson-annotations; do
  JAR_NAME="$ARTIFACT-$JACKSON_VERSION.jar"
  JAR_PATH="$DEPS_DIR/$JAR_NAME"
  if [[ ! -f "$JAR_PATH" ]]; then
    CACHED_JAR="$HOME/.m2/repository/com/fasterxml/jackson/core/$ARTIFACT/$JACKSON_VERSION/$JAR_NAME"
    if [[ ! -f "$CACHED_JAR" ]]; then
      printf 'Missing dependency: %s\nImport pom.xml in IDEA, or run Maven dependency:copy-dependencies with -DoutputDirectory=%s\n' "$JAR_NAME" "$DEPS_DIR" >&2
      exit 1
    fi
    /bin/cp "$CACHED_JAR" "$JAR_PATH"
  fi
  AGENT_CLASSPATH="${AGENT_CLASSPATH:+$AGENT_CLASSPATH:}$JAR_PATH"
done
printf '%s\n' "$AGENT_CLASSPATH"

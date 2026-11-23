#!/bin/bash
set -euo pipefail

PROJECT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd "$PROJECT_DIR"

if command -v mvn >/dev/null; then
  exec mvn -q -DskipTests "-Dspring-boot.run.arguments=$*" spring-boot:run
fi

# Offline startup for the local Maven cache. Versions follow the BOM in pom.xml.
CACHE="${MAVEN_REPO_LOCAL:-$HOME/.m2/repository}"
WEB_CP=""
add_jars() {
  local group="$1" version="$2" artifact jar
  shift 2
  for artifact in "$@"; do
    jar="$CACHE/$group/$artifact/$version/$artifact-$version.jar"
    if [[ ! -r "$jar" ]]; then
      printf 'Missing cached dependency: %s. Install Maven and rerun bash personal-agent/web.sh.\n' "$artifact-$version" >&2
      exit 1
    fi
    WEB_CP="${WEB_CP:+$WEB_CP:}$jar"
  done
}

add_jars org/springframework/boot 3.4.3 spring-boot spring-boot-autoconfigure
add_jars org/springframework 6.2.3 spring-core spring-jcl spring-aop spring-beans spring-context spring-expression spring-web spring-webmvc
add_jars org/apache/tomcat/embed 10.1.36 tomcat-embed-core
add_jars jakarta/annotation 2.1.1 jakarta.annotation-api
add_jars io/micrometer 1.14.4 micrometer-observation micrometer-commons
add_jars com/fasterxml/jackson/core 2.18.2 jackson-databind jackson-core jackson-annotations
add_jars com/fasterxml/jackson/datatype 2.18.2 jackson-datatype-jdk8 jackson-datatype-jsr310
add_jars com/fasterxml/jackson/module 2.18.2 jackson-module-parameter-names
add_jars org/slf4j 2.0.16 slf4j-api
add_jars ch/qos/logback 1.5.16 logback-core logback-classic

BUILD_DIR="$(mktemp -d "${TMPDIR:-/tmp}/personal-agent-web.XXXXXX")"
trap 'rm -rf -- "$BUILD_DIR"' EXIT
SOURCES=()
while IFS= read -r -d '' SOURCE; do
  SOURCES+=("$SOURCE")
done < <(find "$PROJECT_DIR/src/main/java" -name '*.java' -type f -print0)
"${JAVA_HOME:+$JAVA_HOME/bin/}javac" -encoding UTF-8 --release 21 -parameters -g -Xlint:all -cp "$WEB_CP" -d "$BUILD_DIR" "${SOURCES[@]}"
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp "$BUILD_DIR:$PROJECT_DIR/src/main/resources:$WEB_CP" personal.agent.web.WebApplication "$@"

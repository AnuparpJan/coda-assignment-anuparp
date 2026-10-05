#!/usr/bin/env bash
# Builds and starts the single-node KV store on http://localhost:7000 (Ctrl+C stops it).
# Usage: scripts/run.sh [--skip-build] [extra Spring args, e.g. --server.port=8080]
set -euo pipefail
cd "$(dirname "$0")/.."

SKIP_BUILD=false
if [[ "${1:-}" == "--skip-build" ]]; then
  SKIP_BUILD=true
  shift
fi

# The project targets Java 25. If java isn't on PATH and JAVA_HOME is unset, fall back to an
# IntelliJ-downloaded JDK under ~/.jdks.
if [[ -z "${JAVA_HOME:-}" ]] && ! command -v java >/dev/null 2>&1; then
  for jdk in "$HOME"/.jdks/*-25* "$HOME"/.jdks/*; do
    if [[ -x "$jdk/bin/java" ]]; then
      export JAVA_HOME="$jdk"
      break
    fi
  done
fi
if [[ -n "${JAVA_HOME:-}" ]]; then
  JAVA="$JAVA_HOME/bin/java"
elif command -v java >/dev/null 2>&1; then
  JAVA=java
else
  echo "No Java found: install JDK 25 or set JAVA_HOME." >&2
  exit 1
fi

if [[ "$SKIP_BUILD" == false ]]; then
  ./mvnw -q -DskipTests package
fi

JAR=$(ls target/coda-kv-store-*.jar 2>/dev/null | grep -v '\.original$' | head -n 1 || true)
if [[ -z "$JAR" ]]; then
  echo "No jar in target/. Run without --skip-build first." >&2
  exit 1
fi

echo "Starting $JAR with $JAVA"
exec "$JAVA" -jar "$JAR" "$@"

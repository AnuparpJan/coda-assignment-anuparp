#!/usr/bin/env bash
# Starts 3 storage nodes (node-1..node-3 on 127.0.0.1:7001-7003). Start the router separately
# (../coda-kv-router) pointing at these URLs; clients should call the router, not the nodes.
# Ctrl+C stops all nodes.
# Usage: scripts/run-nodes.sh [--skip-build]
set -euo pipefail
cd "$(dirname "$0")/.."
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"

# Fail fast instead of mistaking someone else's server for ours.
port_free() { ! (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }
wait_up() {  # pid url name log
  for _ in $(seq 1 120); do
    kill -0 "$1" 2>/dev/null || { echo "$3 exited during startup; last log lines:"; tail -n 5 "$4"; exit 1; }
    curl -s -o /dev/null "$2" && return 0
    sleep 0.5
  done
  echo "$3 did not start within 60s (log: $4)"; exit 1
}

if [[ "${1:-}" != "--skip-build" ]]; then
  ./mvnw -q -DskipTests package
fi
JAR=$(ls target/coda-kv-store-node-*-exec.jar | head -n 1)

for i in 1 2 3; do
  port_free 700$i || { echo "Port 700$i is already in use; stop whatever is running there first."; exit 1; }
done

mkdir -p target/node-logs
PIDS=()
trap 'kill "${PIDS[@]}" 2>/dev/null; wait' EXIT INT TERM

for i in 1 2 3; do
  # Loopback only: nodes are an internal tier behind the router.
  "$JAVA" -jar "$JAR" --server.address=127.0.0.1 --server.port=700$i --kv.node-id=node-$i \
    > "target/node-logs/node-$i.log" 2>&1 &
  PIDS+=($!)
  echo "node-$i -> http://127.0.0.1:700$i (log: target/node-logs/node-$i.log)"
done

for i in 1 2 3; do
  wait_up "${PIDS[$((i-1))]}" "http://127.0.0.1:700$i/internal/keys" node-$i target/node-logs/node-$i.log
done
cat <<'USAGE'
Nodes are up. Start the router with:
  java -jar ../coda-kv-router/target/coda-kv-router-*.jar \
    --kv.router.nodes.node-1=http://127.0.0.1:7001 \
    --kv.router.nodes.node-2=http://127.0.0.1:7002 \
    --kv.router.nodes.node-3=http://127.0.0.1:7003
USAGE
wait -n  # stop everything as soon as any node exits

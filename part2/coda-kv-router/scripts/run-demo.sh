#!/usr/bin/env bash
# Starts 3 storage nodes (node-1..node-3 on 127.0.0.1:7001-7003) and the router on :7000.
# Clients talk only to the router. Ctrl+C stops everything.
# Usage: scripts/run-demo.sh [--skip-build]
#   NODE_DIR  path to the storage node project (default: ../coda-kv-store-part2)
set -euo pipefail
cd "$(dirname "$0")/.."
NODE_DIR="${NODE_DIR:-../coda-kv-store-part2}"
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
  (cd "$NODE_DIR" && ./mvnw -q -DskipTests install)
  ./mvnw -q -DskipTests package
fi
NODE_JAR=$(ls "$NODE_DIR"/target/coda-kv-store-node-*-exec.jar | head -n 1)
ROUTER_JAR=$(ls target/coda-kv-router-*.jar | grep -v '\.original$' | head -n 1)

for port in 7000 7001 7002 7003; do
  port_free $port || { echo "Port $port is already in use; stop whatever is running there first."; exit 1; }
done

mkdir -p target/demo-logs
PIDS=()
trap 'kill "${PIDS[@]}" 2>/dev/null; wait' EXIT INT TERM

ROUTER_ARGS=(--server.port=7000)
for i in 1 2 3; do
  # Nodes listen on loopback only: they are an internal tier behind the router.
  "$JAVA" -jar "$NODE_JAR" --server.address=127.0.0.1 --server.port=700$i --kv.node-id=node-$i \
    > "target/demo-logs/node-$i.log" 2>&1 &
  PIDS+=($!)
  ROUTER_ARGS+=(--kv.router.nodes.node-$i=http://127.0.0.1:700$i)
  echo "node-$i  -> http://127.0.0.1:700$i (log: target/demo-logs/node-$i.log)"
done
"$JAVA" -jar "$ROUTER_JAR" "${ROUTER_ARGS[@]}" > target/demo-logs/router.log 2>&1 &
PIDS+=($!)
echo "router  -> http://localhost:7000  (log: target/demo-logs/router.log)"

for i in 1 2 3; do
  wait_up "${PIDS[$((i-1))]}" "http://127.0.0.1:700$i/internal/keys" node-$i target/demo-logs/node-$i.log
done
wait_up "${PIDS[3]}" "http://127.0.0.1:7000/kv" router target/demo-logs/router.log
cat <<'USAGE'
Up. Try:
  curl -i -X PUT localhost:7000/kv/user:42 -H 'Content-Type: application/json' -d '{"name":"Ari","points":10}'
  curl -i localhost:7000/kv/user:42           # X-Kv-Node shows the owner
  curl -i -X PUT 'localhost:7000/kv/user:42?ifVersion=9' -H 'Content-Type: application/json' -d 1   # 409
  curl localhost:7000/kv                      # NDJSON key listing
USAGE
wait -n  # stop everything as soon as any process exits

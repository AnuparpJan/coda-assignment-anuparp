# coda-kv-store-node (Part 2 storage node)

An in-memory JSON key-value node for Part 2 of the KV Store assignment. Several of these run behind the router in `../coda-kv-router`.

## Status and scope

In scope:

- The Part 1 API and semantics, unchanged: `GET`, `PUT` and `PATCH /kv/{key}`, per-key atomicity, a version per key, and `ifVersion` returning 409 on a mismatch (404 if the key does not exist).
- A node id (`kv.node-id`), returned in an `X-Kv-Node` header on every response.
- `GET /internal/keys`, which lists the keys stored on this node only. The router uses it to build `GET /kv`.
- A script that starts three nodes for the demo.

Explicitly not in scope:

- Routing or knowledge of other nodes. The router does that.
- Replication, failover, persistence or rebalancing. A restarted node starts empty.
- Authentication. Nodes are meant to sit behind the router on a private network.

## Quick start

The project needs Java 25. If `java` is not on your `PATH`, point `JAVA_HOME` at a JDK 25 first, for example:

```bash
export JAVA_HOME=~/.jdks/temurin-25.0.4.1
```

Build, run the tests and install the jar into the local Maven repository (the router's tests use it):

```bash
./mvnw install
```

Start three nodes on `127.0.0.1:7001`, `7002` and `7003`:

```bash
scripts/run-nodes.sh
```

Then start the router against them (see `../coda-kv-router`). Or start nodes and router together from the router project:

```bash
../coda-kv-router/scripts/run-demo.sh
```

Run the tests only:

```bash
./mvnw test
```

## API reference

Clients should call the router, not a node. The examples below call node-1 directly to show what a node returns.

`PUT /kv/{key}` replaces the value. `ifVersion` is optional.

```bash
curl -X PUT 'http://127.0.0.1:7001/kv/user:42' -H 'Content-Type: application/json' -d '{"name":"Ari","points":10}'
```

```json
{"key":"user:42","value":{"name":"Ari","points":10},"version":1}
```

`GET /kv/{key}` returns the value and its version.

```bash
curl http://127.0.0.1:7001/kv/user:42
```

```json
{"key":"user:42","value":{"name":"Ari","points":10},"version":1}
```

`PATCH /kv/{key}` shallow-merges when both the stored value and the body are JSON objects. Otherwise it replaces. Without `ifVersion` it creates the key if it is missing. `ifVersion` is optional.

```bash
curl -X PATCH 'http://127.0.0.1:7001/kv/user:42' -H 'Content-Type: application/json' -d '{"rank":"gold"}'
```

```json
{"key":"user:42","value":{"name":"Ari","points":10,"rank":"gold"},"version":2}
```

`GET /internal/keys` lists the keys on this node. It is for the router.

```bash
curl http://127.0.0.1:7001/internal/keys
```

```json
{"node":"node-1","keys":["user:42"]}
```

Every response also carries the header `X-Kv-Node: <node id>`.

Errors use the shape `{"code":<status>,"message":"..."}`:

| Status | When | Example message |
|---|---|---|
| 400 | Body is not valid JSON | `Request body must be valid JSON` |
| 400 | `ifVersion` is not a number | `Invalid value for parameter 'ifVersion'` |
| 404 | Key does not exist: GET, or PUT/PATCH with `ifVersion` | `Key not found: nope` |
| 405 | Method other than GET, PUT or PATCH | `Method 'DELETE' is not supported.` |
| 409 | `ifVersion` does not match the current version of an existing key | `Version conflict on key user:42: expected 1, actual 3` |
| 415 | Content-Type is not JSON | `Content-Type 'text/plain;charset=UTF-8' is not supported.` |

## How it works

- Keys live in one `ConcurrentHashMap` per node.
- Every write goes through `ConcurrentHashMap.compute()` for its key. This serializes writes to the same key while writes to other keys run in parallel. The `ifVersion` check, the update and the version bump all happen inside that one call, so no update is lost.
- Versions start at 1 and go up by one on every successful write.
- `GET /internal/keys` takes a sorted snapshot of this node's keys. It never calls other nodes.
- A servlet filter adds `X-Kv-Node` to every response.

## Configuration

| Property | Default | Meaning |
|---|---|---|
| `server.port` | `7000` | HTTP port. `run-nodes.sh` uses 7001 to 7003. |
| `server.address` | all interfaces | Bind address. `run-nodes.sh` uses `127.0.0.1` so nodes are not reachable from other machines. |
| `kv.node-id` | `node-1` | This node's id, returned in `X-Kv-Node` and `/internal/keys`. It must match the id the router is configured with. |

## Testing

```bash
./mvnw test
```

`KvApiTests` starts one node on a random port and checks, over real HTTP:

- PUT then GET, with the version going up on each write.
- A missing key returns 404.
- PUT or PATCH with `ifVersion` on a missing key returns 404 and creates nothing.
- A stale `ifVersion` returns 409 for PUT and PATCH, and a matching one succeeds.
- PATCH creates, shallow-merges objects, and replaces non-objects.
- Errors contain only `code` and `message`, including 400 for bad JSON or `ifVersion` and 405 for other methods.
- 3 concurrent clients doing 100 optimistic increments each end at value 300 and version 301.
- `/internal/keys` lists the stored key, and responses carry `X-Kv-Node`.

The cluster tests (several nodes behind the router) are in `../coda-kv-router`.

## Design decisions and tradeoffs

Each node keeps the Part 1 design. The first three decisions come from the assignment spec.

- **One in-memory map, updated per key with `ConcurrentHashMap.compute()`.**
  - Why: the spec asks for in-memory storage at this stage, and for operations on the same key to be serialized while different keys proceed concurrently. `compute()` locks only that key's entry for the length of one update, so the version check, the write and the version bump happen together.
  - Alternative considered: a single global lock, or an explicit lock object per key.
  - Tradeoff: no durability, and data is limited by one JVM's memory. The update inside `compute()` must stay short, because keys that share a map bin wait for each other.
- **`ifVersion` as an optional query parameter, with 409 on a mismatch and 404 for a missing key.**
  - Why: the spec defines an optional `ifVersion=<version>` guard for PUT and PATCH and requires 409 when it does not match. A conditional write on a key that does not exist returns 404, the same as GET, and creates nothing.
  - Alternative considered: an `If-Match` header with ETags.
  - Tradeoff: the version is visible in URLs and logs, and clients must read before a conditional write.
- **PATCH shallow-merges top-level fields only.**
  - Why: the spec says to shallow-merge when both the stored value and the delta are JSON objects, and to replace otherwise.
  - Alternative considered: a deep merge (JSON Merge Patch) or JSON Patch operations.
  - Tradeoff: a nested object in the delta replaces the whole nested object, so clients cannot update one nested field alone.
- **Nodes only store data and know nothing about each other.**
  - Why: single responsibility and easier maintenance. Routing lives in the router, so the node stays the Part 1 service plus a node id and a local key listing.
  - Alternative considered: every node also routing and proxying to its peers (an earlier version of this part).
  - Tradeoff: a node trusts that requests reach the right owner. A write sent straight to the wrong node is stored there, so clients must go through the router.

## Known limits

- Data is in memory only. Restarting a node loses its keys.
- A node accepts requests from anyone who can reach it. A write sent straight to a node that does not own the key is stored there anyway, and the router will not find it. Clients must use the router.
- A key containing an encoded `/` (`%2F`), `\` (`%5C`) or an invalid `%` escape is rejected by Tomcat with an HTML 400, not the `{code,message}` shape.
- A raw `;` in the path starts a path parameter, so `/kv/a;b` stores key `a`. Use `%3B` for a `;` inside a key.
- Request bodies have no size limit and are held fully in memory.
- `/internal/keys` returns all keys in one response, with no paging.

## Layout

```
coda-kv-store-part2/
  pom.xml                      Spring Boot 4.1.1, Java 25; runnable jar has the "exec" classifier
  scripts/run-nodes.sh         starts node-1..node-3 on 127.0.0.1:7001-7003
  src/main/java/org/example/codakvstore/
    controller/                GET, PUT, PATCH /kv/{key} and response DTOs
    service/KvService.java     ConcurrentHashMap storage, versioning, merge
    node/                      NodeIdFilter (X-Kv-Node), LocalKeysController (/internal/keys)
    exception/                 error types and the {code,message} handler
  src/test/java/org/example/codakvstore/KvApiTests.java
```

## Related parts

- Part 1, single node: [../coda-kv-store-part1](../coda-kv-store-part1/README.md)
- Part 2, router: [../coda-kv-router](../coda-kv-router/README.md)
- Part 3, roadmap: [../coda-kv-store-roadmap](../coda-kv-store-roadmap/README.md)

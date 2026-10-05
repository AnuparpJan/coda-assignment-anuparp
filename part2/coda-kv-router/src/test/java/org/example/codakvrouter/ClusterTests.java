package org.example.codakvrouter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.example.codakvrouter.TestCluster.Response;
import org.example.codakvrouter.routing.SlotTable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ClusterTests {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final List<String> NODE_IDS = List.of("node-1", "node-2", "node-3");
    private static final SlotTable SLOTS = new SlotTable(Map.of(
            "node-1", "http://unused", "node-2", "http://unused", "node-3", "http://unused"));

    private static TestCluster cluster;

    @BeforeAll
    static void start() {
        cluster = new TestCluster(3);
    }

    @AfterAll
    static void stop() {
        cluster.close();
    }

    private static String newKey() {
        return "k-" + UUID.randomUUID();
    }

    private static String path(String key) {
        return "/kv/" + URLEncoder.encode(key, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static JsonNode json(Response response) {
        return MAPPER.readTree(response.body());
    }

    @Test
    void sameKeyIsConsistentAcrossRepeatedAccess() throws Exception {
        String key = newKey();
        Response put = cluster.router("PUT", path(key), "{\"a\":1}");
        assertEquals(200, put.status());
        String owner = SLOTS.ownerOf(key);
        assertEquals(owner, put.node());

        for (int i = 0; i < 10; i++) {
            Response get = cluster.router("GET", path(key), null);
            assertEquals(200, get.status());
            assertEquals(owner, get.node());
            assertEquals(MAPPER.readTree("{\"key\":\"" + key + "\",\"value\":{\"a\":1},\"version\":1}"), json(get));
        }

        assertEquals(200, cluster.node(owner, "GET", path(key), null).status());
        for (String other : NODE_IDS) {
            if (!other.equals(owner)) {
                assertEquals(404, cluster.node(other, "GET", path(key), null).status());
            }
        }
    }

    @Test
    void keysWithSpecialCharactersRoundTrip() throws Exception {
        for (String key : List.of("user:42", "with space", "ünïcødé-キー", "a+b=c&d")) {
            Response put = cluster.router("PUT", path(key), "\"v\"");
            assertEquals(200, put.status(), key);
            assertEquals(key, json(put).get("key").asString());
            assertEquals(SLOTS.ownerOf(key), put.node(), key);
            assertEquals(key, json(cluster.router("GET", path(key), null)).get("key").asString());
        }
    }

    @Test
    void rawSemicolonIsRoutedByTheKeyTheNodeActuallyStores() throws Exception {
        String stored = "semi-" + UUID.randomUUID();
        Response put = cluster.router("PUT", "/kv/" + stored + ";x=1", "1");
        assertEquals(200, put.status());
        assertEquals(stored, json(put).get("key").asString());
        assertEquals(SLOTS.ownerOf(stored), put.node());
        assertEquals(200, cluster.router("GET", path(stored), null).status());
    }

    @Test
    void keysSpreadOverMoreThanOneNode() throws Exception {
        Map<String, Integer> perNode = new HashMap<>();
        for (int i = 0; i < 60; i++) {
            Response put = cluster.router("PUT", path(newKey()), "1");
            assertEquals(200, put.status());
            perNode.merge(put.node(), 1, Integer::sum);
        }
        assertEquals(Set.copyOf(NODE_IDS), perNode.keySet(), "60 random keys should hit all 3 nodes: " + perNode);
    }

    @Test
    void errorsPassThroughUnchanged() throws Exception {
        String key = newKey();
        Response missing = cluster.router("GET", path(key), null);
        assertEquals(404, missing.status());
        assertEquals(MAPPER.readTree("{\"code\":404,\"message\":\"Key not found: " + key + "\"}"), json(missing));

        Response missingConditional = cluster.router("PUT", path(key) + "?ifVersion=1", "1");
        assertEquals(404, missingConditional.status());
        assertEquals(MAPPER.readTree("{\"code\":404,\"message\":\"Key not found: " + key + "\"}"),
                json(missingConditional));
        assertEquals(404, cluster.router("PATCH", path(key) + "?ifVersion=1", "1").status());

        cluster.router("PUT", path(key), "1");
        Response conflict = cluster.router("PUT", path(key) + "?ifVersion=7", "2");
        assertEquals(409, conflict.status());
        assertEquals(409, json(conflict).get("code").asInt());
        assertEquals(Set.of("code", "message"), Set.copyOf(json(conflict).propertyNames()));
        assertEquals(409, cluster.router("PATCH", path(key) + "?ifVersion=7", "2").status());

        Response ok = cluster.router("PATCH", path(key) + "?ifVersion=1", "{\"x\":1}");
        assertEquals(200, ok.status());
        assertEquals(2, json(ok).get("version").asInt());

        Response badJson = cluster.router("PUT", path(key), "{not json");
        assertEquals(400, badJson.status());
        assertEquals(400, json(badJson).get("code").asInt());

        Response wrongMethod = cluster.router("DELETE", path(key), null);
        assertEquals(405, wrongMethod.status());
        assertEquals(Set.of("code", "message"), Set.copyOf(json(wrongMethod).propertyNames()));
    }

    @Test
    void threeClientsIncrementThroughTheRouter() throws Exception {
        String key = newKey();
        cluster.router("PUT", path(key), "0");

        int clients = 3;
        int increments = 100;
        try (ExecutorService pool = Executors.newFixedThreadPool(clients)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int c = 0; c < clients; c++) {
                futures.add(pool.submit(() -> {
                    for (int i = 0; i < increments; i++) {
                        while (true) {
                            JsonNode current = json(cluster.router("GET", path(key), null));
                            int value = current.get("value").asInt();
                            int version = current.get("version").asInt();
                            Response put = cluster.router("PUT", path(key) + "?ifVersion=" + version,
                                    String.valueOf(value + 1));
                            if (put.status() == 200) {
                                break;
                            }
                            assertEquals(409, put.status());
                        }
                    }
                    return null;
                }));
            }
            for (Future<?> f : futures) {
                f.get();
            }
        }

        JsonNode result = json(cluster.router("GET", path(key), null));
        assertEquals(300, result.get("value").asInt());
        assertEquals(301, result.get("version").asInt());
    }

    @Test
    void listingIsNdjsonWithExactKeysAndOwners() throws Exception {
        try (TestCluster fresh = new TestCluster(3)) {
            Set<String> written = new HashSet<>();
            for (int i = 0; i < 30; i++) {
                written.add(newKey());
            }
            written.add("user:42");
            written.add("with space");
            written.add("line\nbreak \"quoted\" ünïcødé"); // must stay on one NDJSON line, escaped
            for (String key : written) {
                assertEquals(200, fresh.router("PUT", path(key), "{\"n\":1}").status());
            }

            Response list = fresh.router("GET", "/kv", null);
            assertEquals(200, list.status());
            assertNotNull(list.contentType());
            assertTrue(list.contentType().startsWith("application/x-ndjson"), list.contentType());
            assertTrue(list.body().endsWith("\n"));

            Set<String> listed = new HashSet<>();
            for (String line : list.body().split("\n")) {
                JsonNode entry = MAPPER.readTree(line);
                assertEquals(Set.of("key", "node"), Set.copyOf(entry.propertyNames()), line);
                String key = entry.get("key").asString();
                assertEquals(SLOTS.ownerOf(key), entry.get("node").asString(), line);
                assertTrue(listed.add(key), "duplicate " + key);
            }
            assertEquals(written, listed);
        }
    }

    @Test
    void emptyClusterListsNothing() throws Exception {
        try (TestCluster fresh = new TestCluster(2)) {
            Response list = fresh.router("GET", "/kv", null);
            assertEquals(200, list.status());
            assertEquals("", list.body());
        }
    }

    @Test
    void downNodeGives503ForItsKeysOnlyAndForTheListing() throws Exception {
        try (TestCluster fresh = new TestCluster(3)) {
            Map<String, String> keyPerNode = new HashMap<>();
            while (keyPerNode.size() < 3) {
                String key = newKey();
                keyPerNode.putIfAbsent(SLOTS.ownerOf(key), key);
            }
            for (String key : keyPerNode.values()) {
                assertEquals(200, fresh.router("PUT", path(key), "1").status());
            }

            fresh.stopNode("node-2");

            String downKey = keyPerNode.get("node-2");
            for (String method : List.of("GET", "PUT", "PATCH")) {
                Response response = fresh.router(method, path(downKey), method.equals("GET") ? null : "2");
                assertEquals(503, response.status(), method);
                assertEquals(MAPPER.readTree("{\"code\":503,\"message\":\"Node node-2 is unavailable\"}"),
                        json(response), method);
            }

            for (String nodeId : List.of("node-1", "node-3")) {
                Response put = fresh.router("PUT", path(keyPerNode.get(nodeId)), "5");
                assertEquals(200, put.status());
                assertEquals(2, json(put).get("version").asInt());
                assertEquals(200, fresh.router("GET", path(keyPerNode.get(nodeId)), null).status());
            }

            Response list = fresh.router("GET", "/kv", null);
            assertEquals(503, list.status());
            assertEquals(MAPPER.readTree("{\"code\":503,\"message\":\"Node node-2 is unavailable\"}"), json(list));
        }
    }
}

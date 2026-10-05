package org.example.codakvstore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class KvApiTests {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final HttpClient client = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int port;

    private record Result(int status, JsonNode body) {}

    private Result send(String method, String path, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        JsonNode json = response.body().isEmpty() ? null : MAPPER.readTree(response.body());
        return new Result(response.statusCode(), json);
    }

    private static String newKey() {
        return "k-" + UUID.randomUUID();
    }

    @Test
    void localKeyListingAndNodeHeader() throws Exception {
        String key = newKey();
        send("PUT", "/kv/" + key, "1");
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/internal/keys")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("node-1", response.headers().firstValue("X-Kv-Node").orElseThrow());
        JsonNode body = MAPPER.readTree(response.body());
        assertEquals("node-1", body.get("node").asString());
        assertTrue(body.get("keys").valueStream().anyMatch(k -> k.asString().equals(key)));
    }

    @Test
    void getMissingKeyReturns404() throws Exception {
        assertEquals(404, send("GET", "/kv/" + newKey(), null).status());
    }

    @Test
    void errorsReturnOnlyCodeAndMessage() throws Exception {
        String key = newKey();
        Result missing = send("GET", "/kv/" + key, null);
        assertEquals(MAPPER.readTree("{\"code\":404,\"message\":\"Key not found: " + key + "\"}"), missing.body());

        send("PUT", "/kv/" + key, "1");
        Result conflict = send("PUT", "/kv/" + key + "?ifVersion=5", "1");
        assertEquals(409, conflict.body().get("code").asInt());
        assertEquals(Set.of("code", "message"), Set.copyOf(conflict.body().propertyNames()));

        Result badJson = send("PUT", "/kv/" + key, "{not json");
        assertEquals(400, badJson.status());
        assertEquals(400, badJson.body().get("code").asInt());

        Result badVersion = send("PUT", "/kv/" + key + "?ifVersion=abc", "1");
        assertEquals(400, badVersion.status());
        assertEquals(Set.of("code", "message"), Set.copyOf(badVersion.body().propertyNames()));

        Result wrongMethod = send("DELETE", "/kv/" + key, null);
        assertEquals(405, wrongMethod.status());
        assertEquals(405, wrongMethod.body().get("code").asInt());
    }

    @Test
    void putThenGetAndVersionBumps() throws Exception {
        String key = newKey();
        Result first = send("PUT", "/kv/" + key, "{\"a\":1}");
        assertEquals(200, first.status());
        assertEquals(1, first.body().get("version").asInt());

        Result second = send("PUT", "/kv/" + key, "[1,2,3]");
        assertEquals(2, second.body().get("version").asInt());

        Result get = send("GET", "/kv/" + key, null);
        assertEquals(200, get.status());
        assertEquals(MAPPER.readTree("[1,2,3]"), get.body().get("value"));
        assertEquals(2, get.body().get("version").asInt());
    }

    @Test
    void ifVersionOnMissingKeyReturns404AndCreatesNothing() throws Exception {
        String key = newKey();
        Result put = send("PUT", "/kv/" + key + "?ifVersion=1", "1");
        assertEquals(404, put.status());
        assertEquals(MAPPER.readTree("{\"code\":404,\"message\":\"Key not found: " + key + "\"}"), put.body());
        assertEquals(404, send("PATCH", "/kv/" + key + "?ifVersion=1", "1").status());
        assertEquals(404, send("GET", "/kv/" + key, null).status());
    }

    @Test
    void staleIfVersionReturns409() throws Exception {
        String key = newKey();
        send("PUT", "/kv/" + key, "1");
        send("PUT", "/kv/" + key, "2");
        assertEquals(409, send("PUT", "/kv/" + key + "?ifVersion=1", "3").status());
        assertEquals(409, send("PATCH", "/kv/" + key + "?ifVersion=1", "3").status());
        Result ok = send("PUT", "/kv/" + key + "?ifVersion=2", "3");
        assertEquals(200, ok.status());
        assertEquals(3, ok.body().get("version").asInt());
    }

    @Test
    void patchCreatesMergesAndReplaces() throws Exception {
        String key = newKey();
        Result created = send("PATCH", "/kv/" + key, "{\"a\":1,\"b\":2}");
        assertEquals(200, created.status());
        assertEquals(1, created.body().get("version").asInt());

        Result merged = send("PATCH", "/kv/" + key, "{\"b\":3,\"c\":4}");
        assertEquals(MAPPER.readTree("{\"a\":1,\"b\":3,\"c\":4}"), merged.body().get("value"));
        assertEquals(2, merged.body().get("version").asInt());

        Result replaced = send("PATCH", "/kv/" + key, "\"plain\"");
        assertEquals(MAPPER.readTree("\"plain\""), replaced.body().get("value"));
        assertEquals(3, replaced.body().get("version").asInt());
    }

    @Test
    void concurrentCountersWithOptimisticRetry() throws Exception {
        String key = newKey();
        send("PUT", "/kv/" + key, "0");

        int clients = 3;
        int increments = 100;
        try (ExecutorService pool = Executors.newFixedThreadPool(clients)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int c = 0; c < clients; c++) {
                futures.add(pool.submit(() -> {
                    for (int i = 0; i < increments; i++) {
                        while (true) {
                            Result current = send("GET", "/kv/" + key, null);
                            int value = current.body().get("value").asInt();
                            int version = current.body().get("version").asInt();
                            Result put = send("PUT", "/kv/" + key + "?ifVersion=" + version, String.valueOf(value + 1));
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

        Result result = send("GET", "/kv/" + key, null);
        assertEquals(300, result.body().get("value").asInt());
        assertEquals(301, result.body().get("version").asInt());
    }
}

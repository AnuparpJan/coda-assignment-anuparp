package org.example.codakvrouter;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.example.codakvstore.CodaKvStoreApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

final class TestCluster implements AutoCloseable {

    record Response(int status, String contentType, String node, String body) {}

    private final Map<String, ConfigurableApplicationContext> nodes = new LinkedHashMap<>();
    private final Map<String, Integer> nodePorts = new LinkedHashMap<>();
    private final ConfigurableApplicationContext router;
    private final int routerPort;
    private final HttpClient client = HttpClient.newHttpClient();

    TestCluster(int nodeCount) {
        List<String> routerArgs = new ArrayList<>(List.of("--server.port=0"));
        for (int i = 1; i <= nodeCount; i++) {
            String id = "node-" + i;
            ConfigurableApplicationContext node = new SpringApplicationBuilder(CodaKvStoreApplication.class)
                    .run("--server.port=0", "--kv.node-id=" + id, "--spring.application.name=" + id);
            nodes.put(id, node);
            nodePorts.put(id, port(node));
            routerArgs.add("--kv.router.nodes." + id + "=http://localhost:" + port(node));
        }
        router = new SpringApplicationBuilder(CodaKvRouterApplication.class).run(routerArgs.toArray(String[]::new));
        routerPort = port(router);
    }

    private static int port(ConfigurableApplicationContext context) {
        return ((WebServerApplicationContext) context).getWebServer().getPort();
    }

    void stopNode(String id) {
        nodes.get(id).close();
    }

    Response router(String method, String pathAndQuery, String body) throws Exception {
        return send(routerPort, method, pathAndQuery, body);
    }

    Response node(String id, String method, String pathAndQuery, String body) throws Exception {
        return send(nodePorts.get(id), method, pathAndQuery, body);
    }

    private Response send(int port, String method, String pathAndQuery, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + pathAndQuery))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) {
            builder.header("Content-Type", "application/json");
        }
        HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(),
                response.headers().firstValue("Content-Type").orElse(null),
                response.headers().firstValue("X-Kv-Node").orElse(null),
                response.body());
    }

    @Override
    public void close() {
        router.close();
        nodes.values().forEach(ConfigurableApplicationContext::close);
    }
}

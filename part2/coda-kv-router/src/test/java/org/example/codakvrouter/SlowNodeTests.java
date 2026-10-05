package org.example.codakvrouter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import com.sun.net.httpserver.HttpServer;

class SlowNodeTests {

    private HttpServer slowNode;
    private final AtomicInteger requestsSeen = new AtomicInteger();
    private ConfigurableApplicationContext router;
    private int routerPort;

    @BeforeEach
    void start() throws Exception {
        slowNode = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        slowNode.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        slowNode.createContext("/", exchange -> {
            requestsSeen.incrementAndGet();
            try {
                Thread.sleep(3000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        slowNode.start();
        router = new SpringApplicationBuilder(CodaKvRouterApplication.class).run(
                "--server.port=0",
                "--kv.router.request-timeout=300ms",
                "--kv.router.nodes.node-1=http://localhost:" + slowNode.getAddress().getPort());
        routerPort = ((WebServerApplicationContext) router).getWebServer().getPort();
    }

    @AfterEach
    void stop() {
        router.close();
        slowNode.stop(0);
    }

    @Test
    void writeTimesOutWith503AndIsSentExactlyOnce() throws Exception {
        long started = System.nanoTime();
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + routerPort + "/kv/some-key"))
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString("1"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        assertEquals(503, response.statusCode());
        assertEquals("{\"code\":503,\"message\":\"Node node-1 did not respond in time; "
                + "the write may or may not have been applied\"}", response.body());
        assertTrue(elapsedMs < 2500, "took " + elapsedMs + " ms");
        Thread.sleep(500);
        assertEquals(1, requestsSeen.get(), "PUT must not be retried");
    }
}

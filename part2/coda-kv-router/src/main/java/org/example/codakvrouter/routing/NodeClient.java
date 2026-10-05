package org.example.codakvrouter.routing;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.example.codakvrouter.config.RouterProperties;
import org.example.codakvrouter.exception.NodeUnavailableException;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Component
@RequiredArgsConstructor
public class NodeClient {

    public static final String LOCAL_KEYS_PATH = "/internal/keys";

    public record LocalKeys(String node, List<String> keys) {}

    private final SlotTable slotTable;
    private final HttpClient nodeHttpClient;
    private final RouterProperties properties;
    private final JsonMapper jsonMapper;

    public HttpResponse<byte[]> send(String nodeId, String method, String rawPathAndQuery, String contentType,
                                     byte[] body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(slotTable.baseUrl(nodeId) + rawPathAndQuery))
                .timeout(properties.requestTimeout())
                .method(method, body.length == 0
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(body));
        if (contentType != null) {
            builder.header("Content-Type", contentType);
        }

        boolean isRead = "GET".equals(method);
        try {
            return nodeHttpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (ConnectException | HttpConnectTimeoutException e) {
            throw unavailable(nodeId);
        } catch (HttpTimeoutException e) {
            throw new NodeUnavailableException(isRead
                    ? "Node " + nodeId + " did not respond in time"
                    : "Node " + nodeId + " did not respond in time; the write may or may not have been applied");
        } catch (IOException e) {
            throw new NodeUnavailableException(isRead
                    ? "Node " + nodeId + " is unavailable"
                    : "Node " + nodeId + " failed mid-request; the write may or may not have been applied");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable(nodeId);
        }
    }

    public CompletableFuture<LocalKeys> fetchLocalKeys(String nodeId) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(slotTable.baseUrl(nodeId) + LOCAL_KEYS_PATH))
                .timeout(properties.requestTimeout())
                .GET()
                .build();
        return nodeHttpClient.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                .handle((response, error) -> {
                    if (error != null || response.statusCode() != 200) {
                        throw unavailable(nodeId);
                    }
                    LocalKeys localKeys;
                    try {
                        localKeys = jsonMapper.readValue(response.body(), LocalKeys.class);
                    } catch (JacksonException e) {
                        throw new NodeUnavailableException("Node " + nodeId + " returned an unreadable key listing");
                    }
                    if (!nodeId.equals(localKeys.node())) {
                        throw new NodeUnavailableException("Node " + nodeId + " is misconfigured: "
                                + slotTable.baseUrl(nodeId) + " reports node id '" + localKeys.node() + "'");
                    }
                    return localKeys;
                });
    }

    private static NodeUnavailableException unavailable(String nodeId) {
        return new NodeUnavailableException("Node " + nodeId + " is unavailable");
    }
}

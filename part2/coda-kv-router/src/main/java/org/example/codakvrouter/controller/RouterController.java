package org.example.codakvrouter.controller;

import java.io.IOException;
import java.io.OutputStream;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.example.codakvrouter.exception.NodeUnavailableException;
import org.example.codakvrouter.routing.NodeClient;
import org.example.codakvrouter.routing.NodeClient.LocalKeys;
import org.example.codakvrouter.routing.SlotTable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequiredArgsConstructor
public class RouterController {

    public static final String NODE_HEADER = "X-Kv-Node";

    private record KeyLocation(String key, String node) {}

    private final SlotTable slotTable;
    private final NodeClient nodeClient;
    private final JsonMapper jsonMapper;

    @RequestMapping(path = "/kv/{key}", method = {RequestMethod.GET, RequestMethod.PUT, RequestMethod.PATCH})
    public void proxy(@PathVariable String key, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        String nodeId = slotTable.ownerOf(key);
        String rawPath = request.getRequestURI().substring(request.getContextPath().length());

        String query = request.getQueryString();
        byte[] body = request.getInputStream().readAllBytes();
        HttpResponse<byte[]> nodeResponse = nodeClient.send(nodeId, request.getMethod(),
                rawPath + (query == null ? "" : "?" + query), request.getContentType(), body);

        response.setStatus(nodeResponse.statusCode());
        nodeResponse.headers().firstValue("Content-Type").ifPresent(response::setContentType);
        response.setHeader(NODE_HEADER, nodeId);
        response.getOutputStream().write(nodeResponse.body());
    }

    @GetMapping("/kv")
    public void listKeys(HttpServletResponse response) throws IOException {
        List<String> nodeIds = slotTable.nodeIds();
        List<CompletableFuture<LocalKeys>> futures = new ArrayList<>();
        for (String nodeId : nodeIds) {
            futures.add(nodeClient.fetchLocalKeys(nodeId));
        }

        List<LocalKeys> listings = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        for (CompletableFuture<LocalKeys> future : futures) {
            try {
                listings.add(future.join());
            } catch (CompletionException e) {
                if (!(e.getCause() instanceof NodeUnavailableException unavailable)) {
                    throw e;
                }
                failures.add(unavailable.getMessage());
            }
        }
        if (!failures.isEmpty()) {
            throw new NodeUnavailableException(String.join("; ", failures));
        }

        response.setContentType("application/x-ndjson");
        OutputStream out = response.getOutputStream();
        for (LocalKeys listing : listings) {
            for (String key : listing.keys()) {
                out.write(jsonMapper.writeValueAsBytes(new KeyLocation(key, listing.node())));
                out.write('\n');
            }
        }
    }
}

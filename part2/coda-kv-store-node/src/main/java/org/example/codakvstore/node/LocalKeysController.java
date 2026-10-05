package org.example.codakvstore.node;

import java.util.List;
import org.example.codakvstore.service.KvService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Keys stored on this node only. Called by the router to build the cluster-wide {@code GET /kv};
 * it never fans out itself.
 */
@RestController
public class LocalKeysController {

    public record LocalKeysResponse(String node, List<String> keys) {}

    private final KvService kvService;
    private final String nodeId;

    public LocalKeysController(KvService kvService, @Value("${kv.node-id}") String nodeId) {
        this.kvService = kvService;
        this.nodeId = nodeId;
    }

    @GetMapping("/internal/keys")
    public LocalKeysResponse localKeys() {
        return new LocalKeysResponse(nodeId, kvService.listKeys());
    }
}

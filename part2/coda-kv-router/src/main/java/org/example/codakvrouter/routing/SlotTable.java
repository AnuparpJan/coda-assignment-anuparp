package org.example.codakvrouter.routing;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.CRC32;

public final class SlotTable {

    public static final int SLOT_COUNT = 16384;

    private final Map<String, URI> nodeUrls;
    private final List<String> nodeIds;
    private final String[] slotOwners = new String[SLOT_COUNT];

    public SlotTable(Map<String, String> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            throw new IllegalStateException("No storage nodes configured; set kv.router.nodes.<node-id>=<base url>");
        }
        Map<String, URI> urls = new TreeMap<>();
        nodes.forEach((id, url) -> urls.put(id, URI.create(url.endsWith("/") ? url.substring(0, url.length() - 1) : url)));
        this.nodeUrls = urls;
        this.nodeIds = List.copyOf(urls.keySet());
        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            slotOwners[slot] = nodeIds.get((int) ((long) slot * nodeIds.size() / SLOT_COUNT));
        }
    }

    public static int slotFor(String key) {
        CRC32 crc = new CRC32();
        crc.update(key.getBytes(StandardCharsets.UTF_8));
        return (int) (crc.getValue() % SLOT_COUNT);
    }

    public String ownerOf(String key) {
        return slotOwners[slotFor(key)];
    }

    public List<String> nodeIds() {
        return nodeIds;
    }

    public URI baseUrl(String nodeId) {
        return nodeUrls.get(nodeId);
    }
}

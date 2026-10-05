package org.example.codakvstore.service;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import org.example.codakvstore.controller.dto.GetKeyResponseDto;
import org.example.codakvstore.controller.dto.PatchKeyResponseDto;
import org.example.codakvstore.controller.dto.PutKeyResponseDto;
import org.example.codakvstore.exception.KeyNotFoundException;
import org.example.codakvstore.exception.VersionConflictException;
import org.example.codakvstore.model.KvEntry;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

@Service
public class KvService {

    private final ConcurrentMap<String, KvEntry> keyValueMap = new ConcurrentHashMap<>();

    public GetKeyResponseDto getKey(String key) {
        KvEntry kvEntry = keyValueMap.get(key);
        if (kvEntry == null) {
            throw new KeyNotFoundException(key);
        }

        return GetKeyResponseDto.builder().key(key).value(kvEntry.value()).version(kvEntry.version()).build();
    }

    public List<String> listKeys() {
        return keyValueMap.keySet().stream().sorted().toList();
    }

    public PutKeyResponseDto putKey(String key, JsonNode value, Integer ifVersion) {
        KvEntry kvEntry = writeKey(key, ifVersion, _ -> value.deepCopy());

        return PutKeyResponseDto.builder().key(key).value(kvEntry.value()).version(kvEntry.version()).build();
    }

    public PatchKeyResponseDto patchKey(String key, JsonNode delta, Integer ifVersion) {
        KvEntry kvEntry = writeKey(key, ifVersion, current -> mergeJson(current, delta));

        return PatchKeyResponseDto.builder().key(key).value(kvEntry.value()).version(kvEntry.version()).build();
    }

    private KvEntry writeKey(String key, Integer ifVersion, Function<JsonNode, JsonNode> update) {
        return keyValueMap.compute(key, (_, existing) -> {
            Integer currentVersion = existing == null ? null : existing.version();

            if (ifVersion != null) {
                if (currentVersion == null) {
                    throw new KeyNotFoundException(key);
                }
                if (!ifVersion.equals(currentVersion)) {
                    throw new VersionConflictException(key, ifVersion, currentVersion);
                }
            }
            JsonNode newValue = update.apply(existing == null ? null : existing.value());

            return new KvEntry(newValue, currentVersion == null ? 1 : currentVersion + 1);
        });
    }

    private JsonNode mergeJson(JsonNode current, JsonNode delta) {
        if (current instanceof ObjectNode currentObject && delta instanceof ObjectNode deltaObject) {
            ObjectNode merged = currentObject.deepCopy();
            merged.setAll(deltaObject.deepCopy());
            return merged;
        }

        return delta.deepCopy();
    }
}

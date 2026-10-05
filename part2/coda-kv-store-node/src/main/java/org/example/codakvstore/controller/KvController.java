package org.example.codakvstore.controller;

import org.example.codakvstore.controller.dto.GetKeyResponseDto;
import org.example.codakvstore.controller.dto.PatchKeyResponseDto;
import org.example.codakvstore.controller.dto.PutKeyResponseDto;
import org.example.codakvstore.service.KvService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/kv")
@RequiredArgsConstructor
public class KvController {
    private final KvService kvService;

    @GetMapping("/{key}")
    public GetKeyResponseDto getKey(@PathVariable String key) {
        return kvService.getKey(key);
    }

    @PutMapping("/{key}")
    public PutKeyResponseDto putKey(@PathVariable String key, @RequestBody JsonNode value,
                                    @RequestParam(required = false) Integer ifVersion) {
        return kvService.putKey(key, value, ifVersion);
    }

    @PatchMapping("/{key}")
    public PatchKeyResponseDto patchKey(@PathVariable String key, @RequestBody JsonNode delta,
                                        @RequestParam(required = false) Integer ifVersion) {
        return kvService.patchKey(key, delta, ifVersion);
    }
}

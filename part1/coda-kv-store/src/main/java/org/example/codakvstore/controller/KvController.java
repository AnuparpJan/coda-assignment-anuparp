package org.example.codakvstore.controller;

import java.nio.charset.StandardCharsets;
import org.example.codakvstore.controller.dto.GetKeyResponseDto;
import org.example.codakvstore.controller.dto.PatchKeyResponseDto;
import org.example.codakvstore.controller.dto.PutKeyResponseDto;
import org.example.codakvstore.exception.BadRequestException;
import org.example.codakvstore.service.KvService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriUtils;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/kv")
@RequiredArgsConstructor
public class KvController {
    private final KvService kvService;

    @GetMapping("/{key}")
    public GetKeyResponseDto getKey(@PathVariable String key, HttpServletRequest request) {
        return kvService.getKey(rawKey(request));
    }

    @PutMapping("/{key}")
    public PutKeyResponseDto putKey(@PathVariable String key, @RequestBody JsonNode value,
                                    @RequestParam(required = false) Integer ifVersion, HttpServletRequest request) {
        requireWellFormedIfVersion(request);
        return kvService.putKey(rawKey(request), value, ifVersion);
    }

    @PatchMapping("/{key}")
    public PatchKeyResponseDto patchKey(@PathVariable String key, @RequestBody JsonNode delta,
                                        @RequestParam(required = false) Integer ifVersion, HttpServletRequest request) {
        requireWellFormedIfVersion(request);
        return kvService.patchKey(rawKey(request), delta, ifVersion);
    }

    // Spring strips ";..." (matrix variables) from @PathVariable, so PUT /kv/a;b would silently write key "a".
    // Take the key from the raw URI instead so every character after /kv/ is part of the key.
    private static String rawKey(HttpServletRequest request) {
        String prefix = request.getContextPath() + "/kv/";
        return UriUtils.decode(request.getRequestURI().substring(prefix.length()), StandardCharsets.UTF_8);
    }

    // An empty (?ifVersion=) or repeated ifVersion would otherwise bind to null or the first value,
    // turning an intended conditional write into an unconditional or ambiguous one.
    private static void requireWellFormedIfVersion(HttpServletRequest request) {
        String[] values = request.getParameterValues("ifVersion");
        if (values != null && (values.length != 1 || values[0].isBlank())) {
            throw new BadRequestException("Invalid value for parameter 'ifVersion'");
        }
    }
}

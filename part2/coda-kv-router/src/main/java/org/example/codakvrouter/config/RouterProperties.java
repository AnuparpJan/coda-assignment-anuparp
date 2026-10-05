package org.example.codakvrouter.config;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("kv.router")
public record RouterProperties(
        Map<String, String> nodes,
        @DefaultValue("500ms") Duration connectTimeout,
        @DefaultValue("2s") Duration requestTimeout) {}

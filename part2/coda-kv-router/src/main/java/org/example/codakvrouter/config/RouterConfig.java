package org.example.codakvrouter.config;

import java.net.http.HttpClient;
import org.example.codakvrouter.routing.SlotTable;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RouterConfig {

    @Bean
    public SlotTable slotTable(RouterProperties properties) {
        return new SlotTable(properties.nodes());
    }

    @Bean
    public HttpClient nodeHttpClient(RouterProperties properties) {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout())
                .build();
    }
}

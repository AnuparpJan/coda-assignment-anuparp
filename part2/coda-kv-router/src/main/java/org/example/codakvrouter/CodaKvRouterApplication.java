package org.example.codakvrouter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class CodaKvRouterApplication {

    public static void main(String[] args) {
        SpringApplication.run(CodaKvRouterApplication.class, args);
    }
}

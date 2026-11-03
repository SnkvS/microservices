package com.example.processor;

import com.example.processor.metadata.Mp3MetadataExtractor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

class ResourceProcessorApplicationTest {

    @Test
    void exposesHealthAndProvidesMetadataExtraction() throws Exception {
        var application = new SpringApplication(ResourceProcessorApplication.class);
        try (var context = application.run("--server.port=0", "--eureka.client.enabled=false",
                "--management.health.rabbit.enabled=false",
                "--spring.rabbitmq.listener.simple.auto-startup=false")) {
            assertThat(application.getWebApplicationType()).isEqualTo(WebApplicationType.SERVLET);
            var server = (ServletWebServerApplicationContext) context;
            var client = HttpClient.newHttpClient();
            var base = "http://127.0.0.1:" + server.getWebServer().getPort();
            var health = client.send(HttpRequest.newBuilder(URI.create(base + "/actuator/health")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(health.statusCode()).isEqualTo(200);
            assertThat(health.body()).contains("\"status\":\"UP\"");
            var otherEndpoint = client.send(HttpRequest.newBuilder(URI.create(base + "/actuator/env")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(otherEndpoint.statusCode()).isEqualTo(404);
            assertThat(context.getBean(Mp3MetadataExtractor.class)).isNotNull();
        }
    }
}

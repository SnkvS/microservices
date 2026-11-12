package com.example.gateway;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

import static org.springframework.web.reactive.function.server.RouterFunctions.route;
import static org.springframework.web.reactive.function.server.RequestPredicates.GET;

@SpringBootTest(classes = {ApiGatewayApplication.class, GatewayErrorHandlingTest.Failures.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "eureka.client.enabled=false")
class GatewayErrorHandlingTest {
    private static HttpServer slowService;
    private static final ExecutorService executor = Executors.newCachedThreadPool();

    @Autowired
    private WebTestClient client;

    @DynamicPropertySource
    static void services(DynamicPropertyRegistry properties) throws IOException {
        try (ServerSocket unused = new ServerSocket(0)) {
            String address = "http://127.0.0.1:" + unused.getLocalPort();
            properties.add("spring.cloud.discovery.client.simple.instances.offline[0].uri", () -> address);
        }
        slowService = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        slowService.setExecutor(executor);
        slowService.createContext("/slow", exchange -> {
            try {
                Thread.sleep(1000);
                exchange.sendResponseHeaders(200, -1);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        slowService.start();
        properties.add("spring.cloud.discovery.client.simple.instances.slow[0].uri",
                () -> "http://127.0.0.1:" + slowService.getAddress().getPort());
        String prefix = "spring.cloud.gateway.server.webflux.routes";
        // A higher-priority list replaces the configured list; include its first entries.
        properties.add(prefix + "[0].id", () -> "song-service");
        properties.add(prefix + "[0].uri", () -> "lb://song-service");
        properties.add(prefix + "[0].predicates[0]", () -> "Path=/songs,/songs/**");
        properties.add(prefix + "[1].id", () -> "resource-service");
        properties.add(prefix + "[1].uri", () -> "lb://resource-service");
        properties.add(prefix + "[1].predicates[0]", () -> "Path=/resources,/resources/**");
        properties.add(prefix + "[2].id", () -> "offline");
        properties.add(prefix + "[2].uri", () -> "lb://offline");
        properties.add(prefix + "[2].predicates[0]", () -> "Path=/offline");
        properties.add(prefix + "[3].id", () -> "slow");
        properties.add(prefix + "[3].uri", () -> "lb://slow");
        properties.add(prefix + "[3].predicates[0]", () -> "Path=/slow");
        properties.add(prefix + "[3].metadata.response-timeout", () -> 100);
    }

    @AfterAll
    static void stopService() {
        if (slowService != null) slowService.stop(0);
        executor.shutdownNow();
    }

    @Test
    void undefinedRouteHasFriendlyJsonErrorEvenForHtmlClients() {
        assertError("/undefined", MediaType.TEXT_HTML, 404, "Route not found");
    }

    @Test
    void noDiscoveredInstanceHasFriendlyUnavailableError() {
        assertError("/songs/1", MediaType.APPLICATION_JSON, 503, "Service unavailable");
    }

    @Test
    void refusedConnectionHasFriendlyUnavailableError() {
        assertError("/offline", MediaType.APPLICATION_JSON, 503, "Service unavailable");
    }

    @Test
    void slowUpstreamHasFriendlyTimeoutError() {
        assertError("/slow", MediaType.APPLICATION_JSON, 504, "Service request timed out");
    }

    @Test
    void unexpectedFailureDoesNotExposeExceptionDetails() {
        assertError("/test-error", MediaType.APPLICATION_JSON, 500, "Internal server error");
    }

    @Test
    void healthEndpointRemainsAvailable() {
        client.get().uri("/actuator/health").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("UP");
    }

    private void assertError(String path, MediaType accept, int status, String message) {
        client.get().uri(path).accept(accept).exchange().expectStatus().isEqualTo(status)
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody().json("{\"errorCode\":\"" + status + "\",\"errorMessage\":\"" + message + "\"}", true);
    }

    @TestConfiguration
    static class Failures {
        @Bean
        RouterFunction<ServerResponse> failureRoute() {
            return route(GET("/test-error"), request -> Mono.error(new IllegalStateException("private target address")));
        }
    }
}

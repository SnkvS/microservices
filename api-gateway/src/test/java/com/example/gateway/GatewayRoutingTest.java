package com.example.gateway;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = ApiGatewayApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"eureka.client.enabled=false", "spring.cloud.loadbalancer.cache.enabled=false"})
class GatewayRoutingTest {
    private static final byte[] MP3 = new byte[]{'I', 'D', '3', 0, 1, (byte) 255, 42};
    private static final List<String> songRequests = new CopyOnWriteArrayList<>();
    private static final List<byte[]> uploads = new CopyOnWriteArrayList<>();
    private static final List<String> resourceRequests = new CopyOnWriteArrayList<>();
    private static HttpServer firstSong;
    private static HttpServer secondSong;
    private static HttpServer resource;

    @Autowired
    private WebTestClient client;

    @DynamicPropertySource
    static void discoveredServices(DynamicPropertyRegistry properties) throws IOException {
        firstSong = songServer("first");
        secondSong = songServer("second");
        resource = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        resource.createContext("/resources", exchange -> {
            resourceRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            byte[] response;
            String contentType;
            switch (exchange.getRequestMethod()) {
                case "POST" -> {
                    uploads.add(exchange.getRequestBody().readAllBytes());
                    contentType = "application/json";
                    response = "{\"id\":1}".getBytes(StandardCharsets.UTF_8);
                }
                case "DELETE" -> {
                    contentType = "application/json";
                    response = "{\"ids\":[1,2]}".getBytes(StandardCharsets.UTF_8);
                }
                default -> {
                    contentType = "audio/mpeg";
                    response = MP3;
                }
            }
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        resource.start();
        properties.add("spring.cloud.discovery.client.simple.instances.song-service[0].uri",
                () -> address(firstSong));
        properties.add("spring.cloud.discovery.client.simple.instances.song-service[1].uri",
                () -> address(secondSong));
        properties.add("spring.cloud.discovery.client.simple.instances.resource-service[0].uri",
                () -> address(resource));
    }

    @Test
    void balancesSongRequestsAndPreservesDownstreamErrors() {
        songRequests.clear();
        for (int i = 0; i < 4; i++) {
            client.get().uri("/songs/1").exchange().expectStatus().isOk()
                    .expectHeader().contentType(MediaType.APPLICATION_JSON)
                    .expectBody().jsonPath("$.id").isEqualTo(1);
        }
        assertThat(songRequests).containsExactlyInAnyOrder("first", "first", "second", "second");
        client.get().uri("/songs/999").exchange().expectStatus().isNotFound()
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody().json("{\"errorCode\":\"404\",\"errorMessage\":\"Song not found\"}");
    }

    @Test
    void preservesMp3BytesAndDeleteQuery() {
        client.post().uri("/resources").contentType(MediaType.parseMediaType("audio/mpeg"))
                .bodyValue(MP3).exchange().expectStatus().isOk().expectBody().json("{\"id\":1}");
        assertThat(uploads).hasSize(1);
        assertThat(uploads.get(0)).isEqualTo(MP3);
        client.get().uri("/resources/1").exchange().expectStatus().isOk()
                .expectHeader().contentType("audio/mpeg")
                .expectHeader().contentLength(MP3.length)
                .expectBody(byte[].class).isEqualTo(MP3);
        client.delete().uri("/resources?id=1,2").exchange().expectStatus().isOk()
                .expectBody().json("{\"ids\":[1,2]}");
        assertThat(resourceRequests).containsExactly("POST /resources", "GET /resources/1",
                "DELETE /resources?id=1,2");
    }

    private static HttpServer songServer(String instance) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/songs", exchange -> {
            songRequests.add(instance);
            boolean missing = exchange.getRequestURI().getPath().equals("/songs/999");
            byte[] response = (missing
                    ? "{\"errorCode\":\"404\",\"errorMessage\":\"Song not found\"}" : "{\"id\":1}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(missing ? 404 : 200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static String address(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopServers() {
        for (HttpServer server : new HttpServer[]{firstSong, secondSong, resource}) {
            if (server != null) server.stop(0);
        }
    }
}

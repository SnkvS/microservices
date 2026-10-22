package com.example.resource.integration;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.retry.RetryContext;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryListener;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = SongClientDiscoveryTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"eureka.client.enabled=false", "spring.cloud.loadbalancer.cache.enabled=false",
                "song.service.url=http://song-service", "communication.http.initial-delay=1", "communication.http.max-delay=2"})
class SongClientDiscoveryTest {

    private static final List<String> firstRequests = new CopyOnWriteArrayList<>();

    private static final List<String> secondRequests = new CopyOnWriteArrayList<>();

    private static HttpServer firstServer;

    private static HttpServer secondServer;

    @Autowired
    private SongClient songClient;

    @Autowired @LoadBalanced RestClient.Builder builder;
    @Autowired @Qualifier("songHttpRetry") RetryTemplate retry;

    @Test
    void missingDiscoveryInstanceIsRetriedAsATemporaryCommunicationFailure() {
        AtomicInteger failures = new AtomicInteger();
        RetryListener listener = new RetryListener() {
            @Override
            public <T, E extends Throwable> void onError(RetryContext context, RetryCallback<T, E> callback, Throwable throwable) {
                failures.incrementAndGet();
            }
        };
        retry.registerListener(listener);
        try {
            SongClient unavailable = new SongClient(builder, "http://not-registered", retry);
            assertThatThrownBy(() -> unavailable.delete(List.of(42L))).isInstanceOf(ResourceAccessException.class);
            assertThat(failures).hasValue(3);
        } finally {
            retry.setListeners(new RetryListener[0]);
        }
    }

    @DynamicPropertySource
    static void discoveredInstances(DynamicPropertyRegistry properties) throws IOException {
        firstServer = startServer(firstRequests);
        secondServer = startServer(secondRequests);
        properties.add("spring.cloud.discovery.client.simple.instances.song-service[0].uri",
                () -> "http://127.0.0.1:" + firstServer.getAddress().getPort());
        properties.add("spring.cloud.discovery.client.simple.instances.song-service[1].uri",
                () -> "http://127.0.0.1:" + secondServer.getAddress().getPort());
    }

    private static HttpServer startServer(List<String> requests) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/songs", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI() + " " + body);
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        return server;
    }

    @AfterAll
    static void stopServers() {
        if (firstServer != null) firstServer.stop(0);
        if (secondServer != null) secondServer.stop(0);
    }

    @Test
    void routesDeleteRequestsAcrossDiscoveredInstances() {
        songClient.delete(List.of(101L));
        songClient.delete(List.of(102L));
        songClient.delete(List.of(101L, 102L));
        songClient.delete(List.of(101L, 102L));

        for (List<String> requests : List.of(firstRequests, secondRequests)) {
            assertThat(requests).hasSize(2);
            assertThat(requests.get(0)).startsWith("DELETE /songs?id=");
            assertThat(requests.get(1)).isEqualTo("DELETE /songs?id=101,102 ");
        }
        assertThat(List.of(firstRequests.get(0), secondRequests.get(0)))
                .containsExactlyInAnyOrder("DELETE /songs?id=101 ", "DELETE /songs?id=102 ");
        int requestCount = firstRequests.size() + secondRequests.size();
        songClient.delete(List.of());
        assertThat(firstRequests.size() + secondRequests.size()).isEqualTo(requestCount);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class})
    @ComponentScan(basePackageClasses = SongClient.class)
    static class TestApplication {

    }
}

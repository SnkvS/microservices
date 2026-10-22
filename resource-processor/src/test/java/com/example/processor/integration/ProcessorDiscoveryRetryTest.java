package com.example.processor.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.RetryListener;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.List;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "eureka.client.enabled=false", "spring.main.keep-alive=false",
        "spring.rabbitmq.listener.simple.auto-startup=false", "resource.service.url=http://not-registered",
        "communication.http.initial-delay=1", "communication.http.max-delay=2"})
class ProcessorDiscoveryRetryTest {

    @Autowired ResourceClient resources;
    @Autowired @Qualifier("processorHttpRetry") RetryTemplate retry;
    @MockitoBean DiscoveryClient discovery;

    @Test
    void instanceAppearingDuringRetryAllowsTheBinaryDownloadToFinish() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/resources/42", exchange -> {
            byte[] bytes = {1, 2, 3};
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            when(discovery.getInstances("not-registered")).thenReturn(List.of(),
                    List.of(new DefaultServiceInstance("late", "not-registered", "127.0.0.1", server.getAddress().getPort(), false)));
            assertThat(resources.download(42)).containsExactly(1, 2, 3);
            verify(discovery, times(2)).getInstances("not-registered");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void absentServiceDiscoveryInstanceRemainsRetryableAfterHttpExhaustion() {
        AtomicInteger failures = new AtomicInteger();
        RetryListener listener = new RetryListener() {
            @Override
            public <T, E extends Throwable> void onError(RetryContext context, RetryCallback<T, E> callback, Throwable throwable) {
                failures.incrementAndGet();
            }
        };
        retry.registerListener(listener);
        try {
            assertThatThrownBy(() -> resources.download(42)).isInstanceOf(ResourceAccessException.class)
                    .satisfies(error -> assertThat(ProcessorClientConfiguration.isTransient(error)).isTrue());
            assertThat(failures).hasValue(3);
        } finally {
            retry.setListeners(new RetryListener[0]);
        }
    }
}

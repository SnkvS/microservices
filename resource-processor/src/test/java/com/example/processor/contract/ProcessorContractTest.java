package com.example.processor.contract;

import com.example.processor.integration.ResourceClient;
import com.example.processor.integration.SongClient;
import com.example.processor.integration.SongMetadata;
import com.example.processor.messaging.ResourceMessagingConfiguration;
import com.example.processor.messaging.ResourceUploadedListener;
import com.example.processor.service.ResourceProcessingService;
import com.example.processor.support.AmqpContractConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.contract.stubrunner.StubFinder;
import org.springframework.cloud.contract.stubrunner.spring.AutoConfigureStubRunner;
import org.springframework.cloud.contract.stubrunner.spring.StubRunnerProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.verify;

@Testcontainers
@ActiveProfiles("contract-test")
@SpringBootTest(classes = ProcessorContractTest.Config.class, webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"eureka.client.enabled=false", "stubrunner.integration.enabled=false", "stubrunner.stream.enabled=false"})
@AutoConfigureStubRunner(ids = {"com.example.microservices:resource-service:1.0.0:stubs",
        "com.example.microservices:song-service:1.0.0:stubs"}, stubsMode = StubRunnerProperties.StubsMode.LOCAL)
class ProcessorContractTest {
    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.1-management");

    @DynamicPropertySource
    static void broker(DynamicPropertyRegistry properties) {
        properties.add("spring.rabbitmq.host", RABBIT::getHost);
        properties.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        properties.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        properties.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
    }

    @Configuration(proxyBeanMethods = false)
    @Profile("contract-test")
    @EnableAutoConfiguration
    @Import({ResourceMessagingConfiguration.class, ResourceUploadedListener.class, AmqpContractConfiguration.class})
    static class Config { }

    @Autowired StubFinder stubs;
    @MockitoBean ResourceProcessingService processing;

    private RetryTemplate retry() {
        return RetryTemplate.builder().maxAttempts(1).fixedBackoff(1).build();
    }

    @Test
    void resourceClientReadsExactBinaryContentAndMissingResources() throws Exception {
        var resources = new ResourceClient(RestClient.builder(), stubs.findStubUrl("resource-service").toString(), retry());
        try (var input = getClass().getResourceAsStream("/mp3/valid.mp3")) {
            assertThat(resources.download(42)).containsExactly(input.readAllBytes());
        }
        assertThatThrownBy(() -> resources.download(99)).isInstanceOf(HttpClientErrorException.NotFound.class);
    }

    @Test
    void songClientCreatesMetadataAndHandlesIdenticalAndDifferentConflicts() {
        var songs = new SongClient(RestClient.builder(), stubs.findStubUrl("song-service").toString(), retry());
        assertThatNoException().isThrownBy(() -> songs.save(metadata(42, "00:07")));
        assertThatNoException().isThrownBy(() -> songs.save(metadata(43, "00:07")));
        assertThatThrownBy(() -> songs.save(metadata(44, "00:07"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> songs.save(metadata(45, "01:60"))).isInstanceOf(HttpClientErrorException.BadRequest.class);
    }

    @Test
    void publishedMessageStubReachesTheRealListener() {
        assertThat(stubs.trigger("resource_uploaded")).isTrue();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> verify(processing).process(42));
    }

    private SongMetadata metadata(long id, String duration) {
        return new SongMetadata(id, "Test Title", "Test Artist", "Test Album", duration, "2025");
    }
}

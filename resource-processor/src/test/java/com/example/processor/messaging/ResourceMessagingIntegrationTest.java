package com.example.processor.messaging;

import com.example.processor.service.ResourceProcessingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.ResourceAccessException;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "eureka.client.enabled=false", "spring.main.keep-alive=false",
        "resource.messaging.retry.initial-delay=10", "resource.messaging.retry.max-delay=20"})
class ResourceMessagingIntegrationTest {

    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.1-management");

    @Autowired RabbitTemplate rabbit;
    @Autowired AmqpAdmin admin;
    @Autowired RabbitListenerEndpointRegistry listeners;
    @MockitoBean ResourceProcessingService service;

    @DynamicPropertySource
    static void broker(DynamicPropertyRegistry properties) {
        properties.add("spring.rabbitmq.host", RABBIT::getHost);
        properties.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        properties.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        properties.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
    }

    @BeforeEach
    void clearQueues() {
        admin.purgeQueue("resource.processing", false);
        admin.purgeQueue("resource.processing.dlq", false);
    }

    @Test
    void transientFailureIsRetriedThenAcknowledged() {
        AtomicInteger attempts = new AtomicInteger();
        doAnswer(call -> {
            if (attempts.incrementAndGet() == 1) throw new ResourceAccessException("offline");
            return null;
        }).when(service).process(42);
        send("{\"resourceId\":42}");
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> verify(service, times(2)).process(42));
        assertThat(rabbit.receive("resource.processing.dlq", 200)).isNull();
    }

    @Test
    void completionPublicationFailureRetriesMetadataAndHoldsAcknowledgementUntilSuccess() throws Exception {
        var resources=mock(com.example.processor.integration.ResourceClient.class);
        var songs=mock(com.example.processor.integration.SongClient.class);
        var publisher=mock(ResourceProcessedPublisher.class);
        var processing=new ResourceProcessingService(resources,new com.example.processor.metadata.Mp3MetadataExtractor(),songs,publisher);
        try(var input=getClass().getResourceAsStream("/mp3/valid.mp3")) { when(resources.download(47)).thenReturn(input.readAllBytes()); }
        var waiting=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);
        var attempts=new AtomicInteger();
        doAnswer(call->{
            if(attempts.incrementAndGet()==1)
                throw new ResourceProcessedPublicationException(47,new org.springframework.amqp.AmqpException("completion unavailable"));
            waiting.countDown();
            if(!release.await(30,java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("Publication was not released");
            return null;
        }).when(publisher).publish(47);
        doAnswer(call->{processing.process(47);return null;}).when(service).process(47);
        try {
            send("{\"resourceId\":47}");
            assertThat(waiting.await(15,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            await().atMost(Duration.ofSeconds(20)).untilAsserted(()->
                    assertThat(processingQueue().path("messages_unacknowledged").asInt()).isEqualTo(1));
            release.countDown();
            await().atMost(Duration.ofSeconds(15)).untilAsserted(()->{
                assertThat(processingQueue().path("messages_unacknowledged").asInt()).isZero();
                verify(publisher,times(2)).publish(47);
            });
            verify(songs,times(2)).save(new com.example.processor.integration.SongMetadata(47L,
                    "Test Title","Test Artist","Test Album","00:07","2025"));
            assertThat(rabbit.receive("resource.processing.dlq",300)).isNull();
        } finally { release.countDown(); }
    }

    private com.fasterxml.jackson.databind.JsonNode processingQueue() {
        String url="http://"+RABBIT.getHost()+":"+RABBIT.getHttpPort()+"/api/queues/%2F/resource.processing";
        return org.springframework.web.client.RestClient.builder()
                .defaultHeaders(h->h.setBasicAuth(RABBIT.getAdminUsername(),RABBIT.getAdminPassword()))
                .build().get().uri(java.net.URI.create(url)).retrieve().body(com.fasterxml.jackson.databind.JsonNode.class);
    }

    @Test
    void exhaustedTransientFailureReachesDeadLetterQueueWithOriginalBody() {
        doThrow(new ResourceAccessException("offline")).when(service).process(43);
        send("{\"resourceId\":43}");
        Message failed = rabbit.receive("resource.processing.dlq", 15000);
        assertThat(failed).isNotNull();
        assertThat(new String(failed.getBody(), StandardCharsets.UTF_8)).isEqualTo("{\"resourceId\":43}");
        verify(service, times(5)).process(43);
    }

    @Test
    void permanentFailureIsRejectedWithoutRepeatedProcessing() {
        doThrow(new IllegalArgumentException("missing tags")).when(service).process(44);
        send("{\"resourceId\":44}");
        assertThat(rabbit.receive("resource.processing.dlq", 15000)).isNotNull();
        verify(service).process(44);
    }

    @Test
    void malformedMessagesAreDeadLetteredWithoutProcessing() {
        for (String body : new String[]{"invalid", "{\"resourceId\":\"45\"}", "{\"resourceId\":0}"}) {
            send(body);
            Message failed = rabbit.receive("resource.processing.dlq", 15000);
            assertThat(failed).isNotNull();
            assertThat(new String(failed.getBody(), StandardCharsets.UTF_8)).isEqualTo(body);
        }
        verifyNoInteractions(service);
    }

    @Test
    void queuedMessagesResumeWhenListenerRestarts() {
        var listener = listeners.getListenerContainer("resourceUploaded");
        listener.stop();
        try {
            send("{\"resourceId\":46}");
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                    assertThat(admin.getQueueProperties("resource.processing").get("QUEUE_MESSAGE_COUNT")).isEqualTo(1));
            verifyNoInteractions(service);
        } finally {
            listener.start();
        }
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> verify(service).process(46));
    }

    private void send(String body) {
        var properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        rabbit.send("resources", "resource.uploaded", new Message(body.getBytes(StandardCharsets.UTF_8), properties));
    }
}

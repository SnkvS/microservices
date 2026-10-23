package com.example.resource.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.retry.support.RetryTemplate;

import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ResourceUploadedPublisherTest {

    private final RabbitTemplate rabbit = mock(RabbitTemplate.class);
    private final ResourceUploadedPublisher publisher = new ResourceUploadedPublisher(rabbit, new ObjectMapper(),
            RetryTemplate.builder().maxAttempts(3).noBackoff().retryOn(org.springframework.amqp.AmqpException.class).build(),
            "resources", "resource.uploaded", 1);

    @Test
    void publishesPersistentJsonWithNoJavaTypeHeadersAndWaitsForConfirmation() {
        doAnswer(call -> {
            Message message = call.getArgument(2);
            assertThat(new String(message.getBody(), StandardCharsets.UTF_8)).isEqualTo("{\"resourceId\":42}");
            assertThat(message.getMessageProperties().getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
            assertThat(message.getMessageProperties().getContentType()).isEqualTo("application/json");
            assertThat(message.getMessageProperties().getMessageId()).isNotBlank();
            assertThat(message.getMessageProperties().getHeaders()).doesNotContainKey("__TypeId__");
            ((CorrelationData) call.getArgument(3)).getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbit).send(eq("resources"), eq("resource.uploaded"), any(Message.class), any(CorrelationData.class));
        publisher.publish(42);
        verify(rabbit).send(eq("resources"), eq("resource.uploaded"), any(Message.class), any(CorrelationData.class));
    }

    @Test
    void retriesConnectionFailureThenPublishesSuccessfully() {
        AtomicInteger attempts = new AtomicInteger();
        doAnswer(call -> {
            if (attempts.incrementAndGet() == 1) throw new AmqpConnectException(new ConnectException("offline"));
            ((CorrelationData) call.getArgument(3)).getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        publisher.publish(42);
        assertThat(attempts).hasValue(2);
    }

    @Test
    void negativeAcknowledgmentsExhaustFiniteRetries() {
        doAnswer(call -> {
            ((CorrelationData) call.getArgument(3)).getFuture().complete(new CorrelationData.Confirm(false, "nack"));
            return null;
        }).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        assertThatThrownBy(() -> publisher.publish(42)).isInstanceOf(ResourcePublicationException.class);
        verify(rabbit, times(3)).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    }

    @Test
    void positiveConfirmDoesNotHideUnroutableReturns() {
        doAnswer(call -> {
            CorrelationData correlation = call.getArgument(3);
            correlation.setReturned(new ReturnedMessage(call.getArgument(2), 312, "NO_ROUTE", "resources", "resource.uploaded"));
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        assertThatThrownBy(() -> publisher.publish(42)).isInstanceOf(ResourcePublicationException.class);
        verify(rabbit, times(3)).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    }

    @Test
    void confirmationTimeoutIsRetriedAndReported() {
        assertThatThrownBy(() -> publisher.publish(42)).isInstanceOf(ResourcePublicationException.class);
        verify(rabbit, times(3)).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    }

    @Test
    void interruptionIsPreservedAndNotRetried() {
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> publisher.publish(42)).isInstanceOf(ResourcePublicationException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void invalidIdsHaveNoMessagingEffects() {
        assertThatThrownBy(() -> publisher.publish(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> publisher.publish(-1)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(rabbit);
    }
}

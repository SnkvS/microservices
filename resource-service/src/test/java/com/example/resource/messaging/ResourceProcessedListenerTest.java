package com.example.resource.messaging;

import com.example.resource.service.ResourcePromotionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ResourceProcessedListenerTest {

    private final ResourcePromotionService service = mock(ResourcePromotionService.class);
    private final ResourceProcessedListener listener = new ResourceProcessedListener(new ObjectMapper(), service);

    @Test
    void acceptsTheCrossServiceJsonContract() {
        listener.receive(message("{\"resourceId\":42}"));
        verify(service).promote(42);
    }

    @ParameterizedTest
    @ValueSource(strings = {"bad json", "{}", "null", "[]", "{\"resourceId\":42} true", "{\"resourceId\":null}", "{\"resourceId\":0}",
            "{\"resourceId\":-1}", "{\"resourceId\":\"42\"}", "{\"resourceId\":1.5}", "{\"resourceId\":9223372036854775808}"})
    void invalidMessagesNeverReachProcessing(String json) {
        assertThatThrownBy(() -> listener.receive(message(json))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(service);
    }

    private Message message(String json) {
        return new Message(json.getBytes(StandardCharsets.UTF_8), new MessageProperties());
    }

    @Test void correlatesProcessingAndRestoresThePreviousContext() {
        org.slf4j.MDC.put("traceId", "previous");
        try {
            doAnswer(call -> {
                org.assertj.core.api.Assertions.assertThat(org.slf4j.MDC.get("traceId")).isEqualTo("message-42");
                return null;
            }).when(service).promote(42);
            var event = message("{\"resourceId\":42}");
            event.getMessageProperties().setHeader("X-Trace-ID", "message-42");
            listener.receive(event);
            org.assertj.core.api.Assertions.assertThat(org.slf4j.MDC.get("traceId")).isEqualTo("previous");
        } finally { org.slf4j.MDC.clear(); }
    }

    @Test void assignsLegacyMessagesAnIdAndRestoresContextAfterFailure() {
        org.slf4j.MDC.put("traceId", "previous");
        try {
            doAnswer(call -> {
                org.assertj.core.api.Assertions.assertThat(org.slf4j.MDC.get("traceId")).matches("[0-9a-f]{32}");
                throw new IllegalStateException("processing failed");
            }).when(service).promote(42);
            assertThatThrownBy(() -> listener.receive(message("{\"resourceId\":42}")))
                    .isInstanceOf(IllegalStateException.class);
            org.assertj.core.api.Assertions.assertThat(org.slf4j.MDC.get("traceId")).isEqualTo("previous");
        } finally { org.slf4j.MDC.clear(); }
    }
}

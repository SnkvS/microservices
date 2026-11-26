package com.example.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.MethodNotAllowedException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GatewayErrorHandlerTest {
    private final GatewayErrorHandler handler = new GatewayErrorHandler(new ObjectMapper());

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"upload-error", ""})
    void exceptionLogUsesRequestTraceAndRestoresPreviousContext(String supplied) {
        var logger=(ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(GatewayErrorHandler.class);
        var events=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>() {
            @Override protected void append(ch.qos.logback.classic.spi.ILoggingEvent event) {
                event.prepareForDeferredProcessing();
                super.append(event);
            }
        };
        events.start(); logger.addAppender(events); org.slf4j.MDC.put("traceId","other-request");
        try {
            var request=MockServerHttpRequest.get("/resources");
            if (!supplied.isEmpty()) request.header("X-Trace-ID",supplied);
            var exchange=MockServerWebExchange.from(request);
            var filter=new com.example.gateway.observability.TraceCorrelationWebFilter();
            var failure=new IllegalStateException("private exception details");
            // Error handling receives the original exchange outside the mutated downstream chain.
            filter.filter(exchange,next -> reactor.core.publisher.Mono.error(failure))
                    .onErrorResume(error -> handler.handle(exchange,error)).block();
            String returned=exchange.getResponse().getHeaders().getFirst("X-Trace-ID");
            assertThat(returned).isNotBlank();
            if (!supplied.isEmpty()) assertThat(returned).isEqualTo(supplied);
            else assertThat(returned).matches("[0-9a-f]{32}");
            assertThat(events.list).hasSize(1);
            assertThat(events.list.get(0).getMDCPropertyMap().get("traceId")).isEqualTo(returned);
            assertThat(events.list.get(0).getThrowableProxy().getMessage()).isEqualTo("private exception details");
            assertThat(org.slf4j.MDC.get("traceId")).isEqualTo("other-request");
            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        } finally { org.slf4j.MDC.clear(); logger.detachAppender(events); events.stop(); }
    }

    @Test
    void committedResponsePropagatesOriginalFailure() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/resources/1"));
        exchange.getResponse().setComplete().block();
        var failure = new IllegalStateException("response already started");

        assertThatThrownBy(() -> handler.handle(exchange, failure).block()).isSameAs(failure);
        assertThat(exchange.getResponse().getBodyAsString().block()).isEmpty();
    }

    @Test
    void frameworkStatusIsPreservedWithoutExposingItsReason() throws Exception {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/some-path"));
        handler.handle(exchange, new ResponseStatusException(HttpStatus.METHOD_NOT_ALLOWED, "private details")).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(new ObjectMapper().readTree(exchange.getResponse().getBodyAsString().block()))
                .isEqualTo(new ObjectMapper().readTree("{\"errorCode\":\"405\",\"errorMessage\":\"Method Not Allowed\"}"));
    }

    @Test
    void methodNotAllowedPreservesAllowedMethodsHeader() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/some-path"));
        handler.handle(exchange, new MethodNotAllowedException(HttpMethod.POST, List.of(HttpMethod.GET))).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(exchange.getResponse().getHeaders().getAllow()).containsExactly(HttpMethod.GET);
    }
}

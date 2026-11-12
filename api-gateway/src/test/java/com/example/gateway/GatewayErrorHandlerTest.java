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

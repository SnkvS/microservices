package com.example.gateway;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ConnectTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.PrematureCloseException;

import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.concurrent.TimeoutException;

@Component
@Order(-2)
public class GatewayErrorHandler implements ErrorWebExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GatewayErrorHandler.class);
    private final ObjectMapper json;

    public GatewayErrorHandler(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable exception) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(exception);
        }
        HttpStatusCode status = status(exception);
        String message = switch (status.value()) {
            case 404 -> "Route not found";
            case 503 -> "Service unavailable";
            case 504 -> "Service request timed out";
            case 500 -> "Internal server error";
            default -> {
                HttpStatus standard = HttpStatus.resolve(status.value());
                yield standard == null ? "Request failed" : standard.getReasonPhrase();
            }
        };
        if (status.value() == 500) {
            log.error("Gateway request failed", exception);
        }
        try {
            byte[] body = json.writeValueAsBytes(Map.of("errorCode", Integer.toString(status.value()),
                    "errorMessage", message));
            exchange.getResponse().setStatusCode(status);
            if (exception instanceof ResponseStatusException responseStatus) {
                exchange.getResponse().getHeaders().putAll(responseStatus.getHeaders());
            }
            exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
            return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
        } catch (JsonProcessingException serializationFailure) {
            return Mono.error(serializationFailure);
        }
    }

    private HttpStatusCode status(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SocketException || cause instanceof UnknownHostException
                    || cause instanceof ConnectTimeoutException || cause instanceof PrematureCloseException) {
                return HttpStatus.SERVICE_UNAVAILABLE;
            }
            if (cause instanceof TimeoutException || cause instanceof io.netty.handler.timeout.TimeoutException) {
                return HttpStatus.GATEWAY_TIMEOUT;
            }
        }
        if (exception instanceof ResponseStatusException responseStatus) {
            return responseStatus.getStatusCode();
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }
}

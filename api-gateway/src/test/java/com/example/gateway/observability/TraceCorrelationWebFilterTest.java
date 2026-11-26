package com.example.gateway.observability;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import static org.assertj.core.api.Assertions.*;

class TraceCorrelationWebFilterTest {
    private final TraceCorrelationWebFilter filter = new TraceCorrelationWebFilter();
    @Test void forwardsAndReturnsTheIncomingIdWithoutChangingThreadContext() {
        MDC.put("traceId", "unrelated");
        try {
            var exchange=MockServerWebExchange.from(MockServerHttpRequest.post("/resources").header("X-Trace-ID","upload-42"));
            filter.filter(exchange, next -> {
                assertThat(next.getRequest().getHeaders().getFirst("X-Trace-ID")).isEqualTo("upload-42");
                assertThat(MDC.get("traceId")).isEqualTo("unrelated");
                return Mono.empty();
            }).block();
            assertThat(exchange.getResponse().getHeaders().getFirst("X-Trace-ID")).isEqualTo("upload-42");
            assertThat(MDC.get("traceId")).isEqualTo("unrelated");
        } finally { MDC.clear(); }
    }
    @Test void createsAnIdForAnInvalidHeaderEvenWhenTheRouteFails() {
        var exchange=MockServerWebExchange.from(MockServerHttpRequest.get("/resources").header("X-Trace-ID","bad id"));
        assertThatThrownBy(() -> filter.filter(exchange, next -> {
            assertThat(next.getRequest().getHeaders().getFirst("X-Trace-ID")).matches("[0-9a-f]{32}");
            return Mono.error(new IllegalStateException("route failed"));
        }).block()).isInstanceOf(IllegalStateException.class);
        assertThat(exchange.getResponse().getHeaders().getFirst("X-Trace-ID")).matches("[0-9a-f]{32}");
        assertThat(MDC.get("traceId")).isNull();
    }
    @Test void generatesAnIdWhenTheCallerDoesNotProvideOne() {
        var exchange=MockServerWebExchange.from(MockServerHttpRequest.get("/resources"));
        filter.filter(exchange,next -> Mono.empty()).block();
        assertThat(exchange.getResponse().getHeaders().getFirst("X-Trace-ID")).matches("[0-9a-f]{32}");
    }
    @Test void keepsConcurrentReactiveRequestsIsolatedAcrossThreadSwitches() {
        var seen=ConcurrentHashMap.<String>newKeySet();
        Flux.range(0,64).flatMap(i -> {
            String id="parallel-"+i;
            var exchange=MockServerWebExchange.from(MockServerHttpRequest.post("/resources").header("X-Trace-ID",id));
            return filter.filter(exchange,next -> Mono.delay(Duration.ofMillis(i%5)).then(Mono.fromRunnable(() -> {
                assertThat(next.getRequest().getHeaders().getFirst("X-Trace-ID")).isEqualTo(id);
                assertThat(exchange.getResponse().getHeaders().getFirst("X-Trace-ID")).isEqualTo(id);
                seen.add(id);
            })));
        },16).blockLast();
        assertThat(seen).hasSize(64);
    }

    @Test void logsItsOwnTraceWhenTheReactiveThreadAlreadyHasAnMdcValue() {
        var logger=(ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(TraceCorrelationWebFilter.class);
        var events=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>() {
            @Override protected void append(ch.qos.logback.classic.spi.ILoggingEvent event) {
                event.prepareForDeferredProcessing();
                super.append(event);
            }
        };
        events.start(); logger.addAppender(events); MDC.put("traceId","other-request");
        try {
            var exchange=MockServerWebExchange.from(MockServerHttpRequest.get("/resources").header("X-Trace-ID","own-request"));
            filter.filter(exchange,next -> Mono.empty()).block();
            assertThat(events.list).hasSize(1);
            assertThat(events.list.get(0).getMDCPropertyMap().get("traceId")).isEqualTo("own-request");
            assertThat(MDC.get("traceId")).isEqualTo("other-request");
        } finally { MDC.clear(); logger.detachAppender(events); events.stop(); }
    }
}

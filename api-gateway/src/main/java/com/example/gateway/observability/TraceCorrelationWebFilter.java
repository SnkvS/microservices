package com.example.gateway.observability;

import org.slf4j.MDC;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceCorrelationWebFilter implements WebFilter {
    private static final Logger log=LoggerFactory.getLogger(TraceCorrelationWebFilter.class);
    public static final String TRACE_ID_ATTRIBUTE=TraceCorrelationWebFilter.class.getName()+".traceId";
    private static final String HEADER="X-Trace-ID";
    private static final Pattern VALID_ID=Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");

    @Override public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        return Mono.defer(() -> {
            String incoming=exchange.getRequest().getHeaders().getFirst(HEADER);
            String id=incoming != null && VALID_ID.matcher(incoming).matches()
                    ? incoming : UUID.randomUUID().toString().replace("-", "");
            exchange.getAttributes().put(TRACE_ID_ATTRIBUTE,id);
            var traced=exchange.mutate().request(request -> request.headers(headers -> headers.set(HEADER,id))).build();
            exchange.getResponse().getHeaders().set(HEADER,id);
            // Bind MDC only while emitting this log, never across asynchronous work.
            return chain.filter(traced).doFinally(signal -> {
                String previous=MDC.get("traceId");
                try {
                    MDC.put("traceId",id);
                    log.info("Gateway HTTP {} {} status={} outcome={}", exchange.getRequest().getMethod(),
                            exchange.getRequest().getPath().value(),
                            exchange.getResponse().getStatusCode() == null ? 200 : exchange.getResponse().getStatusCode().value(),
                            signal.name());
                } finally {
                    if (previous == null) MDC.remove("traceId");
                    else MDC.put("traceId",previous);
                }
            });
        });
    }
}

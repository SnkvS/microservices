package com.example.song.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceCorrelationFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(TraceCorrelationFilter.class);

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                               FilterChain chain) throws ServletException, IOException {
        try (var trace = TraceContext.open(request.getHeader(TraceContext.HEADER))) {
            response.setHeader(TraceContext.HEADER, trace.id());
            boolean completed = false;
            try {
                chain.doFilter(request, response);
                completed = true;
            } finally {
                log.info("HTTP {} {} status={} outcome={}", request.getMethod(), request.getRequestURI(),
                        response.getStatus(), completed ? "completed" : "failed");
            }
        }
    }
}

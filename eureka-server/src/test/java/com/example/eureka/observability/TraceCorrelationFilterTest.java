package com.example.eureka.observability;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.assertj.core.api.Assertions.*;

class TraceCorrelationFilterTest {
    private final TraceCorrelationFilter filter = new TraceCorrelationFilter();
    @AfterEach void clear() { MDC.clear(); }

    @Test void carriesAnIncomingIdAndRestoresThePreviousThreadContext() throws Exception {
        MDC.put("traceId", "previous");
        MDC.put("unrelated", "keep");
        var request = new MockHttpServletRequest("POST", "/resources");
        request.addHeader("X-Trace-ID", "upload_test-42");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> assertThat(MDC.get("traceId")).isEqualTo("upload_test-42"));
        assertThat(response.getHeader("X-Trace-ID")).isEqualTo("upload_test-42");
        assertThat(MDC.get("traceId")).isEqualTo("previous");
        assertThat(MDC.get("unrelated")).isEqualTo("keep");
    }

    @ParameterizedTest @NullAndEmptySource
    @ValueSource(strings = {"bad id", "bad\nvalue", "unicode-Ã©", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
    void generatesAnIdForMissingOrInvalidInput(String value) throws Exception {
        var request = new MockHttpServletRequest();
        if (value != null) request.addHeader("X-Trace-ID", value);
        var response = new MockHttpServletResponse();
        filter.doFilter(request,response,(req,res) -> assertThat(MDC.get("traceId")).matches("[0-9a-f]{32}"));
        assertThat(response.getHeader("X-Trace-ID")).matches("[0-9a-f]{32}");
        assertThat(MDC.get("traceId")).isNull();
    }

    @Test void restoresMdcWhenTheRequestFails() {
        MDC.put("traceId", "previous");
        var request = new MockHttpServletRequest();
        request.addHeader("X-Trace-ID", "failure-42");
        assertThatThrownBy(() -> filter.doFilter(request,new MockHttpServletResponse(),(req,res) -> {
            assertThat(MDC.get("traceId")).isEqualTo("failure-42");
            throw new ServletException("failure");
        })).isInstanceOf(ServletException.class);
        assertThat(MDC.get("traceId")).isEqualTo("previous");
    }
}

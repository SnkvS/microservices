package com.example.processor.integration;
import com.example.processor.integration.ProcessorClientConfiguration;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.assertj.core.api.Assertions.*;

class TraceHttpClientTest {
    @Test void forwardsTheRequestContextThroughTheRealClientBuilder() {
        var builder = new ProcessorClientConfiguration().processorRestClientBuilder(100,100);
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://example.test/check")).andExpect(header("X-Trace-ID", "http-42"))
                .andRespond(withSuccess("ok",MediaType.TEXT_PLAIN));
        MDC.put("traceId", "http-42");
        try { assertThat(builder.build().get().uri("http://example.test/check").retrieve().body(String.class)).isEqualTo("ok"); }
        finally { MDC.clear(); }
        server.verify();
    }
}

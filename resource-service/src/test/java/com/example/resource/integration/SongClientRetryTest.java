package com.example.resource.integration;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class SongClientRetryTest {

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final SongClient client = new SongClient(builder, "http://song-service",
            new SongClientConfiguration().songHttpRetry(3, 1, 2));

    @Test
    void retriesTransientDeleteFailure() {
        server.expect(requestTo("http://song-service/songs?id=42")).andExpect(method(HttpMethod.DELETE))
                .andRespond(withServerError());
        server.expect(requestTo("http://song-service/songs?id=42")).andRespond(withSuccess());
        client.delete(List.of(42L));
        server.verify();
    }

    @Test
    void doesNotRetryPermanentClientError() {
        server.expect(requestTo("http://song-service/songs?id=42")).andRespond(withBadRequest());
        assertThatThrownBy(() -> client.delete(List.of(42L))).isInstanceOf(HttpClientErrorException.class);
        server.verify();
    }

    @Test
    void exhaustsFiniteAttempts() {
        for (int attempt = 0; attempt < 3; attempt++)
            server.expect(requestTo("http://song-service/songs?id=42")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertThatThrownBy(() -> client.delete(List.of(42L))).isInstanceOf(HttpServerErrorException.class);
        server.verify();
    }

    @Test
    void emptyIdsDoNotSendRequests() {
        client.delete(List.of());
        server.verify();
    }
}

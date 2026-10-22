package com.example.processor.integration;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class ProcessorClientsTest {

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final org.springframework.retry.support.RetryTemplate retry = new ProcessorClientConfiguration().processorHttpRetry(3, 1, 2);
    private final ResourceClient resources = new ResourceClient(builder, "http://resource-service", retry);
    private final SongClient songs = new SongClient(builder, "http://song-service", retry);
    private final SongMetadata metadata = new SongMetadata(42L, "Test Title", "Test Artist", "Test Album", "00:07", "2025");
    private static final String JSON = "{\"id\":42,\"name\":\"Test Title\",\"artist\":\"Test Artist\",\"album\":\"Test Album\",\"duration\":\"00:07\",\"year\":\"2025\"}";

    @Test
    void binaryDownloadRetriesTransientErrorsAndPreservesBytes() {
        server.expect(requestTo("http://resource-service/resources/42")).andRespond(withServerError());
        server.expect(requestTo("http://resource-service/resources/42"))
                .andRespond(withSuccess(new byte[]{0, -1, 42}, MediaType.parseMediaType("audio/mpeg")));
        assertThat(resources.download(42)).containsExactly(0, -1, 42);
        server.verify();
    }

    @Test
    void saveRetriesTransientErrorsAndPostsAllMetadataFields() {
        server.expect(requestTo("http://song-service/songs")).andExpect(content().json(JSON)).andRespond(withServerError());
        server.expect(requestTo("http://song-service/songs")).andExpect(content().json(JSON)).andRespond(withSuccess("{\"id\":42}", MediaType.APPLICATION_JSON));
        songs.save(metadata);
        server.verify();
    }

    @Test
    void permanentErrorsDoNotRetry() {
        server.expect(requestTo("http://song-service/songs")).andRespond(withBadRequest());
        assertThatThrownBy(() -> songs.save(metadata)).isInstanceOf(HttpClientErrorException.class);
        server.verify();
    }

    @Test
    void identicalConflictIsSuccessfulDuplicateDelivery() {
        server.expect(requestTo("http://song-service/songs")).andRespond(withStatus(HttpStatus.CONFLICT));
        server.expect(requestTo("http://song-service/songs/42")).andRespond(withSuccess(JSON, MediaType.APPLICATION_JSON));
        songs.save(metadata);
        server.verify();
    }

    @Test
    void differingConflictIsNotTreatedAsSuccess() {
        server.expect(requestTo("http://song-service/songs")).andRespond(withStatus(HttpStatus.CONFLICT));
        server.expect(requestTo("http://song-service/songs/42")).andRespond(withSuccess(JSON.replace("Test Title", "Other"), MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> songs.save(metadata)).isInstanceOf(IllegalStateException.class);
        server.verify();
    }

    @Test
    void conflictLookupRetriesTransientFailures() {
        server.expect(requestTo("http://song-service/songs")).andRespond(withStatus(HttpStatus.CONFLICT));
        server.expect(requestTo("http://song-service/songs/42")).andRespond(withServerError());
        server.expect(requestTo("http://song-service/songs/42")).andRespond(withSuccess(JSON, MediaType.APPLICATION_JSON));
        songs.save(metadata);
        server.verify();
    }

    @Test
    void responseLossThenConflictDoesNotCreateADuplicate() {
        server.expect(requestTo("http://song-service/songs")).andRespond(withException(new IOException("response lost")));
        server.expect(requestTo("http://song-service/songs")).andRespond(withStatus(HttpStatus.CONFLICT));
        server.expect(requestTo("http://song-service/songs/42")).andRespond(withSuccess(JSON, MediaType.APPLICATION_JSON));
        songs.save(metadata);
        server.verify();
    }

    @Test
    void exhaustedConflictLookupRemainsRetryableForTheListener() {
        server.expect(requestTo("http://song-service/songs")).andRespond(withStatus(HttpStatus.CONFLICT));
        for (int attempt = 0; attempt < 3; attempt++)
            server.expect(requestTo("http://song-service/songs/42")).andRespond(withServerError());
        assertThatThrownBy(() -> songs.save(metadata)).isInstanceOf(HttpServerErrorException.class)
                .satisfies(ex -> assertThat(ProcessorClientConfiguration.isTransient(ex)).isTrue());
        server.verify();
    }

    @Test
    void retriesOnlyTransientStatusCodesAndConnectionFailures() {
        for (HttpStatus status : new HttpStatus[]{HttpStatus.REQUEST_TIMEOUT, HttpStatus.TOO_MANY_REQUESTS, HttpStatus.SERVICE_UNAVAILABLE})
            assertThat(ProcessorClientConfiguration.isTransient(new org.springframework.web.client.RestClientResponseException("failure", status.value(), "", null, null, null))).isTrue();
        assertThat(ProcessorClientConfiguration.isTransient(new ResourceAccessException("offline"))).isTrue();
        assertThat(ProcessorClientConfiguration.isTransient(new IllegalArgumentException("bad metadata"))).isFalse();
    }
}

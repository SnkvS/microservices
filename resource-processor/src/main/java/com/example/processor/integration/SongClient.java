package com.example.processor.integration;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.http.MediaType;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

@Component
public class SongClient {

    private final RestClient client;
    private final RetryTemplate retry;

    public SongClient(@LoadBalanced RestClient.Builder builder,
                      @Value("${song.service.url:http://song-service}") String url,
                      @Qualifier("processorHttpRetry") RetryTemplate retry) {
        this.client = builder.baseUrl(url).build();
        this.retry = retry;
    }

    public void save(SongMetadata metadata) {
        try {
            retry.execute(context -> client.post().uri("/songs").contentType(MediaType.APPLICATION_JSON)
                    .body(metadata).retrieve().toBodilessEntity());
        } catch (HttpClientErrorException.Conflict conflict) {
            SongMetadata existing = retry.execute(context -> client.get().uri("/songs/{id}", metadata.id())
                    .retrieve().body(SongMetadata.class));
            if (!metadata.equals(existing))
                throw new IllegalStateException("Different metadata already exists for resource " + metadata.id(), conflict);
        }
    }
}

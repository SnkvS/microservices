package com.example.resource.integration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

@Component
public class SongClient {

    private final RestClient client;

    private final RetryTemplate retry;

    public SongClient(@LoadBalanced RestClient.Builder songRestClientBuilder,
                      @Value("${song.service.url}") String url,
                      @Qualifier("songHttpRetry") RetryTemplate retry) {
        client = songRestClientBuilder.baseUrl(url).build();
        this.retry = retry;
    }

    public void delete(List<Long> ids) {
        if (ids.isEmpty()) return;
        String csv = ids.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
        retry.execute(context -> client.delete().uri(builder -> builder.path("/songs").queryParam("id", csv).build())
                .retrieve().toBodilessEntity());
    }
}

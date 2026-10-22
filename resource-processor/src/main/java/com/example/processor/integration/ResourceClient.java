package com.example.processor.integration;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class ResourceClient {

    private final RestClient client;
    private final RetryTemplate retry;

    public ResourceClient(@LoadBalanced RestClient.Builder builder,
                          @Value("${resource.service.url:http://resource-service}") String url,
                          @Qualifier("processorHttpRetry") RetryTemplate retry) {
        this.client = builder.baseUrl(url).build();
        this.retry = retry;
    }

    public byte[] download(long resourceId) {
        return retry.execute(context -> client.get().uri("/resources/{id}", resourceId).retrieve().body(byte[].class));
    }
}

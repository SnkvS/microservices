package com.example.resource.integration;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;

@Configuration
public class SongClientConfiguration {

    @Bean
    @LoadBalanced
    RestClient.Builder songRestClientBuilder(@Value("${communication.http.connect-timeout:2000}") int connectTimeout,
                                             @Value("${communication.http.read-timeout:5000}") int readTimeout) {
        var requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(connectTimeout);
        requests.setReadTimeout(readTimeout);
        return RestClient.builder().requestFactory(requests).requestInterceptor((request, body, execution) -> {
            try {
                return execution.execute(request, body);
            } catch (IllegalArgumentException | IllegalStateException failure) {
                // Spring Cloud reports missing instances using untyped exceptions.
                String message = failure.getMessage();
                if (message != null && (message.startsWith("No instances available for ")
                        || message.startsWith("Service Instance cannot be null")))
                    throw new ResourceAccessException("Service discovery has no instance for " + request.getURI().getHost(),
                            new java.io.IOException(failure));
                throw failure;
            }
        });
    }

    @Bean
    RetryTemplate songHttpRetry(@Value("${communication.http.max-attempts:3}") int attempts,
                                 @Value("${communication.http.initial-delay:1000}") long initialDelay,
                                 @Value("${communication.http.max-delay:5000}") long maxDelay) {
        return RetryTemplate.builder().maxAttempts(attempts).exponentialBackoff(initialDelay, 2, maxDelay)
                .retryOn(error -> error instanceof ResourceAccessException
                        || error instanceof RestClientResponseException response
                        && (response.getStatusCode().is5xxServerError()
                        || response.getStatusCode().value() == 408 || response.getStatusCode().value() == 429)).build();
    }
}

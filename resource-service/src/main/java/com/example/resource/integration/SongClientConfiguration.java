package com.example.resource.integration;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class SongClientConfiguration {
    @Bean
    @LoadBalanced
    RestClient.Builder songRestClientBuilder() {
        return RestClient.builder();
    }
}

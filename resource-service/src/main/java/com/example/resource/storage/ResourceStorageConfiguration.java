package com.example.resource.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;
import java.time.Duration;

@Configuration
public class ResourceStorageConfiguration {

    @Bean(destroyMethod = "close")
    S3Client resourceS3Client(@Value("${resource.storage.endpoint}") URI endpoint,
                              @Value("${resource.storage.region}") String region,
                              @Value("${resource.storage.access-key}") String accessKey,
                              @Value("${resource.storage.secret-key}") String secretKey) {
        return S3Client.builder().endpointOverride(endpoint).region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .forcePathStyle(true)
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallAttemptTimeout(Duration.ofSeconds(5))
                        .apiCallTimeout(Duration.ofSeconds(10)).build())
                .build();
    }

    @Bean
    ResourceStorage resourceStorage(S3Client resourceS3Client, @Value("${resource.storage.bucket}") String bucket) {
        return new S3ResourceStorage(resourceS3Client, bucket);
    }
}

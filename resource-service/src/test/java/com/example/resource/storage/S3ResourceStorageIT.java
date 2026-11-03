package com.example.resource.storage;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.testcontainers.containers.localstack.LocalStackContainer.Service.S3;

@Testcontainers
class S3ResourceStorageIT {
    @Container
    static final LocalStackContainer LOCALSTACK = new LocalStackContainer(DockerImageName.parse("localstack/localstack:4.14.0"))
            .withServices(S3);

    @Test
    void storesAndDownloadsExactBytesThenDeletesIdempotently() throws Exception {
        try (S3Client client = S3Client.builder().endpointOverride(LOCALSTACK.getEndpointOverride(S3))
                .region(Region.of(LOCALSTACK.getRegion())).forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey()))).build()) {
            String bucket = "integration-" + UUID.randomUUID();
            client.createBucket(request -> request.bucket(bucket));
            var storage = new S3ResourceStorage(client, bucket);
            byte[] bytes;
            try (var input = getClass().getResourceAsStream("/mp3/valid.mp3")) {
                bytes = input.readAllBytes();
            }
            var location = storage.put("recording.mp3", bytes);
            assertThat(location).isEqualTo(new StoredResourceLocation(bucket, "recording.mp3"));
            assertThat(storage.get(location)).containsExactly(bytes);
            assertThat(client.headObject(request -> request.bucket(bucket).key("recording.mp3")).contentType())
                    .isEqualTo("audio/mpeg");
            storage.delete(location);
            assertThat(client.listObjectsV2(request -> request.bucket(bucket)).contents()).isEmpty();
            storage.delete(location);
            assertThatThrownBy(() -> storage.get(location)).isInstanceOf(ResourceStorageException.class);
        }
    }
}

package com.example.resource.storage;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class S3ResourceStorageTest {

    private final S3Client client = mock(S3Client.class);

    private final S3ResourceStorage storage = new S3ResourceStorage(client, "resources");

    @Test
    void storesOriginalBytesWithMp3ContentTypeAndReturnsDurableLocation() throws Exception {
        byte[] bytes = new byte[]{0, 1, (byte) 255, 42};
        assertThat(storage.put("resources/test.mp3", bytes))
                .isEqualTo(new StoredResourceLocation("resources", "resources/test.mp3"));
        var request = ArgumentCaptor.forClass(PutObjectRequest.class);
        var body = ArgumentCaptor.forClass(RequestBody.class);
        verify(client).putObject(request.capture(), body.capture());
        assertThat(request.getValue().bucket()).isEqualTo("resources");
        assertThat(request.getValue().key()).isEqualTo("resources/test.mp3");
        assertThat(request.getValue().contentType()).isEqualTo("audio/mpeg");
        try (var stream = body.getValue().contentStreamProvider().newStream()) {
            assertThat(stream.readAllBytes()).isEqualTo(bytes);
        }
    }

    @Test
    void retrievesExactBytesUsingThePersistedBucketAndKey() {
        byte[] bytes = new byte[]{'I', 'D', '3', (byte) 255};
        when(client.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenReturn(ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), bytes));
        assertThat(storage.get(new StoredResourceLocation("old-bucket", "old-key"))).isEqualTo(bytes);
        var request = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(client).getObjectAsBytes(request.capture());
        assertThat(request.getValue().bucket()).isEqualTo("old-bucket");
        assertThat(request.getValue().key()).isEqualTo("old-key");
    }

    @Test
    void deletingAnAbsentObjectSucceedsWithoutAnExistenceCheck() {
        storage.delete(new StoredResourceLocation("resources", "absent"));
        verify(client).deleteObject(DeleteObjectRequest.builder().bucket("resources").key("absent").build());
    }

    @Test
    void wrapsStorageFailuresIncludingMissingObjectsForDownload() {
        var failure = S3Exception.builder().statusCode(404).message("NoSuchKey").build();
        when(client.getObjectAsBytes(any(GetObjectRequest.class))).thenThrow(failure);
        assertThatThrownBy(() -> storage.get(new StoredResourceLocation("resources", "absent")))
                .isInstanceOf(ResourceStorageException.class).hasCause(failure);
    }

    @Test
    void uploadAndDeleteFailuresAreNotReportedAsSuccess() {
        var failure = S3Exception.builder().statusCode(503).message("Unavailable").build();
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenThrow(failure);
        when(client.deleteObject(any(DeleteObjectRequest.class))).thenThrow(failure);
        assertThatThrownBy(() -> storage.put("key", new byte[]{1}))
                .isInstanceOf(ResourceStorageException.class).hasCause(failure);
        assertThatThrownBy(() -> storage.delete(new StoredResourceLocation("resources", "key")))
                .isInstanceOf(ResourceStorageException.class).hasCause(failure);
    }
}

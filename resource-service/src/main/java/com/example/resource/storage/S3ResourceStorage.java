package com.example.resource.storage;

import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

public class S3ResourceStorage implements ResourceStorage {

    private final S3Client client;

    private final String bucket;

    public S3ResourceStorage(S3Client client, String bucket) {
        this.client = client;
        this.bucket = bucket;
    }

    @Override
    public StoredResourceLocation put(String key, byte[] data) {
        try {
            client.putObject(PutObjectRequest.builder().bucket(bucket).key(key)
                    .contentType("audio/mpeg").build(), RequestBody.fromBytes(data));
            return new StoredResourceLocation(bucket, key);
        } catch (SdkException ex) {
            throw new ResourceStorageException("upload", ex);
        }
    }

    @Override
    public byte[] get(StoredResourceLocation location) {
        try {
            return client.getObjectAsBytes(GetObjectRequest.builder()
                    .bucket(location.bucket()).key(location.key()).build()).asByteArray();
        } catch (SdkException ex) {
            throw new ResourceStorageException("download", ex);
        }
    }

    @Override
    public void delete(StoredResourceLocation location) {
        try {
            client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(location.bucket()).key(location.key()).build());
        } catch (SdkException ex) {
            throw new ResourceStorageException("deletion", ex);
        }
    }
}

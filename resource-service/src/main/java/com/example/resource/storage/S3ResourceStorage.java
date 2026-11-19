package com.example.resource.storage;

import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

public class S3ResourceStorage implements ResourceStorage {

    private final S3Client client;

    public S3ResourceStorage(S3Client client) {
        this.client = client;
    }

    @Override
    public void put(StoredResourceLocation location, byte[] data) {
        try {
            client.putObject(PutObjectRequest.builder().bucket(location.bucket()).key(location.key())
                    .contentType("audio/mpeg").build(), RequestBody.fromBytes(data));
        } catch (SdkException ex) {
            throw new ResourceStorageException("upload", ex);
        }
    }

    @Override
    public void copy(StoredResourceLocation source,StoredResourceLocation target) {
        try {
            String encoded=java.net.URLEncoder.encode(source.bucket()+"/"+source.key(),java.nio.charset.StandardCharsets.UTF_8)
                    .replace("+","%20").replace("%2F","/");
            client.copyObject(software.amazon.awssdk.services.s3.model.CopyObjectRequest.builder()
                    .copySource(encoded).bucket(target.bucket()).key(target.key()).build());
        } catch (SdkException ex) { throw new ResourceStorageException("copy",ex); }
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

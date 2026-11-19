package com.example.storage.api;
import com.example.storage.model.StorageType;
public record CreateStorageRequest(StorageType storageType, String bucket, String path) {}

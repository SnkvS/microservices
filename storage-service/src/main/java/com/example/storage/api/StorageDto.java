package com.example.storage.api;
import com.example.storage.model.StorageType;
public record StorageDto(Long id, StorageType storageType, String bucket, String path) {}

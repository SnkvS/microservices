package com.example.resource.storage;

public interface ResourceStorage {

    StoredResourceLocation put(String key, byte[] data);

    byte[] get(StoredResourceLocation location);

    void delete(StoredResourceLocation location);
}

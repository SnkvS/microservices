package com.example.resource.storage;
public interface ResourceStorage {
    void put(StoredResourceLocation location,byte[] data);
    byte[] get(StoredResourceLocation location);
    void delete(StoredResourceLocation location);
    void copy(StoredResourceLocation source,StoredResourceLocation target);
}

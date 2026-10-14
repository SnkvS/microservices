package com.example.resource.storage;

public class ResourceStorageException extends RuntimeException {

    public ResourceStorageException(String operation, Throwable cause) {
        super("Resource storage " + operation + " failed", cause);
    }
}

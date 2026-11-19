package com.example.resource.integration;
public class StorageCatalogException extends RuntimeException {
    public StorageCatalogException(String message) { super(message); }
    public StorageCatalogException(String message,Throwable cause) { super(message,cause); }
}

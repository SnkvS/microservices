package com.example.resource.service;

public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(long id) { super("Resource with ID=" + id + " not found"); }
}

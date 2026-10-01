package com.example.resource;

class ResourceNotFoundException extends RuntimeException {
    ResourceNotFoundException(long id) { super("Resource with ID=" + id + " not found"); }
}

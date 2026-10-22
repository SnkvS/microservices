package com.example.resource.messaging;

public class ResourcePublicationException extends RuntimeException {

    public ResourcePublicationException(long resourceId, Throwable cause) {
        super("Could not publish resource " + resourceId, cause);
    }
}

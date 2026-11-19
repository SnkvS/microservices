package com.example.processor.messaging;
public class ResourceProcessedPublicationException extends RuntimeException {
    public ResourceProcessedPublicationException(long id,Throwable cause) { super("Could not publish processed resource "+id,cause); }
}

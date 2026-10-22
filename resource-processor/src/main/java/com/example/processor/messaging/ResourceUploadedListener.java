package com.example.processor.messaging;

import com.example.processor.service.ResourceProcessingService;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class ResourceUploadedListener {

    private static final Logger log = LoggerFactory.getLogger(ResourceUploadedListener.class);

    private final ObjectMapper mapper;
    private final ResourceProcessingService service;

    public ResourceUploadedListener(ObjectMapper mapper, ResourceProcessingService service) {
        this.mapper = mapper;
        this.service = service;
    }

    @RabbitListener(id = "resourceUploaded", queues = "${resource.messaging.queue:resource.processing}")
    public void receive(Message message) {
        Long resourceId = null;
        try {
            var event = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(message.getBody());
            var id = event == null ? null : event.get("resourceId");
            if (id == null || !id.isIntegralNumber() || !id.canConvertToLong() || id.longValue() <= 0)
                throw new IllegalArgumentException("Event requires a positive integral resourceId");
            resourceId = id.longValue();
            service.process(resourceId);
        } catch (IOException ex) {
            log.warn("Invalid resource event: {}", ex.getMessage());
            throw new IllegalArgumentException("Invalid resource event JSON", ex);
        } catch (RuntimeException ex) {
            log.warn("Resource event {} failed: {}", resourceId, ex.getMessage());
            throw ex;
        }
    }
}

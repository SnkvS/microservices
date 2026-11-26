package com.example.resource.messaging;

import com.example.resource.observability.TraceContext;
import com.example.resource.service.ResourcePromotionService;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class ResourceProcessedListener {

    private static final Logger log = LoggerFactory.getLogger(ResourceProcessedListener.class);

    private final ObjectMapper mapper;
    private final ResourcePromotionService service;

    public ResourceProcessedListener(ObjectMapper mapper, ResourcePromotionService service) {
        this.mapper = mapper;
        this.service = service;
    }

    @RabbitListener(id = "resourceProcessed", queues = "resource.promotion", containerFactory = "promotionRabbitListenerContainerFactory")
    public void receive(Message message) {
        try (var trace = TraceContext.open(message.getMessageProperties().getHeader("X-Trace-ID"))) {
            message.getMessageProperties().setHeader("X-Trace-ID", trace.id());
            Long resourceId = null;
            try {
                var event = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(message.getBody());
                var id = event == null ? null : event.get("resourceId");
                if (id == null || !id.isIntegralNumber() || !id.canConvertToLong() || id.longValue() <= 0)
                    throw new IllegalArgumentException("Event requires a positive integral resourceId");
                resourceId = id.longValue();
                log.info("Processing resource event {}", resourceId);
                service.promote(resourceId);
                log.info("Completed resource event {}", resourceId);
            } catch (IOException ex) {
                log.warn("Invalid resource event: {}", ex.getMessage());
                throw new IllegalArgumentException("Invalid resource event JSON", ex);
            } catch (RuntimeException ex) {
                log.warn("Resource event {} failed: {}", resourceId, ex.getMessage());
                throw ex;
            }
        }
    }
}

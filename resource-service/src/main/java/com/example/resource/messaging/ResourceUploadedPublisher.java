package com.example.resource.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class ResourceUploadedPublisher {

    private static final Logger log = LoggerFactory.getLogger(ResourceUploadedPublisher.class);

    private final RabbitTemplate rabbit;
    private final ObjectMapper mapper;
    private final RetryTemplate retry;
    private final String exchange;
    private final String routingKey;
    private final long confirmTimeout;

    public ResourceUploadedPublisher(RabbitTemplate rabbit, ObjectMapper mapper,
                                    @Qualifier("resourcePublishRetry") RetryTemplate retry,
                                    @Value("${resource.messaging.exchange:resources}") String exchange,
                                    @Value("${resource.messaging.routing-key:resource.uploaded}") String routingKey,
                                    @Value("${resource.messaging.confirm-timeout:5000}") long confirmTimeout) {
        this.rabbit = rabbit;
        this.mapper = mapper;
        this.retry = retry;
        this.exchange = exchange;
        this.routingKey = routingKey;
        this.confirmTimeout = confirmTimeout;
    }

    public void publish(long resourceId) {
        if (resourceId <= 0) throw new IllegalArgumentException("Invalid resource ID");
        var properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setContentEncoding("UTF-8");
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setMessageId(UUID.randomUUID().toString());
        try {
            var message = new Message(mapper.writeValueAsBytes(Map.of("resourceId", resourceId)), properties);
            retry.execute(context -> {
                var correlation = new CorrelationData(UUID.randomUUID().toString());
                rabbit.send(exchange, routingKey, message, correlation);
                try {
                    var confirmation = correlation.getFuture().get(confirmTimeout, TimeUnit.MILLISECONDS);
                    if (!confirmation.isAck() || correlation.getReturned() != null)
                        throw new AmqpException("Resource event was not routed and confirmed");
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new ResourcePublicationException(resourceId, ex);
                } catch (ExecutionException | TimeoutException ex) {
                    throw new AmqpException("Resource publication confirmation failed", ex);
                }
                return null;
            });
        } catch (JsonProcessingException | RuntimeException ex) {
            log.error("Resource {} committed but notification failed", resourceId, ex);
            if (ex instanceof ResourcePublicationException failure) throw failure;
            throw new ResourcePublicationException(resourceId, ex);
        }
    }
}

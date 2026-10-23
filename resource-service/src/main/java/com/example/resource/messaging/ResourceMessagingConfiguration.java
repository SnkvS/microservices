package com.example.resource.messaging;

import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.support.RetryTemplate;

@Configuration
public class ResourceMessagingConfiguration {

    @Bean
    Declarables resourceTopology(@Value("${resource.messaging.exchange:resources}") String exchange,
                                @Value("${resource.messaging.queue:resource.processing}") String queue,
                                @Value("${resource.messaging.routing-key:resource.uploaded}") String routingKey) {
        var events = new DirectExchange(exchange, true, false);
        var dead = new DirectExchange("resources.dead", true, false);
        var processing = QueueBuilder.durable(queue).deadLetterExchange(dead.getName())
                .deadLetterRoutingKey("resource.failed").build();
        var failed = QueueBuilder.durable("resource.processing.dlq").build();
        return new Declarables(events, dead, processing, failed,
                BindingBuilder.bind(processing).to(events).with(routingKey),
                BindingBuilder.bind(failed).to(dead).with("resource.failed"));
    }

    @Bean
    RetryTemplate resourcePublishRetry(@Value("${resource.messaging.retry.max-attempts:3}") int attempts,
                                       @Value("${resource.messaging.retry.initial-delay:1000}") long initialDelay,
                                       @Value("${resource.messaging.retry.max-delay:5000}") long maxDelay) {
        return RetryTemplate.builder().maxAttempts(attempts).exponentialBackoff(initialDelay, 2, maxDelay)
                .retryOn(AmqpException.class).build();
    }
}

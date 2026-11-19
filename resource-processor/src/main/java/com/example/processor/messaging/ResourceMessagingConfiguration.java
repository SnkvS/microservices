package com.example.processor.messaging;

import com.example.processor.integration.ProcessorClientConfiguration;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
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
        var promotion=QueueBuilder.durable("resource.promotion").deadLetterExchange(dead.getName())
                .deadLetterRoutingKey("resource.promotion.failed").build();
        var promotionFailed=QueueBuilder.durable("resource.promotion.dlq").build();
        return new Declarables(events, dead, processing, failed, promotion, promotionFailed,
                BindingBuilder.bind(promotion).to(events).with("resource.processed"),
                BindingBuilder.bind(promotionFailed).to(dead).with("resource.promotion.failed"),
                BindingBuilder.bind(processing).to(events).with(routingKey),
                BindingBuilder.bind(failed).to(dead).with("resource.failed"));
    }

    @Bean
    RetryTemplate processedPublishRetry(
            @Value("${processed.publish.max-attempts:3}") int attempts,
            @Value("${processed.publish.initial-delay:1000}") long delay,
            @Value("${processed.publish.max-delay:5000}") long maxDelay) {
        return RetryTemplate.builder().maxAttempts(attempts).exponentialBackoff(delay,2,maxDelay)
                .retryOn(org.springframework.amqp.AmqpException.class).build();
    }

    @Bean
    SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer, ConnectionFactory connectionFactory,
            @Value("${resource.messaging.retry.max-attempts:5}") int attempts,
            @Value("${resource.messaging.retry.initial-delay:1000}") long initialDelay,
            @Value("${resource.messaging.retry.max-delay:10000}") long maxDelay) {
        var factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        var retry = RetryTemplate.builder().maxAttempts(attempts).exponentialBackoff(initialDelay, 2, maxDelay)
                .retryOn(ProcessorClientConfiguration::isTransient).build();
        factory.setAdviceChain(RetryInterceptorBuilder.stateless().retryOperations(retry)
                .recoverer(new RejectAndDontRequeueRecoverer()).build());
        factory.setDefaultRequeueRejected(false);
        factory.setPrefetchCount(1);
        return factory;
    }
}

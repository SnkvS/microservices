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
    org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory promotionRabbitListenerContainerFactory(
            org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer configurer,
            org.springframework.amqp.rabbit.connection.ConnectionFactory connections,
            @Value("${promotion.retry.max-attempts:5}") int attempts,
            @Value("${promotion.retry.initial-delay:1000}") long delay,
            @Value("${promotion.retry.max-delay:10000}") long maxDelay) {
        var factory=new org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory();
        configurer.configure(factory,connections);
        var retry=RetryTemplate.builder().maxAttempts(attempts).exponentialBackoff(delay,2,maxDelay)
                .retryOn(ResourceMessagingConfiguration::isTransientPromotionFailure).build();
        factory.setAdviceChain(org.springframework.amqp.rabbit.config.RetryInterceptorBuilder.stateless()
                .retryOperations(retry).recoverer(new org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer()).build());
        factory.setDefaultRequeueRejected(false); factory.setPrefetchCount(1);
        return factory;
    }

    static boolean isTransientPromotionFailure(Throwable failure) {
        for (Throwable cause=failure;cause!=null;cause=cause.getCause()) {
            if (cause instanceof software.amazon.awssdk.core.exception.SdkClientException
                    || cause instanceof org.springframework.dao.TransientDataAccessException) return true;
            if (cause instanceof software.amazon.awssdk.awscore.exception.AwsServiceException response)
                return response.statusCode()>=500 || response.statusCode()==408 || response.statusCode()==429;
        }
        return false;
    }

    @Bean
    RetryTemplate resourcePublishRetry(@Value("${resource.messaging.retry.max-attempts:3}") int attempts,
                                       @Value("${resource.messaging.retry.initial-delay:1000}") long initialDelay,
                                       @Value("${resource.messaging.retry.max-delay:5000}") long maxDelay) {
        return RetryTemplate.builder().maxAttempts(attempts).exponentialBackoff(initialDelay, 2, maxDelay)
                .retryOn(AmqpException.class).build();
    }
}

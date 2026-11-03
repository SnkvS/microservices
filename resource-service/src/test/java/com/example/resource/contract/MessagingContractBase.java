package com.example.resource.contract;

import com.example.resource.messaging.ResourceMessagingConfiguration;
import com.example.resource.messaging.ResourceUploadedPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.contract.verifier.converter.YamlContract;
import org.springframework.cloud.contract.verifier.messaging.MessageVerifierReceiver;
import org.springframework.cloud.contract.verifier.messaging.boot.AutoConfigureMessageVerifier;
import org.springframework.cloud.contract.verifier.messaging.internal.ContractVerifierMessage;
import org.springframework.cloud.contract.verifier.messaging.internal.ContractVerifierMessaging;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@ActiveProfiles("contract-test")
@AutoConfigureMessageVerifier
@SpringBootTest(classes = MessagingContractBase.Config.class, webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"eureka.client.enabled=false", "spring.rabbitmq.publisher-confirm-type=correlated",
                "spring.rabbitmq.publisher-returns=true", "spring.rabbitmq.template.mandatory=true"})
public abstract class MessagingContractBase {
    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.1-management");

    @DynamicPropertySource
    static void broker(DynamicPropertyRegistry properties) {
        properties.add("spring.rabbitmq.host", RABBIT::getHost);
        properties.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        properties.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        properties.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
    }

    @Autowired ResourceUploadedPublisher publisher;
    @Autowired AmqpAdmin admin;

    @BeforeEach
    void bindContractQueue() {
        admin.declareQueue(new Queue("contract.uploaded", false, false, true));
        admin.declareBinding(new Binding("contract.uploaded", Binding.DestinationType.QUEUE,
                "resources", "resource.uploaded", Map.of()));
        admin.purgeQueue("contract.uploaded");
    }

    public void publishResource() {
        publisher.publish(42);
    }

    @Configuration(proxyBeanMethods = false)
    @Profile("contract-test")
    @EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
    @Import({ResourceMessagingConfiguration.class, ResourceUploadedPublisher.class})
    static class Config {
        @Bean
        ContractVerifierMessaging<Message> contractVerifierMessaging(RabbitTemplate rabbit) {
            var receiver = new MessageVerifierReceiver<Message>() {
                @Override
                public Message receive(String destination, long timeout, TimeUnit unit, YamlContract contract) {
                    assertThat(destination).isEqualTo("resources:resource.uploaded");
                    return rabbit.receive("contract.uploaded", unit.toMillis(timeout));
                }

                @Override
                public Message receive(String destination, YamlContract contract) {
                    return receive(destination, 10, TimeUnit.SECONDS, contract);
                }
            };
            return new ContractVerifierMessaging<>(null, receiver) {
                @Override
                protected ContractVerifierMessage convert(Message message) {
                    if (message == null) return null;
                    assertThat(message.getMessageProperties().getReceivedDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
                    return new ContractVerifierMessage(message.getBody(),
                            Map.of("contentType", message.getMessageProperties().getContentType()));
                }
            };
        }
    }
}

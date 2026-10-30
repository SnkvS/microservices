package com.example.processor.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cloud.contract.verifier.converter.YamlContract;
import org.springframework.cloud.contract.verifier.messaging.MessageVerifierSender;
import org.springframework.cloud.contract.verifier.messaging.internal.ContractVerifierMessaging;
import org.springframework.context.annotation.Bean;

import java.util.Map;
import java.nio.charset.StandardCharsets;

/** Bridges generated message stubs to the real RabbitMQ transport. */
@TestConfiguration(proxyBeanMethods = false)
public class AmqpContractConfiguration {
    @Bean
    ContractVerifierMessaging<Message> amqpContractVerifierMessaging(MessageVerifierSender<Message> sender) {
        // Supply the AMQP helper explicitly so SCC does not select its generic
        // Spring Integration helper, whose Message<T> type is a different API.
        return new ContractVerifierMessaging<>(sender, null);
    }

    @Bean
    MessageVerifierSender<Message> contractMessageSender(RabbitTemplate rabbit, ObjectMapper mapper) {
        return new MessageVerifierSender<>() {
            @Override
            public void send(Message message, String destination, YamlContract contract) {
                String[] address = destination.split(":", 2);
                if (address.length != 2) throw new IllegalArgumentException("Expected exchange:routing-key: " + destination);
                rabbit.send(address[0], address[1], message);
            }

            @Override
            public <T> void send(T payload, Map<String, Object> headers, String destination, YamlContract contract) {
                var properties = new MessageProperties();
                properties.setContentType(String.valueOf(headers.get("contentType")));
                headers.forEach(properties::setHeader);
                try {
                    byte[] body;
                    if (payload instanceof byte[] bytes) body = bytes;
                    else if (payload instanceof String text) body = text.getBytes(StandardCharsets.UTF_8);
                    else body = mapper.writeValueAsBytes(payload);
                    send(new Message(body, properties), destination, contract);
                } catch (JsonProcessingException failure) {
                    throw new IllegalArgumentException("Cannot encode contract message", failure);
                }
            }
        };
    }
}

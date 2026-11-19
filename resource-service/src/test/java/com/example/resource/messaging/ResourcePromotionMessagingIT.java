package com.example.resource.messaging;
import com.example.resource.service.ResourcePromotionService;
import com.example.resource.integration.StorageCatalogException;
import com.example.resource.storage.ResourceStorageException;
import org.junit.jupiter.api.*;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.*;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.*;
import software.amazon.awssdk.core.exception.SdkClientException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;
@Testcontainers @ActiveProfiles("promotion-test")
@SpringBootTest(classes=ResourcePromotionMessagingIT.Config.class,webEnvironment=SpringBootTest.WebEnvironment.NONE,properties={
    "eureka.client.enabled=false","promotion.retry.initial-delay=1","promotion.retry.max-delay=2"})
class ResourcePromotionMessagingIT {
    @Container static final RabbitMQContainer RABBIT=new RabbitMQContainer("rabbitmq:4.1-management");
    @DynamicPropertySource static void broker(DynamicPropertyRegistry r) {
        r.add("spring.rabbitmq.host",RABBIT::getHost); r.add("spring.rabbitmq.port",RABBIT::getAmqpPort);
        r.add("spring.rabbitmq.username",RABBIT::getAdminUsername);r.add("spring.rabbitmq.password",RABBIT::getAdminPassword);
    }
    @Configuration(proxyBeanMethods=false) @Profile("promotion-test")
    @EnableAutoConfiguration(exclude=DataSourceAutoConfiguration.class)
    @Import({ResourceMessagingConfiguration.class,ResourceProcessedListener.class})
    static class Config {}
    @Autowired RabbitTemplate rabbit;
    @Autowired AmqpAdmin admin;
    @MockitoBean ResourcePromotionService promotion;
    @BeforeEach void clean() { admin.purgeQueue("resource.promotion");admin.purgeQueue("resource.promotion.dlq"); }
    void send(String json) { var p=new MessageProperties();p.setContentType("application/json");rabbit.send("resources","resource.processed",new Message(json.getBytes(StandardCharsets.UTF_8),p)); }
    @Test void deliversDuplicateEventsToTheIdempotentOperation() {
        send("{\"resourceId\":42}"); send("{\"resourceId\":42}");
        await().atMost(Duration.ofSeconds(10)).untilAsserted(()->verify(promotion,times(2)).promote(42));
    }
    @Test void retriesTransientStorageFailureThenAcknowledges() {
        doThrow(new ResourceStorageException("copy",SdkClientException.create("offline"))).doNothing().when(promotion).promote(42);
        send("{\"resourceId\":42}");
        await().atMost(Duration.ofSeconds(10)).untilAsserted(()->verify(promotion,times(2)).promote(42));
        assertThat(rabbit.receive("resource.promotion.dlq",300)).isNull();
    }
    @Test void malformedMessageIsDeadLetteredWithoutCallingPromotion() {
        send("{\"resourceId\":\"42\"}"); awaitDeadLetter();
        verifyNoInteractions(promotion);
    }
    @Test void permanentConfigurationFailureIsNotRetried() {
        doThrow(new StorageCatalogException("invalid location")).when(promotion).promote(42);
        send("{\"resourceId\":42}"); awaitDeadLetter();
        verify(promotion,times(1)).promote(42);
    }
    @Test void exhaustedTransientFailureHasFiveAttemptsAndReachesDlq() {
        doThrow(new ResourceStorageException("copy",SdkClientException.create("offline"))).when(promotion).promote(42);
        send("{\"resourceId\":42}"); awaitDeadLetter();
        verify(promotion,times(5)).promote(42);
    }
    void awaitDeadLetter() {
        var received=new AtomicReference<Message>();
        await().atMost(Duration.ofSeconds(15)).until(()->{received.set(rabbit.receive("resource.promotion.dlq",100));return received.get()!=null;});
        assertThat(received.get().getMessageProperties().getHeaders()).containsKey("x-death");
    }
}

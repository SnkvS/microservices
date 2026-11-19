package com.example.resource.contract;
import com.example.resource.messaging.*;
import com.example.resource.service.ResourcePromotionService;
import com.example.resource.support.AmqpContractConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.*;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.contract.stubrunner.StubFinder;
import org.springframework.cloud.contract.stubrunner.spring.*;
import org.springframework.context.annotation.*;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.*;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;
@Testcontainers @ActiveProfiles("contract-test")
@SpringBootTest(classes=ResourceProcessedContractTest.Config.class,webEnvironment=SpringBootTest.WebEnvironment.NONE,
    properties={"eureka.client.enabled=false","stubrunner.integration.enabled=false","stubrunner.stream.enabled=false"})
@AutoConfigureStubRunner(ids="com.example.microservices:resource-processor:1.0.0:stubs",stubsMode=StubRunnerProperties.StubsMode.LOCAL)
class ResourceProcessedContractTest {
    @Container static final RabbitMQContainer RABBIT=new RabbitMQContainer("rabbitmq:4.1-management");
    @DynamicPropertySource static void broker(DynamicPropertyRegistry r) {
        r.add("spring.rabbitmq.host",RABBIT::getHost);r.add("spring.rabbitmq.port",RABBIT::getAmqpPort);
        r.add("spring.rabbitmq.username",RABBIT::getAdminUsername);r.add("spring.rabbitmq.password",RABBIT::getAdminPassword);
    }
    @Configuration(proxyBeanMethods=false) @Profile("contract-test")
    @EnableAutoConfiguration(exclude=DataSourceAutoConfiguration.class)
    @Import({ResourceMessagingConfiguration.class,ResourceProcessedListener.class,AmqpContractConfiguration.class})
    static class Config {}
    @Autowired StubFinder stubs;
    @MockitoBean ResourcePromotionService promotion;
    @Test void processedProviderStubReachesTheRealPromotionListener() {
        assertThat(stubs.trigger("resource_processed")).isTrue();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(()->verify(promotion).promote(42));
    }
}

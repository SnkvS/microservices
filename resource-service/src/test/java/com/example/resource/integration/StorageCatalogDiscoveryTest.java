package com.example.resource.integration;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.*;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;
@ActiveProfiles("catalog-test")
@SpringBootTest(classes=StorageCatalogDiscoveryTest.Config.class,webEnvironment=SpringBootTest.WebEnvironment.NONE,
    properties={"eureka.client.enabled=false","spring.cloud.loadbalancer.retry.enabled=false","spring.cloud.loadbalancer.cache.enabled=false"})
class StorageCatalogDiscoveryTest {
    @Configuration(proxyBeanMethods=false) @Profile("catalog-test")
    @EnableAutoConfiguration(exclude={DataSourceAutoConfiguration.class,RabbitAutoConfiguration.class})
    @Import({SongClientConfiguration.class,StorageCatalogConfiguration.class,StorageFallbackProperties.class})
    static class Config {}
    @Autowired StorageCatalogClient catalog;
    @Autowired CircuitBreaker breaker;
    @Test void coldStartsWithNoDiscoveredStorageInstancesAndOpensOnBoundedCalls() {
        for(int i=0;i<3;i++) {
            var selected=catalog.select(StorageType.STAGING);
            assertThat(selected.bucket()).isEqualTo("resources-staging");
            assertThat(selected.locationFor("recording.mp3").key()).isEqualTo("files/recording.mp3");
        }
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }
}

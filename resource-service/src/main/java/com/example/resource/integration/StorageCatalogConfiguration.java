package com.example.resource.integration;
import io.github.resilience4j.circuitbreaker.*;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.*;
import org.springframework.context.annotation.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import java.time.Duration;
@Configuration
public class StorageCatalogConfiguration {
    @Bean CircuitBreaker storageCatalogBreaker(@Value("${storage.catalog.breaker.open-millis:10000}") long openMillis) {
        var config=CircuitBreakerConfig.custom().slidingWindowSize(5).minimumNumberOfCalls(3)
                .failureRateThreshold(50).waitDurationInOpenState(Duration.ofMillis(openMillis))
                .permittedNumberOfCallsInHalfOpenState(2)
                .recordException(StorageCatalogClient::isOutage).ignoreExceptions(StorageCatalogException.class).build();
        var breaker=CircuitBreaker.of("storageCatalog",config);
        breaker.getEventPublisher().onStateTransition(event->
                LoggerFactory.getLogger(StorageCatalogClient.class).info("Storage circuit breaker {} -> {}",event.getStateTransition().getFromState(),event.getStateTransition().getToState()));
        return breaker;
    }
    @Bean StorageCatalogClient storageCatalogClient(
            @Qualifier("songRestClientBuilder") RestClient.Builder builder,CircuitBreaker storageCatalogBreaker,
            StorageFallbackProperties fallback,
            @Value("${storage.service.url:http://storage-service}") String baseUrl,
            @Value("${storage.catalog.connect-timeout:1000}") int connectTimeout,
            @Value("${storage.catalog.read-timeout:2000}") int readTimeout) {
        var requests=new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(connectTimeout); requests.setReadTimeout(readTimeout);
        return new StorageCatalogClient(builder.clone().requestFactory(requests).baseUrl(baseUrl).build(),
                storageCatalogBreaker,fallback.definitions());
    }
}

package com.example.resource.integration;
import io.github.resilience4j.circuitbreaker.*;
import org.junit.jupiter.api.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import java.time.Duration;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.ExpectedCount.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class StorageCatalogClientTest {
    final List<StorageDefinition> fallback=List.of(
        new StorageDefinition(1,StorageType.STAGING,"resources-staging","/files"),
        new StorageDefinition(2,StorageType.PERMANENT,"resources-permanent","/files"));
    final CircuitBreaker breaker=CircuitBreaker.of("test",CircuitBreakerConfig.custom()
        .slidingWindowSize(5).minimumNumberOfCalls(3).failureRateThreshold(50)
        .waitDurationInOpenState(Duration.ofSeconds(10)).permittedNumberOfCallsInHalfOpenState(2)
        .ignoreExceptions(StorageCatalogException.class).build());
    MockRestServiceServer server;
    StorageCatalogClient client;
    @BeforeEach void setup() {
        var builder=RestClient.builder().baseUrl("http://storage-service");
        server=MockRestServiceServer.bindTo(builder).build();
        client=new StorageCatalogClient(builder.build(),breaker,fallback);
    }
    final String catalog="""
        [{"id":9,"storageType":"STAGING","bucket":"other-bucket","path":"/first"},
         {"id":3,"storageType":"STAGING","bucket":"selected-bucket","path":"/chosen"},
         {"id":4,"storageType":"PERMANENT","bucket":"final-bucket","path":"/done"}]
        """;
    @Test void selectsLowestIdFromLiveUnsortedCatalog() {
        server.expect(requestTo("http://storage-service/storages")).andRespond(withSuccess(catalog,MediaType.APPLICATION_JSON));
        assertThat(client.select(StorageType.STAGING).locationFor("recording.mp3").key()).isEqualTo("chosen/recording.mp3");
        server.verify();
    }
    @Test void opensAfterFailedCallsAvoidsHttpWhileOpenAndRecoversThroughProbes() {
        server.expect(times(3),requestTo("http://storage-service/storages")).andRespond(withServerError());
        server.expect(times(2),requestTo("http://storage-service/storages")).andRespond(withSuccess(catalog,MediaType.APPLICATION_JSON));
        for(int i=0;i<3;i++) assertThat(client.list()).isEqualTo(fallback);
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        for(int i=0;i<5;i++) assertThat(client.list()).isEqualTo(fallback);
        breaker.transitionToHalfOpenState();
        assertThat(client.select(StorageType.PERMANENT).bucket()).isEqualTo("final-bucket");
        assertThat(client.select(StorageType.STAGING).id()).isEqualTo(3);
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        server.verify();
    }
    @Test void failedHalfOpenProbesReopenBreaker() {
        server.expect(times(2),requestTo("http://storage-service/storages")).andRespond(withServerError());
        breaker.transitionToOpenState(); breaker.transitionToHalfOpenState();
        client.list(); client.list();
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        server.verify();
    }
    @Test void invalidSuccessfulResponseDoesNotFallBackOrCountAsOutage() {
        for(String body:List.of("[]","null","{}",catalog.replace("PERMANENT","UNKNOWN"),
                catalog.replace("\"id\":4","\"id\":-4"),catalog.replace("PERMANENT","STAGING"),catalog.replace("\"id\":4","\"id\":4.5"),
                catalog.replace("\"storageType\":\"STAGING\"","\"storageType\":0"),catalog+" true")) {
            var builder=RestClient.builder().baseUrl("http://storage-service");
            var fake=MockRestServiceServer.bindTo(builder).build();
            fake.expect(anything()).andRespond(withSuccess(body,MediaType.APPLICATION_JSON));
            var tested=new StorageCatalogClient(builder.build(),breaker,fallback);
            assertThatThrownBy(tested::list).isInstanceOf(StorageCatalogException.class);
            fake.verify();
        }
        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isZero();
    }
    @Test void unexpected4xxIsConfigurationError() {
        server.expect(anything()).andRespond(withStatus(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(client::list).isInstanceOf(StorageCatalogException.class);
        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isZero();
    }
    @Test void rateLimitAndTimeoutAreOutages() {
        server.expect(anything()).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(anything()).andRespond(withException(new java.net.SocketTimeoutException("offline")));
        assertThat(client.list()).isEqualTo(fallback);
        assertThat(client.list()).isEqualTo(fallback);
        server.verify();
    }
    @Test void rejectsFallbackDefinitionsThatResolveToTheSamePhysicalLocation() {
        assertThatThrownBy(()->new StorageFallbackProperties("same-bucket","/files","same-bucket","/files/"))
                .isInstanceOf(StorageCatalogException.class);
    }
    @Test void rejectsEquivalentLocationsInLiveCatalogBeforeWritingAnything() {
        String aliases="[{'id':1,'storageType':'STAGING','bucket':'same-bucket','path':'/files'},"
                +"{'id':2,'storageType':'PERMANENT','bucket':'same-bucket','path':'/files/'}]";
        server.expect(anything()).andRespond(withSuccess(aliases.replace((char)39,(char)34),MediaType.APPLICATION_JSON));
        assertThatThrownBy(client::list).isInstanceOf(StorageCatalogException.class);
        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isZero();
        server.verify();
    }
    @Test void rootPrefixDoesNotCreateLeadingSlash() {
        assertThat(new StorageDefinition(1,StorageType.STAGING,"valid-bucket","/").locationFor("x.mp3").key()).isEqualTo("x.mp3");
    }
}

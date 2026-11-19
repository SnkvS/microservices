package com.example.resource.contract;
import com.example.resource.integration.*;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.contract.stubrunner.*;
import org.springframework.cloud.contract.stubrunner.spring.*;
import org.springframework.context.annotation.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.*;
@SpringBootTest(classes=StorageCatalogContractTest.Config.class,webEnvironment=SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("contract-test")
@AutoConfigureStubRunner(ids="com.example.microservices:storage-service:1.0.0:stubs",stubsMode=StubRunnerProperties.StubsMode.LOCAL)
class StorageCatalogContractTest {
    @Configuration(proxyBeanMethods=false) @Profile("contract-test") static class Config {}
    @Autowired StubFinder stubs;
    @Test void consumesRealStorageProviderResponse() {
        var fallback=new StorageFallbackProperties("resources-staging","/files","resources-permanent","/files");
        var client=new StorageCatalogClient(RestClient.create(stubs.findStubUrl("storage-service").toString()),
                CircuitBreaker.ofDefaults("contract"),fallback.definitions());
        assertThat(client.list()).hasSize(2);
        assertThat(client.select(StorageType.STAGING).bucket()).isEqualTo("resources-staging");
        assertThat(client.select(StorageType.PERMANENT).path()).isEqualTo("/files");
    }
}

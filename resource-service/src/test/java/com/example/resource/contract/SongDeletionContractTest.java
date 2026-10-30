package com.example.resource.contract;

import com.example.resource.integration.SongClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.contract.stubrunner.StubFinder;
import org.springframework.cloud.contract.stubrunner.spring.AutoConfigureStubRunner;
import org.springframework.cloud.contract.stubrunner.spring.StubRunnerProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatNoException;

@SpringBootTest(classes = SongDeletionContractTest.Config.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("contract-test")
@AutoConfigureStubRunner(ids = "com.example.microservices:song-service:1.0.0:stubs",
        stubsMode = StubRunnerProperties.StubsMode.LOCAL)
class SongDeletionContractTest {
    @Configuration(proxyBeanMethods = false)
    @Profile("contract-test")
    static class Config { }

    @Autowired StubFinder stubs;

    @Test
    void realClientDeletesExistingAndMissingMetadataUsingProviderStubs() {
        var client = new SongClient(RestClient.builder(), stubs.findStubUrl("song-service").toString(),
                RetryTemplate.builder().maxAttempts(1).fixedBackoff(1).build());
        assertThatNoException().isThrownBy(() -> client.delete(List.of(43L, 99L)));
        assertThatNoException().isThrownBy(() -> client.delete(List.of(99L)));
    }
}

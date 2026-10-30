package com.example.processor.component;

import com.example.processor.ResourceProcessorApplication;
import com.example.processor.integration.ResourceClient;
import com.example.processor.integration.SongClient;
import com.example.processor.support.AmqpContractConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cucumber.java.Before;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.When;
import io.cucumber.java.en.Then;
import io.cucumber.spring.CucumberContextConfiguration;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cloud.contract.stubrunner.StubFinder;
import org.springframework.cloud.contract.stubrunner.spring.AutoConfigureStubRunner;
import org.springframework.cloud.contract.stubrunner.spring.StubRunnerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.RabbitMQContainer;

import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.time.Duration;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@CucumberContextConfiguration
@SpringBootTest(classes = {ResourceProcessorApplication.class, ProcessorSteps.StubClients.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"eureka.client.enabled=false", "spring.main.keep-alive=false",
                "stubrunner.integration.enabled=false", "stubrunner.stream.enabled=false"})
@AutoConfigureStubRunner(ids = {"com.example.microservices:resource-service:1.0.0:stubs",
        "com.example.microservices:song-service:1.0.0:stubs"}, stubsMode = StubRunnerProperties.StubsMode.LOCAL)
@Import(AmqpContractConfiguration.class)
public class ProcessorSteps {
    private static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.1-management");

    @DynamicPropertySource
    static void broker(DynamicPropertyRegistry properties) {
        // Cucumber is a separate engine: Jupiter's @Container extension does not run here.
        RABBIT.start();
        properties.add("spring.rabbitmq.host", RABBIT::getHost);
        properties.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        properties.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        properties.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StubClients {
        @Bean @Primary
        ResourceClient stubResourceClient(StubFinder stubs) {
            return new ResourceClient(RestClient.builder(), stubs.findStubUrl("resource-service").toString(),
                    RetryTemplate.builder().maxAttempts(1).fixedBackoff(1).build());
        }

        @Bean @Primary
        SongClient stubSongClient(StubFinder stubs) {
            return new SongClient(RestClient.builder(), stubs.findStubUrl("song-service").toString(),
                    RetryTemplate.builder().maxAttempts(1).fixedBackoff(1).build());
        }
    }

    @Autowired StubFinder stubs;
    @Autowired RabbitTemplate rabbit;
    @Autowired ObjectMapper mapper;
    private long resourceId;
    private long acknowledgementsBefore;

    @Before
    public void clearRequestJournals() {
        acknowledgementsBefore = queueState().path("message_stats").path("ack").asLong();
        for (String provider : new String[]{"resource-service", "song-service"}) {
            RestClient.create(stubs.findStubUrl(provider).toString()).delete().uri("/__admin/requests")
                    .retrieve().toBodilessEntity();
        }
    }

    @Given("resource {long} contains an MP3 with complete tags")
    @Given("resource {long} already has identical song metadata")
    @Given("resource {long} has already been deleted")
    public void resourceState(long id) {
        resourceId = id;
    }

    @When("its upload notification is delivered")
    public void deliverNotification() {
        var properties = new MessageProperties();
        properties.setContentType("application/json");
        rabbit.send("resources", "resource.uploaded", new Message(
                ("{\"resourceId\":" + resourceId + "}").getBytes(StandardCharsets.UTF_8), properties));
    }

    @Then("the extracted title, artist, album, duration and year are sent to Song Service")
    public void metadataIsPosted() {
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            var posts = StreamSupport.stream(requests("song-service").path("requests").spliterator(), false)
                    .map(event -> event.path("request"))
                    .filter(request -> request.path("method").asText().equals("POST")).toList();
            assertThat(posts).hasSize(1);
            assertThat(mapper.readTree(posts.get(0).path("body").asText())).isEqualTo(mapper.readTree(
                    "{\"id\":42,\"name\":\"Test Title\",\"artist\":\"Test Artist\",\"album\":\"Test Album\",\"duration\":\"00:07\",\"year\":\"2025\"}"));
        });
        noFailedMessage();
    }

    @Then("the existing metadata is checked and the notification completes successfully")
    public void duplicateCompletes() {
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(requests("song-service").toString()).contains("/songs/43"));
        noFailedMessage();
    }

    @Then("no metadata is created and the notification completes successfully")
    public void deletedResourceIsSkipped() {
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(requests("resource-service").toString()).contains("/resources/99"));
        noFailedMessage();
        assertThat(requests("song-service").path("requests")).isEmpty();
    }

    private JsonNode requests(String provider) {
        return RestClient.create(stubs.findStubUrl(provider).toString()).get().uri("/__admin/requests")
                .retrieve().body(JsonNode.class);
    }

    private void noFailedMessage() {
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(queueState().path("message_stats").path("ack").asLong())
                        .isGreaterThan(acknowledgementsBefore));
        assertThat(rabbit.receive("resource.processing.dlq", 500)).isNull();
    }

    private JsonNode queueState() {
        return RestClient.builder().baseUrl("http://" + RABBIT.getHost() + ":" + RABBIT.getHttpPort())
                .defaultHeaders(headers -> headers.setBasicAuth(RABBIT.getAdminUsername(), RABBIT.getAdminPassword()))
                .build().get().uri(URI.create("http://" + RABBIT.getHost() + ":" + RABBIT.getHttpPort()
                        + "/api/queues/%2F/resource.processing")).retrieve().body(JsonNode.class);
    }
}

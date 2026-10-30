package com.example.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cucumber.java.After;
import io.cucumber.java.Before;
import io.cucumber.java.en.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

public class ApiSteps {
    private final String baseUrl = System.getProperty("e2e.base-url");
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json = new ObjectMapper();
    private byte[] recording;
    private Long resourceId;
    private HttpResponse<byte[]> response;

    @Before
    public void awaitApis() {
        assertThat(baseUrl).as("Pass -De2e.base-url for the isolated test gateway").isNotBlank();
        await().ignoreExceptions().atMost(Duration.ofSeconds(120)).pollInterval(Duration.ofSeconds(1)).untilAsserted(() -> {
            assertThat(request("GET", "/resources/999999999999999999", null, null).statusCode()).isEqualTo(404);
            assertThat(request("GET", "/songs/999999999999999999", null, null).statusCode()).isEqualTo(404);
        });
    }

    @Given("an MP3 with known title, artist, album, duration and year")
    public void knownRecording() throws Exception {
        try (var input = getClass().getResourceAsStream("/mp3/valid.mp3")) {
            recording = input.readAllBytes();
        }
    }

    @When("I upload the recording through the gateway")
    public void upload() throws Exception {
        response = request("POST", "/resources", "audio/mpeg", recording);
        assertThat(response.statusCode()).isEqualTo(200);
        resourceId = body(response).path("id").longValue();
        assertThat(resourceId).isPositive();
    }

    @Then("matching song metadata becomes available within 60 seconds")
    public void metadataAppears() {
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(500)).untilAsserted(() -> {
            var metadata = request("GET", "/songs/" + resourceId, null, null);
            assertThat(metadata.statusCode()).isEqualTo(200);
            assertThat(body(metadata)).isEqualTo(json.readTree("{\"id\":" + resourceId
                    + ",\"name\":\"Test Title\",\"artist\":\"Test Artist\",\"album\":\"Test Album\",\"duration\":\"00:07\",\"year\":\"2025\"}"));
        });
    }

    @Then("downloading the resource returns the original MP3 bytes")
    public void download() throws Exception {
        var downloaded = request("GET", "/resources/" + resourceId, null, null);
        assertThat(downloaded.statusCode()).isEqualTo(200);
        assertThat(downloaded.headers().firstValue("Content-Type")).contains("audio/mpeg");
        assertThat(downloaded.headers().firstValueAsLong("Content-Length")).hasValue(recording.length);
        assertThat(downloaded.body()).containsExactly(recording);
    }

    @When("I delete the resource through the gateway")
    public void deleteResource() throws Exception {
        var deleted = request("DELETE", "/resources?id=" + resourceId + "," + resourceId, null, null);
        assertThat(deleted.statusCode()).isEqualTo(200);
        assertThat(body(deleted)).isEqualTo(json.readTree("{\"ids\":[" + resourceId + "]}"));
    }

    @Then("both resource and song metadata are unavailable")
    public void bothAreMissing() throws Exception {
        assertThat(request("GET", "/resources/" + resourceId, null, null).statusCode()).isEqualTo(404);
        assertThat(request("GET", "/songs/" + resourceId, null, null).statusCode()).isEqualTo(404);
    }

    @Then("repeating deletion returns an empty list")
    public void repeatDelete() throws Exception {
        var deleted = request("DELETE", "/resources?id=" + resourceId, null, null);
        assertThat(deleted.statusCode()).isEqualTo(200);
        assertThat(body(deleted)).isEqualTo(json.readTree("{\"ids\":[]}"));
        resourceId = null;
    }

    @When("I submit a resource with content type {string}")
    public void invalidUpload(String contentType) throws Exception {
        response = request("POST", "/resources", contentType, "not an MP3".getBytes(StandardCharsets.UTF_8));
    }

    @When("I request {string} through the gateway")
    public void invalidRequest(String path) throws Exception {
        response = request("GET", path, null, null);
    }

    @When("I submit song metadata without required fields")
    public void invalidSong() throws Exception {
        response = request("POST", "/songs", "application/json", "{\"id\":42}".getBytes(StandardCharsets.UTF_8));
    }

    @Then("the API responds with status {int} and error code {string}")
    public void errorResponse(int status, String code) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(body(response).path("errorCode").asText()).isEqualTo(code);
        assertThat(body(response).path("errorMessage").asText()).isNotBlank();
    }

    @After
    public void cleanOwnedResource() throws Exception {
        if (resourceId != null) {
            assertThat(request("DELETE", "/resources?id=" + resourceId, null, null).statusCode())
                    .as("Clean up resource %s", resourceId).isEqualTo(200);
        }
    }

    private HttpResponse<byte[]> request(String method, String path, String contentType, byte[] payload) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(30));
        if (contentType != null) builder.header("Content-Type", contentType);
        return http.send(builder.method(method, payload == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(payload)).build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private JsonNode body(HttpResponse<byte[]> message) throws Exception {
        return json.readTree(message.body());
    }
}

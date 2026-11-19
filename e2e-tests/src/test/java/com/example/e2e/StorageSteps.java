package com.example.e2e;
import com.fasterxml.jackson.databind.*;
import io.cucumber.java.After;
import io.cucumber.java.en.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
public class StorageSteps {
    final String base=System.getProperty("e2e.base-url");
    final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    final ObjectMapper json=new ObjectMapper();
    Long created;
    JsonNode deleted;
    String prefix;
    HttpResponse<String> request(String method,String path,String body) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(15)).header("Content-Type","application/json");
        return http.send(builder.method(method,body==null ? HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    @Given("both default storage types are available")
    public void defaults() {
        await().ignoreExceptions().atMost(Duration.ofSeconds(120)).untilAsserted(()->{
            var response=request("GET","/storages",null);
            assertThat(response.statusCode()).isEqualTo(200);
            var entries=json.readTree(response.body());
            assertThat(entries.findValuesAsText("storageType")).contains("STAGING","PERMANENT");
        });
    }
    @When("I create an additional staging definition")
    public void create() throws Exception {
        prefix="/e2e-"+UUID.randomUUID();
        var response=request("POST","/storages","{\"storageType\":\"STAGING\",\"bucket\":\"resources-staging\",\"path\":\""+prefix+"/\"}");
        assertThat(response.statusCode()).isEqualTo(200);
        created=json.readTree(response.body()).path("id").longValue();
        assertThat(created).isPositive();
    }
    @Then("it appears alongside the existing staging definition")
    public void listed() throws Exception {
        var response=request("GET","/storages",null);
        assertThat(response.statusCode()).isEqualTo(200);
        var entries=json.readTree(response.body());
        assertThat(entries.findValuesAsText("storageType").stream().filter("STAGING"::equals).count()).isGreaterThanOrEqualTo(2);
        assertThat(entries.findValuesAsText("path")).contains(prefix);
    }
    @When("I delete the additional definition with a duplicate and missing ID")
    public void delete() throws Exception {
        var response=request("DELETE","/storages?id="+created+","+created+",9223372036854775807",null);
        assertThat(response.statusCode()).isEqualTo(200); deleted=json.readTree(response.body());
    }
    @Then("storage deletion returns only the deleted ID and is repeatable")
    public void deleted() throws Exception {
        assertThat(deleted).isEqualTo(json.readTree("["+created+"]"));
        var response=request("DELETE","/storages?id="+created,null);
        assertThat(response.statusCode()).isEqualTo(200); assertThat(json.readTree(response.body()).isEmpty()).isTrue();created=null;
    }
    @Then("a 199 character storage deletion CSV succeeds and a 200 character CSV fails")
    public void csv() throws Exception {
        String accepted=String.join(",",java.util.Collections.nCopies(10,"9223372036854775807"));
        String rejected=accepted.substring(1)+",9";
        assertThat(accepted).hasSize(199);assertThat(rejected).hasSize(200);
        assertThat(request("DELETE","/storages?id="+accepted,null).statusCode()).isEqualTo(200);
        assertThat(request("DELETE","/storages?id="+rejected,null).statusCode()).isEqualTo(400);
    }
    @After public void cleanup() throws Exception { if(created!=null) assertThat(request("DELETE","/storages?id="+created,null).statusCode()).isEqualTo(200); }
}

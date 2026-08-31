package io.github.hectorvent.floci.services.devopsguru;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Verifies DevOps Guru event-sources restJson1 describe/update lifecycle. */
@QuarkusTest
public class DevOpsGuruEventSourcesConfigIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String WEST = "us-west-2";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void describeReturnsDisabledByDefault() {
        Response response = describe(auth("000000001401", EAST));
        assertEquals("DISABLED", response.path("EventSources.AmazonCodeGuruProfiler.Status"));
    }

    @Test
    void updateEnableDisableAndDescribeLifecycle() {
        String authorization = auth("000000001402", EAST);

        assertEquals("DISABLED", describe(authorization).path("EventSources.AmazonCodeGuruProfiler.Status"));

        update(authorization, profiler("ENABLED")).then().statusCode(200);
        assertEquals("ENABLED", describe(authorization).path("EventSources.AmazonCodeGuruProfiler.Status"));

        update(authorization, profiler("DISABLED")).then().statusCode(200);
        assertEquals("DISABLED", describe(authorization).path("EventSources.AmazonCodeGuruProfiler.Status"));
    }

    @Test
    void eventSourcesAreIsolatedByAccountAndRegion() {
        String first = auth("000000001403", EAST);
        String second = auth("000000001404", EAST);
        String west = auth("000000001403", WEST);

        update(first, profiler("ENABLED"));
        update(second, profiler("DISABLED"));
        update(west, profiler("ENABLED"));

        assertEquals("ENABLED", describe(first).path("EventSources.AmazonCodeGuruProfiler.Status"));
        assertEquals("DISABLED", describe(second).path("EventSources.AmazonCodeGuruProfiler.Status"));
        assertEquals("ENABLED", describe(west).path("EventSources.AmazonCodeGuruProfiler.Status"));
    }

    @Test
    void updateRejectsInvalidProfilerStatus() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000001405", EAST))
                .body(profiler("MAYBE"))
                .when()
                .put("/event-sources")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));
    }

    private static Response describe(String authorization) {
        return given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/event-sources")
                .then()
                .statusCode(200)
                .extract()
                .response();
    }

    private static Response update(String authorization, String body) {
        return given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body(body)
                .when()
                .put("/event-sources");
    }

    private static String profiler(String status) {
        return "{\"EventSources\":{\"AmazonCodeGuruProfiler\":{\"Status\":\"" + status + "\"}}}";
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region
                + "/devops-guru/aws4_request";
    }
}

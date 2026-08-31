package io.github.hectorvent.floci.services.securitylake;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;

/**
 * restJson1 coverage for the Alchemy Security Lake bindings suite:
 * {@code ListDataLakeExceptions} and {@code GetDataLakeSources} return empty
 * collections (and a default data-lake ARN) rather than
 * {@code UnknownOperationException}.
 */
@QuarkusTest
class SecurityLakeBindingsIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String ACCOUNT = "000000000701";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void listDataLakeExceptionsReturnsEmptyCollection() {
        post("/v1/datalake/exceptions", "{}")
                .then()
                .statusCode(200)
                .body("exceptions", hasSize(0));
    }

    @Test
    void getDataLakeSourcesReturnsEmptyCollection() {
        post("/v1/datalake/sources", "{}")
                .then()
                .statusCode(200)
                .body("dataLakeSources", hasSize(0))
                .body("dataLakeArn", containsString(":securitylake:"));
    }

    private static Response post(String path, String body) {
        return given()
                .contentType("application/json")
                .header("Authorization", auth(ACCOUNT, EAST))
                .body(body)
                .when()
                .post(path);
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region + "/securitylake/aws4_request";
    }
}

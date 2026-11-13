package io.floci.aws.services.finspace;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/** FinSpace's data API after its end of support: every call is refused, as there is no environment to serve it. */
@QuarkusTest
class FinspaceDataIntegrationTest {

    @Test
    void theDataApiRefusesCallsWithoutAnEnvironment() {
        given().header("Authorization", "AWS4-HMAC-SHA256 Credential=000000000000/20260101/us-east-1/finspace-api"
                        + "/aws4_request")
                .get("/datasetsv2/zzzzzzzzzzzzzzzzzzzzzzzzzz").then().statusCode(403)
                .header("x-amzn-ErrorType", equalTo("AccessDeniedException"))
                .body(equalTo(FinspaceDataRouteFilter.MESSAGE));
    }
}

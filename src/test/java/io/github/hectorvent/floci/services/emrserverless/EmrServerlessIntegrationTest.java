package io.github.hectorvent.floci.services.emrserverless;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * Pins the live-AWS service gate on {@code GetResourceDashboard}: EMR Serverless
 * denies the action for every API caller (even {@code Action: "*"}).
 */
@QuarkusTest
class EmrServerlessIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String APPLICATION_ID = "00abcdefabcdef01";
    private static final String RESOURCE_ID = "00abcdefabcdef01";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getResourceDashboardIsServiceGatedAccessDenied() {
        given()
                .header("Authorization", auth(EAST))
                .queryParam("resourceId", RESOURCE_ID)
                .queryParam("resourceType", "SPARK_DRIVER")
                .when()
                .get("/applications/" + APPLICATION_ID + "/dashboard")
                .then()
                .statusCode(403)
                .body("__type", equalTo("AccessDeniedException"))
                .body("message", containsString("emr-serverless:GetResourceDashboard"));
    }

    private static String auth(String region) {
        return "AWS4-HMAC-SHA256 Credential=AKID/20260205/" + region + "/emr-serverless/aws4_request";
    }
}

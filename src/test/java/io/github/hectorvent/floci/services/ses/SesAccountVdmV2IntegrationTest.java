package io.github.hectorvent.floci.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class SesAccountVdmV2IntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/ses/aws4_request";

    @Test
    void putAndGetAccountVdmAttributes() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"VdmAttributes\":{\"VdmEnabled\":\"ENABLED\","
                        + "\"DashboardAttributes\":{\"EngagementMetrics\":\"ENABLED\"},"
                        + "\"GuardianAttributes\":{\"OptimizedSharedDelivery\":\"ENABLED\"}}}")
                .when().put("/v2/email/account/vdm")
                .then().statusCode(200);

        given().header("Authorization", AUTH)
                .when().get("/v2/email/account")
                .then().statusCode(200)
                .body("VdmAttributes.VdmEnabled", equalTo("ENABLED"))
                .body("VdmAttributes.DashboardAttributes.EngagementMetrics", equalTo("ENABLED"));
    }
}

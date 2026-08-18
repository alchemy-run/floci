package io.github.hectorvent.floci.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesMultiRegionEndpointV2IntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/ses/aws4_request";

    @Test
    @Order(1)
    void createAndGetMultiRegionEndpoint() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"EndpointName\":\"floci-mre\",\"Details\":{\"RoutesDetails\":[{\"Region\":\"eu-west-1\"}]}}")
                .when().post("/v2/email/multi-region-endpoints")
                .then().statusCode(200)
                .body("EndpointId", notNullValue())
                .body("Status", equalTo("CREATING"));

        given().header("Authorization", AUTH)
                .when().get("/v2/email/multi-region-endpoints/floci-mre")
                .then().statusCode(200)
                .body("EndpointName", equalTo("floci-mre"))
                .body("Routes.Region", hasItem("eu-west-1"));
    }

    @Test
    @Order(2)
    void listAndDelete() {
        given().header("Authorization", AUTH)
                .when().get("/v2/email/multi-region-endpoints")
                .then().statusCode(200)
                .body("MultiRegionEndpoints.EndpointName", hasItem("floci-mre"));

        given().header("Authorization", AUTH)
                .when().delete("/v2/email/multi-region-endpoints/floci-mre")
                .then().statusCode(200);

        given().header("Authorization", AUTH)
                .when().get("/v2/email/multi-region-endpoints/floci-mre")
                .then().statusCode(404);
    }
}

package io.github.hectorvent.floci.services.controltower;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertFalse;

@QuarkusTest
class ControlTowerBaselineReadIntegrationTest {
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260921/us-east-1/controltower/aws4_request";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void everyListedBaselineCanBeReadAndUnknownIdentifiersFail() {
        List<String> arns = given().header("Authorization", AUTH).contentType("application/json").body("{}")
                .post("/list-baselines").then().statusCode(200)
                .extract().jsonPath().getList("baselines.arn", String.class);
        assertFalse(arns.isEmpty());
        for (String arn : arns) {
            given().header("Authorization", AUTH).contentType("application/json")
                    .body(Map.of("baselineIdentifier", arn))
                    .post("/get-baseline").then().statusCode(200)
                    .body("arn", equalTo(arn)).body("name", not(emptyString()));
        }
        given().header("Authorization", AUTH).contentType("application/json")
                .body(Map.of("baselineIdentifier", arns.getFirst().replace("us-east-1", "us-west-2")))
                .post("/get-baseline").then().statusCode(404)
                .body("__type", containsString("ResourceNotFoundException"));
        given().header("Authorization", AUTH).contentType("application/json")
                .body(Map.of("baselineIdentifier", "not-an-arn"))
                .post("/get-baseline").then().statusCode(400)
                .body("__type", containsString("ValidationException"));
    }
}

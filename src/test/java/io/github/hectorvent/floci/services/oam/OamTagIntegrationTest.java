package io.github.hectorvent.floci.services.oam;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class OamTagIntegrationTest {
    private static final String OWNER =
            "AWS4-HMAC-SHA256 Credential=333333333333/20260921/us-east-1/oam/aws4_request";
    private static final String OTHER =
            "AWS4-HMAC-SHA256 Credential=444444444444/20260921/us-east-1/oam/aws4_request";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void sharedTagRoutePreservesSinkOwnershipAndWireShape() {
        String arn = given().header("Authorization", OWNER).contentType("application/json")
                .body(Map.of("Name", "tag-routing", "Tags", Map.of("initial", "one")))
                .post("/CreateSink").then().statusCode(200).extract().path("Arn");
        try {
            given().header("Authorization", OWNER)
                    .get("/tags/{arn}", arn).then().statusCode(200)
                    .body("Tags.initial", equalTo("one"));
            given().header("Authorization", OWNER).contentType("application/json")
                    .body(Map.of("Tags", Map.of("added", "two")))
                    .put("/tags/{arn}", arn).then().statusCode(200);
            given().header("Authorization", OWNER).queryParam("tagKeys", "initial")
                    .delete("/tags/{arn}", arn).then().statusCode(200);
            given().header("Authorization", OWNER)
                    .get("/tags/{arn}", arn).then().statusCode(200)
                    .body("Tags", equalTo(Map.of("added", "two")));
            given().header("Authorization", OTHER)
                    .get("/tags/{arn}", arn).then().statusCode(404);
        } finally {
            given().header("Authorization", OWNER).contentType("application/json")
                    .body(Map.of("Identifier", arn)).post("/DeleteSink").then().statusCode(200);
        }
    }

    @Test
    void sameAccountLinkIsRejectedEvenWithAnAllowingPolicy() {
        String arn = given().header("Authorization", OWNER).contentType("application/json")
                .body(Map.of("Name", "same-account-link"))
                .post("/CreateSink").then().statusCode(200).extract().path("Arn");
        try {
            String policy = "{\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":\"*\","
                    + "\"Action\":\"oam:CreateLink\",\"Resource\":\"*\"}]}";
            given().header("Authorization", OWNER).contentType("application/json")
                    .body(Map.of("SinkIdentifier", arn, "Policy", policy))
                    .post("/PutSinkPolicy").then().statusCode(200);
            given().header("Authorization", OWNER).contentType("application/json")
                    .body(Map.of("SinkIdentifier", arn, "LabelTemplate", "$AccountName",
                            "ResourceTypes", List.of("AWS::CloudWatch::Metric")))
                    .post("/CreateLink").then().statusCode(400)
                    .body("__type", containsString("InvalidParameterException"))
                    .body("message", containsString("same account"));
            given().header("Authorization", OWNER).contentType("application/json")
                    .body(Map.of("SinkIdentifier", arn))
                    .post("/ListAttachedLinks").then().statusCode(200)
                    .body("Items", empty());
        } finally {
            given().header("Authorization", OWNER).contentType("application/json")
                    .body(Map.of("Identifier", arn)).post("/DeleteSink").then().statusCode(200);
        }
    }
}

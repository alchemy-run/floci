package io.github.hectorvent.floci.services.rbin;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;

/** Verifies Recycle Bin restJson1 rule CRUD, list, tags, and not-found. */
@QuarkusTest
class RbinIntegrationTest {

    private static final String EAST = "us-east-1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getRuleOnANonexistentRuleFailsWithResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth(EAST))
                .when()
                .get("/rules/00000000-0000-0000-0000-000000000000")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("Reason", equalTo("RULE_NOT_FOUND"));
    }

    @Test
    void createGetListTagUntagDeleteLifecycle() {
        String authorization = auth(EAST);
        String identifier = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body(tagLevelBody(7, "alchemy rbin bindings fixture rule"))
                .when()
                .post("/rules")
                .then()
                .statusCode(200)
                .body("Identifier", notNullValue())
                .body("ResourceType", equalTo("EBS_SNAPSHOT"))
                .body("RetentionPeriod.RetentionPeriodValue", equalTo(7))
                .body("RetentionPeriod.RetentionPeriodUnit", equalTo("DAYS"))
                .body("Description", equalTo("alchemy rbin bindings fixture rule"))
                .body("Status", equalTo("available"))
                .body("RuleArn", notNullValue())
                .extract()
                .path("Identifier");

        String arn = given()
                .header("Authorization", authorization)
                .when()
                .get("/rules/" + identifier)
                .then()
                .statusCode(200)
                .body("Identifier", equalTo(identifier))
                .body("ResourceType", equalTo("EBS_SNAPSHOT"))
                .body("RetentionPeriod.RetentionPeriodValue", equalTo(7))
                .body("Description", equalTo("alchemy rbin bindings fixture rule"))
                .body("ResourceTags[0].ResourceTagKey", equalTo("AlchemyRbinBindings"))
                .body("ResourceTags[0].ResourceTagValue", equalTo("true"))
                .body("Status", equalTo("available"))
                .extract()
                .path("RuleArn");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"ResourceType\":\"EBS_SNAPSHOT\"}")
                .when()
                .post("/list-rules")
                .then()
                .statusCode(200)
                .body("Rules.Identifier", hasItem(identifier));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"RetentionPeriod\":{\"RetentionPeriodValue\":14,\"RetentionPeriodUnit\":\"DAYS\"},"
                        + "\"Description\":\"alchemy rbin lifecycle test (updated)\","
                        + "\"ResourceTags\":[{\"ResourceTagKey\":\"AlchemyRbinBindings\",\"ResourceTagValue\":\"updated\"}]}")
                .when()
                .patch("/rules/" + identifier)
                .then()
                .statusCode(200)
                .body("RetentionPeriod.RetentionPeriodValue", equalTo(14))
                .body("Description", equalTo("alchemy rbin lifecycle test (updated)"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"Tags\":[{\"Key\":\"Extra\",\"Value\":\"1\"}]}")
                .when()
                .post("/tags/" + encode(arn))
                .then()
                .statusCode(204);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/tags/" + encode(arn))
                .then()
                .statusCode(200)
                .body("Tags.find { it.Key == 'purpose' }.Value", equalTo("test"))
                .body("Tags.find { it.Key == 'Extra' }.Value", equalTo("1"));

        given()
                .header("Authorization", authorization)
                .queryParam("tagKeys", "Extra")
                .when()
                .delete("/tags/" + encode(arn))
                .then()
                .statusCode(204);

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/rules/" + identifier)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/rules/" + identifier)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void listRulesFiltersByResourceType() {
        String authorization = auth(EAST);
        String snapshotId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body(tagLevelBody(7, "snapshot rule"))
                .when()
                .post("/rules")
                .then()
                .statusCode(200)
                .extract()
                .path("Identifier");

        String imageId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "ResourceType":"EC2_IMAGE",
                          "RetentionPeriod":{"RetentionPeriodValue":7,"RetentionPeriodUnit":"DAYS"},
                          "Description":"ami rule",
                          "ResourceTags":[{"ResourceTagKey":"AlchemyRbinBindings","ResourceTagValue":"true"}]
                        }
                        """)
                .when()
                .post("/rules")
                .then()
                .statusCode(200)
                .extract()
                .path("Identifier");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"ResourceType\":\"EBS_SNAPSHOT\"}")
                .when()
                .post("/list-rules")
                .then()
                .statusCode(200)
                .body("Rules.Identifier", hasItem(snapshotId));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"ResourceType\":\"EC2_IMAGE\"}")
                .when()
                .post("/list-rules")
                .then()
                .statusCode(200)
                .body("Rules.Identifier", hasItem(imageId));
    }

    private static String tagLevelBody(int days, String description) {
        return """
                {
                  "ResourceType":"EBS_SNAPSHOT",
                  "RetentionPeriod":{"RetentionPeriodValue":%d,"RetentionPeriodUnit":"DAYS"},
                  "Description":"%s",
                  "ResourceTags":[{"ResourceTagKey":"AlchemyRbinBindings","ResourceTagValue":"true"}],
                  "Tags":[{"Key":"purpose","Value":"test"}]
                }
                """.formatted(days, description);
    }

    private static String auth(String region) {
        return "AWS4-HMAC-SHA256 Credential=AKID/20260205/" + region + "/rbin/aws4_request";
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}

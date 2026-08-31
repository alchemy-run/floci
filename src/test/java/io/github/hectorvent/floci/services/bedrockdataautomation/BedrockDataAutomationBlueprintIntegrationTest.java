package io.github.hectorvent.floci.services.bedrockdataautomation;

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

/** Verifies Bedrock Data Automation restJson1 blueprint lifecycle and tags. */
@QuarkusTest
class BedrockDataAutomationBlueprintIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String SCHEMA = """
            {"$schema":"http://json-schema.org/draft-07/schema#","class":"invoice","type":"object",\
            "properties":{"invoice_number":{"type":"string","inferenceType":"explicit",\
            "instruction":"The invoice number"}}}
            """;
    private static final String UPDATED_SCHEMA = """
            {"$schema":"http://json-schema.org/draft-07/schema#","class":"invoice","type":"object",\
            "properties":{"invoice_number":{"type":"string","inferenceType":"explicit",\
            "instruction":"The unique invoice number on the header"}}}
            """;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getBlueprintOnANonexistentArnFailsWithResourceNotFoundException() {
        String arn = "arn:aws:bedrock:" + EAST + ":000000000401:blueprint/nonexistent-alchemy-probe";
        given()
                .contentType("application/json")
                .header("Authorization", auth(EAST))
                .body("{}")
                .when()
                .post("/blueprints/" + encode(arn) + "/")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createUpdateTagAndDeleteBlueprintLifecycle() {
        String authorization = auth(EAST);

        String arn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "blueprintName":"lifecycle-blueprint",
                          "type":"DOCUMENT",
                          "schema":%s,
                          "tags":[
                            {"key":"Environment","value":"test"},
                            {"key":"alchemy::id","value":"TestBlueprint"}
                          ]
                        }
                        """.formatted(jsonString(SCHEMA)))
                .when()
                .put("/blueprints/")
                .then()
                .statusCode(200)
                .body("blueprint.blueprintArn", notNullValue())
                .body("blueprint.blueprintName", equalTo("lifecycle-blueprint"))
                .body("blueprint.type", equalTo("DOCUMENT"))
                .body("blueprint.blueprintStage", equalTo("LIVE"))
                .extract().path("blueprint.blueprintArn");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/blueprints/" + encode(arn) + "/")
                .then()
                .statusCode(200)
                .body("blueprint.blueprintName", equalTo("lifecycle-blueprint"))
                .body("blueprint.schema", equalTo(SCHEMA.trim()));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"resourceOwner\":\"ACCOUNT\"}")
                .when()
                .post("/blueprints/")
                .then()
                .statusCode(200)
                .body("blueprints.blueprintArn", hasItem(arn))
                .body("blueprints.blueprintName", hasItem("lifecycle-blueprint"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"resourceARN\":\"" + arn + "\"}")
                .when()
                .post("/listTagsForResource")
                .then()
                .statusCode(200)
                .body("tags.key", hasItem("Environment"))
                .body("tags.value", hasItem("test"))
                .body("tags.key", hasItem("alchemy::id"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "schema":%s
                        }
                        """.formatted(jsonString(UPDATED_SCHEMA)))
                .when()
                .put("/blueprints/" + encode(arn) + "/")
                .then()
                .statusCode(200)
                .body("blueprint.blueprintArn", equalTo(arn))
                .body("blueprint.schema", equalTo(UPDATED_SCHEMA.trim()));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/blueprints/" + encode(arn) + "/")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/blueprints/" + encode(arn) + "/")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    private static String auth(String region) {
        return "AWS4-HMAC-SHA256 Credential=000000000401/20260205/" + region + "/bedrock/aws4_request";
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String jsonString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "") + "\"";
    }
}

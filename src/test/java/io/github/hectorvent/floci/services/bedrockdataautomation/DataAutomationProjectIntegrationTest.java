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

/** Verifies Bedrock Data Automation restJson1 project lifecycle, tags, and blueprint refs. */
@QuarkusTest
class DataAutomationProjectIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String DOCUMENT_OUTPUT = """
            {"document":{"extraction":{"granularity":{"types":["DOCUMENT","PAGE"]},\
            "boundingBox":{"state":"DISABLED"}},"generativeField":{"state":"DISABLED"},\
            "outputFormat":{"textFormat":{"types":["MARKDOWN"]},"additionalFileFormat":{"state":"DISABLED"}}}}
            """;
    private static final String DOCUMENT_OUTPUT_V2 = """
            {"document":{"extraction":{"granularity":{"types":["DOCUMENT","PAGE","ELEMENT"]},\
            "boundingBox":{"state":"DISABLED"}},"generativeField":{"state":"DISABLED"},\
            "outputFormat":{"textFormat":{"types":["MARKDOWN"]},"additionalFileFormat":{"state":"DISABLED"}}}}
            """;
    private static final String SCHEMA = """
            {"$schema":"http://json-schema.org/draft-07/schema#","description":"Extract receipt fields",\
            "class":"receipt","type":"object","definitions":{},"properties":{"total_amount":{\
            "type":"string","inferenceType":"explicit","instruction":"The total amount on the receipt"}}}
            """;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getDataAutomationProjectOnANonexistentArnFailsWithResourceNotFoundException() {
        String arn = "arn:aws:bedrock:" + EAST + ":000000000401:data-automation-project/nonexistent-alchemy-probe";
        given()
                .contentType("application/json")
                .header("Authorization", auth(EAST))
                .body("{}")
                .when()
                .post("/data-automation-projects/" + encode(arn) + "/")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createUpdateTagAndDeleteProjectLifecycle() {
        String authorization = auth(EAST);

        String arn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "projectName":"lifecycle-project",
                          "projectDescription":"alchemy test project",
                          "standardOutputConfiguration":%s,
                          "tags":[
                            {"key":"Environment","value":"test"},
                            {"key":"alchemy::id","value":"TestProject"}
                          ]
                        }
                        """.formatted(DOCUMENT_OUTPUT))
                .when()
                .put("/data-automation-projects/")
                .then()
                .statusCode(200)
                .body("projectArn", notNullValue())
                .body("status", equalTo("COMPLETED"))
                .extract().path("projectArn");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/data-automation-projects/" + encode(arn) + "/")
                .then()
                .statusCode(200)
                .body("project.projectArn", equalTo(arn))
                .body("project.projectName", equalTo("lifecycle-project"))
                .body("project.projectDescription", equalTo("alchemy test project"))
                .body("project.status", equalTo("COMPLETED"))
                .body("project.standardOutputConfiguration.document.extraction.granularity.types",
                        equalTo(java.util.List.of("DOCUMENT", "PAGE")));

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
                          "projectDescription":"alchemy test project v2",
                          "standardOutputConfiguration":%s
                        }
                        """.formatted(DOCUMENT_OUTPUT_V2))
                .when()
                .put("/data-automation-projects/" + encode(arn) + "/")
                .then()
                .statusCode(200)
                .body("projectArn", equalTo(arn))
                .body("status", equalTo("COMPLETED"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/data-automation-projects/" + encode(arn) + "/")
                .then()
                .statusCode(200)
                .body("project.projectDescription", equalTo("alchemy test project v2"))
                .body("project.standardOutputConfiguration.document.extraction.granularity.types",
                        equalTo(java.util.List.of("DOCUMENT", "PAGE", "ELEMENT")));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/data-automation-projects/" + encode(arn) + "/")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/data-automation-projects/" + encode(arn) + "/")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void projectWithCustomOutputDrivenByABlueprint() {
        String authorization = auth(EAST);

        String blueprintArn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "blueprintName":"custom-blueprint",
                          "type":"DOCUMENT",
                          "schema":%s
                        }
                        """.formatted(jsonString(SCHEMA)))
                .when()
                .put("/blueprints/")
                .then()
                .statusCode(200)
                .body("blueprint.blueprintArn", notNullValue())
                .extract().path("blueprint.blueprintArn");

        String projectArn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "projectName":"custom-project",
                          "standardOutputConfiguration":{"document":{"extraction":{"granularity":{"types":["DOCUMENT"]}}}},
                          "customOutputConfiguration":{"blueprints":[{"blueprintArn":"%s"}]}
                        }
                        """.formatted(blueprintArn))
                .when()
                .put("/data-automation-projects/")
                .then()
                .statusCode(200)
                .body("status", equalTo("COMPLETED"))
                .extract().path("projectArn");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/data-automation-projects/" + encode(projectArn) + "/")
                .then()
                .statusCode(200)
                .body("project.customOutputConfiguration.blueprints.blueprintArn", hasItem(blueprintArn));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/data-automation-projects/" + encode(projectArn) + "/")
                .then()
                .statusCode(200);
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

package io.github.hectorvent.floci.services.bedrockdataautomation;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.oneOf;

/**
 * Binding-surface operations used by Alchemy's BedrockDataAutomation Bindings suite:
 * async/sync invoke, library ingestion + entities, blueprint version/copy-stage,
 * and blueprint optimization typed errors.
 */
@QuarkusTest
class BedrockDataAutomationBindingsIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String JSON_11 = "application/x-amz-json-1.1";
    private static final String SCHEMA =
            "{\\\"$schema\\\":\\\"http://json-schema.org/draft-07/schema#\\\",\\\"class\\\":\\\"invoice\\\",\\\"type\\\":\\\"object\\\"}";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void invokeAsyncThenGetStatus() {
        String authorization = auth(EAST);
        String projectArn = createProject(authorization, "bindings-project");
        String profileArn = "arn:aws:bedrock:" + EAST + ":000000000401:data-automation-profile/us.data-automation-v1";

        String invocationArn = given()
                .contentType(JSON_11)
                .header("X-Amz-Target", "AmazonBedrockKeystoneRuntimeService.InvokeDataAutomationAsync")
                .header("Authorization", authorization)
                .body("""
                        {
                          "inputConfiguration":{"s3Uri":"s3://bindings-bucket/inputs/hello.pdf"},
                          "outputConfiguration":{"s3Uri":"s3://bindings-bucket/results/"},
                          "dataAutomationProfileArn":"%s",
                          "dataAutomationConfiguration":{"dataAutomationProjectArn":"%s","stage":"LIVE"}
                        }
                        """.formatted(profileArn, projectArn))
                .when().post("/")
                .then()
                .statusCode(200)
                .body("invocationArn", notNullValue())
                .extract().path("invocationArn");

        given()
                .contentType(JSON_11)
                .header("X-Amz-Target", "AmazonBedrockKeystoneRuntimeService.GetDataAutomationStatus")
                .header("Authorization", authorization)
                .body("{\"invocationArn\":\"" + invocationArn + "\"}")
                .when().post("/")
                .then()
                .statusCode(200)
                .body("status", oneOf("Created", "InProgress", "Success", "ServiceError", "ClientError"));
    }

    @Test
    void invokeSyncEmptyInputIsValidationException() {
        String authorization = auth(EAST);
        String profileArn = "arn:aws:bedrock:" + EAST + ":000000000401:data-automation-profile/us.data-automation-v1";
        given()
                .contentType(JSON_11)
                .header("X-Amz-Target", "AmazonBedrockKeystoneRuntimeService.InvokeDataAutomation")
                .header("Authorization", authorization)
                .body("""
                        {"inputConfiguration":{},"dataAutomationProfileArn":"%s"}
                        """.formatted(profileArn))
                .when().post("/")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));
    }

    @Test
    void libraryIngestionJobsAndMissingEntity() {
        String authorization = auth(EAST);
        String libraryArn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"libraryName\":\"bindings-library\",\"libraryDescription\":\"vocab\"}")
                .when().put("/data-automation-libraries/")
                .then().statusCode(200)
                .extract().path("libraryArn");

        String jobArn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "entityType":"VOCABULARY",
                          "operationType":"UPSERT",
                          "inputConfiguration":{"inlinePayload":{"upsertEntitiesInfo":[{
                            "vocabulary":{"language":"EN","phrases":[{"text":"Alchemy","displayAsText":"Alchemy"}]}
                          }]}},
                          "outputConfiguration":{"s3Uri":"s3://bindings-bucket/library-results/"}
                        }
                        """)
                .when().put("/data-automation-libraries/" + encode(libraryArn) + "/library-ingestion-jobs/")
                .then()
                .statusCode(200)
                .body("jobArn", notNullValue())
                .extract().path("jobArn");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when().post("/data-automation-libraries/" + encode(libraryArn)
                        + "/library-ingestion-jobs/" + encode(jobArn))
                .then()
                .statusCode(200)
                .body("job.jobStatus", oneOf("IN_PROGRESS", "COMPLETED", "COMPLETED_WITH_ERRORS", "FAILED"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when().post("/data-automation-libraries/" + encode(libraryArn) + "/library-ingestion-jobs/")
                .then()
                .statusCode(200)
                .body("jobs.size()", greaterThanOrEqualTo(1));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when().post("/data-automation-libraries/" + encode(libraryArn)
                        + "/entityType/VOCABULARY/entities/")
                .then()
                .statusCode(200)
                .body("entities.size()", greaterThanOrEqualTo(0));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when().post("/data-automation-libraries/" + encode(libraryArn)
                        + "/entityType/VOCABULARY/entities/nonexistent-alchemy-probe")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void blueprintVersionAndCopyStageFromMissingDevelopment() {
        String authorization = auth(EAST);
        String arn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {"blueprintName":"bindings-blueprint","type":"DOCUMENT","schema":"%s"}
                        """.formatted(SCHEMA))
                .when().put("/blueprints/")
                .then().statusCode(200)
                .extract().path("blueprint.blueprintArn");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when().post("/blueprints/" + encode(arn) + "/versions/")
                .then()
                .statusCode(200)
                .body("blueprint.blueprintVersion", notNullValue());

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"sourceStage\":\"DEVELOPMENT\",\"targetStage\":\"LIVE\"}")
                .when().put("/blueprints/" + encode(arn) + "/copy-stage")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void optimizeEmptySamplesAndMissingStatus() {
        String authorization = auth(EAST);
        String profileArn = "arn:aws:bedrock:" + EAST + ":000000000401:data-automation-profile/us.data-automation-v1";
        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "blueprint":{"blueprintArn":"arn:aws:bedrock:%s:000000000401:blueprint/x","stage":"LIVE"},
                          "samples":[],
                          "outputConfiguration":{"s3Object":{"s3Uri":"s3://b/out/"}},
                          "dataAutomationProfileArn":"%s"
                        }
                        """.formatted(EAST, profileArn))
                .when().post("/invokeBlueprintOptimizationAsync")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));

        String missing = "arn:aws:bedrock:" + EAST
                + ":000000000401:blueprint-optimization-invocation/00000000-0000-0000-0000-000000000000";
        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when().post("/getBlueprintOptimizationStatus/" + encode(missing))
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    private static String createProject(String authorization, String name) {
        return given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "projectName":"%s",
                          "projectDescription":"bindings",
                          "standardOutputConfiguration":{"document":{"extraction":{"granularity":{"types":["DOCUMENT"]}}}}
                        }
                        """.formatted(name))
                .when().put("/data-automation-projects/")
                .then()
                .statusCode(200)
                .body("projectArn", notNullValue())
                .extract().path("projectArn");
    }

    private static String auth(String region) {
        return "AWS4-HMAC-SHA256 Credential=000000000401/20260205/" + region + "/bedrock/aws4_request";
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

}

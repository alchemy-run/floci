package io.github.hectorvent.floci.services.osis;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

/** Verifies OSIS restJson1 GetPipeline / resource policy / endpoint probes used by Alchemy. */
@QuarkusTest
class OsisIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=000000000000/20260101/us-east-1/osis/aws4_request";
    private static final String PIPELINE_NAME = "osis-it-pipe";
    private static final String CONFIG = """
            version: "2"
            log-pipeline:
              source:
                http:
                  path: "/logs"
              sink:
                - s3:
                    bucket: "unused"
            """;
    private static final String INVALID_CONFIG = """
            version: "2"
            bad-pipeline:
              source:
                http:
                  path: "/logs/ingest"
              sink:
                - not-a-real-sink-plugin:
                    some_option: true
            """;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getPipelineOnANonexistentPipelineFailsWithResourceNotFoundException() {
        given()
                .header("Authorization", AUTH)
                .when()
                .get("/2022-01-01/osis/getPipeline/alchemy-nonexistent-probe")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void getResourcePolicyOnANonexistentPipelineReturnsTheEmptyDocument() {
        String arn = "arn:aws:osis:us-east-1:000000000000:pipeline/alchemy-nonexistent-probe";
        given()
                .header("Authorization", AUTH)
                .when()
                .get("/2022-01-01/osis/resourcePolicy/" + arn)
                .then()
                .statusCode(200)
                .body("Policy", equalTo("{}"));
    }

    @Test
    void validatePipelineAcceptsAValidHttpToS3Configuration() {
        given()
                .contentType("application/json")
                .header("Authorization", AUTH)
                .body("{\"PipelineConfigurationBody\":" + jsonString(CONFIG) + "}")
                .when()
                .post("/2022-01-01/osis/validatePipeline")
                .then()
                .statusCode(200)
                .body("isValid", equalTo(true))
                .body("Errors.size()", equalTo(0));
    }

    @Test
    void validatePipelineRejectsAnUnknownSinkPlugin() {
        given()
                .contentType("application/json")
                .header("Authorization", AUTH)
                .body("{\"PipelineConfigurationBody\":" + jsonString(INVALID_CONFIG) + "}")
                .when()
                .post("/2022-01-01/osis/validatePipeline")
                .then()
                .statusCode(200)
                .body("isValid", equalTo(false))
                .body("Errors.size()", greaterThan(0))
                .body("Errors[0].Message", not(emptyOrNullString()));
    }

    @Test
    void listPipelineBlueprintsReturnsTheCatalogAndGetReturnsATemplate() {
        String name = given()
                .header("Authorization", AUTH)
                .when()
                .post("/2022-01-01/osis/listPipelineBlueprints")
                .then()
                .statusCode(200)
                .body("Blueprints.size()", greaterThan(0))
                .extract()
                .path("Blueprints[0].BlueprintName");

        given()
                .header("Authorization", AUTH)
                .when()
                .get("/2022-01-01/osis/getPipelineBlueprint/" + name)
                .then()
                .statusCode(200)
                .body("Blueprint.BlueprintName", equalTo(name))
                .body("Blueprint.PipelineConfigurationBody", not(emptyOrNullString()));
    }

    @Test
    void listPipelineEndpointConnectionsReturnsAPage() {
        given()
                .header("Authorization", AUTH)
                .when()
                .get("/2022-01-01/osis/listPipelineEndpointConnections")
                .then()
                .statusCode(200)
                .body("PipelineEndpointConnections.size()", greaterThanOrEqualTo(0));
    }

    @Test
    void listPipelineEndpointsSucceedsOnAnEmptyAccount() {
        given()
                .header("Authorization", AUTH)
                .when()
                .get("/2022-01-01/osis/listPipelineEndpoints")
                .then()
                .statusCode(200)
                .body("PipelineEndpoints", org.hamcrest.Matchers.notNullValue());
    }

    @Test
    void pipelineCreateGetPolicyEndpointDeleteLifecycle() {
        given()
                .contentType("application/json")
                .header("Authorization", AUTH)
                .body("""
                        {
                          "PipelineName":"%s",
                          "MinUnits":1,
                          "MaxUnits":1,
                          "PipelineConfigurationBody":%s,
                          "Tags":[{"Key":"fixture","Value":"osis-pipeline"}]
                        }
                        """.formatted(PIPELINE_NAME, jsonString(CONFIG)))
                .when()
                .post("/2022-01-01/osis/createPipeline")
                .then()
                .statusCode(200)
                .body("Pipeline.PipelineName", equalTo(PIPELINE_NAME))
                .body("Pipeline.Status", equalTo("ACTIVE"))
                .body("Pipeline.MinUnits", equalTo(1))
                .body("Pipeline.MaxUnits", equalTo(1))
                .body("Pipeline.IngestEndpointUrls.size()", greaterThan(0));

        String arn = given()
                .header("Authorization", AUTH)
                .when()
                .get("/2022-01-01/osis/getPipeline/" + PIPELINE_NAME)
                .then()
                .statusCode(200)
                .body("Pipeline.Status", equalTo("ACTIVE"))
                .body("Pipeline.PipelineConfigurationBody", org.hamcrest.Matchers.containsString("log-pipeline"))
                .extract().path("Pipeline.PipelineArn");

        given()
                .header("Authorization", AUTH)
                .queryParam("arn", arn)
                .when()
                .get("/2022-01-01/osis/listTagsForResource")
                .then()
                .statusCode(200)
                .body("Tags.Key", hasItem("fixture"));

        given()
                .contentType("application/json")
                .header("Authorization", AUTH)
                .body("""
                        {"Policy":"{\\"Version\\":\\"2012-10-17\\",\\"Statement\\":[{\\"Effect\\":\\"Allow\\",\\"Action\\":\\"osis:Ingest\\",\\"Resource\\":\\"*\\"}]}"}
                        """)
                .when()
                .put("/2022-01-01/osis/resourcePolicy/" + arn)
                .then()
                .statusCode(200)
                .body("Policy", org.hamcrest.Matchers.containsString("osis:Ingest"));

        given()
                .header("Authorization", AUTH)
                .when()
                .get("/2022-01-01/osis/resourcePolicy/" + arn)
                .then()
                .statusCode(200)
                .body("Policy", org.hamcrest.Matchers.containsString("osis:Ingest"));

        String endpointId = given()
                .contentType("application/json")
                .header("Authorization", AUTH)
                .body("""
                        {
                          "PipelineArn":"%s",
                          "VpcOptions":{"SubnetIds":["subnet-aaa"],"SecurityGroupIds":["sg-aaa"]}
                        }
                        """.formatted(arn))
                .when()
                .post("/2022-01-01/osis/createPipelineEndpoint")
                .then()
                .statusCode(200)
                .body("EndpointId", startsWith("pe-"))
                .body("Status", equalTo("ACTIVE"))
                .extract().path("EndpointId");

        given()
                .header("Authorization", AUTH)
                .when()
                .get("/2022-01-01/osis/listPipelineEndpoints")
                .then()
                .statusCode(200)
                .body("PipelineEndpoints.EndpointId", hasItem(endpointId));

        given()
                .header("Authorization", AUTH)
                .when()
                .delete("/2022-01-01/osis/deletePipelineEndpoint/" + endpointId)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", AUTH)
                .when()
                .delete("/2022-01-01/osis/deletePipeline/" + PIPELINE_NAME)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", AUTH)
                .when()
                .get("/2022-01-01/osis/getPipeline/" + PIPELINE_NAME)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    private static String jsonString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }
}

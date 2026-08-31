package io.github.hectorvent.floci.services.fis;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies FIS restJson1 experiment-template, experiment, catalog, and safety-lever APIs. */
@QuarkusTest
class FisIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String ROLE = "arn:aws:iam::000000000701:role/FisRole";
    private static final String WAIT_BODY = """
            {
              "clientToken":"%s",
              "description":"alchemy fis bindings fixture template",
              "roleArn":"%s",
              "stopConditions":[{"source":"none"}],
              "actions":{
                "Wait":{
                  "actionId":"aws:fis:wait",
                  "parameters":{"duration":"PT3M"}
                }
              },
              "tags":{"alchemy-test":"fis","alchemy::instance":"inst-1"}
            }
            """;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void listExperimentTemplatesIsEmptyByDefault() {
        given()
                .header("Authorization", auth("000000000701", EAST))
                .when()
                .get("/experimentTemplates")
                .then()
                .statusCode(200)
                .body("experimentTemplates.size()", equalTo(0));
    }

    @Test
    void experimentTemplateLifecycleAndTags() {
        String authorization = auth("000000000702", EAST);
        String id = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body(WAIT_BODY.formatted("token-create", ROLE))
                .when()
                .post("/experimentTemplates")
                .then()
                .statusCode(200)
                .body("experimentTemplate.id", startsWith("EXT"))
                .body("experimentTemplate.actions.Wait.actionId", equalTo("aws:fis:wait"))
                .body("experimentTemplate.tags.alchemy-test", equalTo("fis"))
                .extract()
                .path("experimentTemplate.id");
        String arn = given()
                .header("Authorization", authorization)
                .when()
                .get("/experimentTemplates/" + id)
                .then()
                .statusCode(200)
                .body("experimentTemplate.id", equalTo(id))
                .body("experimentTemplate.roleArn", equalTo(ROLE))
                .extract()
                .path("experimentTemplate.arn");
        assertTrue(arn.contains(":experiment-template/" + id));

        String instanceId = given()
                .header("Authorization", authorization)
                .when()
                .get("/experimentTemplates")
                .then()
                .statusCode(200)
                .body("experimentTemplates.id", hasItem(id))
                .extract()
                .path("experimentTemplates.find { it.id == '" + id + "' }.tags['alchemy::instance']");
        assertEquals("inst-1", instanceId);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "description":"updated wait template",
                          "roleArn":"%s",
                          "stopConditions":[{"source":"none"}],
                          "actions":{
                            "Wait":{
                              "actionId":"aws:fis:wait",
                              "parameters":{"duration":"PT1M"}
                            }
                          }
                        }
                        """.formatted(ROLE))
                .when()
                .patch("/experimentTemplates/" + id)
                .then()
                .statusCode(200)
                .body("experimentTemplate.description", equalTo("updated wait template"))
                .body("experimentTemplate.actions.Wait.parameters.duration", equalTo("PT1M"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"tags\":{\"extra\":\"yes\"}}")
                .when()
                .post("/tags/" + arn)
                .then()
                .statusCode(204);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/tags/" + arn)
                .then()
                .statusCode(200)
                .body("tags.extra", equalTo("yes"))
                .body("tags['alchemy-test']", equalTo("fis"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/experimentTemplates/" + id)
                .then()
                .statusCode(200)
                .body("experimentTemplate.id", equalTo(id));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/experimentTemplates/" + id)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void startGetListStopWaitExperiment() {
        String authorization = auth("000000000703", EAST);
        String templateId = createWaitTemplate(authorization, "token-exp");

        String experimentId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "clientToken":"start-1",
                          "experimentTemplateId":"%s",
                          "tags":{"alchemy-test":"fis-bindings"}
                        }
                        """.formatted(templateId))
                .when()
                .post("/experiments")
                .then()
                .statusCode(200)
                .body("experiment.id", startsWith("EXP"))
                .body("experiment.state.status", equalTo("running"))
                .extract()
                .path("experiment.id");

        given()
                .header("Authorization", authorization)
                .when()
                .get("/experiments/" + experimentId)
                .then()
                .statusCode(200)
                .body("experiment.id", equalTo(experimentId))
                .body("experiment.experimentTemplateId", equalTo(templateId));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/experiments?experimentTemplateId=" + templateId)
                .then()
                .statusCode(200)
                .body("experiments.id", hasItem(experimentId));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/experiments/" + experimentId + "/resolvedTargets")
                .then()
                .statusCode(200)
                .body("resolvedTargets.size()", equalTo(0));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/experiments/" + experimentId)
                .then()
                .statusCode(200)
                .body("experiment.state.status", equalTo("stopping"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/experimentTemplates/" + templateId)
                .then()
                .statusCode(200);
    }

    @Test
    void actionAndTargetResourceTypeCatalog() {
        String authorization = auth("000000000704", EAST);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/actions/aws:fis:wait")
                .then()
                .statusCode(200)
                .body("action.id", equalTo("aws:fis:wait"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/actions")
                .then()
                .statusCode(200)
                .body("actions.id", hasItem("aws:fis:wait"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/targetResourceTypes/aws:ec2:instance")
                .then()
                .statusCode(200)
                .body("targetResourceType.resourceType", equalTo("aws:ec2:instance"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/targetResourceTypes")
                .then()
                .statusCode(200)
                .body("targetResourceTypes.resourceType", hasItem("aws:ec2:instance"));
    }

    @Test
    void defaultSafetyLeverIsDisengaged() {
        given()
                .header("Authorization", auth("000000000705", EAST))
                .when()
                .get("/safetyLevers/default")
                .then()
                .statusCode(200)
                .body("safetyLever.id", equalTo("default"))
                .body("safetyLever.state.status", equalTo("disengaged"));
    }

    @Test
    void createIsIdempotentOnClientToken() {
        String authorization = auth("000000000706", EAST);
        Response first = createWaitTemplateResponse(authorization, "same-token");
        String firstId = first.path("experimentTemplate.id");
        Response second = createWaitTemplateResponse(authorization, "same-token");
        assertEquals(firstId, second.path("experimentTemplate.id"));
        assertEquals(200, second.statusCode());
    }

    @Test
    void missingTemplateIsNotFound() {
        given()
                .header("Authorization", auth("000000000707", EAST))
                .when()
                .get("/experimentTemplates/EXTmissing00000000")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    private static String createWaitTemplate(String authorization, String clientToken) {
        return createWaitTemplateResponse(authorization, clientToken).path("experimentTemplate.id");
    }

    private static Response createWaitTemplateResponse(String authorization, String clientToken) {
        return given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body(WAIT_BODY.formatted(clientToken, ROLE))
                .when()
                .post("/experimentTemplates")
                .then()
                .statusCode(200)
                .body("experimentTemplate.id", notNullValue())
                .extract()
                .response();
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region + "/fis/aws4_request";
    }
}

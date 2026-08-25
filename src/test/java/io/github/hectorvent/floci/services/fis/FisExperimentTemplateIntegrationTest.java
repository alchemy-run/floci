package io.github.hectorvent.floci.services.fis;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

/** Verifies FIS restJson1 experiment-template lifecycle, tags, and list. */
@QuarkusTest
class FisExperimentTemplateIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String ROLE_ARN =
            "arn:aws:iam::000000000000:role/FisExperimentRole";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getMissingTemplateFailsWithResourceNotFoundException() {
        given()
                .header("Authorization", auth(EAST))
                .when()
                .get("/experimentTemplates/EXTmissing00000000")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createGetUpdateTagUntagListAndDeleteLifecycle() {
        String authorization = auth(EAST);
        String clientToken = UUID.randomUUID().toString();

        String id = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "clientToken":"%s",
                          "description":"alchemy fis test",
                          "roleArn":"%s",
                          "stopConditions":[{"source":"none"}],
                          "targets":{
                            "Instances":{
                              "resourceType":"aws:ec2:instance",
                              "resourceTags":{"alchemy:fis-test":"true"},
                              "selectionMode":"COUNT(1)"
                            }
                          },
                          "actions":{
                            "StopInstances":{
                              "actionId":"aws:ec2:stop-instances",
                              "parameters":{"startInstancesAfterDuration":"PT2M"},
                              "targets":{"Instances":"Instances"}
                            }
                          },
                          "tags":{"Environment":"test","alchemy::id":"StopInstancesTemplate"}
                        }
                        """.formatted(clientToken, ROLE_ARN))
                .when()
                .post("/experimentTemplates")
                .then()
                .statusCode(200)
                .body("experimentTemplate.id", startsWith("EXT"))
                .body("experimentTemplate.arn", notNullValue())
                .body("experimentTemplate.description", equalTo("alchemy fis test"))
                .body("experimentTemplate.roleArn", equalTo(ROLE_ARN))
                .body("experimentTemplate.stopConditions[0].source", equalTo("none"))
                .body("experimentTemplate.targets.Instances.resourceType", equalTo("aws:ec2:instance"))
                .body("experimentTemplate.targets.Instances.selectionMode", equalTo("COUNT(1)"))
                .body("experimentTemplate.targets.Instances.resourceTags.alchemy:fis-test", equalTo("true"))
                .body("experimentTemplate.actions.StopInstances.actionId", equalTo("aws:ec2:stop-instances"))
                .body("experimentTemplate.actions.StopInstances.parameters.startInstancesAfterDuration",
                        equalTo("PT2M"))
                .body("experimentTemplate.tags.Environment", equalTo("test"))
                .body("experimentTemplate.experimentOptions.accountTargeting", equalTo("single-account"))
                .extract().path("experimentTemplate.id");

        String arn = given()
                .header("Authorization", authorization)
                .when()
                .get("/experimentTemplates/" + id)
                .then()
                .statusCode(200)
                .body("experimentTemplate.id", equalTo(id))
                .body("experimentTemplate.tags.'alchemy::id'", equalTo("StopInstancesTemplate"))
                .extract().path("experimentTemplate.arn");

        given()
                .header("Authorization", authorization)
                .when()
                .get("/experimentTemplates")
                .then()
                .statusCode(200)
                .body("experimentTemplates.id", hasItem(id))
                .body("experimentTemplates.tags.Environment", hasItem("test"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "description":"alchemy fis test updated",
                          "roleArn":"%s",
                          "stopConditions":[{"source":"none"}],
                          "targets":{
                            "Instances":{
                              "resourceType":"aws:ec2:instance",
                              "resourceTags":{"alchemy:fis-test":"true"},
                              "selectionMode":"ALL"
                            }
                          },
                          "actions":{
                            "StopInstances":{
                              "actionId":"aws:ec2:stop-instances",
                              "parameters":{"startInstancesAfterDuration":"PT2M"},
                              "targets":{"Instances":"Instances"}
                            },
                            "Wait":{
                              "actionId":"aws:fis:wait",
                              "parameters":{"duration":"PT1M"},
                              "startAfter":["StopInstances"]
                            }
                          }
                        }
                        """.formatted(ROLE_ARN))
                .when()
                .patch("/experimentTemplates/" + id)
                .then()
                .statusCode(200)
                .body("experimentTemplate.id", equalTo(id))
                .body("experimentTemplate.description", equalTo("alchemy fis test updated"))
                .body("experimentTemplate.targets.Instances.selectionMode", equalTo("ALL"))
                .body("experimentTemplate.actions.Wait.actionId", equalTo("aws:fis:wait"))
                .body("experimentTemplate.actions.Wait.startAfter[0]", equalTo("StopInstances"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"tags\":{\"Team\":\"chaos\"}}")
                .when()
                .post("/tags/" + encode(arn))
                .then()
                .statusCode(204);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/experimentTemplates/" + id)
                .then()
                .statusCode(200)
                .body("experimentTemplate.tags.Team", equalTo("chaos"))
                .body("experimentTemplate.tags.Environment", equalTo("test"));

        given()
                .header("Authorization", authorization)
                .queryParam("tagKeys", "Team")
                .when()
                .delete("/tags/" + encode(arn))
                .then()
                .statusCode(204);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/experimentTemplates/" + id)
                .then()
                .statusCode(200)
                .body("experimentTemplate.tags.Team", equalTo(null))
                .body("experimentTemplate.tags.Environment", equalTo("test"))
                .body("experimentTemplate.tags.'alchemy::id'", equalTo("StopInstancesTemplate"));

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
    void createWaitTemplateDefaultsAccountTargetingAndHonorsMultiAccount() {
        String authorization = auth(EAST);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "clientToken":"%s",
                          "description":"WaitTemplate",
                          "roleArn":"%s",
                          "stopConditions":[{"source":"none"}],
                          "actions":{
                            "Wait":{
                              "actionId":"aws:fis:wait",
                              "parameters":{"duration":"PT1M"}
                            }
                          }
                        }
                        """.formatted(UUID.randomUUID(), ROLE_ARN))
                .when()
                .post("/experimentTemplates")
                .then()
                .statusCode(200)
                .body("experimentTemplate.description", equalTo("WaitTemplate"))
                .body("experimentTemplate.experimentOptions.accountTargeting", equalTo("single-account"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "clientToken":"%s",
                          "description":"WaitTemplate",
                          "roleArn":"%s",
                          "stopConditions":[{"source":"none"}],
                          "actions":{
                            "Wait":{
                              "actionId":"aws:fis:wait",
                              "parameters":{"duration":"PT1M"}
                            }
                          },
                          "experimentOptions":{"accountTargeting":"multi-account"}
                        }
                        """.formatted(UUID.randomUUID(), ROLE_ARN))
                .when()
                .post("/experimentTemplates")
                .then()
                .statusCode(200)
                .body("experimentTemplate.experimentOptions.accountTargeting", equalTo("multi-account"));
    }

    private static String auth(String region) {
        return "AWS4-HMAC-SHA256 Credential=AKID/20260205/" + region + "/fis/aws4_request";
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}

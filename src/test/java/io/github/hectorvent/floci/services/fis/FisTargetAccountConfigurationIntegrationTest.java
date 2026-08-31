package io.github.hectorvent.floci.services.fis;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

/**
 * Target-account configuration lifecycle plus the parent experiment-template
 * ops Alchemy's TargetAccountConfiguration provider depends on.
 */
@QuarkusTest
class FisTargetAccountConfigurationIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final String ROLE = "arn:aws:iam::000000000000:role/FisTargetRole";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void listExperimentTemplatesReturnsArray() {
        given()
                .header("Authorization", auth(ACCOUNT, EAST))
                .when()
                .get("/experimentTemplates")
                .then()
                .statusCode(200)
                .body("experimentTemplates", notNullValue());
    }

    @Test
    void missingTemplateIsResourceNotFound() {
        given()
                .header("Authorization", auth(ACCOUNT, EAST))
                .when()
                .get("/experimentTemplates/EXTfffffffffffffffff")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void targetAccountConfigurationCreateUpdateGetListDeleteLifecycle() {
        String authorization = auth(ACCOUNT, EAST);
        String templateId = createMultiAccountTemplate(authorization, "tac-lifecycle");

        given()
                .header("Authorization", authorization)
                .when()
                .get("/experimentTemplates")
                .then()
                .statusCode(200)
                .body("experimentTemplates.id", hasItem(templateId));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "roleArn": "%s",
                          "description": "alchemy fis tac test"
                        }
                        """.formatted(ROLE))
                .when()
                .post("/experimentTemplates/" + templateId + "/targetAccountConfigurations/" + ACCOUNT)
                .then()
                .statusCode(200)
                .body("targetAccountConfiguration.accountId", equalTo(ACCOUNT))
                .body("targetAccountConfiguration.roleArn", equalTo(ROLE))
                .body("targetAccountConfiguration.description", equalTo("alchemy fis tac test"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "roleArn": "%s",
                          "description": "duplicate"
                        }
                        """.formatted(ROLE))
                .when()
                .post("/experimentTemplates/" + templateId + "/targetAccountConfigurations/" + ACCOUNT)
                .then()
                .statusCode(409)
                .body("__type", equalTo("ConflictException"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/experimentTemplates/" + templateId + "/targetAccountConfigurations/" + ACCOUNT)
                .then()
                .statusCode(200)
                .body("targetAccountConfiguration.accountId", equalTo(ACCOUNT))
                .body("targetAccountConfiguration.description", equalTo("alchemy fis tac test"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "roleArn": "%s",
                          "description": "alchemy fis tac test updated"
                        }
                        """.formatted(ROLE))
                .when()
                .patch("/experimentTemplates/" + templateId + "/targetAccountConfigurations/" + ACCOUNT)
                .then()
                .statusCode(200)
                .body("targetAccountConfiguration.description", equalTo("alchemy fis tac test updated"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/experimentTemplates/" + templateId + "/targetAccountConfigurations")
                .then()
                .statusCode(200)
                .body("targetAccountConfigurations.accountId", hasItem(ACCOUNT))
                .body("targetAccountConfigurations.description", hasItem("alchemy fis tac test updated"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/experimentTemplates/" + templateId)
                .then()
                .statusCode(200)
                .body("experimentTemplate.targetAccountConfigurationsCount", equalTo(1))
                .body("experimentTemplate.experimentOptions.accountTargeting", equalTo("multi-account"))
                .body("experimentTemplate.arn", startsWith("arn:aws:fis:" + EAST + ":" + ACCOUNT
                        + ":experiment-template/"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/experimentTemplates/" + templateId + "/targetAccountConfigurations/" + ACCOUNT)
                .then()
                .statusCode(200)
                .body("targetAccountConfiguration.accountId", equalTo(ACCOUNT));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/experimentTemplates/" + templateId + "/targetAccountConfigurations/" + ACCOUNT)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/experimentTemplates/" + templateId)
                .then()
                .statusCode(200)
                .body("experimentTemplate.id", equalTo(templateId));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/experimentTemplates")
                .then()
                .statusCode(200)
                .body("experimentTemplates.id", not(hasItem(templateId)));
    }

    @Test
    void targetAccountConfigurationRequiresMultiAccountTemplate() {
        String authorization = auth(ACCOUNT, EAST);
        String templateId = createTemplate(authorization, "single-account", null);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "roleArn": "%s",
                          "description": "should fail"
                        }
                        """.formatted(ROLE))
                .when()
                .post("/experimentTemplates/" + templateId + "/targetAccountConfigurations/" + ACCOUNT)
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));
    }

    private static String createMultiAccountTemplate(String authorization, String description) {
        return createTemplate(authorization, description, "multi-account");
    }

    private static String createTemplate(String authorization, String description, String accountTargeting) {
        String options = accountTargeting == null
                ? ""
                : """
                ,
                  "experimentOptions": { "accountTargeting": "%s" }
                """.formatted(accountTargeting);
        return given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "clientToken": "%s",
                          "description": "%s",
                          "roleArn": "%s",
                          "stopConditions": [{ "source": "none" }],
                          "actions": {
                            "Wait": {
                              "actionId": "aws:fis:wait",
                              "parameters": { "duration": "PT1M" }
                            }
                          },
                          "tags": { "Owner": "floci" }
                          %s
                        }
                        """.formatted(UUID.randomUUID(), description, ROLE, options))
                .when()
                .post("/experimentTemplates")
                .then()
                .statusCode(200)
                .body("experimentTemplate.id", startsWith("EXT"))
                .extract()
                .path("experimentTemplate.id");
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region + "/fis/aws4_request";
    }
}

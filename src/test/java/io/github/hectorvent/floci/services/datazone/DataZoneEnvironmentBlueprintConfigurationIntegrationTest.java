package io.github.hectorvent.floci.services.datazone;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

/**
 * Verifies DataZone restJson1 environment blueprint configuration PUT/GET/list/delete
 * against the managed DefaultDataLake blueprint.
 */
@QuarkusTest
class DataZoneEnvironmentBlueprintConfigurationIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String ROLE =
            "arn:aws:iam::000000000000:role/datazone-provisioning";
    private static final String MANAGE_ROLE =
            "arn:aws:iam::000000000000:role/datazone-manage-access";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getConfigurationOnAMissingBlueprintFailsWithResourceNotFoundException() {
        String authorization = auth(EAST);
        String domainId = createDomain(authorization, "dz-bp-missing-" + UUID.randomUUID().toString().substring(0, 8));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/v2/domains/" + domainId
                        + "/environment-blueprint-configurations/11111111-1111-1111-1111-111111111111")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void putGetUpdateAndDeleteDefaultDataLakeConfiguration() {
        String authorization = auth(EAST);
        String domainId = createDomain(authorization, "dz-bp-" + UUID.randomUUID().toString().substring(0, 8));

        String blueprintId = given()
                .header("Authorization", authorization)
                .when()
                .get("/v2/domains/" + domainId + "/environment-blueprints?managed=true")
                .then()
                .statusCode(200)
                .body("items.name", hasItem("DefaultDataLake"))
                .extract()
                .path("items.find { it.name == 'DefaultDataLake' }.id");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "enabledRegions":["us-west-2"],
                          "provisioningRoleArn":"%s",
                          "manageAccessRoleArn":"%s"
                        }
                        """.formatted(ROLE, MANAGE_ROLE))
                .when()
                .put("/v2/domains/" + domainId + "/environment-blueprint-configurations/" + blueprintId)
                .then()
                .statusCode(200)
                .body("domainId", equalTo(domainId))
                .body("environmentBlueprintId", equalTo(blueprintId))
                .body("enabledRegions", hasItem("us-west-2"))
                .body("provisioningRoleArn", equalTo(ROLE))
                .body("manageAccessRoleArn", equalTo(MANAGE_ROLE))
                .body("createdAt", notNullValue());

        given()
                .header("Authorization", authorization)
                .when()
                .get("/v2/domains/" + domainId + "/environment-blueprint-configurations/" + blueprintId)
                .then()
                .statusCode(200)
                .body("enabledRegions", hasItem("us-west-2"))
                .body("provisioningRoleArn", equalTo(ROLE))
                .body("manageAccessRoleArn", equalTo(MANAGE_ROLE));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "enabledRegions":["us-west-2"],
                          "provisioningRoleArn":"%s",
                          "manageAccessRoleArn":"%s",
                          "regionalParameters":{"us-west-2":{"S3Location":"s3://alchemy-datazone-test"}}
                        }
                        """.formatted(ROLE, MANAGE_ROLE))
                .when()
                .put("/v2/domains/" + domainId + "/environment-blueprint-configurations/" + blueprintId)
                .then()
                .statusCode(200)
                .body("regionalParameters.us-west-2.S3Location", equalTo("s3://alchemy-datazone-test"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/v2/domains/" + domainId + "/environment-blueprint-configurations")
                .then()
                .statusCode(200)
                .body("items.find { it.environmentBlueprintId == '" + blueprintId
                        + "' }.regionalParameters.'us-west-2'.S3Location",
                        equalTo("s3://alchemy-datazone-test"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/v2/domains/" + domainId + "/environment-blueprint-configurations/" + blueprintId)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/v2/domains/" + domainId + "/environment-blueprint-configurations/" + blueprintId)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/v2/domains/" + domainId)
                .then()
                .statusCode(200)
                .body("status", equalTo("DELETING"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/v2/domains/" + domainId + "/environment-blueprint-configurations/" + blueprintId)
                .then()
                .statusCode(403)
                .body("__type", equalTo("AccessDeniedException"));
    }

    private static String createDomain(String authorization, String name) {
        return given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "name":"%s",
                          "description":"alchemy datazone blueprint config test",
                          "domainExecutionRole":"arn:aws:iam::000000000000:role/datazone-domain-execution"
                        }
                        """.formatted(name))
                .when()
                .post("/v2/domains")
                .then()
                .statusCode(200)
                .body("id", startsWith("dzd"))
                .extract().path("id");
    }

    private static String auth(String region) {
        return "AWS4-HMAC-SHA256 Credential=AKID/20260205/" + region + "/datazone/aws4_request";
    }
}

package io.github.hectorvent.floci.services.dlm;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

/** Verifies DLM restJson1 lifecycle policy CRUD, tags, and not-found. */
@QuarkusTest
class DlmIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/DlmExecution";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getLifecyclePolicyOnANonexistentPolicyFailsWithResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth(EAST))
                .when()
                .get("/policies/policy-missing00000000")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createGetUpdateTagUntagDeleteLifecycle() {
        String authorization = auth(EAST);
        String policyId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body(snapshotBody(7, "ENABLED", "test"))
                .when()
                .post("/policies")
                .then()
                .statusCode(200)
                .body("PolicyId", startsWith("policy-"))
                .extract()
                .path("PolicyId");

        String arn = given()
                .header("Authorization", authorization)
                .when()
                .get("/policies/" + policyId)
                .then()
                .statusCode(200)
                .body("Policy.PolicyId", equalTo(policyId))
                .body("Policy.State", equalTo("ENABLED"))
                .body("Policy.ExecutionRoleArn", equalTo(ROLE_ARN))
                .body("Policy.PolicyDetails.PolicyType", equalTo("EBS_SNAPSHOT_MANAGEMENT"))
                .body("Policy.PolicyDetails.ResourceTypes[0]", equalTo("VOLUME"))
                .body("Policy.PolicyDetails.TargetTags[0].Key", equalTo("AlchemyDlmTest"))
                .body("Policy.PolicyDetails.Schedules[0].RetainRule.Count", equalTo(7))
                .body("Policy.Tags.Environment", equalTo("test"))
                .body("Policy.PolicyArn", notNullValue())
                .extract()
                .path("Policy.PolicyArn");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body(snapshotBody(14, "DISABLED", "test"))
                .when()
                .patch("/policies/" + policyId)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/policies/" + policyId)
                .then()
                .statusCode(200)
                .body("Policy.State", equalTo("DISABLED"))
                .body("Policy.PolicyDetails.Schedules[0].RetainRule.Count", equalTo(14));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"Tags\":{\"Extra\":\"1\"}}")
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
                .body("Tags.Environment", equalTo("test"))
                .body("Tags.Extra", equalTo("1"));

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
                .get("/policies/" + policyId)
                .then()
                .statusCode(200)
                .body("Policy.Tags.Environment", equalTo("test"))
                .body("Policy.Tags.Extra", equalTo(null));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/policies/" + policyId)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/policies/" + policyId)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void imageManagementPolicyPreservesNoReboot() {
        String authorization = auth(EAST);
        String policyId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "ExecutionRoleArn":"%s",
                          "Description":"ami policy",
                          "State":"DISABLED",
                          "PolicyDetails":{
                            "PolicyType":"IMAGE_MANAGEMENT",
                            "ResourceTypes":["INSTANCE"],
                            "TargetTags":[{"Key":"AlchemyDlmTest","Value":"ami"}],
                            "Parameters":{"NoReboot":true},
                            "Schedules":[{
                              "Name":"Nightly",
                              "CreateRule":{"Interval":24,"IntervalUnit":"HOURS"},
                              "RetainRule":{"Count":2}
                            }]
                          }
                        }
                        """.formatted(ROLE_ARN))
                .when()
                .post("/policies")
                .then()
                .statusCode(200)
                .extract()
                .path("PolicyId");

        given()
                .header("Authorization", authorization)
                .when()
                .get("/policies/" + policyId)
                .then()
                .statusCode(200)
                .body("Policy.PolicyDetails.PolicyType", equalTo("IMAGE_MANAGEMENT"))
                .body("Policy.PolicyDetails.Parameters.NoReboot", equalTo(true));
    }

    private static String snapshotBody(int retainCount, String state, String environment) {
        return """
                {
                  "ExecutionRoleArn":"%s",
                  "Description":"alchemy dlm test",
                  "State":"%s",
                  "PolicyDetails":{
                    "PolicyType":"EBS_SNAPSHOT_MANAGEMENT",
                    "ResourceTypes":["VOLUME"],
                    "TargetTags":[{"Key":"AlchemyDlmTest","Value":"true"}],
                    "Schedules":[{
                      "Name":"Daily",
                      "CopyTags":false,
                      "CreateRule":{"Interval":24,"IntervalUnit":"HOURS","Times":["03:00"]},
                      "RetainRule":{"Count":%d}
                    }]
                  },
                  "Tags":{"Environment":"%s"}
                }
                """.formatted(ROLE_ARN, state, retainCount, environment);
    }

    private static String auth(String region) {
        return "AWS4-HMAC-SHA256 Credential=AKID/20260205/" + region + "/dlm/aws4_request";
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}

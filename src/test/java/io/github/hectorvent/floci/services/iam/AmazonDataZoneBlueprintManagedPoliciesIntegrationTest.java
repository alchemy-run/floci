package io.github.hectorvent.floci.services.iam;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.UUID;

/**
 * DataZone blueprint-configuration roles attach these AWS managed policies.
 * AttachRolePolicy fails with NoSuchEntity if they are missing from the seed catalog.
 */
@QuarkusTest
class AmazonDataZoneBlueprintManagedPoliciesIntegrationTest {

    private static final String IAM_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/iam/aws4_request";

    private static final String PROVISIONING_POLICY_ARN =
            "arn:aws:iam::aws:policy/AmazonDataZoneRedshiftGlueProvisioningPolicy";
    private static final String MANAGE_ACCESS_POLICY_ARN =
            "arn:aws:iam::aws:policy/service-role/AmazonDataZoneGlueManageAccessRolePolicy";

    private static final String TRUST_POLICY =
            "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
            + "\"Principal\":{\"Service\":\"datazone.amazonaws.com\"},\"Action\":\"sts:AssumeRole\"}]}";

    @Test
    void getAndAttachDataZoneBlueprintManagedPolicies() {
        given()
            .formParam("Action", "GetPolicy")
            .formParam("PolicyArn", PROVISIONING_POLICY_ARN)
            .header("Authorization", IAM_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body("GetPolicyResponse.GetPolicyResult.Policy.PolicyName",
                    equalTo("AmazonDataZoneRedshiftGlueProvisioningPolicy"))
            .body("GetPolicyResponse.GetPolicyResult.Policy.Arn", equalTo(PROVISIONING_POLICY_ARN));

        given()
            .formParam("Action", "GetPolicy")
            .formParam("PolicyArn", MANAGE_ACCESS_POLICY_ARN)
            .header("Authorization", IAM_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body("GetPolicyResponse.GetPolicyResult.Policy.PolicyName",
                    equalTo("AmazonDataZoneGlueManageAccessRolePolicy"))
            .body("GetPolicyResponse.GetPolicyResult.Policy.Arn", equalTo(MANAGE_ACCESS_POLICY_ARN));

        String roleName = "it-datazone-bp-role-" + UUID.randomUUID().toString().substring(0, 8);
        given()
            .formParam("Action", "CreateRole")
            .formParam("RoleName", roleName)
            .formParam("Path", "/")
            .formParam("AssumeRolePolicyDocument", TRUST_POLICY)
            .header("Authorization", IAM_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .formParam("Action", "AttachRolePolicy")
            .formParam("RoleName", roleName)
            .formParam("PolicyArn", PROVISIONING_POLICY_ARN)
            .header("Authorization", IAM_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .formParam("Action", "AttachRolePolicy")
            .formParam("RoleName", roleName)
            .formParam("PolicyArn", MANAGE_ACCESS_POLICY_ARN)
            .header("Authorization", IAM_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }
}

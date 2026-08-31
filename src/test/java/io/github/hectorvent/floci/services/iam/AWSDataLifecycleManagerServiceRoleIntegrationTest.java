package io.github.hectorvent.floci.services.iam;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * DLM service-role managed policies Alchemy LifecyclePolicy attaches. AttachRolePolicy
 * fails with NoSuchEntity if they are missing from the seed catalog.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AWSDataLifecycleManagerServiceRoleIntegrationTest {

    private static final String IAM_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/iam/aws4_request";

    private static final String SNAPSHOT_POLICY_ARN =
            "arn:aws:iam::aws:policy/service-role/AWSDataLifecycleManagerServiceRole";
    private static final String AMI_POLICY_ARN =
            "arn:aws:iam::aws:policy/service-role/AWSDataLifecycleManagerServiceRoleForAMIManagement";

    private static final String TRUST_POLICY =
            "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
            + "\"Principal\":{\"Service\":\"dlm.amazonaws.com\"},\"Action\":\"sts:AssumeRole\"}]}";

    @Test
    @Order(1)
    void getSnapshotServiceRolePolicy() {
        given()
            .formParam("Action", "GetPolicy")
            .formParam("PolicyArn", SNAPSHOT_POLICY_ARN)
            .header("Authorization", IAM_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body("GetPolicyResponse.GetPolicyResult.Policy.PolicyName",
                    equalTo("AWSDataLifecycleManagerServiceRole"))
            .body("GetPolicyResponse.GetPolicyResult.Policy.Arn", equalTo(SNAPSHOT_POLICY_ARN));
    }

    @Test
    @Order(2)
    void getAmiServiceRolePolicy() {
        given()
            .formParam("Action", "GetPolicy")
            .formParam("PolicyArn", AMI_POLICY_ARN)
            .header("Authorization", IAM_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body("GetPolicyResponse.GetPolicyResult.Policy.PolicyName",
                    equalTo("AWSDataLifecycleManagerServiceRoleForAMIManagement"))
            .body("GetPolicyResponse.GetPolicyResult.Policy.Arn", equalTo(AMI_POLICY_ARN));
    }

    @Test
    @Order(3)
    void attachDlmServiceRolePoliciesToRole() {
        given()
            .formParam("Action", "CreateRole")
            .formParam("RoleName", "DlmLifecyclePolicyTestRole")
            .formParam("Path", "/")
            .formParam("AssumeRolePolicyDocument", TRUST_POLICY)
            .header("Authorization", IAM_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .formParam("Action", "AttachRolePolicy")
            .formParam("RoleName", "DlmLifecyclePolicyTestRole")
            .formParam("PolicyArn", SNAPSHOT_POLICY_ARN)
            .header("Authorization", IAM_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .formParam("Action", "AttachRolePolicy")
            .formParam("RoleName", "DlmLifecyclePolicyTestRole")
            .formParam("PolicyArn", AMI_POLICY_ARN)
            .header("Authorization", IAM_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }
}

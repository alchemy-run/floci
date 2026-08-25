package io.github.hectorvent.floci.services.iam;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * AWS Backup service-role managed policies the Alchemy Backup Bindings
 * fixture attaches. {@code AttachRolePolicy} fails with NoSuchEntity if they
 * are missing from the seed catalog.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AWSBackupServiceRolePolicyIntegrationTest {

    private static final String IAM_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/iam/aws4_request";

    private static final String BACKUP_POLICY_ARN =
            "arn:aws:iam::aws:policy/service-role/AWSBackupServiceRolePolicyForBackup";
    private static final String RESTORE_POLICY_ARN =
            "arn:aws:iam::aws:policy/service-role/AWSBackupServiceRolePolicyForRestores";

    private static final String TRUST_POLICY =
            "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
            + "\"Principal\":{\"Service\":\"backup.amazonaws.com\"},\"Action\":\"sts:AssumeRole\"}]}";

    @Test
    @Order(1)
    void getBackupServiceRolePolicy() {
        given()
            .formParam("Action", "GetPolicy")
            .formParam("PolicyArn", BACKUP_POLICY_ARN)
            .header("Authorization", IAM_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body("GetPolicyResponse.GetPolicyResult.Policy.PolicyName",
                    equalTo("AWSBackupServiceRolePolicyForBackup"))
            .body("GetPolicyResponse.GetPolicyResult.Policy.Arn", equalTo(BACKUP_POLICY_ARN));
    }

    @Test
    @Order(2)
    void getRestoreServiceRolePolicy() {
        given()
            .formParam("Action", "GetPolicy")
            .formParam("PolicyArn", RESTORE_POLICY_ARN)
            .header("Authorization", IAM_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body("GetPolicyResponse.GetPolicyResult.Policy.PolicyName",
                    equalTo("AWSBackupServiceRolePolicyForRestores"))
            .body("GetPolicyResponse.GetPolicyResult.Policy.Arn", equalTo(RESTORE_POLICY_ARN));
    }

    @Test
    @Order(3)
    void attachBackupServiceRolePoliciesToRole() {
        given()
            .formParam("Action", "CreateRole")
            .formParam("RoleName", "BackupBindingsTestRole")
            .formParam("Path", "/")
            .formParam("AssumeRolePolicyDocument", TRUST_POLICY)
            .header("Authorization", IAM_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .formParam("Action", "AttachRolePolicy")
            .formParam("RoleName", "BackupBindingsTestRole")
            .formParam("PolicyArn", BACKUP_POLICY_ARN)
            .header("Authorization", IAM_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .formParam("Action", "AttachRolePolicy")
            .formParam("RoleName", "BackupBindingsTestRole")
            .formParam("PolicyArn", RESTORE_POLICY_ARN)
            .header("Authorization", IAM_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }
}

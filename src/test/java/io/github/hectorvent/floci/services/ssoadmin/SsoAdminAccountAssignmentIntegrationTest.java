package io.github.hectorvent.floci.services.ssoadmin;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

/**
 * JSON 1.1 SSO Admin coverage used by Alchemy {@code AccountAssignment}:
 * seed an instance, create a permission set + identity-store group, assign,
 * list the assignment, then delete.
 */
@QuarkusTest
class SsoAdminAccountAssignmentIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String SSO_AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/sso/aws4_request";
    private static final String STORE_AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/identitystore/aws4_request";
    private static final String SSO = "SWBExternalService.";
    private static final String STORE = "AWSIdentityStore.";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void createListAndDeleteAccountAssignment() {
        String instanceArn = sso("ListInstances", "{}")
                .then()
                .statusCode(200)
                .body("Instances[0].InstanceArn", startsWith("arn:aws:sso:::instance/"))
                .extract()
                .path("Instances[0].InstanceArn");
        String identityStoreId = sso("ListInstances", "{}")
                .jsonPath().getString("Instances[0].IdentityStoreId");

        Response permissionSet = sso("CreatePermissionSet", """
                {
                  "InstanceArn": "%s",
                  "Name": "alchemy-assignment-permission-set",
                  "Description": "assignment list coverage",
                  "SessionDuration": "PT1H"
                }
                """.formatted(instanceArn));
        permissionSet.then()
                .statusCode(200)
                .body("PermissionSet.PermissionSetArn", startsWith("arn:aws:sso:::permissionSet/"));
        String permissionSetArn = permissionSet.jsonPath().getString("PermissionSet.PermissionSetArn");

        Response group = store("CreateGroup", """
                {
                  "IdentityStoreId": "%s",
                  "DisplayName": "alchemy-assignment-group",
                  "Description": "assignment list coverage"
                }
                """.formatted(identityStoreId));
        group.then().statusCode(200).body("GroupId", notNullValue());
        String groupId = group.jsonPath().getString("GroupId");

        Response created = sso("CreateAccountAssignment", """
                {
                  "InstanceArn": "%s",
                  "PermissionSetArn": "%s",
                  "PrincipalType": "GROUP",
                  "PrincipalId": "%s",
                  "TargetId": "000000000000",
                  "TargetType": "AWS_ACCOUNT"
                }
                """.formatted(instanceArn, permissionSetArn, groupId));
        created.then()
                .statusCode(200)
                .body("AccountAssignmentCreationStatus.Status", equalTo("SUCCEEDED"))
                .body("AccountAssignmentCreationStatus.RequestId", notNullValue())
                .body("AccountAssignmentCreationStatus.TargetId", equalTo("000000000000"));
        String requestId = created.jsonPath().getString("AccountAssignmentCreationStatus.RequestId");

        sso("DescribeAccountAssignmentCreationStatus", """
                {"InstanceArn":"%s","AccountAssignmentCreationRequestId":"%s"}
                """.formatted(instanceArn, requestId))
                .then()
                .statusCode(200)
                .body("AccountAssignmentCreationStatus.Status", equalTo("SUCCEEDED"));

        sso("ListAccountsForProvisionedPermissionSet", """
                {"InstanceArn":"%s","PermissionSetArn":"%s"}
                """.formatted(instanceArn, permissionSetArn))
                .then()
                .statusCode(200)
                .body("AccountIds", hasItem("000000000000"));

        sso("ListAccountAssignments", """
                {
                  "InstanceArn": "%s",
                  "AccountId": "000000000000",
                  "PermissionSetArn": "%s"
                }
                """.formatted(instanceArn, permissionSetArn))
                .then()
                .statusCode(200)
                .body("AccountAssignments", hasSize(1))
                .body("AccountAssignments[0].PrincipalId", equalTo(groupId))
                .body("AccountAssignments[0].PrincipalType", equalTo("GROUP"));

        sso("DeleteAccountAssignment", """
                {
                  "InstanceArn": "%s",
                  "PermissionSetArn": "%s",
                  "PrincipalType": "GROUP",
                  "PrincipalId": "%s",
                  "TargetId": "000000000000",
                  "TargetType": "AWS_ACCOUNT"
                }
                """.formatted(instanceArn, permissionSetArn, groupId))
                .then()
                .statusCode(200)
                .body("AccountAssignmentDeletionStatus.Status", equalTo("SUCCEEDED"));
    }

    private static Response sso(String action, String body) {
        return invoke(SSO + action, SSO_AUTH, body);
    }

    private static Response store(String action, String body) {
        return invoke(STORE + action, STORE_AUTH, body);
    }

    private static Response invoke(String target, String authorization, String body) {
        return given()
                .contentType(CONTENT_TYPE)
                .accept(CONTENT_TYPE)
                .header("X-Amz-Target", target)
                .header("Authorization", authorization)
                .body(body)
                .post("/");
    }
}

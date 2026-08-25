package io.github.hectorvent.floci.services.identitycenter;

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

@QuarkusTest
class IdentityCenterIntegrationTest {

    private static final String JSON = "application/x-amz-json-1.1";
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
    void listInstances_seedsDefaultActiveInstance() {
        sso("ListInstances", "{}")
                .then()
                .statusCode(200)
                .body("Instances", hasSize(1))
                .body("Instances[0].InstanceArn", startsWith("arn:aws:sso:::instance/"))
                .body("Instances[0].IdentityStoreId", startsWith("d-"))
                .body("Instances[0].Status", equalTo("ACTIVE"));
    }

    @Test
    void createPermissionSetGroupAndAccountAssignment_roundTrip() {
        String instanceArn = sso("ListInstances", "{}")
                .jsonPath().getString("Instances[0].InstanceArn");
        String identityStoreId = sso("ListInstances", "{}")
                .jsonPath().getString("Instances[0].IdentityStoreId");

        Response permissionSet = sso("CreatePermissionSet", """
                {
                  "InstanceArn": "%s",
                  "Name": "alchemy-list-test-permission-set",
                  "Description": "Permission set used to verify list() enumeration",
                  "SessionDuration": "PT1H"
                }
                """.formatted(instanceArn));
        permissionSet.then()
                .statusCode(200)
                .body("PermissionSet.PermissionSetArn", startsWith("arn:aws:sso:::permissionSet/"))
                .body("PermissionSet.Name", equalTo("alchemy-list-test-permission-set"))
                .body("PermissionSet.SessionDuration", equalTo("PT1H"));
        String permissionSetArn = permissionSet.jsonPath().getString("PermissionSet.PermissionSetArn");

        sso("DescribePermissionSet", """
                {"InstanceArn":"%s","PermissionSetArn":"%s"}
                """.formatted(instanceArn, permissionSetArn))
                .then()
                .statusCode(200)
                .body("PermissionSet.PermissionSetArn", equalTo(permissionSetArn));

        sso("ListPermissionSets", "{\"InstanceArn\":\"" + instanceArn + "\"}")
                .then()
                .statusCode(200)
                .body("PermissionSets", hasItem(permissionSetArn));

        Response group = store("CreateGroup", """
                {
                  "IdentityStoreId": "%s",
                  "DisplayName": "alchemy-list-assignment-group",
                  "Description": "Group used to verify assignment list() enumeration"
                }
                """.formatted(identityStoreId));
        group.then()
                .statusCode(200)
                .body("GroupId", notNullValue())
                .body("IdentityStoreId", equalTo(identityStoreId));
        String groupId = group.jsonPath().getString("GroupId");

        store("DescribeGroup", """
                {"IdentityStoreId":"%s","GroupId":"%s"}
                """.formatted(identityStoreId, groupId))
                .then()
                .statusCode(200)
                .body("GroupId", equalTo(groupId))
                .body("DisplayName", equalTo("alchemy-list-assignment-group"));

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
                .body("AccountAssignmentCreationStatus.RequestId", notNullValue());
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
                .body("AccountAssignments[0].PrincipalType", equalTo("GROUP"))
                .body("AccountAssignments[0].AccountId", equalTo("000000000000"));

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

        store("DeleteGroup", """
                {"IdentityStoreId":"%s","GroupId":"%s"}
                """.formatted(identityStoreId, groupId))
                .then()
                .statusCode(200);

        sso("DeletePermissionSet", """
                {"InstanceArn":"%s","PermissionSetArn":"%s"}
                """.formatted(instanceArn, permissionSetArn))
                .then()
                .statusCode(200);
    }

    @Test
    void describePermissionSet_unknownArn_resourceNotFound() {
        String instanceArn = sso("ListInstances", "{}")
                .jsonPath().getString("Instances[0].InstanceArn");
        sso("DescribePermissionSet", """
                {
                  "InstanceArn": "%s",
                  "PermissionSetArn": "arn:aws:sso:::permissionSet/ssoins-missing/ps-missing"
                }
                """.formatted(instanceArn))
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    private static Response sso(String action, String body) {
        return invoke(SSO + action, SSO_AUTH, body);
    }

    private static Response store(String action, String body) {
        return invoke(STORE + action, STORE_AUTH, body);
    }

    private static Response invoke(String target, String authorization, String body) {
        return given()
                .contentType(JSON)
                .accept(JSON)
                .header("X-Amz-Target", target)
                .header("Authorization", authorization)
                .body(body)
                .post("/");
    }
}

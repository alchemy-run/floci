package io.github.hectorvent.floci.services.ssoadmin;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;

/**
 * JSON 1.1 SSO Admin coverage used by Alchemy {@code PermissionSet}:
 * {@code ListInstances} seeds a default instance, then create / describe / list /
 * update / delete permission set round-trip session duration.
 */
@QuarkusTest
class SsoAdminPermissionSetIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/sso/aws4_request";
    private static final String TARGET = "SWBExternalService.";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void listInstances_seedsDefaultActiveInstance() {
        invoke("ListInstances", "{}")
                .then()
                .statusCode(200)
                .body("Instances.size()", equalTo(1))
                .body("Instances[0].InstanceArn", startsWith("arn:aws:sso:::instance/"))
                .body("Instances[0].IdentityStoreId", startsWith("d-"))
                .body("Instances[0].Status", equalTo("ACTIVE"));
    }

    @Test
    void permissionSet_createDescribeListUpdateDelete() {
        String instanceArn = invoke("ListInstances", "{}")
                .then()
                .statusCode(200)
                .extract()
                .path("Instances[0].InstanceArn");

        String name = "alchemy-test-list-permission-set";
        invoke("DeletePermissionSet", """
                {"InstanceArn":"%s","PermissionSetArn":"arn:aws:sso:::permissionSet/missing/ps-missing"}
                """.formatted(instanceArn));

        Response created = invoke("CreatePermissionSet", """
                {
                  "InstanceArn":"%s",
                  "Name":"%s",
                  "Description":"list() coverage test",
                  "SessionDuration":"PT1H"
                }
                """.formatted(instanceArn, name));
        created.then()
                .statusCode(200)
                .body("PermissionSet.Name", equalTo(name))
                .body("PermissionSet.SessionDuration", equalTo("PT1H"))
                .body("PermissionSet.PermissionSetArn", startsWith("arn:aws:sso:::permissionSet/"));
        String permissionSetArn = created.jsonPath().getString("PermissionSet.PermissionSetArn");

        invoke("DescribePermissionSet", """
                {"InstanceArn":"%s","PermissionSetArn":"%s"}
                """.formatted(instanceArn, permissionSetArn))
                .then()
                .statusCode(200)
                .body("PermissionSet.SessionDuration", equalTo("PT1H"))
                .body("PermissionSet.Name", equalTo(name));

        invoke("ListPermissionSets", "{\"InstanceArn\":\"" + instanceArn + "\"}")
                .then()
                .statusCode(200)
                .body("PermissionSets", hasItem(permissionSetArn));

        invoke("UpdatePermissionSet", """
                {
                  "InstanceArn":"%s",
                  "PermissionSetArn":"%s",
                  "Description":"list() coverage test",
                  "SessionDuration":"PT1H30M"
                }
                """.formatted(instanceArn, permissionSetArn))
                .then()
                .statusCode(200);

        invoke("DescribePermissionSet", """
                {"InstanceArn":"%s","PermissionSetArn":"%s"}
                """.formatted(instanceArn, permissionSetArn))
                .then()
                .statusCode(200)
                .body("PermissionSet.SessionDuration", equalTo("PT1H30M"));

        invoke("DeletePermissionSet", """
                {"InstanceArn":"%s","PermissionSetArn":"%s"}
                """.formatted(instanceArn, permissionSetArn))
                .then()
                .statusCode(200);

        invoke("DescribePermissionSet", """
                {"InstanceArn":"%s","PermissionSetArn":"%s"}
                """.formatted(instanceArn, permissionSetArn))
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void describePermissionSet_missing_returnsResourceNotFound() {
        String instanceArn = invoke("ListInstances", "{}")
                .then()
                .statusCode(200)
                .extract()
                .path("Instances[0].InstanceArn");
        invoke("DescribePermissionSet", """
                {"InstanceArn":"%s","PermissionSetArn":"arn:aws:sso:::permissionSet/ssoins-missing/ps-missing"}
                """.formatted(instanceArn))
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createPermissionSet_missingName_returnsValidationException() {
        String instanceArn = invoke("ListInstances", "{}")
                .then()
                .statusCode(200)
                .extract()
                .path("Instances[0].InstanceArn");
        invoke("CreatePermissionSet", "{\"InstanceArn\":\"" + instanceArn + "\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));
    }

    private static Response invoke(String action, String body) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + action)
                .header("Authorization", AUTH)
                .body(body)
                .when()
                .post("/");
    }
}

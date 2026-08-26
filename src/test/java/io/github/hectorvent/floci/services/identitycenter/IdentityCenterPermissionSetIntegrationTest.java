package io.github.hectorvent.floci.services.identitycenter;

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
class IdentityCenterPermissionSetIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/sso/aws4_request";
    private static final String TARGET = "SWBExternalService.";
    private static final String NAME = "alchemy-test-list-permission-set";

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

        Response listed = invoke("ListPermissionSets", "{\"InstanceArn\":\"" + instanceArn + "\"}");
        listed.then().statusCode(200);
        java.util.List<String> existing = listed.jsonPath().getList("PermissionSets");
        if (existing != null) {
            for (String arn : existing) {
                Response described = invoke("DescribePermissionSet", """
                        {"InstanceArn":"%s","PermissionSetArn":"%s"}
                        """.formatted(instanceArn, arn));
                if (described.statusCode() == 200 && NAME.equals(described.jsonPath().getString("PermissionSet.Name"))) {
                    invoke("DeletePermissionSet", """
                            {"InstanceArn":"%s","PermissionSetArn":"%s"}
                            """.formatted(instanceArn, arn));
                }
            }
        }

        Response created = invoke("CreatePermissionSet", """
                {
                  "InstanceArn":"%s",
                  "Name":"%s",
                  "Description":"list() coverage test",
                  "SessionDuration":"PT1H"
                }
                """.formatted(instanceArn, NAME));
        created.then()
                .statusCode(200)
                .body("PermissionSet.Name", equalTo(NAME))
                .body("PermissionSet.SessionDuration", equalTo("PT1H"))
                .body("PermissionSet.PermissionSetArn", startsWith("arn:aws:sso:::permissionSet/"));
        String permissionSetArn = created.jsonPath().getString("PermissionSet.PermissionSetArn");

        invoke("DescribePermissionSet", """
                {"InstanceArn":"%s","PermissionSetArn":"%s"}
                """.formatted(instanceArn, permissionSetArn))
                .then()
                .statusCode(200)
                .body("PermissionSet.SessionDuration", equalTo("PT1H"))
                .body("PermissionSet.Name", equalTo(NAME));

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

package io.github.hectorvent.floci.services.ram;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

/**
 * The restJson1 wire shapes of the RAM operations Alchemy's ResourceShare and Permission
 * providers and its RAM runtime bindings call: tags on CreateResourceShare,
 * GetResourceShareAssociations, ListPendingInvitationResources, GetResourcePolicies, and the
 * managed permission operations. Each test uses its own account so shared Quarkus state from
 * other RAM tests cannot leak in.
 */
@QuarkusTest
class RamPermissionsIntegrationTest {

    private static RequestSpecification ram(String accountId) {
        return given()
                .contentType("application/json")
                .header("Authorization",
                        "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260101/us-east-1/ram/aws4_request");
    }

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void createResourceShareTagsAndPrincipalAssociationsRoundTrip() {
        String account = "444455556661";
        String shareArn = ram(account)
                .body("""
                    {
                        "name": "tagged-share",
                        "principals": ["123456789012"],
                        "allowExternalPrincipals": true,
                        "tags": [{"key": "team", "value": "platform"}, {"key": "alchemy::id", "value": "TestShare"}]
                    }
                    """)
            .when()
                .post("/createresourceshare")
            .then()
                .statusCode(200)
                .body("resourceShare.tags.key", hasItem("team"))
                .body("resourceShare.tags.key", hasItem("alchemy::id"))
            .extract().path("resourceShare.resourceShareArn");

        ram(account)
                .body("""
                    { "resourceOwner": "SELF", "resourceShareArns": ["%s"] }
                    """.formatted(shareArn))
            .when()
                .post("/getresourceshares")
            .then()
                .statusCode(200)
                .body("resourceShares[0].tags.find { it.key == 'team' }.value", equalTo("platform"));

        ram(account)
                .body("""
                    { "associationType": "PRINCIPAL", "resourceShareArns": ["%s"] }
                    """.formatted(shareArn))
            .when()
                .post("/getresourceshareassociations")
            .then()
                .statusCode(200)
                .body("resourceShareAssociations", hasSize(1))
                .body("resourceShareAssociations[0].associatedEntity", equalTo("123456789012"))
                .body("resourceShareAssociations[0].associationType", equalTo("PRINCIPAL"))
                .body("resourceShareAssociations[0].status", equalTo("ASSOCIATED"))
                .body("resourceShareAssociations[0].external", equalTo(true));

        ram(account)
                .body("""
                    { "resourceShareArn": "%s", "principals": ["123456789012"] }
                    """.formatted(shareArn))
            .when()
                .post("/disassociateresourceshare")
            .then()
                .statusCode(200);

        ram(account)
                .body("""
                    { "associationType": "PRINCIPAL", "resourceShareArns": ["%s"] }
                    """.formatted(shareArn))
            .when()
                .post("/getresourceshareassociations")
            .then()
                .statusCode(200)
                .body("resourceShareAssociations", hasSize(0));

        ram(account)
                .body("{}")
            .when()
                .post("/getresourceshareassociations")
            .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidParameterException"));
    }

    @Test
    void invitationAndPolicyReadsAnswerForUnknownTargets() {
        String account = "444455556662";
        ram(account)
                .body("""
                    { "resourceShareInvitationArn":
                      "arn:aws:ram:us-east-1:444455556662:resource-share-invitation/00000000-0000-4000-8000-000000000000" }
                    """)
            .when()
                .post("/listpendinginvitationresources")
            .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceShareInvitationArnNotFoundException"));

        ram(account)
                .body("""
                    { "resourceArns": ["arn:aws:ec2:us-east-1:444455556662:subnet/subnet-00000000000000000"] }
                    """)
            .when()
                .post("/getresourcepolicies")
            .then()
                .statusCode(200)
                .body("policies", hasSize(0));
    }

    @Test
    void awsManagedPermissionsAreListedWithRealArns() {
        String account = "444455556663";
        String firstArn = ram(account)
                .body("{ \"maxResults\": 5 }")
            .when()
                .post("/listpermissions")
            .then()
                .statusCode(200)
                .body("permissions.arn",
                        hasItem("arn:aws:ram::aws:permission/AWSRAMDefaultPermissionSubnet"))
                .body("permissions[0].permissionType", equalTo("AWS_MANAGED"))
                .body("permissions[0].version", equalTo("1"))
            .extract().path("permissions[0].arn");

        ram(account)
                .body("{ \"permissionArn\": \"%s\" }".formatted(firstArn))
            .when()
                .post("/getpermission")
            .then()
                .statusCode(200)
                .body("permission.name", notNullValue())
                .body("permission.status", equalTo("ATTACHABLE"))
                .body("permission.permission", containsString("\"Effect\":\"Allow\""));
    }

    @Test
    void customerManagedPermissionLifecycleOverHttp() {
        String account = "444455556664";
        String arn = ram(account)
                .body("""
                    {
                        "name": "SourceGraphQLOnly",
                        "resourceType": "appsync:Apis",
                        "policyTemplate": "{\\"Effect\\":\\"Allow\\",\\"Action\\":[\\"appsync:SourceGraphQL\\"]}",
                        "tags": [{"key": "team", "value": "platform"}]
                    }
                    """)
            .when()
                .post("/createpermission")
            .then()
                .statusCode(200)
                .body("permission.arn",
                        equalTo("arn:aws:ram:us-east-1:444455556664:permission/SourceGraphQLOnly"))
                .body("permission.version", equalTo("1"))
                .body("permission.status", equalTo("ATTACHABLE"))
                .body("permission.permissionType", equalTo("CUSTOMER_MANAGED"))
            .extract().path("permission.arn");

        ram(account)
                .body("""
                    { "permissionArn": "%s",
                      "policyTemplate": "{\\"Effect\\":\\"Allow\\",\\"Action\\":[\\"appsync:SourceGraphQL\\",\\"appsync:GraphQL\\"]}" }
                    """.formatted(arn))
            .when()
                .post("/createpermissionversion")
            .then()
                .statusCode(200)
                .body("permission.version", equalTo("2"))
                .body("permission.defaultVersion", equalTo(true))
                .body("permission.permission", containsString("appsync:GraphQL"));

        ram(account)
            .when()
                .delete("/deletepermissionversion?permissionArn=" + arn + "&permissionVersion=1")
            .then()
                .statusCode(200)
                .body("returnValue", equalTo(true))
                .body("permissionStatus", equalTo("ATTACHABLE"));

        ram(account)
                .body("{ \"permissionArn\": \"%s\" }".formatted(arn))
            .when()
                .post("/listpermissionversions")
            .then()
                .statusCode(200)
                .body("permissions", hasSize(2))
                .body("permissions.find { it.version == '1' }.status", equalTo("DELETED"))
                .body("permissions.find { it.version == '2' }.status", equalTo("ATTACHABLE"));

        ram(account)
                .body("""
                    { "resourceArn": "%s", "tags": [{"key": "env", "value": "prod"}] }
                    """.formatted(arn))
            .when()
                .post("/tagresource")
            .then()
                .statusCode(200);

        ram(account)
                .body("""
                    { "resourceArn": "%s", "tagKeys": ["team"] }
                    """.formatted(arn))
            .when()
                .post("/untagresource")
            .then()
                .statusCode(200);

        ram(account)
                .body("{ \"permissionArn\": \"%s\" }".formatted(arn))
            .when()
                .post("/getpermission")
            .then()
                .statusCode(200)
                .body("permission.version", equalTo("2"))
                .body("permission.tags.key", hasItem("env"))
                .body("permission.tags.key", not(hasItem("team")));

        ram(account)
                .body("{ \"permissionType\": \"CUSTOMER_MANAGED\" }")
            .when()
                .post("/listpermissions")
            .then()
                .statusCode(200)
                .body("permissions.arn", equalTo(java.util.List.of(arn)));

        ram(account)
            .when()
                .delete("/deletepermission?permissionArn=" + arn)
            .then()
                .statusCode(200)
                .body("returnValue", equalTo(true))
                .body("permissionStatus", equalTo("DELETED"));

        ram(account)
                .body("{ \"permissionArn\": \"%s\" }".formatted(arn))
            .when()
                .post("/getpermission")
            .then()
                .statusCode(400)
                .body("__type", equalTo("UnknownResourceException"));
    }
}

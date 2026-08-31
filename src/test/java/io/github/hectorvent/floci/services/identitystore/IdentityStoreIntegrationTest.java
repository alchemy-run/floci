package io.github.hectorvent.floci.services.identitystore;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Identity Store JSON 1.1 coverage used by Alchemy IdentityCenter bindings:
 * typed not-found on a missing store, plus user/membership round-trip.
 */
@QuarkusTest
class IdentityStoreIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String STORE_AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/identitystore/aws4_request";
    private static final String SSO_AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/sso/aws4_request";
    private static final String STORE = "AWSIdentityStore.";
    private static final String SSO = "SWBExternalService.";
    private static final String MISSING_STORE = "d-9067000000";
    private static final String MISSING_USER = "00000000-0000-0000-0000-000000000000";
    private static final String MISSING_GROUP = "00000000-0000-0000-0000-000000000000";
    private static final String MISSING_INSTANCE = "arn:aws:sso:::instance/ssoins-0000000000000000";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void describeUser_unknownStore_resourceNotFound() {
        store("DescribeUser", "{\"IdentityStoreId\":\"" + MISSING_STORE
                + "\",\"UserId\":\"" + MISSING_USER + "\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void isMemberInGroups_unknownStore_resourceNotFound() {
        store("IsMemberInGroups", "{"
                + "\"IdentityStoreId\":\"" + MISSING_STORE + "\","
                + "\"MemberId\":{\"UserId\":\"" + MISSING_USER + "\"},"
                + "\"GroupIds\":[\"" + MISSING_GROUP + "\"]"
                + "}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void listPermissionSets_unknownInstance_resourceNotFound() {
        sso("ListPermissionSets", "{\"InstanceArn\":\"" + MISSING_INSTANCE + "\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void userAndMembershipRoundTrip() {
        String storeId = sso("ListInstances", "{}")
                .then()
                .statusCode(200)
                .extract()
                .path("Instances[0].IdentityStoreId");

        String groupId = store("CreateGroup", "{"
                + "\"IdentityStoreId\":\"" + storeId + "\","
                + "\"DisplayName\":\"alchemy-idc-bindings-group\""
                + "}")
                .then()
                .statusCode(200)
                .body("GroupId", notNullValue())
                .extract()
                .path("GroupId");

        Response created = store("CreateUser", "{"
                + "\"IdentityStoreId\":\"" + storeId + "\","
                + "\"UserName\":\"alchemy-idc-bindings-user\","
                + "\"DisplayName\":\"Alchemy Bindings Test User\","
                + "\"Name\":{\"GivenName\":\"Alchemy\",\"FamilyName\":\"Test\"},"
                + "\"Emails\":[{\"Value\":\"alchemy-idc-test@example.com\",\"Primary\":true}]"
                + "}");
        created.then().statusCode(200).body("UserId", notNullValue());
        String userId = created.jsonPath().getString("UserId");

        store("GetUserId", "{"
                + "\"IdentityStoreId\":\"" + storeId + "\","
                + "\"AlternateIdentifier\":{\"UniqueAttribute\":{"
                + "\"AttributePath\":\"userName\",\"AttributeValue\":\"alchemy-idc-bindings-user\"}}"
                + "}")
                .then()
                .statusCode(200)
                .body("UserId", equalTo(userId));

        store("DescribeUser", "{\"IdentityStoreId\":\"" + storeId + "\",\"UserId\":\"" + userId + "\"}")
                .then()
                .statusCode(200)
                .body("UserName", equalTo("alchemy-idc-bindings-user"))
                .body("DisplayName", equalTo("Alchemy Bindings Test User"));

        store("UpdateUser", "{"
                + "\"IdentityStoreId\":\"" + storeId + "\","
                + "\"UserId\":\"" + userId + "\","
                + "\"Operations\":[{\"AttributePath\":\"displayName\","
                + "\"AttributeValue\":\"Alchemy Bindings Test User (updated)\"}]"
                + "}")
                .then()
                .statusCode(200);

        store("DescribeUser", "{\"IdentityStoreId\":\"" + storeId + "\",\"UserId\":\"" + userId + "\"}")
                .then()
                .statusCode(200)
                .body("DisplayName", equalTo("Alchemy Bindings Test User (updated)"));

        String membershipId = store("CreateGroupMembership", "{"
                + "\"IdentityStoreId\":\"" + storeId + "\","
                + "\"GroupId\":\"" + groupId + "\","
                + "\"MemberId\":{\"UserId\":\"" + userId + "\"}"
                + "}")
                .then()
                .statusCode(200)
                .extract()
                .path("MembershipId");

        store("IsMemberInGroups", "{"
                + "\"IdentityStoreId\":\"" + storeId + "\","
                + "\"MemberId\":{\"UserId\":\"" + userId + "\"},"
                + "\"GroupIds\":[\"" + groupId + "\"]"
                + "}")
                .then()
                .statusCode(200)
                .body("Results[0].MembershipExists", equalTo(true));

        store("ListGroupMemberships", "{"
                + "\"IdentityStoreId\":\"" + storeId + "\","
                + "\"GroupId\":\"" + groupId + "\"}")
                .then()
                .statusCode(200)
                .body("GroupMemberships.MembershipId", hasItem(membershipId));

        store("DeleteGroupMembership", "{"
                + "\"IdentityStoreId\":\"" + storeId + "\","
                + "\"MembershipId\":\"" + membershipId + "\"}")
                .then()
                .statusCode(200);

        store("IsMemberInGroups", "{"
                + "\"IdentityStoreId\":\"" + storeId + "\","
                + "\"MemberId\":{\"UserId\":\"" + userId + "\"},"
                + "\"GroupIds\":[\"" + groupId + "\"]"
                + "}")
                .then()
                .statusCode(200)
                .body("Results[0].MembershipExists", equalTo(false));

        store("DeleteUser", "{\"IdentityStoreId\":\"" + storeId + "\",\"UserId\":\"" + userId + "\"}")
                .then()
                .statusCode(200);
    }

    private static Response store(String action, String body) {
        return given()
                .contentType(CONTENT_TYPE)
                .accept(CONTENT_TYPE)
                .header("Authorization", STORE_AUTH)
                .header("X-Amz-Target", STORE + action)
                .body(body)
                .post("/");
    }

    private static Response sso(String action, String body) {
        return given()
                .contentType(CONTENT_TYPE)
                .accept(CONTENT_TYPE)
                .header("Authorization", SSO_AUTH)
                .header("X-Amz-Target", SSO + action)
                .body(body)
                .post("/");
    }
}

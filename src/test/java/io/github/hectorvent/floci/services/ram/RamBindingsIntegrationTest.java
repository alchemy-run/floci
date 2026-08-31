package io.github.hectorvent.floci.services.ram;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies RAM restJson1 operations used by Alchemy {@code Bindings.test.ts}:
 * resource-share lifecycle, list/get permissions, empty association lists, and
 * typed not-found for invitation ARNs.
 */
@QuarkusTest
class RamBindingsIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String ACCOUNT = "000000002401";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getResourceSharesWithoutOwnerFailsWithMissingRequiredParameter() {
        given()
                .contentType("application/json")
                .header("Authorization", auth(ACCOUNT, EAST))
                .body("{}")
                .when()
                .post("/getresourceshares")
                .then()
                .statusCode(400)
                .header("X-Amzn-Errortype", equalTo("MissingRequiredParameterException"))
                .body("__type", equalTo("MissingRequiredParameterException"));
    }

    @Test
    void shareLifecycleListsPermissionsAndTypedInvitationNotFound() {
        String authorization = auth(ACCOUNT, EAST);
        String name = "bindings-share-" + id();

        String arn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "name":"%s",
                          "allowExternalPrincipals":true,
                          "tags":[{"key":"alchemy::id","value":"BindingsShare"}]
                        }
                        """.formatted(name))
                .when()
                .post("/createresourceshare")
                .then()
                .statusCode(200)
                .body("resourceShare.resourceShareArn", notNullValue())
                .body("resourceShare.name", equalTo(name))
                .body("resourceShare.status", equalTo("ACTIVE"))
                .body("resourceShare.allowExternalPrincipals", equalTo(true))
                .body("resourceShare.owningAccountId", equalTo(ACCOUNT))
                .extract()
                .path("resourceShare.resourceShareArn");
        assertTrue(arn.contains(":ram:" + EAST + ":" + ACCOUNT + ":resource-share/"));

        List<Map<String, Object>> shares = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"resourceOwner\":\"SELF\"}")
                .when()
                .post("/getresourceshares")
                .then()
                .statusCode(200)
                .extract()
                .path("resourceShares");
        assertTrue(shares.stream().anyMatch(share -> arn.equals(share.get("resourceShareArn"))));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"resourceOwner\":\"SELF\",\"name\":\"" + name + "\"}")
                .when()
                .post("/getresourceshares")
                .then()
                .statusCode(200)
                .body("resourceShares.size()", equalTo(1))
                .body("resourceShares[0].resourceShareArn", equalTo(arn));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"associationType\":\"PRINCIPAL\",\"resourceShareArns\":[\"" + arn + "\"]}")
                .when()
                .post("/getresourceshareassociations")
                .then()
                .statusCode(200)
                .body("resourceShareAssociations.size()", greaterThanOrEqualTo(0));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/getresourceshareinvitations")
                .then()
                .statusCode(200)
                .body("resourceShareInvitations.size()", greaterThanOrEqualTo(0));

        String fakeInvitation = "arn:aws:ram:" + EAST + ":" + ACCOUNT
                + ":resource-share-invitation/00000000-0000-4000-8000-000000000000";
        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"resourceShareInvitationArn\":\"" + fakeInvitation + "\"}")
                .when()
                .post("/acceptresourceshareinvitation")
                .then()
                .statusCode(400)
                .header("X-Amzn-Errortype", equalTo("ResourceShareInvitationArnNotFoundException"))
                .body("__type", equalTo("ResourceShareInvitationArnNotFoundException"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"resourceShareInvitationArn\":\"" + fakeInvitation + "\"}")
                .when()
                .post("/rejectresourceshareinvitation")
                .then()
                .statusCode(400)
                .header("X-Amzn-Errortype", equalTo("ResourceShareInvitationArnNotFoundException"))
                .body("__type", equalTo("ResourceShareInvitationArnNotFoundException"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"resourceShareInvitationArn\":\"" + fakeInvitation + "\"}")
                .when()
                .post("/listpendinginvitationresources")
                .then()
                .statusCode(400)
                .header("X-Amzn-Errortype", equalTo("ResourceShareInvitationArnNotFoundException"))
                .body("__type", equalTo("ResourceShareInvitationArnNotFoundException"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"resourceOwner\":\"SELF\"}")
                .when()
                .post("/listresources")
                .then()
                .statusCode(200)
                .body("resources.size()", greaterThanOrEqualTo(0));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"resourceOwner\":\"SELF\"}")
                .when()
                .post("/listprincipals")
                .then()
                .statusCode(200)
                .body("principals.size()", greaterThanOrEqualTo(0));

        String fakeSubnet = "arn:aws:ec2:" + EAST + ":" + ACCOUNT + ":subnet/subnet-00000000000000000";
        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"resourceArns\":[\"" + fakeSubnet + "\"]}")
                .when()
                .post("/getresourcepolicies")
                .then()
                .statusCode(200)
                .body("policies.size()", equalTo(0));

        List<Map<String, Object>> permissions = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/listpermissions")
                .then()
                .statusCode(200)
                .body("permissions.size()", greaterThan(0))
                .extract()
                .path("permissions");
        String permissionArn = (String) permissions.get(0).get("arn");
        assertEquals("AWS_MANAGED", permissions.get(0).get("permissionType"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"permissionArn\":\"" + permissionArn + "\"}")
                .when()
                .post("/getpermission")
                .then()
                .statusCode(200)
                .body("permission.name", notNullValue())
                .body("permission.arn", equalTo(permissionArn));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"resourceShareArn\":\"" + arn + "\",\"tags\":[{\"key\":\"team\",\"value\":\"platform\"}]}")
                .when()
                .post("/tagresource")
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/deleteresourceshare?resourceShareArn=" + arn)
                .then()
                .statusCode(200)
                .body("returnValue", equalTo(true));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"resourceOwner\":\"SELF\",\"resourceShareArns\":[\"" + arn + "\"]}")
                .when()
                .post("/getresourceshares")
                .then()
                .statusCode(400)
                .body("__type", equalTo("UnknownResourceException"));
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region
                + "/ram/aws4_request";
    }

    private static String id() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}

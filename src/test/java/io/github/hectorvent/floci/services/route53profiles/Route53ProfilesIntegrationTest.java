package io.github.hectorvent.floci.services.route53profiles;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the Route 53 Profiles restJson1 APIs Alchemy ProfileAssociation uses. */
@QuarkusTest
class Route53ProfilesIntegrationTest {

    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "us-east-1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getProfileWhenMissingFailsWithResourceNotFoundException() {
        given()
                .header("Authorization", auth(ACCOUNT, REGION))
                .when()
                .get("/profile/rp-does-not-exist")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void profileAndVpcAssociationLifecycle() {
        String authorization = auth(ACCOUNT, REGION);
        String name = "alchemy-rp-" + UUID.randomUUID().toString().substring(0, 8);
        String vpcId = "vpc-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String clientToken = UUID.randomUUID().toString();

        String profileId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "Name":"%s",
                          "ClientToken":"%s",
                          "Tags":[{"Key":"purpose","Value":"alchemy-test"}]
                        }
                        """.formatted(name, clientToken))
                .when()
                .post("/profile")
                .then()
                .statusCode(200)
                .body("Profile.Id", startsWith("rp-"))
                .body("Profile.Name", equalTo(name))
                .body("Profile.Status", equalTo("COMPLETE"))
                .extract().path("Profile.Id");
        String profileArn = given()
                .header("Authorization", authorization)
                .when()
                .get("/profile/" + profileId)
                .then()
                .statusCode(200)
                .body("Profile.Id", equalTo(profileId))
                .body("Profile.Name", equalTo(name))
                .extract().path("Profile.Arn");
        assertTrue(profileArn.contains(":route53profiles:" + REGION + ":"));
        assertTrue(profileArn.endsWith(":profile/" + profileId));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/profiles")
                .then()
                .statusCode(200)
                .body("ProfileSummaries.find { it.Id == '" + profileId + "' }.Name", equalTo(name));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"Tags\":{\"team\":\"platform\"}}")
                .when()
                .post("/tags/" + encode(profileArn))
                .then()
                .statusCode(204);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/tags/" + encode(profileArn))
                .then()
                .statusCode(200)
                .body("Tags.purpose", equalTo("alchemy-test"))
                .body("Tags.team", equalTo("platform"));

        given()
                .header("Authorization", authorization)
                .queryParam("tagKeys", "purpose")
                .when()
                .delete("/tags/" + encode(profileArn))
                .then()
                .statusCode(204);
        given()
                .header("Authorization", authorization)
                .when()
                .get("/tags/" + encode(profileArn))
                .then()
                .statusCode(200)
                .body("Tags.purpose", equalTo(null))
                .body("Tags.team", equalTo("platform"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "Name":"%s",
                          "ClientToken":"%s"
                        }
                        """.formatted(name, clientToken))
                .when()
                .post("/profile")
                .then()
                .statusCode(200)
                .body("Profile.Id", equalTo(profileId));

        String associationId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "ProfileId":"%s",
                          "ResourceId":"%s",
                          "Name":"vpc-dns",
                          "Tags":[{"Key":"alchemy::id","Value":"VpcDns"}]
                        }
                        """.formatted(profileId, vpcId))
                .when()
                .post("/profileassociation")
                .then()
                .statusCode(200)
                .body("ProfileAssociation.Id", startsWith("rpassoc-"))
                .body("ProfileAssociation.ProfileId", equalTo(profileId))
                .body("ProfileAssociation.ResourceId", equalTo(vpcId))
                .body("ProfileAssociation.Status", equalTo("COMPLETE"))
                .extract().path("ProfileAssociation.Id");

        given()
                .header("Authorization", authorization)
                .when()
                .get("/profileassociation/" + associationId)
                .then()
                .statusCode(200)
                .body("ProfileAssociation.ProfileId", equalTo(profileId))
                .body("ProfileAssociation.ResourceId", equalTo(vpcId));

        given()
                .header("Authorization", authorization)
                .queryParam("profileId", profileId)
                .queryParam("resourceId", vpcId)
                .when()
                .get("/profileassociations")
                .then()
                .statusCode(200)
                .body("ProfileAssociations.size()", equalTo(1))
                .body("ProfileAssociations[0].Id", equalTo(associationId));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "ProfileId":"%s",
                          "ResourceId":"%s",
                          "Name":"vpc-dns-again"
                        }
                        """.formatted(profileId, vpcId))
                .when()
                .post("/profileassociation")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceExistsException"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/profile/" + profileId)
                .then()
                .statusCode(409)
                .body("__type", equalTo("ConflictException"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/profileassociation/Profileid/" + profileId + "/resourceid/" + vpcId)
                .then()
                .statusCode(200)
                .body("ProfileAssociation.Id", equalTo(associationId));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/profileassociation/" + associationId)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/profile/" + profileId)
                .then()
                .statusCode(200)
                .body("Profile.Id", equalTo(profileId));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/profile/" + profileId)
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void associateFirewallRuleGroup_updatePriorityInPlace() {
        String authorization = auth(ACCOUNT, REGION);
        String name = "firewall-profile-" + UUID.randomUUID().toString().substring(0, 8);
        String profileId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {"Name":"%s","ClientToken":"%s"}
                        """.formatted(name, UUID.randomUUID()))
                .when()
                .post("/profile")
                .then()
                .statusCode(200)
                .extract().path("Profile.Id");
        String ruleGroupArn = "arn:aws:route53resolver:" + REGION + ":" + ACCOUNT
                + ":firewall-rule-group/rslvr-frg-" + UUID.randomUUID().toString().replace("-", "").substring(0, 17);
        String associationId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "ProfileId":"%s",
                          "ResourceArn":"%s",
                          "Name":"firewall-attach",
                          "ResourceProperties":"{\\"priority\\":102}"
                        }
                        """.formatted(profileId, ruleGroupArn))
                .when()
                .post("/profileresourceassociation")
                .then()
                .statusCode(200)
                .body("ProfileResourceAssociation.Id", notNullValue())
                .body("ProfileResourceAssociation.ResourceType", equalTo("FIREWALL_RULE_GROUP"))
                .body("ProfileResourceAssociation.ResourceProperties", equalTo("{\"priority\":102}"))
                .extract().path("ProfileResourceAssociation.Id");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"ResourceProperties\":\"{\\\"priority\\\":103}\"}")
                .when()
                .patch("/profileresourceassociation/" + associationId)
                .then()
                .statusCode(200)
                .body("ProfileResourceAssociation.Id", equalTo(associationId))
                .body("ProfileResourceAssociation.ResourceProperties", equalTo("{\"priority\":103}"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/profileresourceassociation/" + associationId)
                .then()
                .statusCode(200)
                .body("ProfileResourceAssociation.ResourceProperties", equalTo("{\"priority\":103}"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/profileresourceassociation/profileid/" + profileId
                        + "/resourcearn/" + encode(ruleGroupArn))
                .then()
                .statusCode(200)
                .body("ProfileResourceAssociation.Id", equalTo(associationId));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/profileresourceassociation/" + associationId)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/profile/" + profileId)
                .then()
                .statusCode(200);
    }

    @Test
    void emptyProfileListsAreEmptyForAlchemyBindings() {
        String authorization = auth(ACCOUNT, REGION);
        String name = "binding-profile-" + UUID.randomUUID().toString().substring(0, 8);
        String profileId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {"Name":"%s","ClientToken":"%s"}
                        """.formatted(name, UUID.randomUUID()))
                .when()
                .post("/profile")
                .then()
                .statusCode(200)
                .extract().path("Profile.Id");

        given()
                .header("Authorization", authorization)
                .queryParam("profileId", profileId)
                .when()
                .get("/profileassociations")
                .then()
                .statusCode(200)
                .body("ProfileAssociations.size()", equalTo(0));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/profileresourceassociations/profileid/" + profileId)
                .then()
                .statusCode(200)
                .body("ProfileResourceAssociations.size()", equalTo(0));
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region
                + "/route53profiles/aws4_request";
    }

    private static String encode(String arn) {
        return URLEncoder.encode(arn, StandardCharsets.UTF_8);
    }
}

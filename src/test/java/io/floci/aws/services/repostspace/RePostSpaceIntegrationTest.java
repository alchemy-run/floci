package io.floci.aws.services.repostspace;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;

/**
 * re:Post Private: a space on the account's IAM Identity Center, administrators and roles that must be users or
 * groups of its identity store, channels with their roles and counts, invitations, tags and deletion.
 */
@QuarkusTest
class RePostSpaceIntegrationTest {

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    static String auth(String service) {
        return "AWS4-HMAC-SHA256 Credential=000000000000/20260101/us-east-1/" + service + "/aws4_request";
    }

    static RequestSpecification repost() {
        return given().header("Authorization", auth("repostspace")).contentType("application/json");
    }

    static RequestSpecification identity(String action, String body) {
        return given().header("Authorization", auth("identitystore"))
                .header("X-Amz-Target", "AWSIdentityStore." + action).contentType("application/x-amz-json-1.1")
                .body(body);
    }

    @Test
    void spacesCheckTheirAccessorsInIdentityCenter() {
        repost().body("{\"name\":\"bad\",\"subdomain\":\"Bad_Sub\",\"tier\":\"BASIC\"}").post("/spaces").then()
                .statusCode(400);
        String space = repost().body("{\"name\":\"support\",\"subdomain\":\"floci-support\",\"tier\":\"BASIC\","
                + "\"description\":\"help\",\"tags\":{\"team\":\"ops\"},\"supportedEmailDomains\":{\"enabled\":"
                + "\"ENABLED\",\"allowedDomains\":[\"Example.com\"]}}").post("/spaces").then().statusCode(200)
                .body("spaceId", startsWith("SP")).extract().path("spaceId");
        repost().body("{\"name\":\"dup\",\"subdomain\":\"floci-support\",\"tier\":\"BASIC\"}").post("/spaces").then()
                .statusCode(409);
        repost().get("/spaces/" + space).then().statusCode(200)
                .body("vanityDomain", equalTo("floci-support.private.repost.aws"))
                .body("supportedEmailDomains.allowedDomains", equalTo(List.of("example.com")));
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100)).until(() -> "CREATE_COMPLETED"
                .equals(repost().get("/spaces/" + space).then().extract().path("status")));
        String store = repost().get("/spaces/" + space).then().body("vanityDomainStatus", equalTo("APPROVED"))
                .body("configurationStatus", equalTo("UNCONFIGURED")).extract().path("identityStoreId");

        String ana = identity("CreateUser", "{\"IdentityStoreId\":\"" + store + "\",\"UserName\":\"ana\","
                + "\"DisplayName\":\"Ana\",\"Name\":{\"GivenName\":\"Ana\",\"FamilyName\":\"Lima\"}}").post("/")
                .then().statusCode(200).extract().path("UserId");
        String bo = identity("CreateUser", "{\"IdentityStoreId\":\"" + store + "\",\"UserName\":\"bo\","
                + "\"DisplayName\":\"Bo\",\"Name\":{\"GivenName\":\"Bo\",\"FamilyName\":\"Ng\"}}").post("/")
                .then().statusCode(200).extract().path("UserId");
        String experts = identity("CreateGroup", "{\"IdentityStoreId\":\"" + store + "\",\"DisplayName\":"
                + "\"experts\"}").post("/").then().statusCode(200).extract().path("GroupId");
        identity("CreateGroupMembership", "{\"IdentityStoreId\":\"" + store + "\",\"GroupId\":\"" + experts
                + "\",\"MemberId\":{\"UserId\":\"" + bo + "\"}}").post("/").then().statusCode(200);

        // Administrators configure the space.
        repost().post("/spaces/" + space + "/admins/not-a-user").then().statusCode(404);
        repost().post("/spaces/" + space + "/admins/" + ana).then().statusCode(200);
        repost().get("/spaces/" + space).then().body("configurationStatus", equalTo("CONFIGURED"))
                .body("userAdmins", equalTo(List.of(ana)));

        // Roles: known accessors are added, unknown ones reported.
        repost().body("{\"accessorIds\":[\"" + experts + "\",\"bogus\"],\"role\":\"EXPERT\"}").post("/spaces/"
                + space + "/roles").then().statusCode(200).body("addedAccessorIds", equalTo(List.of(experts)))
                .body("errors[0].accessorId", equalTo("bogus")).body("errors[0].error", equalTo(404));
        repost().get("/spaces/" + space).then().body("roles." + experts, equalTo(List.of("EXPERT")))
                .body("userCount", equalTo(2));
        repost().body("{\"accessorIds\":[\"" + experts + "\"],\"role\":\"EXPERT\"}").patch("/spaces/" + space
                + "/roles").then().statusCode(200).body("removedAccessorIds", equalTo(List.of(experts)));

        // Channels.
        String channel = repost().body("{\"channelName\":\"billing\",\"channelDescription\":\"money\"}")
                .post("/spaces/" + space + "/channels").then().statusCode(200).extract().path("channelId");
        repost().body("{\"channelName\":\"billing\"}").post("/spaces/" + space + "/channels").then().statusCode(409);
        repost().body("{\"accessorIds\":[\"" + bo + "\",\"" + experts + "\"],\"channelRole\":\"MODERATOR\"}")
                .post("/spaces/" + space + "/channels/" + channel + "/roles").then().statusCode(200)
                .body("addedAccessorIds", equalTo(List.of(bo, experts)));
        repost().get("/spaces/" + space + "/channels").then().statusCode(200)
                .body("channels[0].userCount", equalTo(1)).body("channels[0].groupCount", equalTo(1));
        repost().body("{\"channelName\":\"payments\"}").put("/spaces/" + space + "/channels/" + channel).then()
                .statusCode(200);
        await().atMost(Duration.ofSeconds(10)).until(() -> "CREATED".equals(repost().get("/spaces/" + space
                + "/channels/" + channel).then().extract().path("channelStatus")));
        repost().get("/spaces/" + space + "/channels/" + channel).then().body("channelName", equalTo("payments"))
                .body("channelRoles." + bo, equalTo(List.of("MODERATOR")));

        // Invitations go to users and to the members of groups.
        repost().body("{\"accessorIds\":[\"" + experts + "\"],\"title\":\"Join\",\"body\":\"Welcome\"}")
                .post("/spaces/" + space + "/invite").then().statusCode(200);
        repost().body("{\"accessorIds\":[\"nobody\"],\"title\":\"Join\",\"body\":\"Welcome\"}")
                .post("/spaces/" + space + "/invite").then().statusCode(404);

        String arn = repost().get("/spaces/" + space).then().extract().path("arn");
        repost().get("/tags/" + arn).then().statusCode(200).body("tags.team", equalTo("ops"));
        repost().get("/spaces").then().body("spaces.spaceId", hasItem(space));
        repost().delete("/spaces/" + space).then().statusCode(200);
        repost().get("/spaces/" + space).then().statusCode(200).body("status", equalTo("DELETING"));
        await().atMost(Duration.ofSeconds(10)).until(() -> repost().get("/spaces/" + space).then().extract()
                .statusCode() == 404);
        repost().get("/spaces/" + space).then().body("__type", containsString("ResourceNotFoundException"));
    }
}

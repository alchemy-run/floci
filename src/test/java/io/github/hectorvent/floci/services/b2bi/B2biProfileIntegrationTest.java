package io.github.hectorvent.floci.services.b2bi;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.github.hectorvent.floci.core.common.AwsJsonController.CONTENT_TYPE_AWS_JSON_1_0;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
class B2biProfileIntegrationTest {

    private static final String CONTENT_TYPE = CONTENT_TYPE_AWS_JSON_1_0;
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/b2bi/aws4_request";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getProfile_missing_returnsResourceNotFound() {
        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "B2BI.GetProfile")
                .header("Authorization", AUTH)
                .body("{\"profileId\":\"p-00000000000000000\"}")
        .when()
                .post("/")
        .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void profileLifecycle_createUpdateListDelete() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String name = "floci-b2bi-profile-" + suffix;

        var created = given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "B2BI.CreateProfile")
                .header("Authorization", AUTH)
                .body("""
                        {
                          "name": "%s",
                          "businessName": "Alchemy Test Corp",
                          "phone": "+15555550100",
                          "email": "edi@alchemy.example",
                          "logging": "ENABLED",
                          "tags": [{"Key":"env","Value":"test"}]
                        }
                        """.formatted(name))
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("profileId", startsWith("p-"))
                .body("name", equalTo(name))
                .body("businessName", equalTo("Alchemy Test Corp"))
                .body("phone", equalTo("+15555550100"))
                .body("email", equalTo("edi@alchemy.example"))
                .body("logging", equalTo("ENABLED"))
                .body("logGroupName", startsWith("/aws/vendedlogs/b2bi/profile/p-"))
                .body("profileArn", containsString(":b2bi:"))
                .extract();
        String profileId = created.path("profileId");
        String profileArn = created.path("profileArn");

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "B2BI.GetProfile")
                .header("Authorization", AUTH)
                .body("{\"profileId\":\"" + profileId + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("profileId", equalTo(profileId))
                .body("businessName", equalTo("Alchemy Test Corp"))
                .body("logGroupName", equalTo("/aws/vendedlogs/b2bi/profile/" + profileId));

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "B2BI.ListProfiles")
                .header("Authorization", AUTH)
                .body("{}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("profiles.profileId", hasItem(profileId))
                .body("profiles.name", hasItem(name));

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "B2BI.ListTagsForResource")
                .header("Authorization", AUTH)
                .body("{\"ResourceARN\":\"" + profileArn + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("Tags.Key", hasItem("env"));

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "B2BI.UpdateProfile")
                .header("Authorization", AUTH)
                .body("""
                        {
                          "profileId": "%s",
                          "businessName": "Alchemy Renamed Corp",
                          "phone": "+15555550199"
                        }
                        """.formatted(profileId))
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("profileId", equalTo(profileId))
                .body("businessName", equalTo("Alchemy Renamed Corp"))
                .body("phone", equalTo("+15555550199"))
                .body("logging", equalTo("ENABLED"));

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "B2BI.GetProfile")
                .header("Authorization", AUTH)
                .body("{\"profileId\":\"" + profileId + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("businessName", equalTo("Alchemy Renamed Corp"))
                .body("phone", equalTo("+15555550199"));

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "B2BI.DeleteProfile")
                .header("Authorization", AUTH)
                .body("{\"profileId\":\"" + profileId + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200);

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "B2BI.GetProfile")
                .header("Authorization", AUTH)
                .body("{\"profileId\":\"" + profileId + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createProfile_loggingDisabled_omitsLogGroupName() {
        String name = "floci-b2bi-profile-nolog-" + UUID.randomUUID().toString().substring(0, 8);

        String profileId = given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "B2BI.CreateProfile")
                .header("Authorization", AUTH)
                .body("""
                        {
                          "name": "%s",
                          "businessName": "Quiet Corp",
                          "phone": "+15555550100",
                          "logging": "DISABLED"
                        }
                        """.formatted(name))
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("logging", equalTo("DISABLED"))
                .body("logGroupName", nullValue())
                .extract().path("profileId");

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "B2BI.DeleteProfile")
                .header("Authorization", AUTH)
                .body("{\"profileId\":\"" + profileId + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200);
    }
}

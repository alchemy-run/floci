package io.github.hectorvent.floci.services.xray;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies X-Ray restJson1 group operations used by Alchemy {@code Group.test.ts}:
 * GetGroup of a missing group, create/get/tags/update insights, list, delete,
 * and replacement-by-rename (new name exists, old name gone).
 */
@QuarkusTest
class XRayGroupIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String ACCOUNT = "000000000401";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getGroupOnAMissingNameFailsWithGroupNotFound() {
        given()
                .contentType("application/json")
                .header("Authorization", auth(ACCOUNT, EAST))
                .body("{\"GroupName\":\"missing-group\"}")
                .when()
                .post("/GetGroup")
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidRequestException"))
                .body("message", equalTo("Group not found"));
    }

    @Test
    void createDuplicateGroupFailsWithAlreadyExists() {
        String authorization = auth(ACCOUNT, "us-west-2");
        String name = "dup-" + UUID.randomUUID().toString().substring(0, 8);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {"GroupName":"%s","FilterExpression":"service(\\"a\\")"}
                        """.formatted(name))
                .when()
                .post("/CreateGroup")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {"GroupName":"%s","FilterExpression":"service(\\"a\\")"}
                        """.formatted(name))
                .when()
                .post("/CreateGroup")
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidRequestException"))
                .body("message", containsString(" already exists"));
    }

    @Test
    void groupCreateUpdateInsightsTagsListAndDeleteLifecycle() {
        String authorization = auth(ACCOUNT, EAST);
        String name = "grp-" + UUID.randomUUID().toString().substring(0, 8);

        Map<String, Object> created = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "GroupName":"%s",
                          "FilterExpression":"service(\\"alchemy-xray-test\\")",
                          "InsightsConfiguration":{"InsightsEnabled":false,"NotificationsEnabled":false},
                          "Tags":[
                            {"Key":"Environment","Value":"test"},
                            {"Key":"alchemy::id","Value":"TestGroup"}
                          ]
                        }
                        """.formatted(name))
                .when()
                .post("/CreateGroup")
                .then()
                .statusCode(200)
                .body("Group.GroupName", equalTo(name))
                .body("Group.GroupARN", containsString(":group/" + name + "/"))
                .body("Group.FilterExpression", equalTo("service(\"alchemy-xray-test\")"))
                .body("Group.InsightsConfiguration.InsightsEnabled", equalTo(false))
                .extract()
                .jsonPath()
                .getMap("Group");

        String arn = (String) created.get("GroupARN");
        assertTrue(arn.contains(":xray:" + EAST + ":" + ACCOUNT + ":group/" + name + "/"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"GroupName\":\"" + name + "\"}")
                .when()
                .post("/GetGroup")
                .then()
                .statusCode(200)
                .body("Group.GroupARN", equalTo(arn))
                .body("Group.FilterExpression", equalTo("service(\"alchemy-xray-test\")"))
                .body("Group.InsightsConfiguration.InsightsEnabled", equalTo(false));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"ResourceARN\":\"" + arn + "\"}")
                .when()
                .post("/ListTagsForResource")
                .then()
                .statusCode(200)
                .body("Tags.Key", hasItem("Environment"))
                .body("Tags.Value", hasItem("test"))
                .body("Tags.Key", hasItem("alchemy::id"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "GroupName":"%s",
                          "FilterExpression":"service(\\"alchemy-xray-test\\") AND responsetime > 2",
                          "InsightsConfiguration":{"InsightsEnabled":true,"NotificationsEnabled":false}
                        }
                        """.formatted(name))
                .when()
                .post("/UpdateGroup")
                .then()
                .statusCode(200)
                .body("Group.GroupARN", equalTo(arn))
                .body("Group.FilterExpression",
                        equalTo("service(\"alchemy-xray-test\") AND responsetime > 2"))
                .body("Group.InsightsConfiguration.InsightsEnabled", equalTo(true));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "GroupName":"%s",
                          "FilterExpression":"service(\\"alchemy-xray-test\\") AND responsetime > 2",
                          "InsightsConfiguration":{"InsightsEnabled":false,"NotificationsEnabled":false}
                        }
                        """.formatted(name))
                .when()
                .post("/UpdateGroup")
                .then()
                .statusCode(200)
                .body("Group.InsightsConfiguration.InsightsEnabled", equalTo(false));

        List<Map<String, Object>> listed = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/Groups")
                .then()
                .statusCode(200)
                .extract()
                .path("Groups");
        assertTrue(listed.stream().anyMatch(item -> name.equals(item.get("GroupName"))));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "ResourceARN":"%s",
                          "Tags":[{"Key":"purpose","Value":"alchemy-test"}]
                        }
                        """.formatted(arn))
                .when()
                .post("/TagResource")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"ResourceARN\":\"" + arn + "\"}")
                .when()
                .post("/ListTagsForResource")
                .then()
                .statusCode(200)
                .body("Tags.Key", hasItem("purpose"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "ResourceARN":"%s",
                          "TagKeys":["purpose"]
                        }
                        """.formatted(arn))
                .when()
                .post("/UntagResource")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"GroupName\":\"" + name + "\"}")
                .when()
                .post("/DeleteGroup")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"GroupName\":\"" + name + "\"}")
                .when()
                .post("/GetGroup")
                .then()
                .statusCode(400)
                .body("message", equalTo("Group not found"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"GroupName\":\"" + name + "\"}")
                .when()
                .post("/DeleteGroup")
                .then()
                .statusCode(400)
                .body("message", equalTo("Group not found"));
    }

    @Test
    void replacementCreatesNewGroupAndRemovesOld() {
        String authorization = auth("000000000402", EAST);
        String first = "alchemy-test-group-a";
        String second = "alchemy-test-group-b";

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {"GroupName":"%s","FilterExpression":"service(\\"alchemy-a\\")"}
                        """.formatted(first))
                .when()
                .post("/CreateGroup")
                .then()
                .statusCode(200)
                .body("Group.GroupName", equalTo(first));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {"GroupName":"%s","FilterExpression":"service(\\"alchemy-b\\")"}
                        """.formatted(second))
                .when()
                .post("/CreateGroup")
                .then()
                .statusCode(200)
                .body("Group.GroupName", equalTo(second));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"GroupName\":\"" + first + "\"}")
                .when()
                .post("/DeleteGroup")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"GroupName\":\"" + second + "\"}")
                .when()
                .post("/GetGroup")
                .then()
                .statusCode(200)
                .body("Group.FilterExpression", equalTo("service(\"alchemy-b\")"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"GroupName\":\"" + first + "\"}")
                .when()
                .post("/GetGroup")
                .then()
                .statusCode(400)
                .body("message", equalTo("Group not found"));

        List<String> names = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/Groups")
                .then()
                .statusCode(200)
                .extract()
                .path("Groups.GroupName");
        assertEquals(true, names.contains(second));
        assertEquals(false, names.contains(first));
        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/Groups")
                .then()
                .body("Groups.GroupName", not(hasItem(first)));
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region + "/xray/aws4_request";
    }
}

package io.github.hectorvent.floci.services.aiops;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the AIOps restJson1 investigation-group lifecycle and isolation. */
@QuarkusTest
class AiOpsControllerIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String WEST = "us-west-2";
    private static final String ROLE = "arn:aws:iam::000000000201:role/AIOps";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getInvestigationGroupOnANonexistentGroupFailsWithResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000000201", EAST))
                .when()
                .get("/investigationGroups/nonexistent0000000000")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    /**
     * Alchemy {@code Bindings.test.ts} fixture: a group with no resource policy,
     * create-time tags, then Get / GetPolicy (typed not-found) / ListTags / List.
     */
    @Test
    void bindingsReadGroupPolicyTagsAndList() {
        String authorization = auth("000000000207", EAST);
        String arn = create(authorization, """
                {
                  "name":"bindings-group",
                  "roleArn":"%s",
                  "retentionInDays":7,
                  "tags":{"Purpose":"bindings-test","alchemy::id":"BindingGroup"}
                }
                """.formatted(ROLE));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/investigationGroups/" + encode(arn))
                .then()
                .statusCode(200)
                .body("name", equalTo("bindings-group"))
                .body("arn", equalTo(arn))
                .body("retentionInDays", equalTo(7));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/investigationGroups/" + encode(arn) + "/policy")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));

        Map<String, String> tags = given()
                .header("Authorization", authorization)
                .when()
                .get("/tags/" + encode(arn))
                .then()
                .statusCode(200)
                .extract()
                .path("tags");
        assertEquals("bindings-test", tags.get("Purpose"));
        assertEquals("BindingGroup", tags.get("alchemy::id"));

        List<Map<String, Object>> listed = list(authorization).path("investigationGroups");
        assertEquals(1, listed.size());
        assertEquals(arn, listed.getFirst().get("arn"));
    }

    @Test
    void investigationGroupCreateUpdatePolicyTagsDeleteLifecycle() {
        String authorization = auth("000000000202", EAST);
        String arn = create(authorization, """
                {
                  "name":"lifecycle-group",
                  "roleArn":"%s",
                  "retentionInDays":7,
                  "tagKeyBoundaries":["Application"],
                  "tags":{"Environment":"test"}
                }
                """.formatted(ROLE));

        assertTrue(arn.contains(":investigation-group/"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/investigationGroups/lifecycle-group")
                .then()
                .statusCode(200)
                .body("name", equalTo("lifecycle-group"))
                .body("arn", equalTo(arn))
                .body("retentionInDays", equalTo(7))
                .body("tagKeyBoundaries[0]", equalTo("Application"))
                .body("isCloudTrailEventHistoryEnabled", equalTo(true))
                .body("encryptionConfiguration.type", equalTo("AWS_OWNED_KEY"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/investigationGroups/" + encode(arn))
                .then()
                .statusCode(200)
                .body("arn", equalTo(arn));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/tags/" + encode(arn))
                .then()
                .statusCode(200)
                .body("tags.Environment", equalTo("test"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "tagKeyBoundaries":["Application","Service"],
                          "isCloudTrailEventHistoryEnabled":false
                        }
                        """)
                .when()
                .patch("/investigationGroups/" + encode(arn))
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/investigationGroups/" + encode(arn))
                .then()
                .statusCode(200)
                .body("tagKeyBoundaries[0]", equalTo("Application"))
                .body("tagKeyBoundaries[1]", equalTo("Service"))
                .body("isCloudTrailEventHistoryEnabled", equalTo(false))
                .body("retentionInDays", equalTo(7));

        String policy = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"Service\":\"aiops.alarms.cloudwatch.amazonaws.com\"},\"Action\":[\"aiops:CreateInvestigation\"],\"Resource\":\"*\"}]}";
        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"policy\":" + quote(policy) + "}")
                .when()
                .post("/investigationGroups/" + encode(arn) + "/policy")
                .then()
                .statusCode(200)
                .body("investigationGroupArn", equalTo(arn));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/investigationGroups/" + encode(arn) + "/policy")
                .then()
                .statusCode(200)
                .body("policy", equalTo(policy));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .delete("/investigationGroups/" + encode(arn) + "/policy")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/investigationGroups/" + encode(arn) + "/policy")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"tags\":{\"Team\":\"obs\"}}")
                .when()
                .post("/tags/" + encode(arn))
                .then()
                .statusCode(204);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/tags/" + encode(arn))
                .then()
                .statusCode(200)
                .body("tags.Environment", equalTo("test"))
                .body("tags.Team", equalTo("obs"));

        List<Map<String, Object>> listed = list(authorization).path("investigationGroups");
        assertEquals(1, listed.size());
        assertEquals("lifecycle-group", listed.getFirst().get("name"));
        assertEquals(arn, listed.getFirst().get("arn"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .delete("/investigationGroups/" + encode(arn))
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/investigationGroups/lifecycle-group")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createSecondGroupInTheSameRegionConflicts() {
        String authorization = auth("000000000203", EAST);
        String first = create(authorization, """
                {"name":"first-group","roleArn":"%s","retentionInDays":7}
                """.formatted(ROLE));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {"name":"second-group","roleArn":"%s","retentionInDays":14}
                        """.formatted(ROLE))
                .when()
                .post("/investigationGroups")
                .then()
                .statusCode(409)
                .body("__type", equalTo("ConflictException"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/investigationGroups/first-group")
                .then()
                .statusCode(200)
                .body("arn", equalTo(first));
    }

    @Test
    void investigationGroupsAreIsolatedByAccount() {
        String firstAuth = auth("000000000204", EAST);
        String secondAuth = auth("000000000205", EAST);

        String firstArn = create(firstAuth, """
                {"name":"shared-group","roleArn":"%s","retentionInDays":7}
                """.formatted(ROLE));
        String secondArn = create(secondAuth, """
                {"name":"shared-group","roleArn":"%s","retentionInDays":14}
                """.formatted(ROLE));

        assertNotEquals(firstArn, secondArn);
        get(firstAuth, "shared-group").then().body("retentionInDays", equalTo(7));
        get(secondAuth, "shared-group").then().body("retentionInDays", equalTo(14));
    }

    @Test
    void investigationGroupsAreIsolatedByRegion() {
        String eastAuth = auth("000000000206", EAST);
        String westAuth = auth("000000000206", WEST);

        create(eastAuth, """
                {"name":"regional-group","roleArn":"%s","retentionInDays":7}
                """.formatted(ROLE));
        create(westAuth, """
                {"name":"regional-group","roleArn":"%s","retentionInDays":14}
                """.formatted(ROLE));

        get(eastAuth, "regional-group").then().body("retentionInDays", equalTo(7));
        get(westAuth, "regional-group").then().body("retentionInDays", equalTo(14));
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region + "/aiops/aws4_request";
    }

    private static String create(String authorization, String body) {
        return given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body(body)
                .when()
                .post("/investigationGroups")
                .then()
                .statusCode(200)
                .body("arn", notNullValue())
                .extract().path("arn");
    }

    private static Response get(String authorization, String identifier) {
        return given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/investigationGroups/" + identifier);
    }

    private static Response list(String authorization) {
        return given()
                .header("Authorization", authorization)
                .when()
                .get("/investigationGroups")
                .then()
                .statusCode(200)
                .extract().response();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }
}

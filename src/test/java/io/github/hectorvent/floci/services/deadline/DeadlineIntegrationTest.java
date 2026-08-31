package io.github.hectorvent.floci.services.deadline;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies Deadline Cloud restJson1 farm/queue/storage-profile/budget lifecycle. */
@QuarkusTest
class DeadlineIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String PREFIX = "/2023-10-12";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getFarmOnANonexistentFarmFailsWithResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000000301", EAST))
                .when()
                .get(PREFIX + "/farms/farm-00000000000000000000000000000000")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("resourceType", equalTo("farm"));
    }

    @Test
    void getMonitorOnANonexistentMonitorFailsWithResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000000301", EAST))
                .when()
                .get(PREFIX + "/monitors/monitor-00000000000000000000000000000000")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("resourceType", equalTo("monitor"));
    }

    @Test
    void createUpdateDeleteFarmQueueStorageProfileAndBudget() {
        String authorization = auth("000000000302", EAST);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        String start = now.minus(1, ChronoUnit.DAYS).toString();
        String end = now.plus(90, ChronoUnit.DAYS).toString();

        String farmId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "displayName":"TestFarm",
                          "description":"alchemy deadline test farm",
                          "tags":{"fixture":"deadline","alchemy::id":"TestFarm"}
                        }
                        """)
                .when()
                .post(PREFIX + "/farms")
                .then()
                .statusCode(200)
                .body("farmId", startsWith("farm-"))
                .extract()
                .path("farmId");

        given()
                .header("Authorization", authorization)
                .when()
                .get(PREFIX + "/farms/" + farmId)
                .then()
                .statusCode(200)
                .body("displayName", equalTo("TestFarm"))
                .body("costScaleFactor", equalTo(1.0f))
                .body("description", equalTo("alchemy deadline test farm"));

        String farmArn = "arn:aws:deadline:" + EAST + ":000000000302:farm/" + farmId;
        Map<String, String> farmTags = listTags(authorization, farmArn);
        assertEquals("deadline", farmTags.get("fixture"));
        assertEquals("TestFarm", farmTags.get("alchemy::id"));

        String storageProfileId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "displayName":"LinuxHosts",
                          "osFamily":"LINUX",
                          "fileSystemLocations":[
                            {"name":"Assets","path":"/mnt/assets","type":"SHARED"}
                          ]
                        }
                        """)
                .when()
                .post(PREFIX + "/farms/" + farmId + "/storage-profiles")
                .then()
                .statusCode(200)
                .body("storageProfileId", startsWith("sp-"))
                .extract()
                .path("storageProfileId");

        String queueId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "displayName":"RenderQueue",
                          "description":"alchemy deadline test queue",
                          "tags":{"fixture":"deadline"}
                        }
                        """)
                .when()
                .post(PREFIX + "/farms/" + farmId + "/queues")
                .then()
                .statusCode(200)
                .body("queueId", startsWith("queue-"))
                .extract()
                .path("queueId");

        String budgetId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "displayName":"QueueBudget",
                          "usageTrackingResource":{"queueId":"%s"},
                          "approximateDollarLimit":1,
                          "actions":[
                            {"type":"STOP_SCHEDULING_AND_COMPLETE_TASKS","thresholdPercentage":100}
                          ],
                          "schedule":{"fixed":{"startTime":"%s","endTime":"%s"}}
                        }
                        """.formatted(queueId, start, end))
                .when()
                .post(PREFIX + "/farms/" + farmId + "/budgets")
                .then()
                .statusCode(200)
                .body("budgetId", startsWith("budget-"))
                .extract()
                .path("budgetId");

        given()
                .header("Authorization", authorization)
                .when()
                .get(PREFIX + "/farms/" + farmId + "/queues/" + queueId)
                .then()
                .statusCode(200)
                .body("displayName", equalTo("RenderQueue"))
                .body("farmId", equalTo(farmId));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "description":"alchemy deadline test farm (updated)",
                          "costScaleFactor":2
                        }
                        """)
                .when()
                .patch(PREFIX + "/farms/" + farmId)
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "tags":{"phase":"two"}
                        }
                        """)
                .when()
                .post(PREFIX + "/tags/" + encode(farmArn))
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "fileSystemLocationsToAdd":[
                            {"name":"Cache","path":"/mnt/cache","type":"LOCAL"}
                          ]
                        }
                        """)
                .when()
                .patch(PREFIX + "/farms/" + farmId + "/storage-profiles/" + storageProfileId)
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "allowedStorageProfileIdsToAdd":["%s"]
                        }
                        """.formatted(storageProfileId))
                .when()
                .patch(PREFIX + "/farms/" + farmId + "/queues/" + queueId)
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "approximateDollarLimit":2,
                          "actionsToAdd":[
                            {"type":"STOP_SCHEDULING_AND_CANCEL_TASKS","thresholdPercentage":90}
                          ],
                          "actionsToRemove":[
                            {"type":"STOP_SCHEDULING_AND_COMPLETE_TASKS","thresholdPercentage":100}
                          ]
                        }
                        """)
                .when()
                .patch(PREFIX + "/farms/" + farmId + "/budgets/" + budgetId)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get(PREFIX + "/farms/" + farmId)
                .then()
                .statusCode(200)
                .body("costScaleFactor", equalTo(2.0f));

        given()
                .header("Authorization", authorization)
                .when()
                .get(PREFIX + "/farms/" + farmId + "/queues/" + queueId)
                .then()
                .statusCode(200)
                .body("allowedStorageProfileIds", equalTo(List.of(storageProfileId)));

        given()
                .header("Authorization", authorization)
                .when()
                .get(PREFIX + "/farms/" + farmId + "/storage-profiles/" + storageProfileId)
                .then()
                .statusCode(200)
                .body("osFamily", equalTo("LINUX"))
                .body("fileSystemLocations", hasSize(2));

        given()
                .header("Authorization", authorization)
                .when()
                .get(PREFIX + "/farms/" + farmId + "/budgets/" + budgetId)
                .then()
                .statusCode(200)
                .body("approximateDollarLimit", equalTo(2.0f))
                .body("actions", hasSize(1))
                .body("actions[0].type", equalTo("STOP_SCHEDULING_AND_CANCEL_TASKS"));

        Map<String, String> updatedTags = listTags(authorization, farmArn);
        assertEquals("two", updatedTags.get("phase"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete(PREFIX + "/farms/" + farmId)
                .then()
                .statusCode(409)
                .body("__type", equalTo("ConflictException"));

        given().header("Authorization", authorization).when()
                .delete(PREFIX + "/farms/" + farmId + "/budgets/" + budgetId)
                .then().statusCode(200);
        given().header("Authorization", authorization).when()
                .delete(PREFIX + "/farms/" + farmId + "/queues/" + queueId)
                .then().statusCode(200);
        given().header("Authorization", authorization).when()
                .delete(PREFIX + "/farms/" + farmId + "/storage-profiles/" + storageProfileId)
                .then().statusCode(200);
        given().header("Authorization", authorization).when()
                .delete(PREFIX + "/farms/" + farmId)
                .then().statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get(PREFIX + "/farms/" + farmId)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));

        Response listed = given()
                .header("Authorization", authorization)
                .when()
                .get(PREFIX + "/farms");
        listed.then().statusCode(200);
        List<Map<String, Object>> farms = listed.path("farms");
        assertTrue(farms.stream().noneMatch(farm -> farmId.equals(farm.get("farmId"))));
    }

    private static Map<String, String> listTags(String authorization, String arn) {
        return given()
                .header("Authorization", authorization)
                .when()
                .get(PREFIX + "/tags/" + encode(arn))
                .then()
                .statusCode(200)
                .extract()
                .path("tags");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region + "/deadline/aws4_request";
    }
}

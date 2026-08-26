package io.github.hectorvent.floci.services.synthetics;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;

/** Verifies the Synthetics restJson1 canary + group lifecycle used by Alchemy. */
@QuarkusTest
class SyntheticsIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String ACCOUNT = "000000002501";
    private static final String ZIP =
            "UEsDBAoAAAAAAIdO4kgAAAAAAAAAAAAAAAAWAAAAbm9kZWpzL25vZGVfbW9kdWxlcy9pbmRleC5qc1BLBQYAAAAAAQABAEAAAAAAAAAA";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getCanaryOnANonexistentNameFailsWithResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth(ACCOUNT, EAST))
                .when()
                .get("/canary/alchemy-nonexistent-synthetics-canary")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void getGroupOnANonexistentNameFailsWithResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth(ACCOUNT, EAST))
                .when()
                .get("/group/alchemy-nonexistent-synthetics-group")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void canaryAndGroupCreateUpdateTagDeleteLifecycle() {
        String authorization = auth(ACCOUNT, EAST);
        String canaryName = "heartbeat";
        String groupName = "heartbeat-group";
        String canaryArn = "arn:aws:synthetics:" + EAST + ":" + ACCOUNT + ":canary:" + canaryName;

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "Name":"heartbeat",
                          "Code":{"Handler":"index.handler","ZipFile":"%s"},
                          "ArtifactS3Location":"s3://artifacts/heartbeat",
                          "ExecutionRoleArn":"arn:aws:iam::%s:role/canary-role",
                          "Schedule":{"Expression":"rate(5 minutes)"},
                          "RuntimeVersion":"syn-nodejs-puppeteer-16.1",
                          "Tags":{"alchemy::id":"Heartbeat","fixture":"one"}
                        }
                        """.formatted(ZIP, ACCOUNT))
                .when()
                .post("/canary")
                .then()
                .statusCode(200)
                .body("Canary.Name", equalTo(canaryName))
                .body("Canary.Status.State", equalTo("READY"))
                .body("Canary.RuntimeVersion", equalTo("syn-nodejs-puppeteer-16.1"))
                .body("Canary.Schedule.Expression", equalTo("rate(5 minutes)"))
                .body("Canary.EngineArn", notNullValue())
                .body("Canary.Tags['alchemy::id']", equalTo("Heartbeat"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/canary/" + canaryName)
                .then()
                .statusCode(200)
                .body("Canary.Status.State", equalTo("READY"))
                .body("Canary.ExecutionRoleArn", equalTo("arn:aws:iam::" + ACCOUNT + ":role/canary-role"))
                .body("Canary.Tags.fixture", equalTo("one"));

        String groupArn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "Name":"heartbeat-group",
                          "Tags":{"alchemy::id":"HeartbeatGroup","alchemyTest":"one"}
                        }
                        """)
                .when()
                .post("/group")
                .then()
                .statusCode(200)
                .body("Group.Name", equalTo(groupName))
                .body("Group.Tags.alchemyTest", equalTo("one"))
                .extract().path("Group.Arn");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"ResourceArn\":\"" + canaryArn + "\"}")
                .when()
                .patch("/group/" + groupName + "/associate")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .post("/group/" + groupName + "/resources")
                .then()
                .statusCode(200)
                .body("Resources", hasItem(canaryArn));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"Schedule\":{\"Expression\":\"rate(10 minutes)\"}}")
                .when()
                .patch("/canary/" + canaryName)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/canary/" + canaryName)
                .then()
                .statusCode(200)
                .body("Canary.Schedule.Expression", equalTo("rate(10 minutes)"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"Tags\":{\"alchemyTest\":\"two\"}}")
                .when()
                .post("/tags/" + encode(groupArn))
                .then()
                .statusCode(204);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/group/" + groupName)
                .then()
                .statusCode(200)
                .body("Group.Tags.alchemyTest", equalTo("two"))
                .body("Group.Tags['alchemy::id']", equalTo("HeartbeatGroup"));

        given()
                .header("Authorization", authorization)
                .when()
                .post("/canary/" + canaryName + "/start")
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/canary/" + canaryName)
                .then()
                .statusCode(200)
                .body("Canary.Status.State", equalTo("RUNNING"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .post("/canary/" + canaryName + "/runs")
                .then()
                .statusCode(200)
                .body("CanaryRuns[0].Status.State", equalTo("PASSED"));

        given()
                .header("Authorization", authorization)
                .queryParam("deleteLambda", true)
                .when()
                .delete("/canary/" + canaryName)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/canary/" + canaryName)
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/group/" + groupName)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/group/" + groupName)
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region
                + "/synthetics/aws4_request";
    }

    private static String encode(String arn) {
        return URLEncoder.encode(arn, StandardCharsets.UTF_8);
    }
}

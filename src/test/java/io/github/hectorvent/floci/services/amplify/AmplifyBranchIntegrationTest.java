package io.github.hectorvent.floci.services.amplify;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

/** Verifies the Amplify restJson1 branch lifecycle used by Alchemy Branch tests. */
@QuarkusTest
class AmplifyBranchIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String BASIC_AUTH_CREDENTIALS = "dXNlcjpwYXNzdzByZA==";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getBranchOnANonexistentBranchFailsWithNotFoundException() {
        String authorization = auth("000000000501", EAST);
        String appId = createApp(authorization, """
                {"name":"branch-missing-app","platform":"WEB"}
                """);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/apps/" + appId + "/branches/missing")
                .then()
                .statusCode(404)
                .body("__type", equalTo("NotFoundException"));
    }

    @Test
    void branchCreateUpdateTagsReplaceDeleteLifecycle() {
        String authorization = auth("000000000502", EAST);
        String appId = createApp(authorization, """
                {"name":"branch-lifecycle-app","platform":"WEB"}
                """);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "branchName":"main",
                          "description":"initial",
                          "stage":"DEVELOPMENT",
                          "enableAutoBuild":false,
                          "ttl":"300",
                          "enableBasicAuth":true,
                          "basicAuthCredentials":"%s",
                          "environmentVariables":{"STAGE":"test"},
                          "tags":{"Environment":"test","alchemy::id":"TestBranch"}
                        }
                        """.formatted(BASIC_AUTH_CREDENTIALS))
                .when()
                .post("/apps/" + appId + "/branches")
                .then()
                .statusCode(200)
                .body("branch.branchName", equalTo("main"))
                .body("branch.branchArn", equalTo(branchArn("000000000502", EAST, appId, "main")))
                .body("branch.description", equalTo("initial"))
                .body("branch.stage", equalTo("DEVELOPMENT"))
                .body("branch.enableAutoBuild", equalTo(false))
                .body("branch.enableBasicAuth", equalTo(true))
                .body("branch.ttl", equalTo("300"))
                .body("branch.environmentVariables.STAGE", equalTo("test"))
                .body("branch.tags.Environment", equalTo("test"))
                .body("branch.tags.'alchemy::id'", equalTo("TestBranch"))
                .body("branch.createTime", notNullValue())
                .body("branch.updateTime", notNullValue());

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/apps/" + appId + "/branches/main")
                .then()
                .statusCode(200)
                .body("branch.branchName", equalTo("main"))
                .body("branch.description", equalTo("initial"))
                .body("branch.ttl", equalTo("300"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/apps/" + appId + "/branches")
                .then()
                .statusCode(200)
                .body("branches.size()", equalTo(1))
                .body("branches[0].branchName", equalTo("main"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "description":"updated",
                          "stage":"DEVELOPMENT",
                          "enableAutoBuild":false,
                          "ttl":"300",
                          "enableBasicAuth":true,
                          "environmentVariables":{"STAGE":"test"}
                        }
                        """)
                .when()
                .post("/apps/" + appId + "/branches/main")
                .then()
                .statusCode(200)
                .body("branch.description", equalTo("updated"))
                .body("branch.branchArn", equalTo(branchArn("000000000502", EAST, appId, "main")));

        String resourceArn = branchArn("000000000502", EAST, appId, "main");
        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"tags\":{\"Extra\":\"yes\"}}")
                .when()
                .post("/tags/" + encode(resourceArn))
                .then()
                .statusCode(204);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/tags/" + encode(resourceArn))
                .then()
                .statusCode(200)
                .body("tags.Environment", equalTo("test"))
                .body("tags.Extra", equalTo("yes"))
                .body("tags.'alchemy::id'", equalTo("TestBranch"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "branchName":"release",
                          "description":"initial",
                          "stage":"DEVELOPMENT",
                          "enableAutoBuild":false,
                          "ttl":"300"
                        }
                        """)
                .when()
                .post("/apps/" + appId + "/branches")
                .then()
                .statusCode(200)
                .body("branch.branchName", equalTo("release"))
                .body("branch.branchArn", not(equalTo(resourceArn)));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .delete("/apps/" + appId + "/branches/main")
                .then()
                .statusCode(200)
                .body("branch.branchName", equalTo("main"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/apps/" + appId + "/branches/main")
                .then()
                .statusCode(404)
                .body("__type", equalTo("NotFoundException"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .delete("/apps/" + appId + "/branches/release")
                .then()
                .statusCode(200)
                .body("branch.branchName", equalTo("release"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/apps/" + appId + "/branches/release")
                .then()
                .statusCode(404)
                .body("__type", equalTo("NotFoundException"));
    }

    @Test
    void createBranchOnMissingAppFailsWithNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000000503", EAST))
                .body("{\"branchName\":\"main\"}")
                .when()
                .post("/apps/dmissing0000000/branches")
                .then()
                .statusCode(404)
                .body("__type", equalTo("NotFoundException"));
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region
                + "/amplify/aws4_request";
    }

    private static String branchArn(String accountId, String region, String appId, String branchName) {
        return "arn:aws:amplify:" + region + ":" + accountId + ":apps/" + appId + "/branches/" + branchName;
    }

    private static String encode(String arn) {
        return URLEncoder.encode(arn, StandardCharsets.UTF_8);
    }

    private static String createApp(String authorization, String body) {
        return given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body(body)
                .when()
                .post("/apps")
                .then()
                .statusCode(200)
                .body("app.appId", notNullValue())
                .extract()
                .path("app.appId");
    }
}

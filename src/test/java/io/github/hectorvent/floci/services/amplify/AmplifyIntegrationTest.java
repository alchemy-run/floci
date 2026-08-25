package io.github.hectorvent.floci.services.amplify;

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

/** Verifies the Amplify restJson1 app lifecycle used by Alchemy App tests. */
@QuarkusTest
class AmplifyIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String WEST = "us-west-2";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void listAppsSignedAsAmplifyIsNotUnknownOperation() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000000400", EAST))
                .when()
                .get("/apps")
                .then()
                .statusCode(200)
                .body("apps", notNullValue());
    }

    @Test
    void getAppOnANonexistentAppFailsWithNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000000401", EAST))
                .when()
                .get("/apps/dmissing0000000")
                .then()
                .statusCode(404)
                .body("__type", equalTo("NotFoundException"));
    }

    @Test
    void appCreateUpdateTagsDeleteLifecycle() {
        String authorization = auth("000000000402", EAST);
        String appId = create(authorization, """
                {
                  "name":"lifecycle-app",
                  "description":"initial description",
                  "platform":"WEB",
                  "environmentVariables":{"STAGE":"test"},
                  "tags":{"Environment":"test"}
                }
                """);

        assertTrue(appId.startsWith("d"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/apps/" + appId)
                .then()
                .statusCode(200)
                .body("app.appId", equalTo(appId))
                .body("app.name", equalTo("lifecycle-app"))
                .body("app.description", equalTo("initial description"))
                .body("app.repository", equalTo(""))
                .body("app.platform", equalTo("WEB"))
                .body("app.defaultDomain", equalTo(appId + ".amplifyapp.com"))
                .body("app.environmentVariables.STAGE", equalTo("test"))
                .body("app.tags.Environment", equalTo("test"))
                .body("app.appArn", equalTo(arn("000000000402", EAST, appId)))
                .body("app.createTime", notNullValue());

        List<Map<String, Object>> listed = list(authorization).path("apps");
        assertEquals(1, listed.size());
        assertEquals(appId, listed.getFirst().get("appId"));
        assertEquals("lifecycle-app", listed.getFirst().get("name"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "description":"updated description",
                          "platform":"WEB",
                          "environmentVariables":{"STAGE":"test","REGION":"us-west-2"}
                        }
                        """)
                .when()
                .post("/apps/" + appId)
                .then()
                .statusCode(200)
                .body("app.description", equalTo("updated description"))
                .body("app.environmentVariables.REGION", equalTo("us-west-2"))
                .body("app.appId", equalTo(appId));

        String resourceArn = arn("000000000402", EAST, appId);
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
                .body("tags.Extra", equalTo("yes"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/apps/" + appId)
                .then()
                .statusCode(200)
                .body("app.tags.Extra", equalTo("yes"))
                .body("app.description", equalTo("updated description"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .delete("/apps/" + appId)
                .then()
                .statusCode(200)
                .body("app.appId", equalTo(appId));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/apps/" + appId)
                .then()
                .statusCode(404)
                .body("__type", equalTo("NotFoundException"));

        listed = list(authorization).path("apps");
        assertEquals(0, listed.size());
    }

    @Test
    void appsAreIsolatedByAccount() {
        String firstAuth = auth("000000000403", EAST);
        String secondAuth = auth("000000000404", EAST);

        String firstId = create(firstAuth, """
                {"name":"shared-name","platform":"WEB"}
                """);
        String secondId = create(secondAuth, """
                {"name":"shared-name","platform":"WEB"}
                """);

        assertNotEquals(firstId, secondId);
        get(firstAuth, firstId).then().body("app.appId", equalTo(firstId));
        get(secondAuth, secondId).then().body("app.appId", equalTo(secondId));
        get(firstAuth, secondId).then().statusCode(404);
    }

    @Test
    void appsAreIsolatedByRegion() {
        String eastAuth = auth("000000000405", EAST);
        String westAuth = auth("000000000405", WEST);

        String eastId = create(eastAuth, """
                {"name":"regional-app","platform":"WEB"}
                """);
        String westId = create(westAuth, """
                {"name":"regional-app","platform":"WEB"}
                """);

        get(eastAuth, eastId).then()
                .body("app.appArn", equalTo(arn("000000000405", EAST, eastId)));
        get(westAuth, westId).then()
                .body("app.appArn", equalTo(arn("000000000405", WEST, westId)));
        get(eastAuth, westId).then().statusCode(404);
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region
                + "/amplify/aws4_request";
    }

    private static String arn(String accountId, String region, String appId) {
        return "arn:aws:amplify:" + region + ":" + accountId + ":apps/" + appId;
    }

    private static String encode(String arn) {
        return URLEncoder.encode(arn, StandardCharsets.UTF_8);
    }

    private static String create(String authorization, String body) {
        return given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body(body)
                .when()
                .post("/apps")
                .then()
                .statusCode(200)
                .body("app.appId", notNullValue())
                .body("app.defaultDomain", notNullValue())
                .extract()
                .path("app.appId");
    }

    private static Response get(String authorization, String appId) {
        return given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/apps/" + appId);
    }

    private static Response list(String authorization) {
        return given()
                .header("Authorization", authorization)
                .when()
                .get("/apps")
                .then()
                .statusCode(200)
                .extract()
                .response();
    }
}

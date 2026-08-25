package io.github.hectorvent.floci.services.appintegrations;

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

/** Verifies the AppIntegrations restJson1 application lifecycle used by Alchemy Application tests. */
@QuarkusTest
class AppIntegrationsApplicationIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String WEST = "us-west-2";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getApplicationOnANonexistentIdFailsWithResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000000501", EAST))
                .when()
                .get("/applications/00000000-0000-0000-0000-000000000000")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void applicationCreateUpdateTagsDeleteLifecycle() {
        String authorization = auth("000000000502", EAST);
        String arn = create(authorization, """
                {
                  "Name":"lifecycle-app",
                  "Namespace":"com.floci.lifecycle",
                  "Description":"alchemy application",
                  "ApplicationSourceConfig":{
                    "ExternalUrlConfig":{"AccessUrl":"https://example.com"}
                  },
                  "Tags":{"purpose":"alchemy-test"}
                }
                """);

        assertTrue(arn.contains(":application/"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/applications/" + encode(arn))
                .then()
                .statusCode(200)
                .body("Arn", equalTo(arn))
                .body("Name", equalTo("lifecycle-app"))
                .body("Namespace", equalTo("com.floci.lifecycle"))
                .body("Description", equalTo("alchemy application"))
                .body("ApplicationSourceConfig.ExternalUrlConfig.AccessUrl", equalTo("https://example.com"))
                .body("Tags.purpose", equalTo("alchemy-test"));

        List<Map<String, Object>> listed = list(authorization).path("Applications");
        assertEquals(1, listed.size());
        assertEquals(arn, listed.getFirst().get("Arn"));
        assertEquals("com.floci.lifecycle", listed.getFirst().get("Namespace"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "Description":"alchemy application v2",
                          "ApplicationSourceConfig":{
                            "ExternalUrlConfig":{"AccessUrl":"https://updated.example.com"}
                          },
                          "Permissions":["User.Details.View"]
                        }
                        """)
                .when()
                .patch("/applications/" + encode(arn))
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/applications/" + encode(arn))
                .then()
                .statusCode(200)
                .body("Description", equalTo("alchemy application v2"))
                .body("ApplicationSourceConfig.ExternalUrlConfig.AccessUrl",
                        equalTo("https://updated.example.com"))
                .body("Permissions[0]", equalTo("User.Details.View"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"tags\":{\"phase\":\"two\"}}")
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
                .body("tags.purpose", equalTo("alchemy-test"))
                .body("tags.phase", equalTo("two"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/applications/" + encode(arn))
                .then()
                .statusCode(200)
                .body("Tags.phase", equalTo("two"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .delete("/applications/" + encode(arn))
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/applications/" + encode(arn))
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void duplicateNamespaceFailsWithInvalidRequestException() {
        String authorization = auth("000000000503", EAST);
        create(authorization, """
                {
                  "Name":"first",
                  "Namespace":"com.floci.dup",
                  "ApplicationSourceConfig":{
                    "ExternalUrlConfig":{"AccessUrl":"https://example.com"}
                  }
                }
                """);
        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "Name":"second",
                          "Namespace":"com.floci.dup",
                          "ApplicationSourceConfig":{
                            "ExternalUrlConfig":{"AccessUrl":"https://example.com"}
                          }
                        }
                        """)
                .when()
                .post("/applications")
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidRequestException"));
    }

    @Test
    void applicationsAreIsolatedByAccount() {
        String firstAuth = auth("000000000504", EAST);
        String secondAuth = auth("000000000505", EAST);

        String firstArn = create(firstAuth, """
                {
                  "Name":"shared-name",
                  "Namespace":"com.floci.shared",
                  "ApplicationSourceConfig":{
                    "ExternalUrlConfig":{"AccessUrl":"https://example.com"}
                  }
                }
                """);
        String secondArn = create(secondAuth, """
                {
                  "Name":"shared-name",
                  "Namespace":"com.floci.shared",
                  "ApplicationSourceConfig":{
                    "ExternalUrlConfig":{"AccessUrl":"https://example.com"}
                  }
                }
                """);

        assertNotEquals(firstArn, secondArn);
        get(firstAuth, firstArn).then().body("Arn", equalTo(firstArn));
        get(secondAuth, secondArn).then().body("Arn", equalTo(secondArn));
        get(firstAuth, secondArn).then().statusCode(404);
    }

    @Test
    void applicationsAreIsolatedByRegion() {
        String eastAuth = auth("000000000506", EAST);
        String westAuth = auth("000000000506", WEST);

        String eastArn = create(eastAuth, """
                {
                  "Name":"regional-app",
                  "Namespace":"com.floci.regional",
                  "ApplicationSourceConfig":{
                    "ExternalUrlConfig":{"AccessUrl":"https://example.com"}
                  }
                }
                """);
        String westArn = create(westAuth, """
                {
                  "Name":"regional-app",
                  "Namespace":"com.floci.regional",
                  "ApplicationSourceConfig":{
                    "ExternalUrlConfig":{"AccessUrl":"https://example.com"}
                  }
                }
                """);

        get(eastAuth, eastArn).then().body("Arn", equalTo(eastArn));
        get(westAuth, westArn).then().body("Arn", equalTo(westArn));
        get(eastAuth, westArn).then().statusCode(404);
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region
                + "/app-integrations/aws4_request";
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
                .post("/applications")
                .then()
                .statusCode(200)
                .body("Arn", notNullValue())
                .body("Id", notNullValue())
                .extract()
                .path("Arn");
    }

    private static Response get(String authorization, String arn) {
        return given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/applications/" + encode(arn));
    }

    private static Response list(String authorization) {
        return given()
                .header("Authorization", authorization)
                .when()
                .get("/applications")
                .then()
                .statusCode(200)
                .extract()
                .response();
    }
}

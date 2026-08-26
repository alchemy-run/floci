package io.github.hectorvent.floci.services.qapps;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * Alchemy {@code test/AWS/QApps/Bindings.test.ts}: representative data-plane
 * operations against a nonexistent Q Business instance yield typed
 * {@code ResourceNotFoundException}.
 */
@QuarkusTest
class QAppsBindingsIntegrationTest {

    private static final String ACCOUNT = "000000000810";
    private static final String REGION = "us-east-1";
    private static final String NONEXISTENT_INSTANCE = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    private static final String NONEXISTENT_ID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void startQAppSessionAgainstNonexistentInstanceYieldsResourceNotFound() {
        given()
                .contentType("application/json")
                .header("Authorization", auth(ACCOUNT))
                .header("instance-id", NONEXISTENT_INSTANCE)
                .body("{\"appId\":\"" + NONEXISTENT_ID + "\",\"appVersion\":1}")
                .when()
                .post("/runtime.startQAppSession")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("resourceId", equalTo(NONEXISTENT_INSTANCE))
                .body("resourceType", equalTo("Application"));
    }

    @Test
    void getQAppSessionAgainstNonexistentInstanceYieldsResourceNotFound() {
        given()
                .header("Authorization", auth(ACCOUNT))
                .header("instance-id", NONEXISTENT_INSTANCE)
                .queryParam("sessionId", NONEXISTENT_ID)
                .when()
                .get("/runtime.getQAppSession")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void listQAppsAgainstNonexistentInstanceYieldsUnauthorized() {
        // ListQApps is not modeled with ResourceNotFoundException. AWS rejects
        // callers who are not Identity Center users of a Q Business instance
        // with UnauthorizedException.
        given()
                .header("Authorization", auth(ACCOUNT))
                .header("instance-id", NONEXISTENT_INSTANCE)
                .when()
                .get("/apps.list")
                .then()
                .statusCode(401)
                .header("X-Amzn-Errortype", equalTo("UnauthorizedException"))
                .body("__type", equalTo("UnauthorizedException"))
                .body("message", equalTo("Unauthorized"));
    }

    @Test
    void listCategoriesAgainstNonexistentInstanceYieldsResourceNotFound() {
        given()
                .header("Authorization", auth(ACCOUNT))
                .header("instance-id", NONEXISTENT_INSTANCE)
                .when()
                .get("/catalog.listCategories")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void batchCreateCategoryAgainstNonexistentInstanceYieldsResourceNotFound() {
        given()
                .contentType("application/json")
                .header("Authorization", auth(ACCOUNT))
                .header("instance-id", NONEXISTENT_INSTANCE)
                .body("{\"categories\":[{\"title\":\"alchemy-probe\"}]}")
                .when()
                .post("/catalog.createCategories")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createLibraryItemAgainstNonexistentInstanceYieldsResourceNotFound() {
        given()
                .contentType("application/json")
                .header("Authorization", auth(ACCOUNT))
                .header("instance-id", NONEXISTENT_INSTANCE)
                .body("{\"appId\":\"" + NONEXISTENT_ID + "\",\"appVersion\":1,\"categories\":[]}")
                .when()
                .post("/catalog.createItem")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void describeQAppPermissionsAgainstNonexistentInstanceYieldsResourceNotFound() {
        given()
                .header("Authorization", auth(ACCOUNT))
                .header("instance-id", NONEXISTENT_INSTANCE)
                .queryParam("appId", NONEXISTENT_ID)
                .when()
                .get("/apps.describeQAppPermissions")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void predictQAppAgainstNonexistentInstanceYieldsResourceNotFound() {
        given()
                .contentType("application/json")
                .header("Authorization", auth(ACCOUNT))
                .header("instance-id", NONEXISTENT_INSTANCE)
                .body("{}")
                .when()
                .post("/apps.predictQApp")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void missingInstanceIdYieldsValidationException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth(ACCOUNT))
                .body("{\"appId\":\"" + NONEXISTENT_ID + "\",\"appVersion\":1}")
                .when()
                .post("/runtime.startQAppSession")
                .then()
                .statusCode(400)
                .header("X-Amzn-Errortype", equalTo("ValidationException"))
                .body("__type", equalTo("ValidationException"));
    }

    private static String auth(String accountId) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + REGION + "/qapps/aws4_request";
    }
}

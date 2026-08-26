package io.github.hectorvent.floci.services.qbusiness;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Q Business restJson1 coverage used by Alchemy Application tests:
 * typed not-found, create that converges to FAILED without Identity Center,
 * list, conflict on duplicate display name, and delete-until-gone.
 */
@QuarkusTest
class QBusinessApplicationIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String MISSING = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    private static final String FAKE_IDC =
            "arn:aws:sso:::instance/ssoins-0000000000000000";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getApplicationOnANonexistentIdFailsWithResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000000701", EAST))
                .when()
                .get("/applications/" + MISSING)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("resourceId", equalTo(MISSING))
                .body("resourceType", equalTo("Application"));
    }

    @Test
    void getIndexOnANonexistentApplicationFailsWithResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000000702", EAST))
                .when()
                .get("/applications/" + MISSING + "/indices/" + MISSING)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createApplicationWithMissingIdentityCenterConvergesToFailedAndDeletes() {
        String authorization = auth("000000000703", EAST);
        String applicationId = create(authorization, """
                {
                  "displayName":"alchemy-qbusiness-probe",
                  "identityCenterInstanceArn":"%s"
                }
                """.formatted(FAKE_IDC));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/applications/" + applicationId)
                .then()
                .statusCode(200)
                .body("applicationId", equalTo(applicationId))
                .body("displayName", equalTo("alchemy-qbusiness-probe"))
                .body("status", equalTo("FAILED"))
                .body("identityType", equalTo("AWS_IAM_IDC"))
                .body("error.errorMessage", containsString("Identity Center"));

        List<Map<String, Object>> listed = list(authorization).path("applications");
        assertEquals(1, listed.size());
        assertEquals(applicationId, listed.getFirst().get("applicationId"));
        assertEquals("FAILED", listed.getFirst().get("status"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "displayName":"alchemy-qbusiness-probe",
                          "identityCenterInstanceArn":"%s"
                        }
                        """.formatted(FAKE_IDC))
                .when()
                .post("/applications")
                .then()
                .statusCode(409)
                .body("__type", equalTo("ConflictException"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .delete("/applications/" + applicationId)
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/applications/" + applicationId)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void applicationsAreIsolatedByAccount() {
        String firstAuth = auth("000000000704", EAST);
        String secondAuth = auth("000000000705", EAST);

        String firstId = create(firstAuth, """
                {"displayName":"shared-name","identityCenterInstanceArn":"%s"}
                """.formatted(FAKE_IDC));
        String secondId = create(secondAuth, """
                {"displayName":"shared-name","identityCenterInstanceArn":"%s"}
                """.formatted(FAKE_IDC));

        assertTrue(!firstId.equals(secondId));
        get(firstAuth, firstId).then().body("applicationId", equalTo(firstId));
        get(secondAuth, secondId).then().body("applicationId", equalTo(secondId));
        get(firstAuth, secondId).then().statusCode(404);
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region
                + "/qbusiness/aws4_request";
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
                .body("applicationId", notNullValue())
                .body("applicationArn", notNullValue())
                .extract()
                .path("applicationId");
    }

    private static Response get(String authorization, String applicationId) {
        return given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .get("/applications/" + applicationId);
    }

    private static Response list(String authorization) {
        return given()
                .header("Authorization", authorization)
                .when()
                .get("/applications");
    }
}

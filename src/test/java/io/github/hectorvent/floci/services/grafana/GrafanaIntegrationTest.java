package io.github.hectorvent.floci.services.grafana;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wire-format coverage for Amazon Managed Grafana restJson1.
 * Mirrors the Alchemy Grafana Bindings suite: ListVersions, workspace
 * auth/config/permissions, and the service-account + token round-trip.
 */
@QuarkusTest
class GrafanaIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String VERSION_PATTERN = "\\d+(\\.\\d+)*";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void listVersionsReturnsSupportedGrafanaVersions() {
        List<String> versions = given()
                .header("Authorization", auth("000000000801", EAST))
                .when()
                .get("/versions")
                .then()
                .statusCode(200)
                .extract()
                .path("grafanaVersions");
        assertTrue(versions.size() > 0);
        for (String version : versions) {
            assertTrue(version.matches(VERSION_PATTERN), version);
        }
    }

    @Test
    void describeWorkspaceOnANonexistentIdFailsWithResourceNotFoundException() {
        given()
                .header("Authorization", auth("000000000802", EAST))
                .when()
                .get("/workspaces/g-doesnotexist")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void workspaceAuthConfigPermissionsAndServiceAccountTokenRoundTrip() {
        String authorization = auth("000000000803", EAST);
        String id = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "accountAccessType":"CURRENT_ACCOUNT",
                          "permissionType":"SERVICE_MANAGED",
                          "authenticationProviders":["SAML"],
                          "workspaceDescription":"grafana bindings fixture",
                          "workspaceDataSources":["CLOUDWATCH"],
                          "tags":{"fixture":"grafana-bindings"}
                        }
                        """)
                .when()
                .post("/workspaces")
                .then()
                .statusCode(200)
                .body("workspace.id", startsWith("g-"))
                .body("workspace.status", equalTo("ACTIVE"))
                .body("workspace.grafanaVersion", matchesPattern(VERSION_PATTERN))
                .body("workspace.authentication.providers[0]", equalTo("SAML"))
                .extract()
                .path("workspace.id");

        given()
                .header("Authorization", authorization)
                .when()
                .get("/workspaces/" + id + "/authentication")
                .then()
                .statusCode(200)
                .body("authentication.providers[0]", equalTo("SAML"))
                .body("authentication.saml.status", notNullValue());

        given()
                .header("Authorization", authorization)
                .when()
                .get("/workspaces/" + id + "/configuration")
                .then()
                .statusCode(200)
                .body("configuration.length()", greaterThan(0))
                .body("grafanaVersion", matchesPattern(VERSION_PATTERN));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/workspaces/" + id + "/permissions")
                .then()
                .statusCode(200)
                .body("permissions.size()", equalTo(0));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/workspaces/" + id + "/serviceaccounts")
                .then()
                .statusCode(200)
                .body("serviceAccounts.size()", equalTo(0));

        String serviceAccountId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {"name":"bindings-roundtrip","grafanaRole":"EDITOR"}
                        """)
                .when()
                .post("/workspaces/" + id + "/serviceaccounts")
                .then()
                .statusCode(200)
                .body("grafanaRole", equalTo("EDITOR"))
                .extract()
                .path("id");

        Response minted = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {"name":"bindings-roundtrip-token","secondsToLive":300}
                        """)
                .when()
                .post("/workspaces/" + id + "/serviceaccounts/" + serviceAccountId + "/tokens")
                .then()
                .statusCode(200)
                .body("serviceAccountToken.key", startsWith("glsa_"))
                .extract()
                .response();
        String key = minted.path("serviceAccountToken.key");
        String tokenId = minted.path("serviceAccountToken.id");
        assertTrue(key.length() > 10);

        List<Object> tokens = given()
                .header("Authorization", authorization)
                .when()
                .get("/workspaces/" + id + "/serviceaccounts/" + serviceAccountId + "/tokens")
                .then()
                .statusCode(200)
                .extract()
                .path("serviceAccountTokens");
        assertEquals(1, tokens.size());

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/workspaces/" + id + "/serviceaccounts/" + serviceAccountId + "/tokens/" + tokenId)
                .then()
                .statusCode(200)
                .body("tokenId", equalTo(tokenId));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/workspaces/" + id + "/serviceaccounts/" + serviceAccountId)
                .then()
                .statusCode(200)
                .body("serviceAccountId", equalTo(serviceAccountId));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/tags/arn:aws:grafana:" + EAST + ":000000000803:/workspaces/" + id)
                .then()
                .statusCode(200)
                .body("tags.fixture", equalTo("grafana-bindings"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/workspaces/" + id)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/workspaces/" + id)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void listVersionsForUnknownWorkspaceIsResourceNotFoundException() {
        given()
                .header("Authorization", auth("000000000804", EAST))
                .queryParam("workspace-id", "g-missing000")
                .when()
                .get("/versions")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region + "/grafana/aws4_request";
    }
}

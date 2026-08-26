package io.github.hectorvent.floci.services.grafana;

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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Grafana workspace operations used by Alchemy {@code test/AWS/Grafana/Workspace.test.ts}.
 */
@QuarkusTest
class GrafanaWorkspaceIntegrationTest {

    private static final String REGION = "us-east-1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void describeWorkspaceOnANonexistentIdFailsWithResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000000610", REGION))
                .when()
                .get("/workspaces/g-0000000000")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createUpdateTagAndDestroyAWorkspace() {
        String authorization = auth("000000000611", REGION);

        Response created = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "accountAccessType":"CURRENT_ACCOUNT",
                          "authenticationProviders":["AWS_SSO"],
                          "permissionType":"SERVICE_MANAGED",
                          "workspaceName":"alchemy-test-grafana",
                          "workspaceDescription":"initial",
                          "workspaceDataSources":["PROMETHEUS","CLOUDWATCH"],
                          "tags":{"Environment":"test","alchemy::id":"Dashboards"}
                        }
                        """)
                .when()
                .post("/workspaces")
                .then()
                .statusCode(200)
                .body("workspace.id", startsWith("g-"))
                .body("workspace.status", equalTo("ACTIVE"))
                .body("workspace.endpoint", containsString("grafana-workspace"))
                .body("workspace.tags['alchemy::id']", equalTo("Dashboards"))
                .extract()
                .response();
        String workspaceId = created.path("workspace.id");
        String endpoint = created.path("workspace.endpoint");
        String arn = "arn:aws:grafana:" + REGION + ":000000000611:/workspaces/" + workspaceId;
        assertTrue(endpoint.contains("grafana-workspace"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/workspaces/" + workspaceId)
                .then()
                .statusCode(200)
                .body("workspace.id", equalTo(workspaceId))
                .body("workspace.description", equalTo("initial"))
                .body("workspace.name", equalTo("alchemy-test-grafana"))
                .body("workspace.tags['alchemy::id']", equalTo("Dashboards"))
                .body("workspace.authentication.providers[0]", equalTo("AWS_SSO"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/tags/" + encode(arn))
                .then()
                .statusCode(200)
                .body("tags.Environment", equalTo("test"))
                .body("tags['alchemy::id']", equalTo("Dashboards"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "workspaceName":"alchemy-test-grafana",
                          "workspaceDescription":"updated",
                          "workspaceDataSources":["PROMETHEUS","CLOUDWATCH"]
                        }
                        """)
                .when()
                .put("/workspaces/" + workspaceId)
                .then()
                .statusCode(200)
                .body("workspace.id", equalTo(workspaceId))
                .body("workspace.description", equalTo("updated"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"tags\":{\"Extra\":\"yes\"}}")
                .when()
                .post("/tags/" + encode(arn))
                .then()
                .statusCode(204);

        Map<String, String> tags = given()
                .header("Authorization", authorization)
                .when()
                .get("/tags/" + encode(arn))
                .then()
                .statusCode(200)
                .extract()
                .path("tags");
        assertEquals("test", tags.get("Environment"));
        assertEquals("yes", tags.get("Extra"));
        assertEquals("Dashboards", tags.get("alchemy::id"));

        List<Map<String, Object>> listed = given()
                .header("Authorization", authorization)
                .when()
                .get("/workspaces")
                .then()
                .statusCode(200)
                .extract()
                .path("workspaces");
        assertEquals(1, listed.size());
        assertEquals(workspaceId, listed.getFirst().get("id"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/workspaces/" + workspaceId)
                .then()
                .statusCode(200)
                .body("workspace.status", equalTo("DELETING"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/workspaces/" + workspaceId)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region + "/grafana/aws4_request";
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}

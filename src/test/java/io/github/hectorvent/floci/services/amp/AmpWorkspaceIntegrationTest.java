package io.github.hectorvent.floci.services.amp;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * AMP workspace + rule-groups + alert-manager operations used by Alchemy
 * {@code test/AWS/AMP/Workspace.test.ts}.
 */
@QuarkusTest
class AmpWorkspaceIntegrationTest {

    private static final String REGION = "us-east-1";

    private static final String RULES_V1 = """
            groups:
              - name: example
                rules:
                  - record: metric:requests:rate5m
                    expr: rate(http_requests_total[5m])""";

    private static final String RULES_V2 = """
            groups:
              - name: example
                rules:
                  - record: metric:requests:rate10m
                    expr: rate(http_requests_total[10m])""";

    private static final String ALERTS = """
            alertmanager_config: |
              route:
                receiver: default
              receivers:
                - name: default""";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void describeWorkspaceOnANonexistentIdFailsWithResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth("000000000411", REGION))
                .when()
                .get("/workspaces/ws-00000000-0000-0000-0000-000000000000")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createUpdateAndDestroyAWorkspaceWithRulesAndAlerts() {
        String authorization = auth("000000000412", REGION);
        String rulesV1 = Base64.getEncoder().encodeToString(RULES_V1.getBytes(StandardCharsets.UTF_8));
        String rulesV2 = Base64.getEncoder().encodeToString(RULES_V2.getBytes(StandardCharsets.UTF_8));
        String alerts = Base64.getEncoder().encodeToString(ALERTS.getBytes(StandardCharsets.UTF_8));

        Response created = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "alias":"alchemy-test-amp",
                          "tags":{"Environment":"test","alchemy::id":"Metrics"}
                        }
                        """)
                .when()
                .post("/workspaces")
                .then()
                .statusCode(200)
                .body("workspaceId", startsWith("ws-"))
                .body("arn", containsString(":workspace/"))
                .body("status.statusCode", equalTo("ACTIVE"))
                .extract()
                .response();
        String workspaceId = created.path("workspaceId");
        String workspaceArn = created.path("arn");

        given()
                .header("Authorization", authorization)
                .when()
                .get("/workspaces/" + workspaceId)
                .then()
                .statusCode(200)
                .body("workspace.workspaceId", equalTo(workspaceId))
                .body("workspace.arn", containsString(":workspace/"))
                .body("workspace.status.statusCode", equalTo("ACTIVE"))
                .body("workspace.prometheusEndpoint", containsString("aps-workspaces"))
                .body("workspace.prometheusEndpoint", containsString("/workspaces/" + workspaceId + "/"))
                .body("workspace.alias", equalTo("alchemy-test-amp"))
                .body("workspace.tags['alchemy::id']", equalTo("Metrics"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "name":"alchemy-test-rules",
                          "data":"%s",
                          "tags":{"Environment":"test","alchemy::id":"Rules"}
                        }
                        """.formatted(rulesV1))
                .when()
                .post("/workspaces/" + workspaceId + "/rulegroupsnamespaces")
                .then()
                .statusCode(200)
                .body("arn", containsString(":rulegroupsnamespace/"))
                .body("status.statusCode", equalTo("ACTIVE"));

        String encodedRules = given()
                .header("Authorization", authorization)
                .when()
                .get("/workspaces/" + workspaceId + "/rulegroupsnamespaces/alchemy-test-rules")
                .then()
                .statusCode(200)
                .body("ruleGroupsNamespace.tags['alchemy::id']", equalTo("Rules"))
                .extract()
                .path("ruleGroupsNamespace.data");
        assertEquals(RULES_V1, new String(Base64.getDecoder().decode(encodedRules), StandardCharsets.UTF_8));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"data\":\"" + alerts + "\"}")
                .when()
                .post("/workspaces/" + workspaceId + "/alertmanager/definition")
                .then()
                .statusCode(200)
                .body("status.statusCode", equalTo("ACTIVE"));

        String encodedAlerts = given()
                .header("Authorization", authorization)
                .when()
                .get("/workspaces/" + workspaceId + "/alertmanager/definition")
                .then()
                .statusCode(200)
                .body("alertManagerDefinition.status.statusCode", equalTo("ACTIVE"))
                .extract()
                .path("alertManagerDefinition.data");
        assertEquals(ALERTS, new String(Base64.getDecoder().decode(encodedAlerts), StandardCharsets.UTF_8));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"alias\":\"alchemy-test-amp-v2\"}")
                .when()
                .post("/workspaces/" + workspaceId + "/alias")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"tags\":{\"Extra\":\"yes\"}}")
                .when()
                .post("/tags/" + workspaceArn)
                .then()
                .statusCode(204);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/workspaces/" + workspaceId)
                .then()
                .statusCode(200)
                .body("workspace.alias", equalTo("alchemy-test-amp-v2"))
                .body("workspace.tags.Extra", equalTo("yes"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"data\":\"" + rulesV2 + "\"}")
                .when()
                .put("/workspaces/" + workspaceId + "/rulegroupsnamespaces/alchemy-test-rules")
                .then()
                .statusCode(200);

        String encodedRulesV2 = given()
                .header("Authorization", authorization)
                .when()
                .get("/workspaces/" + workspaceId + "/rulegroupsnamespaces/alchemy-test-rules")
                .then()
                .statusCode(200)
                .extract()
                .path("ruleGroupsNamespace.data");
        assertEquals(RULES_V2, new String(Base64.getDecoder().decode(encodedRulesV2), StandardCharsets.UTF_8));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/workspaces/" + workspaceId)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/workspaces/" + workspaceId)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region + "/aps/aws4_request";
    }
}

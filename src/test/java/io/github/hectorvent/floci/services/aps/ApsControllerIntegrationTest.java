package io.github.hectorvent.floci.services.aps;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class ApsControllerIntegrationTest {

    // Region comes from the SigV4 credential scope; requests without the header use the default us-east-1.
    private static String auth(String region) {
        return "AWS4-HMAC-SHA256 Credential=AKID/20260827/" + region
                + "/aps/aws4_request, SignedHeaders=host, Signature=abc";
    }

    private String createWorkspace(String alias) {
        return given()
            .contentType("application/json")
            .body("""
                {"alias": "%s", "tags": {"team": "devops"}, "clientToken": "token-123"}
                """.formatted(alias))
        .when()
            .post("/workspaces")
        .then()
            .statusCode(202)
            .body("workspaceId", startsWith("ws-"))
            .body("arn", containsString(":workspace/ws-"))
            .body("status.statusCode", equalTo("ACTIVE"))
            .body("tags.team", equalTo("devops"))
            .extract().path("workspaceId");
    }

    @Test
    void defaultScraperConfigurationIsABase64EncodedPrometheusConfiguration() {
        String configuration = given()
                .header("Authorization", auth("us-east-1"))
                .when().get("/scraperconfiguration")
                .then().statusCode(200)
                .extract().path("configuration");
        assertTrue(new String(Base64.getDecoder().decode(configuration),
                StandardCharsets.UTF_8).contains("scrape_configs:"));
    }

    @Test
    void scraperDescribeReturnsTypedNotFoundRatherThanS3Validation() {
        given().header("Authorization", auth("us-east-1"))
                .when().get("/scrapers/s-00000000-0000-0000-0000-000000000000")
                .then().statusCode(404).header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"));
    }

    @Test
    void alertManagerDefinitionRoutesPreserveValidatedYaml() {
        String workspaceId = createWorkspace("alert-definition");
        String path = "/workspaces/" + workspaceId + "/alertmanager/definition";
        String definition = Base64.getEncoder().encodeToString("""
                alertmanager_config: |
                  route:
                    receiver: default
                  receivers:
                    - name: default
                """.getBytes(StandardCharsets.UTF_8));
        given().header("Authorization", auth("us-east-1")).when().get(path)
                .then().statusCode(404).header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"));
        given().contentType("application/json").body(Map.of("data", definition))
                .when().post(path).then().statusCode(202)
                .body("status.statusCode", equalTo("ACTIVE"))
                .body("status.statusReason", containsString("not implemented"));
        given().when().get(path).then().statusCode(200)
                .body("alertManagerDefinition.data", equalTo(definition))
                .body("alertManagerDefinition.createdAt", notNullValue());
        given().contentType("application/json").body(Map.of("data", definition))
                .when().put(path).then().statusCode(202);
        given().header("Authorization", auth("us-east-1")).contentType("application/json")
                .body(Map.of("data", "invalid-base64!"))
                .when().put(path).then().statusCode(400).header("X-Amzn-Errortype", equalTo("ValidationException"));
        given().when().delete(path).then().statusCode(202).body(is(emptyString()));
        given().when().get(path).then().statusCode(404);
        given().when().delete("/workspaces/" + workspaceId).then().statusCode(202);
    }

    @Test
    void workspaceSettingsLoggingAndPolicyWireContracts() {
        String workspaceId = createWorkspace("configuration-contracts");
        String path = "/workspaces/" + workspaceId;
        given().contentType("application/json").body(Map.of("retentionPeriodInDays", 30))
                .when().patch(path + "/configuration").then().statusCode(202);
        given().when().get(path + "/configuration").then().statusCode(200)
                .body("workspaceConfiguration.retentionPeriodInDays", equalTo(30));
        String arn = "arn:aws:logs:us-east-1:000000000000:log-group:/aws/vendedlogs/prometheus/test:*";
        given().contentType("application/json").body(Map.of("logGroupArn", arn))
                .when().post(path + "/logging").then().statusCode(202);
        given().when().get(path + "/logging").then().statusCode(200)
                .body("loggingConfiguration.workspace", equalTo(workspaceId))
                .body("loggingConfiguration.logGroupArn", equalTo(arn));
        for (int threshold : List.of(0, 1000)) {
            Map<String, Object> body = Map.of("destinations", List.of(Map.of("cloudWatchLogs", Map.of("logGroupArn", arn),
                    "filters", Map.of("qspThreshold", threshold))));
            if (threshold == 0) {
                given().contentType("application/json").body(body).when().post(path + "/logging/query")
                        .then().statusCode(202);
            } else {
                given().contentType("application/json").body(body).when().put(path + "/logging/query")
                        .then().statusCode(202);
            }
            given().when().get(path + "/logging/query").then().statusCode(200)
                    .body("queryLoggingConfiguration.destinations[0].filters.qspThreshold", equalTo(threshold));
        }
        String policy = """
                {"Statement":[{"Effect":"Allow","Principal":"*","Action":"aps:QueryMetrics",
                "Resource":"arn:aws:aps:us-east-1:000000000000:workspace/%s"}]}
                """.formatted(workspaceId);
        String revision = given().contentType("application/json").body(Map.of("policyDocument", policy))
                .when().put(path + "/policy").then().statusCode(202).extract().path("revisionId");
        given().when().get(path + "/policy").then().statusCode(200)
                .body("policyDocument", equalTo(policy)).body("revisionId", equalTo(revision));
        given().header("Authorization", auth("us-east-1")).queryParam("revisionId", "stale")
                .when().delete(path + "/policy").then().statusCode(409)
                .header("X-Amzn-Errortype", equalTo("ConflictException"));
        given().queryParam("revisionId", revision).when().delete(path + "/policy").then().statusCode(202);
        given().when().delete(path).then().statusCode(202);
        for (String suffix : List.of("/configuration", "/logging", "/logging/query", "/policy")) {
            given().header("Authorization", auth("us-east-1")).when().get(path + suffix)
                    .then().statusCode(404).header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"));
        }
    }

    @Test
    void anomalyDetectorRoutesExposeMetadataAndUnsupportedExecution() {
        String workspaceId = createWorkspace("anomaly-metadata");
        String path = "/workspaces/" + workspaceId + "/anomalydetectors";
        Map<String, Object> configuration = Map.of("randomCutForest", Map.of("query", "up"));
        String id = given().contentType("application/json").body(Map.of("alias", "detector", "configuration", configuration,
                        "evaluationIntervalInSeconds", 60, "tags", Map.of("team", "metrics")))
                .when().post(path).then().statusCode(202)
                .body("status.statusCode", equalTo("CREATION_FAILED"))
                .body("status.statusReason", containsString("not implemented"))
                .extract().path("anomalyDetectorId");
        given().when().get(path + "/" + id).then().statusCode(200)
                .body("anomalyDetector.configuration.randomCutForest.query", equalTo("up"))
                .body("anomalyDetector.evaluationIntervalInSeconds", equalTo(60));
        given().when().get(path + "?alias=det&maxResults=1").then().statusCode(200)
                .body("anomalyDetectors.anomalyDetectorId", hasItem(id));
        given().contentType("application/json").body(Map.of("configuration", configuration, "evaluationIntervalInSeconds", 120))
                .when().put(path + "/" + id).then().statusCode(202).body("status.statusCode", equalTo("UPDATE_FAILED"));
        given().when().get(path + "/" + id).then().statusCode(200)
                .body("anomalyDetector.evaluationIntervalInSeconds", equalTo(120));
        given().when().delete(path + "/" + id).then().statusCode(202);
        given().header("Authorization", auth("us-east-1")).when().get(path + "/" + id)
                .then().statusCode(404).header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"));
        given().when().delete("/workspaces/" + workspaceId).then().statusCode(202);
    }

    @Test
    void dataPlaneRejectsUnsignedRequestsWithoutStartingBackend() {
        String workspaceId = createWorkspace("unsigned-data-plane");
        given().when().get("/workspaces/{workspaceId}/api/v1/query?query=up", workspaceId)
                .then().statusCode(403);
        given().contentType("application/x-protobuf").body(new byte[]{0, 1, 2})
                .when().post("/workspaces/{workspaceId}/api/v1/remote_write", workspaceId)
                .then().statusCode(403);
        given().when().delete("/workspaces/{workspaceId}", workspaceId).then().statusCode(202);
    }

    @Test
    void workspaceLifecycleRoundTrip() {
        String workspaceId = createWorkspace("lifecycle-test");

        given()
        .when()
            .get("/workspaces/{workspaceId}", workspaceId)
        .then()
            .statusCode(200)
            .body("workspace.workspaceId", equalTo(workspaceId))
            .body("workspace.alias", equalTo("lifecycle-test"))
            .body("workspace.status.statusCode", equalTo("ACTIVE"))
            .body("workspace.prometheusEndpoint",
                    containsString("://aps-workspaces-" + workspaceId + ".localhost.floci.io"))
            // Epoch-seconds number, not an ISO string: restJson1 timestamps with no
            // timestampFormat trait fail SDK deserialization as strings.
            .body("workspace.createdAt", notNullValue())
            .body("workspace.tags.team", equalTo("devops"));

        given()
        .when()
            .get("/workspaces")
        .then()
            .statusCode(200)
            .body("workspaces.workspaceId", hasItem(workspaceId))
            // The WorkspaceSummary shape never carries prometheusEndpoint.
            .body("workspaces.find { it.workspaceId == '" + workspaceId + "' }.prometheusEndpoint",
                    equalTo(null));

        // The model documents "an HTTP 202 response with an empty HTTP body" for the delete.
        given()
        .when()
            .delete("/workspaces/{workspaceId}", workspaceId)
        .then()
            .statusCode(202)
            .body(is(emptyString()));

        // The terraform/pulumi provider's delete waiter matches the typed error via the
        // X-Amzn-Errortype header; assert the wire contract, not just the status.
        given()
        .when()
            .get("/workspaces/{workspaceId}", workspaceId)
        .then()
            .statusCode(404)
            .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"));
    }

    @Test
    void listWorkspacesFiltersByAliasPrefix() {
        String matching = createWorkspace("prefix-match-a");
        createWorkspace("other-alias");

        given()
        .when()
            .get("/workspaces?alias=prefix-match")
        .then()
            .statusCode(200)
            .body("workspaces.workspaceId", hasItem(matching))
            .body("workspaces.alias", not(hasItem("other-alias")));
    }

    @Test
    void listWorkspacesRejectsZeroMaxResults() {
        given()
        .when()
            .get("/workspaces?maxResults=0")
        .then()
            .statusCode(400)
            .header("X-Amzn-Errortype", equalTo("ValidationException"));
    }

    @Test
    void updateWorkspaceAliasReturns204AndPersists() {
        String workspaceId = createWorkspace("alias-before");

        given()
            .contentType("application/json")
            .body("{\"alias\": \"alias-after\"}")
        .when()
            .post("/workspaces/{workspaceId}/alias", workspaceId)
        .then()
            .statusCode(204);

        given()
        .when()
            .get("/workspaces/{workspaceId}", workspaceId)
        .then()
            .statusCode(200)
            .body("workspace.alias", equalTo("alias-after"));
    }

    @Test
    void workspacesAreIsolatedBetweenRegions() {
        String workspaceId = createWorkspace("region-isolation-test");

        // The same workspace id does not exist when the request is signed for another region.
        given()
            .header("Authorization", auth("eu-west-1"))
        .when()
            .get("/workspaces/{workspaceId}", workspaceId)
        .then()
            .statusCode(404)
            .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"));

        given()
            .header("Authorization", auth("eu-west-1"))
        .when()
            .get("/workspaces")
        .then()
            .statusCode(200)
            .body("workspaces.workspaceId", not(hasItem(workspaceId)));

        given()
            .header("Authorization", auth("eu-west-1"))
        .when()
            .delete("/workspaces/{workspaceId}", workspaceId)
        .then()
            .statusCode(404);

        // Still present in its own region after the cross-region delete attempt.
        given()
        .when()
            .get("/workspaces/{workspaceId}", workspaceId)
        .then()
            .statusCode(200);
    }

    @Test
    void deleteWorkspaceUnknownIdReturns404() {
        given()
        .when()
            .delete("/workspaces/{workspaceId}", "ws-missing")
        .then()
            .statusCode(404)
            .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"));
    }

    @Test
    void tagsRoundTripThroughSharedTagsDispatcher() {
        String workspaceId = createWorkspace("tags-test");
        String arn = given()
            .when().get("/workspaces/{workspaceId}", workspaceId)
            .then().statusCode(200)
            .extract().path("workspace.arn");

        given()
        .when()
            .get("/tags/{arn}", arn)
        .then()
            .statusCode(200)
            .body("tags.team", equalTo("devops"));

        // AMP defines TagResource/UntagResource with 200 responses, not the dispatcher's
        // default 204.
        given()
            .contentType("application/json")
            .body("{\"tags\": {\"env\": \"test\"}}")
        .when()
            .post("/tags/{arn}", arn)
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/tags/{arn}", arn)
        .then()
            .statusCode(200)
            .body("tags.team", equalTo("devops"))
            .body("tags.env", equalTo("test"));

        given()
        .when()
            .delete("/tags/{arn}?tagKeys=team", arn)
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/tags/{arn}", arn)
        .then()
            .statusCode(200)
            .body("tags.env", equalTo("test"))
            .body("tags", not(org.hamcrest.Matchers.hasKey("team")));
    }

    private static final String RULES = Base64.getEncoder().encodeToString(
            "groups:\n- name: alerts\n  rules: []\n".getBytes(StandardCharsets.UTF_8));

    @Test
    void ruleGroupsNamespaceLifecycleRoundTrip() {
        String workspaceId = createWorkspace("rules-lifecycle");

        String arn = given()
            .contentType("application/json")
            .body("""
                {"name": "alerts", "data": "%s", "tags": {"team": "devops"}, "clientToken": "token-123"}
                """.formatted(RULES))
        .when()
            .post("/workspaces/{workspaceId}/rulegroupsnamespaces", workspaceId)
        .then()
            .statusCode(202)
            .body("name", equalTo("alerts"))
            .body("arn", containsString(":rulegroupsnamespace/" + workspaceId + "/alerts"))
            .body("status.statusCode", equalTo("ACTIVE"))
            .body("tags.team", equalTo("devops"))
            .extract().path("arn");

        given()
        .when()
            .get("/workspaces/{workspaceId}/rulegroupsnamespaces/{name}", workspaceId, "alerts")
        .then()
            .statusCode(200)
            .body("ruleGroupsNamespace.name", equalTo("alerts"))
            .body("ruleGroupsNamespace.arn", equalTo(arn))
            .body("ruleGroupsNamespace.status.statusCode", equalTo("ACTIVE"))
            .body("ruleGroupsNamespace.data", equalTo(RULES))
            .body("ruleGroupsNamespace.createdAt", notNullValue())
            .body("ruleGroupsNamespace.modifiedAt", notNullValue());

        given()
        .when()
            .get("/workspaces/{workspaceId}/rulegroupsnamespaces", workspaceId)
        .then()
            .statusCode(200)
            .body("ruleGroupsNamespaces.name", hasItem("alerts"))
            .body("ruleGroupsNamespaces.find { it.name == 'alerts' }.data", equalTo(null));

        String updated = Base64.getEncoder().encodeToString(
                "groups:\n- name: updated\n  rules: []\n".getBytes(StandardCharsets.UTF_8));
        given()
            .contentType("application/json")
            .body("{\"data\": \"%s\"}".formatted(updated))
        .when()
            .put("/workspaces/{workspaceId}/rulegroupsnamespaces/{name}", workspaceId, "alerts")
        .then()
            .statusCode(202)
            .body("status.statusCode", equalTo("ACTIVE"));

        given()
        .when()
            .get("/workspaces/{workspaceId}/rulegroupsnamespaces/{name}", workspaceId, "alerts")
        .then()
            .statusCode(200)
            .body("ruleGroupsNamespace.data", equalTo(updated));

        given()
        .when()
            .delete("/workspaces/{workspaceId}/rulegroupsnamespaces/{name}", workspaceId, "alerts")
        .then()
            .statusCode(202)
            .body(is(emptyString()));

        given()
        .when()
            .get("/workspaces/{workspaceId}/rulegroupsnamespaces/{name}", workspaceId, "alerts")
        .then()
            .statusCode(404)
            .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createRuleGroupsNamespaceRejectsDuplicateNameWithConflict() {
        String workspaceId = createWorkspace("rules-duplicate");
        String body = """
            {"name": "alerts", "data": "%s"}
            """.formatted(RULES);

        given().contentType("application/json").body(body)
            .when().post("/workspaces/{workspaceId}/rulegroupsnamespaces", workspaceId)
            .then().statusCode(202);

        given().contentType("application/json").body(body)
        .when()
            .post("/workspaces/{workspaceId}/rulegroupsnamespaces", workspaceId)
        .then()
            .statusCode(409)
            .header("X-Amzn-Errortype", equalTo("ConflictException"));
    }

    @Test
    void ruleGroupsNamespaceRoutesRejectAnUnknownWorkspace() {
        given()
        .when()
            .get("/workspaces/{workspaceId}/rulegroupsnamespaces", "ws-missing")
        .then()
            .statusCode(404)
            .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"));
    }

    @Test
    void deletingAWorkspaceDeletesItsRuleGroupsNamespaces() {
        String workspaceId = createWorkspace("rules-cascade");
        given()
            .contentType("application/json")
            .body("{\"name\": \"alerts\", \"data\": \"%s\"}".formatted(RULES))
            .when().post("/workspaces/{workspaceId}/rulegroupsnamespaces", workspaceId)
            .then().statusCode(202);

        given().when().delete("/workspaces/{workspaceId}", workspaceId).then().statusCode(202);

        given()
        .when()
            .get("/workspaces/{workspaceId}/rulegroupsnamespaces/{name}", workspaceId, "alerts")
        .then()
            .statusCode(404)
            .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"));
    }

    @Test
    void ruleGroupsNamespaceTagsRoundTripThroughSharedTagsDispatcher() {
        String workspaceId = createWorkspace("rules-tags");
        String arn = given()
            .contentType("application/json")
            .body("{\"name\": \"alerts\", \"data\": \"%s\", \"tags\": {\"team\": \"devops\"}}".formatted(RULES))
            .when().post("/workspaces/{workspaceId}/rulegroupsnamespaces", workspaceId)
            .then().statusCode(202)
            .extract().path("arn");

        given()
            .contentType("application/json")
            .body("{\"tags\": {\"env\": \"test\"}}")
        .when()
            .post("/tags/{arn}", arn)
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/tags/{arn}", arn)
        .then()
            .statusCode(200)
            .body("tags.team", equalTo("devops"))
            .body("tags.env", equalTo("test"));
    }

    @Test
    void tagResourceRejectsReservedAwsKeyPrefix() {
        String workspaceId = createWorkspace("aws-prefix-test");
        String arn = given()
            .when().get("/workspaces/{workspaceId}", workspaceId)
            .then().statusCode(200)
            .extract().path("workspace.arn");

        // The shared /tags route resolves the error-header protocol from the SigV4 credential
        // scope, so this request carries one the way a real SDK call would.
        given()
            .header("Authorization", auth("us-east-1"))
            .contentType("application/json")
            .body("{\"tags\": {\"aws:cloudformation:stack\": \"x\"}}")
        .when()
            .post("/tags/{arn}", arn)
        .then()
            .statusCode(400)
            .header("X-Amzn-Errortype", equalTo("ValidationException"));
    }
}

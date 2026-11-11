package io.floci.aws.services.grafana;

import com.github.dockerjava.api.DockerClient;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Amazon Managed Grafana backed by real Grafana containers: a workspace's endpoint serves its Grafana, the service
 * account tokens its API makes are Grafana's own, and a version upgrade keeps the workspace's data.
 */
@Tag("docker")
@QuarkusTest
@TestProfile(GrafanaDockerIntegrationTest.RealGrafanaProfile.class)
class GrafanaDockerIntegrationTest {

    public static class RealGrafanaProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.grafana.mock", "false",
                    "floci.services.grafana.host-port-base", "18300",
                    "floci.services.grafana.host-port-max", "18399");
        }
    }

    @Inject
    DockerClient dockerClient;

    @BeforeEach
    void requireDocker() {
        RestAssuredJsonUtils.configureAwsContentTypes();
        boolean available;
        try {
            dockerClient.pingCmd().exec();
            available = true;
        } catch (RuntimeException e) {
            available = false;
        }
        Assumptions.assumeTrue(available, "Docker daemon must be available for Grafana container tests");
    }

    private static RequestSpecification grafana() {
        return given().header("Authorization", "AWS4-HMAC-SHA256 Credential=000000000000/20260101/us-east-1/"
                + "grafana/aws4_request").contentType("application/json");
    }

    private static void await(String id, String status) {
        for (int attempt = 0; attempt < 240; attempt++) {
            String current = grafana().get("/workspaces/" + id).then().statusCode(200).extract()
                    .path("workspace.status");
            if (status.equals(current)) {
                return;
            }
            if (current.endsWith("FAILED")) {
                fail("Workspace " + id + " is " + current);
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("interrupted");
            }
        }
        fail("Workspace " + id + " did not become " + status);
    }

    @Test
    void theEndpointServesTheWorkspacesGrafanaAndItsTokens() {
        String id = grafana().body("{\"accountAccessType\":\"CURRENT_ACCOUNT\",\"authenticationProviders\":"
                + "[\"SAML\"],\"permissionType\":\"SERVICE_MANAGED\",\"grafanaVersion\":\"12.4\"}")
                .post("/workspaces").then().statusCode(202).body("workspace.status", equalTo("CREATING"))
                .extract().path("workspace.id");
        try {
            await(id, "ACTIVE");
            String endpoint = grafana().get("/workspaces/" + id).then().extract().path("workspace.endpoint");
            given().header("Host", endpoint).get("/api/health").then().statusCode(200)
                    .body("version", equalTo("12.4.15"));
            given().header("Host", endpoint).get("/api/search").then().statusCode(401);

            String account = grafana().body("{\"name\":\"ci\",\"grafanaRole\":\"EDITOR\"}")
                    .post("/workspaces/" + id + "/serviceaccounts").then().statusCode(200).extract().path("id");
            String key = grafana().body("{\"name\":\"deploy\",\"secondsToLive\":3600}")
                    .post("/workspaces/" + id + "/serviceaccounts/" + account + "/tokens").then().statusCode(200)
                    .extract().path("serviceAccountToken.key");
            String tokenId = grafana().get("/workspaces/" + id + "/serviceaccounts/" + account + "/tokens").then()
                    .extract().path("serviceAccountTokens[0].id");
            assertTrue(key.startsWith("glsa_"), key);
            given().header("Host", endpoint).header("Authorization", "Bearer " + key).contentType("application/json")
                    .body("{\"dashboard\":{\"title\":\"floci\",\"panels\":[]},\"overwrite\":true}")
                    .post("/api/dashboards/db").then().statusCode(200);

            grafana().body("{\"configuration\":\"{}\",\"grafanaVersion\":\"13.2\"}")
                    .put("/workspaces/" + id + "/configuration").then().statusCode(202);
            await(id, "ACTIVE");
            given().header("Host", endpoint).get("/api/health").then().statusCode(200)
                    .body("version", equalTo("13.2.6"));
            String found = given().header("Host", endpoint).header("Authorization", "Bearer " + key)
                    .get("/api/search?query=floci").then().statusCode(200).extract().asString();
            assertTrue(found.contains("\"title\":\"floci\""), found);

            grafana().delete("/workspaces/" + id + "/serviceaccounts/" + account + "/tokens/" + tokenId).then()
                    .statusCode(200);
            given().header("Host", endpoint).header("Authorization", "Bearer " + key).get("/api/search").then()
                    .statusCode(401);
        } finally {
            grafana().delete("/workspaces/" + id).then().statusCode(202);
        }
        for (int attempt = 0; attempt < 60; attempt++) {
            boolean running = !dockerClient.listContainersCmd().withShowAll(true)
                    .withNameFilter(List.of(GrafanaContainerManager.containerName(id))).exec().isEmpty();
            if (!running) {
                return;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        assertEquals(0, 1, "the workspace's Grafana container was not removed");
    }
}

package io.github.hectorvent.floci.services.opensearch;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class OpenSearchMissingDomainsIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260921/us-east-1/es/aws4_request, SignedHeaders=host, Signature=test";
    private static final String MISSING = "missing-domain-probe";

    private static RequestSpecification request() {
        return given().header("Authorization", AUTH_HEADER).contentType("application/json");
    }

    @Test
    void describeDomainsOmitsUnknownNames() {
        request()
                .body("{\"DomainNames\":[\"missing-domains-contract\",\"missing-domains-contract\"]}")
                .post("/2021-01-01/opensearch/domain-info")
                .then().statusCode(200).body("DomainStatusList", empty());
    }

    @Test
    void healthNodesProgressAndMaintenanceReadsReportBaseException() {
        String base = "/2021-01-01/opensearch/domain/" + MISSING;
        for (String path : new String[] {"/health", "/nodes", "/progress", "/domainMaintenances"}) {
            request().get(base + path)
                    .then().statusCode(400).body("__type", equalTo("BaseException"));
        }
        request().queryParam("maintenanceId", "nonexistent-maintenance-id")
                .get(base + "/domainMaintenance")
                .then().statusCode(400).body("__type", equalTo("BaseException"));
    }

    @Test
    void domainScopedOperationsReportResourceNotFound() {
        String base = "/2021-01-01/opensearch/domain/" + MISSING;
        for (String path : new String[] {"/autoTunes", "/scheduledActions", "/dryRun"}) {
            request().get(base + path)
                    .then().statusCode(409).body("__type", equalTo("ResourceNotFoundException"));
        }
        for (String path : new String[] {"/history", "/status"}) {
            request().get("/2021-01-01/opensearch/upgradeDomain/" + MISSING + path)
                    .then().statusCode(409).body("__type", equalTo("ResourceNotFoundException"));
        }
        request().body("{\"Action\":\"REBOOT_NODE\"}")
                .post(base + "/domainMaintenance")
                .then().statusCode(409).body("__type", equalTo("ResourceNotFoundException"));
        for (String op : new String[] {"start", "cancel"}) {
            request().body("{\"DomainName\":\"" + MISSING + "\"}")
                    .post("/2021-01-01/opensearch/serviceSoftwareUpdate/" + op)
                    .then().statusCode(409).body("__type", equalTo("ResourceNotFoundException"));
        }
    }

    @Test
    void maintenanceRoundTripsOnExistingDomain() {
        String domain = "maintenance-domain";
        request().body("{\"DomainName\":\"" + domain + "\",\"EngineVersion\":\"OpenSearch_2.11\","
                        + "\"ClusterConfig\":{\"InstanceType\":\"r6g.large.search\",\"InstanceCount\":2}}")
                .post("/2021-01-01/opensearch/domain")
                .then().statusCode(200);
        try {
            String base = "/2021-01-01/opensearch/domain/" + domain;

            String nodeId = request().get(base + "/nodes")
                    .then().statusCode(200)
                    .body("DomainNodesStatusList", hasSize(2))
                    .body("DomainNodesStatusList[0].InstanceType", equalTo("r6g.large.search"))
                    .extract().path("DomainNodesStatusList[0].NodeId");

            request().get(base + "/health")
                    .then().statusCode(200)
                    .body("DataNodeCount", equalTo("2"));

            request().get(base + "/scheduledActions")
                    .then().statusCode(200).body("ScheduledActions", empty());

            request().body("{\"Action\":\"REBOOT_NODE\",\"NodeId\":\"not-a-node\"}")
                    .post(base + "/domainMaintenance")
                    .then().statusCode(400).body("__type", equalTo("ValidationException"));

            String maintenanceId = request()
                    .body("{\"Action\":\"REBOOT_NODE\",\"NodeId\":\"" + nodeId + "\"}")
                    .post(base + "/domainMaintenance")
                    .then().statusCode(200).body("MaintenanceId", notNullValue())
                    .extract().path("MaintenanceId");

            request().queryParam("maintenanceId", maintenanceId)
                    .get(base + "/domainMaintenance")
                    .then().statusCode(200)
                    .body("Action", equalTo("REBOOT_NODE"))
                    .body("NodeId", equalTo(nodeId))
                    .body("Status", notNullValue());

            request().queryParam("maintenanceId", "unknown-maintenance")
                    .get(base + "/domainMaintenance")
                    .then().statusCode(409).body("__type", equalTo("ResourceNotFoundException"));

            request().queryParam("action", "REBOOT_NODE")
                    .get(base + "/domainMaintenances")
                    .then().statusCode(200)
                    .body("DomainMaintenances", hasSize(1))
                    .body("DomainMaintenances[0].MaintenanceId", equalTo(maintenanceId))
                    .body("DomainMaintenances[0].DomainName", equalTo(domain));

            request().queryParam("action", "RESTART_DASHBOARD")
                    .get(base + "/domainMaintenances")
                    .then().statusCode(200).body("DomainMaintenances", empty());
        } finally {
            request().delete("/2021-01-01/opensearch/domain/" + domain);
        }
    }
}

package io.github.hectorvent.floci.services.opensearch;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Binding-plane OpenSearch operations exercised by Alchemy's Bindings.test.ts:
 * missing-domain typed errors ({@code ResourceNotFoundException} vs
 * {@code BaseException}), batch describe omitting unknown names, and the
 * engine-version / instance-type catalogs.
 */
@QuarkusTest
class OpenSearchBindingsIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/es/aws4_request";
    private static final String MISSING = "alchemy-nonexistent-os-probe";
    private static final String DOMAIN = "os-bind-domain";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @AfterEach
    void cleanup() {
        given().header("Authorization", AUTH)
                .when().delete("/2021-01-01/opensearch/domain/" + DOMAIN);
    }

    @Test
    void describeDomainOnAMissingNameIsResourceNotFoundException() {
        given().header("Authorization", AUTH)
                .when().get("/2021-01-01/opensearch/domain/" + MISSING)
                .then()
                .statusCode(409)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void describeDomainsOmitsUnknownNames() {
        given()
                .contentType("application/json")
                .header("Authorization", AUTH)
                .body("{\"DomainNames\":[\"" + MISSING + "\"]}")
                .when().post("/2021-01-01/opensearch/domain-info")
                .then()
                .statusCode(200)
                .body("DomainStatusList", empty());
    }

    @Test
    void describeDomainsAcceptsAJsonBodyWithoutContentType() {
        // Alchemy's Lambda HTTP client may omit Content-Type; AWS still
        // accepts the JSON body. Class-level @Consumes(APPLICATION_JSON)
        // alone 415s that request.
        given()
                .header("Authorization", AUTH)
                .body("{\"DomainNames\":[\"" + MISSING + "\"]}")
                .when().post("/2021-01-01/opensearch/domain-info")
                .then()
                .statusCode(200)
                .body("DomainStatusList", empty());
    }

    @Test
    void describeDomainConfigOnAMissingNameIsResourceNotFoundException() {
        given().header("Authorization", AUTH)
                .when().get("/2021-01-01/opensearch/domain/" + MISSING + "/config")
                .then()
                .statusCode(409)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void describeDomainHealthOnAMissingNameIsBaseException() {
        given().header("Authorization", AUTH)
                .when().get("/2021-01-01/opensearch/domain/" + MISSING + "/health")
                .then()
                .statusCode(400)
                .header("X-Amzn-Errortype", equalTo("BaseException"))
                .body("__type", equalTo("BaseException"));
    }

    @Test
    void describeDomainNodesOnAMissingNameIsBaseException() {
        given().header("Authorization", AUTH)
                .when().get("/2021-01-01/opensearch/domain/" + MISSING + "/nodes")
                .then()
                .statusCode(400)
                .header("X-Amzn-Errortype", equalTo("BaseException"))
                .body("__type", equalTo("BaseException"));
    }

    @Test
    void describeDomainChangeProgressOnAMissingNameIsBaseException() {
        given().header("Authorization", AUTH)
                .when().get("/2021-01-01/opensearch/domain/" + MISSING + "/progress")
                .then()
                .statusCode(400)
                .header("X-Amzn-Errortype", equalTo("BaseException"))
                .body("__type", equalTo("BaseException"));
    }

    @Test
    void describeDomainAutoTunesOnAMissingNameIsResourceNotFoundException() {
        given().header("Authorization", AUTH)
                .when().get("/2021-01-01/opensearch/domain/" + MISSING + "/autoTunes")
                .then()
                .statusCode(409)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void listScheduledActionsOnAMissingNameIsResourceNotFoundException() {
        given().header("Authorization", AUTH)
                .when().get("/2021-01-01/opensearch/domain/" + MISSING + "/scheduledActions")
                .then()
                .statusCode(409)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void startDomainMaintenanceOnAMissingNameIsResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", AUTH)
                .body("{\"Action\":\"REBOOT_NODE\"}")
                .when().post("/2021-01-01/opensearch/domain/" + MISSING + "/domainMaintenance")
                .then()
                .statusCode(409)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void getDomainMaintenanceStatusOnAMissingNameIsBaseException() {
        given().header("Authorization", AUTH)
                .queryParam("maintenanceId", "nonexistent-maintenance-id")
                .when().get("/2021-01-01/opensearch/domain/" + MISSING + "/domainMaintenance")
                .then()
                .statusCode(400)
                .header("X-Amzn-Errortype", equalTo("BaseException"))
                .body("__type", equalTo("BaseException"));
    }

    @Test
    void listDomainMaintenancesOnAMissingNameIsBaseException() {
        given().header("Authorization", AUTH)
                .when().get("/2021-01-01/opensearch/domain/" + MISSING + "/domainMaintenances")
                .then()
                .statusCode(400)
                .header("X-Amzn-Errortype", equalTo("BaseException"))
                .body("__type", equalTo("BaseException"));
    }

    @Test
    void startServiceSoftwareUpdateOnAMissingNameIsResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", AUTH)
                .body("{\"DomainName\":\"" + MISSING + "\"}")
                .when().post("/2021-01-01/opensearch/serviceSoftwareUpdate/start")
                .then()
                .statusCode(409)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void cancelServiceSoftwareUpdateOnAMissingNameIsResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", AUTH)
                .body("{\"DomainName\":\"" + MISSING + "\"}")
                .when().post("/2021-01-01/opensearch/serviceSoftwareUpdate/cancel")
                .then()
                .statusCode(409)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void getUpgradeStatusOnAMissingNameIsResourceNotFoundException() {
        given().header("Authorization", AUTH)
                .when().get("/2021-01-01/opensearch/upgradeDomain/" + MISSING + "/status")
                .then()
                .statusCode(409)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void getUpgradeHistoryOnAMissingNameIsResourceNotFoundException() {
        given().header("Authorization", AUTH)
                .when().get("/2021-01-01/opensearch/upgradeDomain/" + MISSING + "/history")
                .then()
                .statusCode(409)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void catalogsAndExistingDomainProbes() {
        given().header("Authorization", AUTH)
                .when().get("/2021-01-01/opensearch/versions")
                .then().statusCode(200)
                .body("Versions.size()", greaterThan(0));

        given().header("Authorization", AUTH)
                .when().get("/2021-01-01/opensearch/compatibleVersions")
                .then().statusCode(200)
                .body("CompatibleVersions.size()", greaterThan(0));

        given().header("Authorization", AUTH)
                .when().get("/2021-01-01/opensearch/instanceTypeDetails/OpenSearch_2.19")
                .then().statusCode(200)
                .body("InstanceTypeDetails.size()", greaterThan(0));

        given().header("Authorization", AUTH)
                .when().get("/2021-01-01/domain")
                .then().statusCode(200)
                .body("DomainNames.size()", greaterThanOrEqualTo(0));

        given()
                .contentType("application/json")
                .header("Authorization", AUTH)
                .body("{\"DomainName\":\"" + DOMAIN + "\",\"EngineVersion\":\"OpenSearch_2.19\"}")
                .when().post("/2021-01-01/opensearch/domain")
                .then().statusCode(200);

        given().header("Authorization", AUTH)
                .when().get("/2021-01-01/opensearch/domain/" + DOMAIN + "/health")
                .then().statusCode(200)
                .body("ClusterHealth", equalTo("Green"));

        given().header("Authorization", AUTH)
                .when().get("/2021-01-01/opensearch/domain/" + DOMAIN + "/nodes")
                .then().statusCode(200)
                .body("DomainNodesStatusList", not(empty()))
                .body("DomainNodesStatusList[0].NodeType", equalTo("Data"));

        given().header("Authorization", AUTH)
                .when().get("/2021-01-01/opensearch/domain/" + DOMAIN + "/scheduledActions")
                .then().statusCode(200)
                .body("ScheduledActions", empty());

        given()
                .contentType("application/json")
                .header("Authorization", AUTH)
                .body("{\"Action\":\"REBOOT_NODE\"}")
                .when().post("/2021-01-01/opensearch/domain/" + DOMAIN + "/domainMaintenance")
                .then().statusCode(200)
                .body("MaintenanceId", notNullValue());

        given().header("Authorization", AUTH)
                .when().get("/2021-01-01/opensearch/domain/" + DOMAIN + "/domainMaintenances")
                .then().statusCode(200)
                .body("DomainMaintenances", empty());
    }
}

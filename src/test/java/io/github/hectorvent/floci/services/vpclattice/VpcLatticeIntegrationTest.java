package io.github.hectorvent.floci.services.vpclattice;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

/**
 * VPC Lattice restJson1 coverage used by Alchemy ServiceChain: service
 * networks, services, associations, target groups, listeners, and rules.
 */
@QuarkusTest
class VpcLatticeIntegrationTest {

    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "us-east-1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void listServiceNetworksWhenEmptyReturnsItemsArray() {
        given()
                .header("Authorization", auth(ACCOUNT, REGION))
                .when()
                .get("/servicenetworks")
                .then()
                .statusCode(200)
                .body("items", notNullValue());
    }

    @Test
    void getServiceNetworkWhenMissingFailsWithResourceNotFoundException() {
        given()
                .header("Authorization", auth(ACCOUNT, REGION))
                .when()
                .get("/servicenetworks/sn-does-not-exist")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void serviceChainLifecycle() {
        String authorization = auth(ACCOUNT, REGION);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String networkName = "chain-net-" + suffix;
        String serviceName = "chain-svc-" + suffix;
        String groupName = "chain-tg-" + suffix;
        String listenerName = "chain-lsn-" + suffix;
        String ruleName = "chain-rule-" + suffix;

        String networkId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {"name":"%s","authType":"NONE"}
                        """.formatted(networkName))
                .when()
                .post("/servicenetworks")
                .then()
                .statusCode(200)
                .body("id", startsWith("sn-"))
                .body("name", equalTo(networkName))
                .extract().path("id");

        String serviceId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {"name":"%s"}
                        """.formatted(serviceName))
                .when()
                .post("/services")
                .then()
                .statusCode(200)
                .body("id", startsWith("svc-"))
                .body("status", equalTo("ACTIVE"))
                .extract().path("id");

        String associationId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {"serviceIdentifier":"%s","serviceNetworkIdentifier":"%s"}
                        """.formatted(serviceId, networkId))
                .when()
                .post("/servicenetworkserviceassociations")
                .then()
                .statusCode(200)
                .body("id", startsWith("snsa-"))
                .body("status", equalTo("ACTIVE"))
                .body("serviceId", equalTo(serviceId))
                .extract().path("id");

        given()
                .header("Authorization", authorization)
                .when()
                .get("/servicenetworkserviceassociations/" + associationId)
                .then()
                .statusCode(200)
                .body("serviceId", equalTo(serviceId));

        String targetGroupId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "name":"%s",
                          "type":"IP",
                          "config":{
                            "port":80,
                            "protocol":"HTTP",
                            "vpcIdentifier":"vpc-12345678",
                            "healthCheck":{"enabled":false,"protocol":"HTTP","path":"/health",
                              "healthCheckIntervalSeconds":30,"healthCheckTimeoutSeconds":5}
                          }
                        }
                        """.formatted(groupName))
                .when()
                .post("/targetgroups")
                .then()
                .statusCode(200)
                .body("id", startsWith("tg-"))
                .body("type", equalTo("IP"))
                .extract().path("id");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {"targets":[{"id":"10.31.0.10","port":80}]}
                        """)
                .when()
                .post("/targetgroups/" + targetGroupId + "/registertargets")
                .then()
                .statusCode(200)
                .body("successful.id", hasItem("10.31.0.10"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .post("/targetgroups/" + targetGroupId + "/listtargets")
                .then()
                .statusCode(200)
                .body("items.id", hasItem("10.31.0.10"));

        String listenerId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "name":"%s",
                          "protocol":"HTTP",
                          "port":80,
                          "defaultAction":{"fixedResponse":{"statusCode":404}}
                        }
                        """.formatted(listenerName))
                .when()
                .post("/services/" + serviceId + "/listeners")
                .then()
                .statusCode(200)
                .body("id", startsWith("listener-"))
                .body("defaultAction.fixedResponse.statusCode", equalTo(404))
                .extract().path("id");

        String ruleId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "name":"%s",
                          "priority":10,
                          "match":{"httpMatch":{"pathMatch":{"match":{"prefix":"/api"}}}},
                          "action":{"forward":{"targetGroups":[{"targetGroupIdentifier":"%s","weight":100}]}}
                        }
                        """.formatted(ruleName, targetGroupId))
                .when()
                .post("/services/" + serviceId + "/listeners/" + listenerId + "/rules")
                .then()
                .statusCode(200)
                .body("id", startsWith("rule-"))
                .body("priority", equalTo(10))
                .extract().path("id");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {"defaultAction":{"fixedResponse":{"statusCode":500}}}
                        """)
                .when()
                .patch("/services/" + serviceId + "/listeners/" + listenerId)
                .then()
                .statusCode(200)
                .body("defaultAction.fixedResponse.statusCode", equalTo(500));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {"priority":20}
                        """)
                .when()
                .patch("/services/" + serviceId + "/listeners/" + listenerId + "/rules/" + ruleId)
                .then()
                .statusCode(200)
                .body("priority", equalTo(20));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {"healthCheck":{"enabled":true,"protocol":"HTTP","path":"/health",
                          "healthCheckIntervalSeconds":30,"healthCheckTimeoutSeconds":5}}
                        """)
                .when()
                .patch("/targetgroups/" + targetGroupId)
                .then()
                .statusCode(200)
                .body("config.healthCheck.enabled", equalTo(true))
                .body("config.healthCheck.path", equalTo("/health"))
                .body("config.healthCheck.healthCheckIntervalSeconds", equalTo(30));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {"targets":[{"id":"10.31.0.10","port":80}]}
                        """)
                .when()
                .post("/targetgroups/" + targetGroupId + "/deregistertargets")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {"targets":[{"id":"10.31.0.11","port":80}]}
                        """)
                .when()
                .post("/targetgroups/" + targetGroupId + "/registertargets")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .when()
                .post("/targetgroups/" + targetGroupId + "/listtargets")
                .then()
                .statusCode(200)
                .body("items.id", hasItem("10.31.0.11"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/services/" + serviceId + "/listeners/" + listenerId + "/rules/" + ruleId)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/services/" + serviceId + "/listeners/" + listenerId)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/targetgroups/" + targetGroupId)
                .then()
                .statusCode(200)
                .body("status", equalTo("DELETE_IN_PROGRESS"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/servicenetworkserviceassociations/" + associationId)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/services/" + serviceId)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/services/" + serviceId)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/servicenetworks/" + networkId)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/targetgroups/" + targetGroupId)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void accessLogSubscriptionCreateUpdateDeleteLifecycle() {
        String authorization = auth(ACCOUNT, REGION);
        String name = "als-net-" + UUID.randomUUID().toString().substring(0, 8);
        String primaryLogs = "arn:aws:logs:" + REGION + ":" + ACCOUNT + ":log-group:lattice-primary";
        String secondaryLogs = "arn:aws:logs:" + REGION + ":" + ACCOUNT + ":log-group:lattice-secondary";

        String networkId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"name\":\"" + name + "\",\"authType\":\"NONE\"}")
                .when()
                .post("/servicenetworks")
                .then()
                .statusCode(200)
                .body("id", startsWith("sn-"))
                .extract().path("id");
        String networkArn = given()
                .header("Authorization", authorization)
                .when()
                .get("/servicenetworks/" + networkId)
                .then()
                .statusCode(200)
                .extract().path("arn");

        String subscriptionId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "resourceIdentifier":"%s",
                          "destinationArn":"%s",
                          "tags":{"Owner":"floci"}
                        }
                        """.formatted(networkId, primaryLogs))
                .when()
                .post("/accesslogsubscriptions")
                .then()
                .statusCode(200)
                .body("id", startsWith("als-"))
                .body("resourceId", equalTo(networkId))
                .body("resourceArn", equalTo(networkArn))
                .body("destinationArn", equalTo(primaryLogs + ":*"))
                .extract().path("id");

        given()
                .header("Authorization", authorization)
                .when()
                .get("/accesslogsubscriptions/" + subscriptionId)
                .then()
                .statusCode(200)
                .body("id", equalTo(subscriptionId))
                .body("destinationArn", equalTo(primaryLogs + ":*"))
                .body("resourceId", equalTo(networkId))
                .body("createdAt", notNullValue());

        given()
                .header("Authorization", authorization)
                .queryParam("resourceIdentifier", networkId)
                .when()
                .get("/accesslogsubscriptions")
                .then()
                .statusCode(200)
                .body("items.size()", equalTo(1))
                .body("items[0].id", equalTo(subscriptionId));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"destinationArn\":\"" + secondaryLogs + "\"}")
                .when()
                .patch("/accesslogsubscriptions/" + subscriptionId)
                .then()
                .statusCode(200)
                .body("id", equalTo(subscriptionId))
                .body("destinationArn", equalTo(secondaryLogs + ":*"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/servicenetworkresourceassociations")
                .then()
                .statusCode(200)
                .body("items.size()", equalTo(0));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/accesslogsubscriptions/" + subscriptionId)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/accesslogsubscriptions/" + subscriptionId)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("resourceType", equalTo("ACCESS_LOG_SUBSCRIPTION"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/servicenetworks/" + networkId)
                .then()
                .statusCode(200);
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region
                + "/vpc-lattice/aws4_request";
    }
}

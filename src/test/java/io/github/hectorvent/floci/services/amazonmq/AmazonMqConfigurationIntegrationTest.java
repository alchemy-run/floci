package io.github.hectorvent.floci.services.amazonmq;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
class AmazonMqConfigurationIntegrationTest {

    private static final String CONFIG_XML_V1 = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <broker xmlns="http://activemq.apache.org/schema/core">
              <destinationPolicy>
                <policyMap>
                  <policyEntries>
                    <policyEntry topic=">">
                      <pendingMessageLimitStrategy>
                        <constantPendingMessageLimitStrategy limit="1000"/>
                      </pendingMessageLimitStrategy>
                    </policyEntry>
                  </policyEntries>
                </policyMap>
              </destinationPolicy>
            </broker>
            """;

    private static final String CONFIG_XML_V2 = CONFIG_XML_V1.replace("limit=\"1000\"", "limit=\"2000\"");

    @Test
    void describeBrokerEngineTypesReturnsActiveMqVersion() {
        given()
        .when()
            .get("/v1/broker-engine-types?engineType=ACTIVEMQ")
        .then()
            .statusCode(200)
            .body("brokerEngineTypes[0].engineType", equalTo("ACTIVEMQ"))
            .body("brokerEngineTypes[0].engineVersions[0].name", startsWith("5."));
    }

    @Test
    void describeConfigurationOnMissingIdIsNotFound() {
        given()
        .when()
            .get("/v1/configurations/{id}", "c-00000000-0000-0000-0000-000000000000")
        .then()
            .statusCode(404)
            .body("__type", equalTo("NotFoundException"));
    }

    @Test
    void createPublishRevisionsTagAndDeleteConfiguration() {
        String name = "it-cfg-" + UUID.randomUUID().toString().substring(0, 8);

        String configurationId = given()
            .contentType("application/json")
            .body("""
                {"name": "%s", "engineType": "ACTIVEMQ", "engineVersion": "5.18",
                 "authenticationStrategy": "SIMPLE", "tags": {"team": "messaging"}}
                """.formatted(name))
        .when()
            .post("/v1/configurations")
        .then()
            .statusCode(200)
            .body("id", startsWith("c-"))
            .body("arn", org.hamcrest.Matchers.containsString(":configuration:"))
            .body("name", equalTo(name))
            .body("latestRevision.revision", equalTo(1))
            .extract().path("id");

        String arn = given()
        .when()
            .get("/v1/configurations/{id}", configurationId)
        .then()
            .statusCode(200)
            .body("engineType", equalTo("ActiveMQ"))
            .body("tags.team", equalTo("messaging"))
            .body("latestRevision.revision", equalTo(1))
            .extract().path("arn");

        given()
            .contentType("application/json")
            .body("""
                {"data": "%s", "description": "alchemy test config"}
                """.formatted(encode(CONFIG_XML_V1)))
        .when()
            .put("/v1/configurations/{id}", configurationId)
        .then()
            .statusCode(200)
            .body("id", equalTo(configurationId))
            .body("latestRevision.revision", greaterThanOrEqualTo(2));

        given()
        .when()
            .get("/v1/configurations/{id}/revisions/{rev}", configurationId, "2")
        .then()
            .statusCode(200)
            .body("configurationId", equalTo(configurationId))
            .body("data", equalTo(encode(CONFIG_XML_V1)));

        given()
            .contentType("application/json")
            .body("""
                {"data": "%s", "description": "alchemy test config v2"}
                """.formatted(encode(CONFIG_XML_V2)))
        .when()
            .put("/v1/configurations/{id}", configurationId)
        .then()
            .statusCode(200)
            .body("latestRevision.revision", greaterThanOrEqualTo(3));

        given()
            .contentType("application/json")
            .header("Authorization", mqAuth())
            .body("""
                {"tags": {"env": "test"}}
                """)
        .when()
            .post("/v1/tags/{arn}", arn)
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/v1/configurations/{id}", configurationId)
        .then()
            .statusCode(200)
            .body("tags.team", equalTo("messaging"))
            .body("tags.env", equalTo("test"));

        given()
        .when()
            .get("/v1/configurations")
        .then()
            .statusCode(200)
            .body("configurations.id", hasItem(configurationId));

        given()
        .when()
            .delete("/v1/configurations/{id}", configurationId)
        .then()
            .statusCode(200)
            .body("configurationId", equalTo(configurationId));

        given()
        .when()
            .get("/v1/configurations/{id}", configurationId)
        .then()
            .statusCode(404)
            .body("__type", equalTo("NotFoundException"));

        given()
        .when()
            .get("/v1/configurations")
        .then()
            .statusCode(200)
            .body("configurations.id", not(hasItem(configurationId)));
    }

    private static String encode(String xml) {
        return Base64.getEncoder().encodeToString(xml.getBytes(StandardCharsets.UTF_8));
    }

    private static String mqAuth() {
        return "AWS4-HMAC-SHA256 Credential=AKID/20260205/us-east-1/mq/aws4_request";
    }
}

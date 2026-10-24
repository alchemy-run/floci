package io.github.hectorvent.floci.services.amazonmq;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
class AmazonMqControllerIntegrationTest {

    private String createRabbitBroker(String name) {
        return given()
            .contentType("application/json")
            .body("""
                {"brokerName": "%s", "engineType": "RABBITMQ",
                 "deploymentMode": "SINGLE_INSTANCE", "hostInstanceType": "mq.t3.micro",
                 "publiclyAccessible": false,
                 "users": [{"username": "admin", "password": "AdminPass123", "consoleAccess": true}]}
                """.formatted(name))
        .when()
            .post("/v1/brokers")
        .then()
            .statusCode(200)
            .extract().path("brokerId");
    }

    @Test
    void createThenDescribeBroker() {
        String brokerId = createRabbitBroker("it-describe");

        given()
        .when()
            .get("/v1/brokers/{id}", brokerId)
        .then()
            .statusCode(200)
            .body("brokerName", equalTo("it-describe"))
            .body("engineType", equalTo("RABBITMQ"))
            .body("brokerState", equalTo("RUNNING"))
            .body("brokerInstances[0].endpoints[0]", startsWith("amqp://"))
            // AWS does not expose RabbitMQ users from DescribeBroker. Returning
            // them makes Terraform call the unsupported standalone User API.
            .body("users", nullValue())
            // internal bookkeeping is persisted but must never leak into the API
            .body("containerId", nullValue())
            .body("accountId", nullValue())
            .body("volumeId", nullValue());
    }

    @Test
    void listBrokersIncludesCreated() {
        createRabbitBroker("it-list");

        given()
        .when()
            .get("/v1/brokers")
        .then()
            .statusCode(200)
            .body("brokerSummaries.brokerName", hasItem("it-list"));
    }

    @Test
    void userApiRejectedForRabbitMq() {
        // The standalone User API applies only to ActiveMQ; AWS rejects it for
        // RabbitMQ brokers. Users are managed through the RabbitMQ web console.
        String brokerId = createRabbitBroker("it-users");

        given()
            .contentType("application/json")
            .body("""
                {"password": "AnotherPass99", "consoleAccess": false}
                """)
        .when()
            .post("/v1/brokers/{id}/users/alice", brokerId)
        .then()
            .statusCode(400);

        given()
        .when()
            .get("/v1/brokers/{id}/users", brokerId)
        .then()
            .statusCode(400);
    }

    @Test
    void userOperationsOnMissingBrokerReturnNotFound() {
        String brokerId = "b-00000000-0000-0000-0000-000000000000";
        String authorization = "AWS4-HMAC-SHA256 Credential=test/20260921/us-east-1/mq/aws4_request, SignedHeaders=host, Signature=test";
        given().header("Authorization", authorization).get("/v1/brokers/{id}/users", brokerId)
                .then().statusCode(404).body("__type", equalTo("NotFoundException"));
        given().header("Authorization", authorization).get("/v1/brokers/{id}/users/alice", brokerId)
                .then().statusCode(404).body("__type", equalTo("NotFoundException"));
        given().header("Authorization", authorization).delete("/v1/brokers/{id}/users/alice", brokerId)
                .then().statusCode(404).body("__type", equalTo("NotFoundException"));
        given().header("Authorization", authorization).contentType("application/json")
                .body("{\"password\":\"AnotherPass99\",\"consoleAccess\":false}")
                .post("/v1/brokers/{id}/users/alice", brokerId)
                .then().statusCode(404).body("__type", equalTo("NotFoundException"));
    }

    @Test
    void promoteRequiresReplicaBroker() {
        String missing = "b-00000000-0000-0000-0000-000000000000";
        given().header("Authorization", MQ_AUTH).contentType("application/json")
                .body("{\"mode\":\"SWITCHOVER\"}")
                .post("/v1/brokers/{id}/promote", missing)
                .then().statusCode(404).body("__type", equalTo("NotFoundException"));

        String brokerId = createRabbitBroker("it-promote");
        given().header("Authorization", MQ_AUTH).contentType("application/json")
                .body("{\"mode\":\"SWITCHOVER\"}")
                .post("/v1/brokers/{id}/promote", brokerId)
                .then().statusCode(400).body("__type", equalTo("BadRequestException"))
                .body("message", containsString("replica"));
        given().header("Authorization", MQ_AUTH).contentType("application/json")
                .body("{\"mode\":\"SIDEWAYS\"}")
                .post("/v1/brokers/{id}/promote", brokerId)
                .then().statusCode(400).body("__type", equalTo("BadRequestException"));
        given().header("Authorization", MQ_AUTH).contentType("application/json")
                .body("{}")
                .post("/v1/brokers/{id}/promote", brokerId)
                .then().statusCode(400).body("__type", equalTo("BadRequestException"));
    }

    @Test
    void updateUserFollowsUserApiRules() {
        String missing = "b-00000000-0000-0000-0000-000000000000";
        given().header("Authorization", MQ_AUTH).contentType("application/json")
                .body("{\"consoleAccess\":true}")
                .put("/v1/brokers/{id}/users/alice", missing)
                .then().statusCode(404).body("__type", equalTo("NotFoundException"));

        String brokerId = createRabbitBroker("it-update-user");
        given().header("Authorization", MQ_AUTH).contentType("application/json")
                .body("{\"consoleAccess\":true}")
                .put("/v1/brokers/{id}/users/alice", brokerId)
                .then().statusCode(400).body("__type", equalTo("BadRequestException"));
    }

    @Test
    void updateBrokerStagesPendingChangesUntilReboot() {
        given().header("Authorization", MQ_AUTH).contentType("application/json")
                .body("{\"autoMinorVersionUpgrade\":true}")
                .put("/v1/brokers/{id}", "b-00000000-0000-0000-0000-000000000000")
                .then().statusCode(404).body("__type", equalTo("NotFoundException"));

        String brokerId = createRabbitBroker("it-update-broker");
        given().header("Authorization", MQ_AUTH).contentType("application/json")
                .body("{\"engineVersion\":\"9.9\"}")
                .put("/v1/brokers/{id}", brokerId)
                .then().statusCode(400).body("__type", equalTo("BadRequestException"));
        given().header("Authorization", MQ_AUTH).contentType("application/json")
                .body("{\"dataReplicationMode\":\"CRDR\"}")
                .put("/v1/brokers/{id}", brokerId)
                .then().statusCode(400).body("__type", equalTo("BadRequestException"));

        given().header("Authorization", MQ_AUTH).contentType("application/json")
                .body("""
                    {"autoMinorVersionUpgrade": true, "hostInstanceType": "mq.m5.large",
                     "maintenanceWindowStartTime": {"dayOfWeek": "MONDAY", "timeOfDay": "03:00", "timeZone": "UTC"},
                     "logs": {"general": true}}
                    """)
                .put("/v1/brokers/{id}", brokerId)
                .then().statusCode(200)
                .body("brokerId", equalTo(brokerId))
                .body("hostInstanceType", equalTo("mq.m5.large"))
                .body("autoMinorVersionUpgrade", equalTo(true));

        given().header("Authorization", MQ_AUTH).get("/v1/brokers/{id}", brokerId)
                .then().statusCode(200)
                .body("hostInstanceType", equalTo("mq.t3.micro"))
                .body("pendingHostInstanceType", equalTo("mq.m5.large"))
                .body("autoMinorVersionUpgrade", equalTo(true))
                .body("maintenanceWindowStartTime.dayOfWeek", equalTo("MONDAY"))
                .body("logs.general", equalTo(true));

        given().header("Authorization", MQ_AUTH).contentType("application/json")
                .post("/v1/brokers/{id}/reboot", brokerId)
                .then().statusCode(200);
        given().header("Authorization", MQ_AUTH).get("/v1/brokers/{id}", brokerId)
                .then().statusCode(200)
                .body("hostInstanceType", equalTo("mq.m5.large"))
                .body("pendingHostInstanceType", nullValue());
    }

    private static String mqAuth(String region) {
        return "AWS4-HMAC-SHA256 Credential=test/20260922/" + region
                + "/mq/aws4_request, SignedHeaders=host, Signature=test";
    }

    private static final String MQ_AUTH = mqAuth("us-east-1");

    @Test
    void describeMissingConfigurationIsNotFound() {
        given().header("Authorization", MQ_AUTH)
            .get("/v1/configurations/{id}", "c-00000000-0000-0000-0000-000000000000")
        .then()
            .statusCode(404)
            .header("X-Amzn-Errortype", "NotFoundException")
            .body("__type", equalTo("NotFoundException"));
    }

    @Test
    void configurationLifecyclePublishesRevisions() {
        String id = given().header("Authorization", MQ_AUTH).contentType("application/json")
            .body("""
                {"name": "it-config", "engineType": "ACTIVEMQ", "engineVersion": "5.18",
                 "tags": {"team": "messaging"}}
                """)
        .when()
            .post("/v1/configurations")
        .then()
            .statusCode(200)
            .body("arn", containsString(":configuration:c-"))
            .body("latestRevision.revision", equalTo(1))
            .extract().path("id");

        given().header("Authorization", MQ_AUTH)
            .get("/v1/configurations/{id}", id)
        .then()
            .statusCode(200)
            .body("name", equalTo("it-config"))
            .body("engineType", equalTo("ActiveMQ"))
            .body("engineVersion", equalTo("5.18"))
            .body("tags.team", equalTo("messaging"));

        String xml = "<broker xmlns=\"http://activemq.apache.org/schema/core\"><plugins/></broker>";
        String data = java.util.Base64.getEncoder().encodeToString(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        given().header("Authorization", MQ_AUTH).contentType("application/json")
            .body("{\"data\": \"" + data + "\", \"description\": \"custom\"}")
        .when()
            .put("/v1/configurations/{id}", id)
        .then()
            .statusCode(200)
            .body("id", equalTo(id))
            .body("latestRevision.revision", equalTo(2))
            .body("warnings", hasSize(0));

        given().header("Authorization", MQ_AUTH)
            .get("/v1/configurations/{id}/revisions/2", id)
        .then()
            .statusCode(200)
            .body("data", equalTo(data))
            .body("description", equalTo("custom"));

        given().header("Authorization", MQ_AUTH)
            .get("/v1/configurations/{id}/revisions", id)
        .then()
            .statusCode(200)
            .body("revisions.revision", contains(1, 2));

        given().header("Authorization", MQ_AUTH)
            .get("/v1/configurations")
        .then()
            .statusCode(200)
            .body("configurations.id", hasItem(id));

        // Another region does not see it.
        given().header("Authorization", mqAuth("eu-west-1"))
            .get("/v1/configurations/{id}", id)
        .then()
            .statusCode(404);

        given().header("Authorization", MQ_AUTH)
            .delete("/v1/configurations/{id}", id)
        .then()
            .statusCode(200)
            .body("configurationId", equalTo(id));

        given().header("Authorization", MQ_AUTH)
            .get("/v1/configurations/{id}", id)
        .then()
            .statusCode(404)
            .body("__type", equalTo("NotFoundException"));
    }

    @Test
    void configurationTagsUseV1TagsPath() {
        String arn = given().header("Authorization", MQ_AUTH).contentType("application/json")
            .body("{\"name\": \"it-config-tags\", \"engineType\": \"RABBITMQ\"}")
        .when()
            .post("/v1/configurations")
        .then()
            .statusCode(200)
            .extract().path("arn");

        given().header("Authorization", MQ_AUTH).contentType("application/json")
            .body("{\"tags\": {\"env\": \"dev\", \"owner\": \"me\"}}")
        .when()
            .post("/v1/tags/{arn}", arn)
        .then()
            .statusCode(204);

        given().header("Authorization", MQ_AUTH)
            .queryParam("tagKeys", "owner")
        .when()
            .delete("/v1/tags/{arn}", arn)
        .then()
            .statusCode(204);

        given().header("Authorization", MQ_AUTH)
            .get("/v1/tags/{arn}", arn)
        .then()
            .statusCode(200)
            .body("tags.env", equalTo("dev"))
            .body("tags.owner", nullValue());
    }

    @Test
    void unsignedConfigurationsPathStillReachesMsk() {
        // MSK owns /v1/configurations too; only mq-signed requests are rerouted.
        given().contentType("application/json")
            .body("""
                {"name": "it-msk-config", "kafkaVersions": ["3.6.0"],
                 "serverProperties": "YXV0by5jcmVhdGUudG9waWNzLmVuYWJsZT10cnVl"}
                """)
        .when()
            .post("/v1/configurations")
        .then()
            .statusCode(200)
            .body("arn", containsString(":kafka:"));
    }

    @Test
    void describeBrokerEngineTypesFiltersByEngine() {
        given().header("Authorization", MQ_AUTH)
            .queryParam("engineType", "ACTIVEMQ")
        .when()
            .get("/v1/broker-engine-types")
        .then()
            .statusCode(200)
            .body("brokerEngineTypes", hasSize(1))
            .body("brokerEngineTypes[0].engineType", equalTo("ACTIVEMQ"))
            .body("brokerEngineTypes[0].engineVersions[0].name", equalTo("5.18"));
    }

    @Test
    void createBrokerRejectsMissingConfigurationReference() {
        given().header("Authorization", MQ_AUTH).contentType("application/json")
            .body("""
                {"brokerName": "it-badconfig", "engineType": "RABBITMQ",
                 "deploymentMode": "SINGLE_INSTANCE", "hostInstanceType": "mq.t3.micro",
                 "configuration": {"id": "c-00000000-0000-0000-0000-000000000000", "revision": 1},
                 "users": [{"username": "admin", "password": "AdminPass123", "consoleAccess": true}]}
                """)
        .when()
            .post("/v1/brokers")
        .then()
            .statusCode(404)
            .body("__type", equalTo("NotFoundException"));
    }

    @Test
    void rejectsBrokerWithoutUser() {
        given()
            .contentType("application/json")
            .body("""
                {"brokerName": "it-nouser", "engineType": "RABBITMQ",
                 "deploymentMode": "SINGLE_INSTANCE", "hostInstanceType": "mq.t3.micro",
                 "publiclyAccessible": false}
                """)
        .when()
            .post("/v1/brokers")
        .then()
            .statusCode(400);
    }

    @Test
    void rejectsActiveMqBrokerWithoutUsers() {
        given()
            .contentType("application/json")
            .body("""
                {"brokerName": "it-activemq", "engineType": "ACTIVEMQ",
                 "deploymentMode": "SINGLE_INSTANCE", "hostInstanceType": "mq.t3.micro",
                 "publiclyAccessible": false}
                """)
        .when()
            .post("/v1/brokers")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"));
    }

    @Test
    void activeMqBrokerExposesEveryProtocolAndItsUsers() {
        String brokerId = given().header("Authorization", MQ_AUTH).contentType("application/json")
            .body("""
                {"brokerName": "it-activemq-broker", "engineType": "ACTIVEMQ",
                 "deploymentMode": "SINGLE_INSTANCE", "hostInstanceType": "mq.t3.micro",
                 "publiclyAccessible": true, "autoMinorVersionUpgrade": true,
                 "users": [{"username": "alchemyadmin", "password": "SuperSecretPassw0rd!"}],
                 "tags": {"team": "messaging"}}
                """)
        .when()
            .post("/v1/brokers")
        .then()
            .statusCode(200)
            .body("brokerArn", containsString(":broker:it-activemq-broker:"))
            .extract().path("brokerId");

        given().header("Authorization", MQ_AUTH).get("/v1/brokers/{id}", brokerId)
            .then().statusCode(200)
            .body("engineType", equalTo("ACTIVEMQ"))
            .body("engineVersion", equalTo("5.18"))
            .body("brokerState", equalTo("RUNNING"))
            .body("deploymentMode", equalTo("SINGLE_INSTANCE"))
            .body("brokerInstances[0].endpoints", hasSize(5))
            .body("brokerInstances[0].endpoints[0]", startsWith("tcp://"))
            .body("brokerInstances[0].consoleURL", startsWith("http://"))
            .body("users.username", contains("alchemyadmin"))
            .body("tags.team", equalTo("messaging"));

        given().header("Authorization", MQ_AUTH).contentType("application/json")
            .body("{\"mode\":\"SWITCHOVER\"}")
            .post("/v1/brokers/{id}/promote", brokerId)
            .then().statusCode(400).body("__type", equalTo("BadRequestException"));

        given().header("Authorization", MQ_AUTH).contentType("application/json")
            .post("/v1/brokers/{id}/reboot", brokerId)
            .then().statusCode(200);

        given().header("Authorization", MQ_AUTH).delete("/v1/brokers/{id}", brokerId)
            .then().statusCode(200).body("brokerId", equalTo(brokerId));
    }

    @Test
    void activeMqUserApiStagesChangesUntilReboot() {
        String brokerId = given().header("Authorization", MQ_AUTH).contentType("application/json")
            .body("""
                {"brokerName": "it-activemq-users", "engineType": "ActiveMQ",
                 "deploymentMode": "SINGLE_INSTANCE", "hostInstanceType": "mq.t3.micro",
                 "users": [{"username": "alchemyadmin", "password": "SuperSecretPassw0rd!"}]}
                """)
            .post("/v1/brokers")
            .then().statusCode(200).extract().path("brokerId");

        given().header("Authorization", MQ_AUTH).contentType("application/json")
            .body("{\"password\": \"AnotherSecretPassw0rd!\", \"consoleAccess\": true, \"groups\": [\"tenants\"]}")
            .post("/v1/brokers/{id}/users/alchemytenant", brokerId)
            .then().statusCode(200);
        given().header("Authorization", MQ_AUTH).contentType("application/json")
            .body("{\"password\": \"AnotherSecretPassw0rd!\"}")
            .post("/v1/brokers/{id}/users/alchemytenant", brokerId)
            .then().statusCode(409).body("__type", equalTo("ConflictException"));

        given().header("Authorization", MQ_AUTH).get("/v1/brokers/{id}/users/alchemytenant", brokerId)
            .then().statusCode(200)
            .body("brokerId", equalTo(brokerId))
            .body("username", equalTo("alchemytenant"))
            .body("consoleAccess", nullValue())
            .body("pending.pendingChange", equalTo("CREATE"))
            .body("pending.consoleAccess", equalTo(true))
            .body("pending.groups", contains("tenants"));

        given().header("Authorization", MQ_AUTH).get("/v1/brokers/{id}/users", brokerId)
            .then().statusCode(200)
            .body("brokerId", equalTo(brokerId))
            .body("users.username", contains("alchemyadmin", "alchemytenant"))
            .body("users.find { it.username == 'alchemytenant' }.pendingChange", equalTo("CREATE"));

        given().header("Authorization", MQ_AUTH).contentType("application/json")
            .body("{\"consoleAccess\": true}")
            .put("/v1/brokers/{id}/users/alchemynonexistentuser", brokerId)
            .then().statusCode(404).body("__type", equalTo("NotFoundException"));

        given().header("Authorization", MQ_AUTH).contentType("application/json")
            .body("{\"consoleAccess\": true}")
            .put("/v1/brokers/{id}/users/alchemyadmin", brokerId)
            .then().statusCode(200);
        given().header("Authorization", MQ_AUTH).get("/v1/brokers/{id}/users/alchemyadmin", brokerId)
            .then().statusCode(200)
            .body("consoleAccess", equalTo(false))
            .body("pending.pendingChange", equalTo("UPDATE"))
            .body("pending.consoleAccess", equalTo(true));

        given().header("Authorization", MQ_AUTH).delete("/v1/brokers/{id}/users/alchemytenant", brokerId)
            .then().statusCode(200);
        given().header("Authorization", MQ_AUTH).get("/v1/brokers/{id}/users/alchemytenant", brokerId)
            .then().statusCode(404).body("__type", equalTo("NotFoundException"));

        given().header("Authorization", MQ_AUTH).contentType("application/json")
            .post("/v1/brokers/{id}/reboot", brokerId)
            .then().statusCode(200);
        given().header("Authorization", MQ_AUTH).get("/v1/brokers/{id}/users/alchemyadmin", brokerId)
            .then().statusCode(200)
            .body("consoleAccess", equalTo(true))
            .body("pending", nullValue());
    }
}

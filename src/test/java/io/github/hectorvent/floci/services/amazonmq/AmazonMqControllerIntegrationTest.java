package io.github.hectorvent.floci.services.amazonmq;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
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
    void describeBrokerReturnsCreateBrokerMembers() {
        String brokerId = given()
            .contentType("application/json")
            .body("""
                {"brokerName": "it-members", "engineType": "RABBITMQ",
                 "deploymentMode": "SINGLE_INSTANCE", "hostInstanceType": "mq.t3.micro",
                 "publiclyAccessible": false,
                 "securityGroups": ["sg-0123"], "subnetIds": ["subnet-0abc"],
                 "logs": {"general": true},
                 "maintenanceWindowStartTime": {"dayOfWeek": "MONDAY", "timeOfDay": "02:00", "timeZone": "UTC"},
                 "storageType": "EBS", "authenticationStrategy": "SIMPLE",
                 "encryptionOptions": {"kmsKeyId": "arn:aws:kms:us-east-1:000000000000:key/k1", "useAwsOwnedKey": false},
                 "configuration": {"id": "c-123", "revision": 2},
                 "users": [{"username": "admin", "password": "AdminPass123", "consoleAccess": true}]}
                """)
        .when()
            .post("/v1/brokers")
        .then()
            .statusCode(200)
            .extract().path("brokerId");

        given()
        .when()
            .get("/v1/brokers/{id}", brokerId)
        .then()
            .statusCode(200)
            .body("securityGroups", equalTo(List.of("sg-0123")))
            .body("subnetIds", equalTo(List.of("subnet-0abc")))
            .body("logs.general", equalTo(true))
            .body("logs.generalLogGroup", equalTo("/aws/amazonmq/broker/" + brokerId + "/general"))
            .body("maintenanceWindowStartTime.dayOfWeek", equalTo("MONDAY"))
            .body("maintenanceWindowStartTime.timeOfDay", equalTo("02:00"))
            .body("maintenanceWindowStartTime.timeZone", equalTo("UTC"))
            .body("storageType", equalTo("EBS"))
            .body("authenticationStrategy", equalTo("SIMPLE"))
            .body("encryptionOptions.kmsKeyId", equalTo("arn:aws:kms:us-east-1:000000000000:key/k1"))
            .body("encryptionOptions.useAwsOwnedKey", equalTo(false))
            .body("configurations.current.id", equalTo("c-123"))
            .body("configurations.current.revision", equalTo(2))
            .body("configuration", nullValue())
            // RabbitMQ users stay out of DescribeBroker (see createThenDescribeBroker).
            .body("users", nullValue());
    }

    @Test
    void describeBrokerOmitsUnsetOptionalMembers() {
        String brokerId = createRabbitBroker("it-minimal");

        given()
        .when()
            .get("/v1/brokers/{id}", brokerId)
        .then()
            .statusCode(200)
            .body("$", not(hasKey("securityGroups")))
            .body("logs.general", equalTo(false))
            .body("logs.generalLogGroup", equalTo("/aws/amazonmq/broker/" + brokerId + "/general"))
            .body("$", not(hasKey("configurations")))
            .body("$", not(hasKey("encryptionOptions")));
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
    void describeSharedResourcesReturnsEmptyList() {
        String brokerId = createRabbitBroker("it-shared");

        given()
        .when()
            .get("/v1/brokers/{id}/shared-resources", brokerId)
        .then()
            .statusCode(200)
            .body("sharedResources", equalTo(List.of()))
            .body("$", not(hasKey("nextToken")));
    }

    @Test
    void describeSharedResourcesUnknownBrokerIsNotFound() {
        given()
        .when()
            .get("/v1/brokers/{id}/shared-resources", "b-does-not-exist")
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

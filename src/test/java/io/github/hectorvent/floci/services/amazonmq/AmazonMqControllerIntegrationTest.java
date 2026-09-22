package io.github.hectorvent.floci.services.amazonmq;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
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
    void rejectsActiveMqEngine() {
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
            .statusCode(400);
    }
}

package io.github.hectorvent.floci.services.lambda;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

@QuarkusTest
class KafkaEsmIntegrationTest {

    private static final String LAMBDA_BASE = "/2015-03-31";
    private static final String FUNCTION_NAME = "kafka-esm-fn";
    private static final String CLUSTER_ARN =
            "arn:aws:kafka:us-east-1:000000000000:cluster/kafka-esm-cluster/11111111-1111-1111-1111-111111111111";

    @Test
    void createMskEventSourceMappingStoresTopics() {
        given()
                .contentType("application/json")
                .body("""
                        {
                            "FunctionName": "%s",
                            "Runtime": "nodejs20.x",
                            "Role": "arn:aws:iam::000000000000:role/lambda-role",
                            "Handler": "index.handler"
                        }
                        """.formatted(FUNCTION_NAME))
                .when()
                .post(LAMBDA_BASE + "/functions")
                .then()
                .statusCode(201);

        String uuid = given()
                .contentType("application/json")
                .body("""
                        {
                            "FunctionName": "%s",
                            "EventSourceArn": "%s",
                            "Topics": ["orders"],
                            "StartingPosition": "LATEST",
                            "AmazonManagedKafkaEventSourceConfig": {
                                "ConsumerGroupId": "alchemy-fixture"
                            }
                        }
                        """.formatted(FUNCTION_NAME, CLUSTER_ARN))
                .when()
                .post(LAMBDA_BASE + "/event-source-mappings")
                .then()
                .statusCode(202)
                .body("Topics[0]", equalTo("orders"))
                .body("State", not(equalTo("Failed")))
                .body("EventSourceArn", equalTo(CLUSTER_ARN))
                .extract().path("UUID");

        given()
                .when()
                .get(LAMBDA_BASE + "/event-source-mappings?FunctionName=" + FUNCTION_NAME
                        + "&EventSourceArn=" + CLUSTER_ARN)
                .then()
                .statusCode(200)
                .body("EventSourceMappings.size()", equalTo(1))
                .body("EventSourceMappings[0].UUID", equalTo(uuid))
                .body("EventSourceMappings[0].Topics[0]", equalTo("orders"))
                .body("EventSourceMappings[0].AmazonManagedKafkaEventSourceConfig.ConsumerGroupId",
                        equalTo("alchemy-fixture"));
    }
}

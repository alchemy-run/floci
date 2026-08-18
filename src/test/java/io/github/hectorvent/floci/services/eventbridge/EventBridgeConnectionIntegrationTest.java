package io.github.hectorvent.floci.services.eventbridge;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.*;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EventBridgeConnectionIntegrationTest {

    private static final String CT = "application/x-amz-json-1.1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void createConnectionReturnsAuthorizedArnAndHidesSecretOnDescribe() {
        given()
            .contentType(CT)
            .header("X-Amz-Target", "AWSEvents.CreateConnection")
            .body("""
                {
                    "Name": "eb-conn-test",
                    "Description": "initial",
                    "AuthorizationType": "API_KEY",
                    "AuthParameters": {
                        "ApiKeyAuthParameters": {
                            "ApiKeyName": "x-api-key",
                            "ApiKeyValue": "super-secret"
                        }
                    }
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ConnectionArn", containsString("connection/eb-conn-test/"))
            .body("ConnectionState", equalTo("AUTHORIZED"));

        given()
            .contentType(CT)
            .header("X-Amz-Target", "AWSEvents.DescribeConnection")
            .body("{\"Name\":\"eb-conn-test\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Name", equalTo("eb-conn-test"))
            .body("Description", equalTo("initial"))
            .body("AuthorizationType", equalTo("API_KEY"))
            .body("ConnectionState", equalTo("AUTHORIZED"))
            .body("SecretArn", containsString("secretsmanager"))
            .body("AuthParameters.ApiKeyAuthParameters.ApiKeyName", equalTo("x-api-key"))
            .body("AuthParameters.ApiKeyAuthParameters.ApiKeyValue", nullValue());
    }

    @Test
    @Order(2)
    void updateConnectionSyncsDescription() {
        given()
            .contentType(CT)
            .header("X-Amz-Target", "AWSEvents.UpdateConnection")
            .body("""
                {
                    "Name": "eb-conn-test",
                    "Description": "updated connection"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ConnectionArn", notNullValue());

        given()
            .contentType(CT)
            .header("X-Amz-Target", "AWSEvents.DescribeConnection")
            .body("{\"Name\":\"eb-conn-test\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Description", equalTo("updated connection"));
    }

    @Test
    @Order(3)
    void createAndUpdateApiDestination() {
        String connectionArn = given()
            .contentType(CT)
            .header("X-Amz-Target", "AWSEvents.DescribeConnection")
            .body("{\"Name\":\"eb-conn-test\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().jsonPath().getString("ConnectionArn");

        given()
            .contentType(CT)
            .header("X-Amz-Target", "AWSEvents.CreateApiDestination")
            .body("""
                {
                    "Name": "eb-dest-test",
                    "ConnectionArn": "%s",
                    "InvocationEndpoint": "https://example.com/events",
                    "HttpMethod": "POST",
                    "InvocationRateLimitPerSecond": 5
                }
                """.formatted(connectionArn))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ApiDestinationArn", containsString("api-destination/eb-dest-test/"))
            .body("ApiDestinationState", equalTo("ACTIVE"));

        given()
            .contentType(CT)
            .header("X-Amz-Target", "AWSEvents.DescribeApiDestination")
            .body("{\"Name\":\"eb-dest-test\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("InvocationEndpoint", equalTo("https://example.com/events"))
            .body("InvocationRateLimitPerSecond", equalTo(5));

        given()
            .contentType(CT)
            .header("X-Amz-Target", "AWSEvents.UpdateApiDestination")
            .body("""
                {
                    "Name": "eb-dest-test",
                    "InvocationRateLimitPerSecond": 10
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType(CT)
            .header("X-Amz-Target", "AWSEvents.DescribeApiDestination")
            .body("{\"Name\":\"eb-dest-test\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("InvocationRateLimitPerSecond", equalTo(10));
    }

    @Test
    @Order(4)
    void deleteConnectionWhileDestinationExistsIsConflict() {
        given()
            .contentType(CT)
            .header("X-Amz-Target", "AWSEvents.DeleteConnection")
            .body("{\"Name\":\"eb-conn-test\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ConcurrentModificationException"));
    }

    @Test
    @Order(5)
    void deleteDestinationThenConnection() {
        given()
            .contentType(CT)
            .header("X-Amz-Target", "AWSEvents.DeleteApiDestination")
            .body("{\"Name\":\"eb-dest-test\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType(CT)
            .header("X-Amz-Target", "AWSEvents.DescribeApiDestination")
            .body("{\"Name\":\"eb-dest-test\"}")
        .when()
            .post("/")
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));

        given()
            .contentType(CT)
            .header("X-Amz-Target", "AWSEvents.DeleteConnection")
            .body("{\"Name\":\"eb-conn-test\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType(CT)
            .header("X-Amz-Target", "AWSEvents.DescribeConnection")
            .body("{\"Name\":\"eb-conn-test\"}")
        .when()
            .post("/")
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void describeMissingConnectionIsNotFound() {
        given()
            .contentType(CT)
            .header("X-Amz-Target", "AWSEvents.DescribeConnection")
            .body("{\"Name\":\"does-not-exist\"}")
        .when()
            .post("/")
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));
    }
}

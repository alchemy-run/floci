package io.github.hectorvent.floci.services.lambda;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Integration tests for tagging Lambda event source mappings:
 * CreateEventSourceMapping.Tags, plus ListTags / TagResource / UntagResource
 * on event-source-mapping ARNs.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EsmTaggingIntegrationTest {

    private static final String LAMBDA_BASE = "/2015-03-31";
    private static final String TAG_BASE = "/2017-03-31";
    private static final String FUNCTION_NAME = "esm-tagging-test-fn";
    private static final String QUEUE_NAME = "esm-tagging-test-queue";
    private static final String ACCOUNT_ID = "000000000000";
    private static final String REGION = "us-east-1";
    private static final String QUEUE_ARN =
            "arn:aws:sqs:" + REGION + ":" + ACCOUNT_ID + ":" + QUEUE_NAME;

    private static String esmUuid;
    private static String esmArn;

    @Test
    @Order(1)
    void setupSqsQueue() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateQueue")
            .formParam("QueueName", QUEUE_NAME)
            .formParam("Version", "2012-11-05")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(2)
    void setupLambdaFunction() {
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
            .statusCode(201)
            .body("FunctionName", equalTo(FUNCTION_NAME));
    }

    @Test
    @Order(3)
    void createEventSourceMappingWithTags() {
        esmUuid = given()
            .contentType("application/json")
            .body("""
                {
                    "FunctionName": "%s",
                    "EventSourceArn": "%s",
                    "Tags": {"team": "platform", "env": "dev"}
                }
                """.formatted(FUNCTION_NAME, QUEUE_ARN))
        .when()
            .post(LAMBDA_BASE + "/event-source-mappings")
        .then()
            .statusCode(202)
            .body("UUID", notNullValue())
        .extract()
            .path("UUID");

        esmArn = "arn:aws:lambda:" + REGION + ":" + ACCOUNT_ID
                + ":event-source-mapping:" + esmUuid;

        given()
        .when()
            .get(LAMBDA_BASE + "/event-source-mappings/" + esmUuid)
        .then()
            .statusCode(200)
            .body("EventSourceMappingArn", equalTo(esmArn));
    }

    @Test
    @Order(4)
    void listTagsOnEsmArnReturnsCreationTags() {
        given()
        .when()
            .get(TAG_BASE + "/tags/" + esmArn)
        .then()
            .statusCode(200)
            .body("Tags.team", equalTo("platform"))
            .body("Tags.env", equalTo("dev"));
    }

    @Test
    @Order(5)
    void tagResourceOnEsmArnMergesTags() {
        given()
            .contentType("application/json")
            .body("{\"Tags\": {\"owner\": \"alchemy\", \"env\": \"prod\"}}")
        .when()
            .post(TAG_BASE + "/tags/" + esmArn)
        .then()
            .statusCode(204);

        given()
        .when()
            .get(TAG_BASE + "/tags/" + esmArn)
        .then()
            .statusCode(200)
            .body("Tags.team", equalTo("platform"))
            .body("Tags.owner", equalTo("alchemy"))
            .body("Tags.env", equalTo("prod"));
    }

    @Test
    @Order(6)
    void untagResourceOnEsmArnRemovesKeys() {
        given()
            .queryParam("tagKeys", "team", "owner")
        .when()
            .delete(TAG_BASE + "/tags/" + esmArn)
        .then()
            .statusCode(204);

        given()
        .when()
            .get(TAG_BASE + "/tags/" + esmArn)
        .then()
            .statusCode(200)
            .body("Tags.team", nullValue())
            .body("Tags.owner", nullValue())
            .body("Tags.env", equalTo("prod"));
    }

    @Test
    @Order(7)
    void listTagsOnUnknownEsmReturns404() {
        given()
        .when()
            .get(TAG_BASE + "/tags/arn:aws:lambda:" + REGION + ":" + ACCOUNT_ID
                    + ":event-source-mapping:00000000-0000-0000-0000-000000000000")
        .then()
            .statusCode(404)
            .body("__type", containsString("ResourceNotFoundException"));
    }

    @Test
    @Order(8)
    void listTagsOnUnsupportedResourceTypeReturns400() {
        given()
        .when()
            .get(TAG_BASE + "/tags/arn:aws:lambda:" + REGION + ":" + ACCOUNT_ID
                    + ":layer:my-layer")
        .then()
            .statusCode(400);
    }

    @Test
    @Order(9)
    void functionTaggingStillWorks() {
        String functionArn = "arn:aws:lambda:" + REGION + ":" + ACCOUNT_ID
                + ":function:" + FUNCTION_NAME;
        given()
            .contentType("application/json")
            .body("{\"Tags\": {\"fn\": \"yes\"}}")
        .when()
            .post(TAG_BASE + "/tags/" + functionArn)
        .then()
            .statusCode(204);

        given()
        .when()
            .get(TAG_BASE + "/tags/" + functionArn)
        .then()
            .statusCode(200)
            .body("Tags.fn", equalTo("yes"));
    }

    @Test
    @Order(10)
    void cleanupEsm() {
        given()
        .when()
            .delete(LAMBDA_BASE + "/event-source-mappings/" + esmUuid)
        .then()
            .statusCode(202);
    }
}

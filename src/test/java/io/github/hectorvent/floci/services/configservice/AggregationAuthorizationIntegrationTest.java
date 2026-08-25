package io.github.hectorvent.floci.services.configservice;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AggregationAuthorizationIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String TARGET_PREFIX = "StarlingDoveService.";
    private static final String ACCOUNT = "123456789012";
    private static final String REGION = "us-east-1";
    private static final String REPLACEMENT_REGION = "us-west-2";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void putAggregationAuthorization() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "PutAggregationAuthorization")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "AuthorizedAccountId": "%s",
                    "AuthorizedAwsRegion": "%s",
                    "Tags": [
                        {"Key": "Environment", "Value": "test"},
                        {"Key": "alchemy::id", "Value": "TestAuth"}
                    ]
                }
                """.formatted(ACCOUNT, REGION))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("AggregationAuthorization.AuthorizedAccountId", equalTo(ACCOUNT))
            .body("AggregationAuthorization.AuthorizedAwsRegion", equalTo(REGION))
            .body("AggregationAuthorization.AggregationAuthorizationArn",
                    containsString(":aggregation-authorization/"))
            .body("AggregationAuthorization.AggregationAuthorizationArn",
                    containsString(ACCOUNT + "/" + REGION))
            .body("AggregationAuthorization.CreationTime", notNullValue());
    }

    @Test
    @Order(2)
    void describeAggregationAuthorizations() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "DescribeAggregationAuthorizations")
            .contentType(CONTENT_TYPE)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("AggregationAuthorizations", hasSize(greaterThanOrEqualTo(1)))
            .body("AggregationAuthorizations.find { it.AuthorizedAccountId == '%s' && it.AuthorizedAwsRegion == '%s' }.AggregationAuthorizationArn"
                            .formatted(ACCOUNT, REGION),
                    containsString(":aggregation-authorization/"));
    }

    @Test
    @Order(3)
    void putIsIdempotentAndIgnoresTags() {
        String arn = authorizationArn(ACCOUNT, REGION);

        given()
            .header("X-Amz-Target", TARGET_PREFIX + "PutAggregationAuthorization")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "AuthorizedAccountId": "%s",
                    "AuthorizedAwsRegion": "%s",
                    "Tags": [{"Key": "Environment", "Value": "prod"}]
                }
                """.formatted(ACCOUNT, REGION))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("AggregationAuthorization.AggregationAuthorizationArn", equalTo(arn));

        given()
            .header("X-Amz-Target", TARGET_PREFIX + "ListTagsForResource")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ResourceArn": "%s"}
                """.formatted(arn))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Tags.find { it.Key == 'Environment' }.Value", equalTo("test"));
    }

    @Test
    @Order(4)
    void tagResourceUpdatesInPlace() {
        String arn = authorizationArn(ACCOUNT, REGION);
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "TagResource")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "ResourceArn": "%s",
                    "Tags": [{"Key": "Environment", "Value": "prod"}]
                }
                """.formatted(arn))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", TARGET_PREFIX + "ListTagsForResource")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ResourceArn": "%s"}
                """.formatted(arn))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Tags.find { it.Key == 'Environment' }.Value", equalTo("prod"))
            .body("Tags.find { it.Key == 'alchemy::id' }.Value", equalTo("TestAuth"));
    }

    @Test
    @Order(5)
    void putReplacementRegionCreatesNewAuthorization() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "PutAggregationAuthorization")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "AuthorizedAccountId": "%s",
                    "AuthorizedAwsRegion": "%s",
                    "Tags": [{"Key": "Environment", "Value": "prod"}]
                }
                """.formatted(ACCOUNT, REPLACEMENT_REGION))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("AggregationAuthorization.AuthorizedAwsRegion", equalTo(REPLACEMENT_REGION))
            .body("AggregationAuthorization.AggregationAuthorizationArn",
                    containsString(ACCOUNT + "/" + REPLACEMENT_REGION));
    }

    @Test
    @Order(6)
    void deleteAggregationAuthorizationIsIdempotent() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "DeleteAggregationAuthorization")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "AuthorizedAccountId": "%s",
                    "AuthorizedAwsRegion": "%s"
                }
                """.formatted(ACCOUNT, REGION))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", TARGET_PREFIX + "DeleteAggregationAuthorization")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "AuthorizedAccountId": "%s",
                    "AuthorizedAwsRegion": "%s"
                }
                """.formatted(ACCOUNT, REGION))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", TARGET_PREFIX + "DescribeAggregationAuthorizations")
            .contentType(CONTENT_TYPE)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("AggregationAuthorizations.find { it.AuthorizedAccountId == '%s' && it.AuthorizedAwsRegion == '%s' }"
                            .formatted(ACCOUNT, REGION),
                    nullValue());
    }

    @Test
    @Order(7)
    void deleteReplacementAndMissingParams() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "DeleteAggregationAuthorization")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "AuthorizedAccountId": "%s",
                    "AuthorizedAwsRegion": "%s"
                }
                """.formatted(ACCOUNT, REPLACEMENT_REGION))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", TARGET_PREFIX + "PutAggregationAuthorization")
            .contentType(CONTENT_TYPE)
            .body("""
                {"AuthorizedAwsRegion": "us-east-1"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParameterValueException"));
    }

    private String authorizationArn(String accountId, String authorizedRegion) {
        return given()
            .header("X-Amz-Target", TARGET_PREFIX + "DescribeAggregationAuthorizations")
            .contentType(CONTENT_TYPE)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract()
            .path("AggregationAuthorizations.find { it.AuthorizedAccountId == '%s' && it.AuthorizedAwsRegion == '%s' }.AggregationAuthorizationArn"
                    .formatted(accountId, authorizedRegion));
    }
}

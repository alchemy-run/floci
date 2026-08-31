package io.github.hectorvent.floci.services.shield;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * JSON 1.1 coverage for the Alchemy Shield bindings suite: subscription
 * state and attack statistics succeed without Shield Advanced; gated
 * operations return the live typed tags; a missing protection group is
 * {@code ResourceNotFoundException}.
 */
@QuarkusTest
class ShieldBindingsIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/shield/aws4_request";
    private static final String MISSING_GROUP = "alchemy-shield-bindings-nonexistent";
    private static final String MISSING_ATTACK = "00000000-0000-0000-0000-000000000000";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void reset() {
        shield("DeleteSubscription", "{}");
    }

    @Test
    void getSubscriptionState_withoutSubscription_returnsInactive() {
        shield("GetSubscriptionState", "{}")
                .then()
                .statusCode(200)
                .body("SubscriptionState", equalTo("INACTIVE"));
    }

    @Test
    void describeAttackStatistics_returnsEmptyDataItems() {
        shield("DescribeAttackStatistics", "{}")
                .then()
                .statusCode(200)
                .body("DataItems", hasSize(0))
                .body("TimeRange.FromInclusive", notNullValue())
                .body("TimeRange.ToExclusive", notNullValue());
    }

    @Test
    void listAttacks_withoutSubscription_returnsInvalidOperation() {
        shield("ListAttacks", "{}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidOperationException"));
    }

    @Test
    void describeAttack_unknownId_returnsEmptyDocument() {
        shield("DescribeAttack", "{\"AttackId\":\"" + MISSING_ATTACK + "\"}")
                .then()
                .statusCode(200)
                .body("Attack", nullValue());
    }

    @Test
    void describeDRTAccess_withoutGrant_returnsResourceNotFound() {
        shield("DescribeDRTAccess", "{}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void listResourcesInProtectionGroup_missingGroup_returnsResourceNotFound() {
        shield("ListResourcesInProtectionGroup",
                "{\"ProtectionGroupId\":\"" + MISSING_GROUP + "\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void listResourcesInProtectionGroup_missingId_returnsInvalidParameter() {
        shield("ListResourcesInProtectionGroup", "{}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidParameterException"));
    }

    @Test
    void listAttacks_afterSubscribe_returnsEmptySummaries() {
        shield("CreateSubscription", "{}")
                .then()
                .statusCode(200);
        shield("GetSubscriptionState", "{}")
                .then()
                .statusCode(200)
                .body("SubscriptionState", equalTo("ACTIVE"));
        shield("ListAttacks", "{}")
                .then()
                .statusCode(200)
                .body("AttackSummaries", hasSize(0));
    }

    private static Response shield(String action, String body) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AWSShield_20160616." + action)
                .header("Authorization", AUTH_HEADER)
                .body(body)
                .when()
                .post("/");
    }
}

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
import static org.hamcrest.Matchers.startsWith;

/**
 * JSON 1.1 coverage for Alchemy {@code Shield.test.ts}: unsubscribed probes
 * plus a subscribed protection / protection-group round-trip.
 */
@QuarkusTest
class ShieldIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/shield/aws4_request";
    private static final String MISSING_PROTECTION = "00000000-0000-0000-0000-000000000000";
    private static final String MISSING_GROUP = "alchemy-nonexistent-probe";
    private static final String RESOURCE_ARN =
            "arn:aws:ec2:us-east-1:000000000000:eip-allocation/eipalloc-test";

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
    void describeSubscription_withoutSubscription_returnsResourceNotFound() {
        shield("DescribeSubscription", "{}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("message", equalTo("The subscription does not exist."));
    }

    @Test
    void describeProtection_withoutSubscription_returnsResourceNotFound() {
        shield("DescribeProtection", "{\"ProtectionId\":\"" + MISSING_PROTECTION + "\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("message", equalTo("The subscription does not exist."));
    }

    @Test
    void listProtections_withoutSubscription_returnsResourceNotFound() {
        shield("ListProtections", "{}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("message", equalTo("The subscription does not exist."));
    }

    @Test
    void describeProtectionGroup_missingGroup_returnsResourceNotFound() {
        shield("DescribeProtectionGroup",
                "{\"ProtectionGroupId\":\"" + MISSING_GROUP + "\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void protectionAndGroup_lifecycle_whenSubscribed() {
        shield("CreateSubscription", "{}").then().statusCode(200);
        shield("GetSubscriptionState", "{}")
                .then()
                .statusCode(200)
                .body("SubscriptionState", equalTo("ACTIVE"));

        String protectionId = shield("CreateProtection",
                "{\"Name\":\"eip-protection\",\"ResourceArn\":\"" + RESOURCE_ARN + "\","
                        + "\"Tags\":[{\"Key\":\"fixture\",\"Value\":\"shield\"}]}")
                .then()
                .statusCode(200)
                .extract()
                .path("ProtectionId");

        String protectionArn = shield("DescribeProtection",
                "{\"ProtectionId\":\"" + protectionId + "\"}")
                .then()
                .statusCode(200)
                .body("Protection.ResourceArn", equalTo(RESOURCE_ARN))
                .body("Protection.HealthCheckIds", hasSize(0))
                .body("Protection.ProtectionArn", startsWith("arn:aws:shield::"))
                .extract()
                .path("Protection.ProtectionArn");

        shield("ListProtections", "{}")
                .then()
                .statusCode(200)
                .body("Protections", hasSize(1));

        shield("ListTagsForResource", "{\"ResourceARN\":\"" + protectionArn + "\"}")
                .then()
                .statusCode(200)
                .body("Tags[0].Key", equalTo("fixture"))
                .body("Tags[0].Value", equalTo("shield"));

        String groupId = "alchemy-all-group";
        shield("CreateProtectionGroup",
                "{\"ProtectionGroupId\":\"" + groupId + "\",\"Aggregation\":\"SUM\","
                        + "\"Pattern\":\"ALL\",\"Tags\":[{\"Key\":\"fixture\",\"Value\":\"shield\"}]}")
                .then()
                .statusCode(200);

        String groupArn = shield("DescribeProtectionGroup",
                "{\"ProtectionGroupId\":\"" + groupId + "\"}")
                .then()
                .statusCode(200)
                .body("ProtectionGroup.Aggregation", equalTo("SUM"))
                .body("ProtectionGroup.Pattern", equalTo("ALL"))
                .extract()
                .path("ProtectionGroup.ProtectionGroupArn");

        shield("UpdateProtectionGroup",
                "{\"ProtectionGroupId\":\"" + groupId + "\",\"Aggregation\":\"MAX\","
                        + "\"Pattern\":\"ALL\"}")
                .then()
                .statusCode(200);
        shield("DescribeProtectionGroup", "{\"ProtectionGroupId\":\"" + groupId + "\"}")
                .then()
                .statusCode(200)
                .body("ProtectionGroup.Aggregation", equalTo("MAX"))
                .body("ProtectionGroup.ProtectionGroupArn", equalTo(groupArn));

        shield("DeleteProtection", "{\"ProtectionId\":\"" + protectionId + "\"}")
                .then()
                .statusCode(200);
        shield("DescribeProtection", "{\"ProtectionId\":\"" + protectionId + "\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));

        shield("DeleteProtectionGroup", "{\"ProtectionGroupId\":\"" + groupId + "\"}")
                .then()
                .statusCode(200);
        shield("DescribeProtectionGroup", "{\"ProtectionGroupId\":\"" + groupId + "\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
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

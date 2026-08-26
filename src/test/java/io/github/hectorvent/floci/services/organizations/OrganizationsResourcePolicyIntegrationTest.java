package io.github.hectorvent.floci.services.organizations;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

/**
 * JSON 1.1 Organizations resource-policy coverage used by Alchemy
 * {@code OrganizationResourcePolicy}: typed not-found on describe, put upsert,
 * describe round-trip, delete.
 */
@QuarkusTest
class OrganizationsResourcePolicyIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/organizations/aws4_request";
    private static final String POLICY = "{\"Version\":\"2012-10-17\",\"Statement\":["
            + "{\"Effect\":\"Allow\",\"Principal\":\"*\",\"Action\":\"organizations:DescribeOrganization\",\"Resource\":\"*\"}"
            + "]}";
    private static final String UPDATED = "{\"Version\":\"2012-10-17\",\"Statement\":["
            + "{\"Effect\":\"Allow\",\"Principal\":\"*\",\"Action\":\"organizations:ListAccounts\",\"Resource\":\"*\"}"
            + "]}";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void ensureOrganization() {
        organizations("CreateOrganization", "{}");
        organizations("DeleteResourcePolicy", "{}");
    }

    @Test
    void describeResourcePolicy_whenMissing_returnsResourcePolicyNotFoundException() {
        organizations("DescribeResourcePolicy", "{}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourcePolicyNotFoundException"));
    }

    @Test
    void putDescribeUpdateAndDelete_resourcePolicy() {
        String escaped = POLICY.replace("\"", "\\\"");
        String id = organizations("PutResourcePolicy", "{\"Content\":\"" + escaped + "\"}")
                .then()
                .statusCode(200)
                .body("ResourcePolicy.ResourcePolicySummary.Id", startsWith("p-"))
                .body("ResourcePolicy.ResourcePolicySummary.Arn", startsWith("arn:aws:organizations::"))
                .body("ResourcePolicy.Content", equalTo(POLICY))
                .extract().path("ResourcePolicy.ResourcePolicySummary.Id");

        organizations("DescribeResourcePolicy", "{}")
                .then()
                .statusCode(200)
                .body("ResourcePolicy.ResourcePolicySummary.Id", equalTo(id))
                .body("ResourcePolicy.Content", equalTo(POLICY));

        String updatedEscaped = UPDATED.replace("\"", "\\\"");
        organizations("PutResourcePolicy", "{\"Content\":\"" + updatedEscaped + "\"}")
                .then()
                .statusCode(200)
                .body("ResourcePolicy.ResourcePolicySummary.Id", equalTo(id))
                .body("ResourcePolicy.Content", equalTo(UPDATED));

        organizations("DeleteResourcePolicy", "{}")
                .then()
                .statusCode(200);

        organizations("DescribeResourcePolicy", "{}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourcePolicyNotFoundException"));
    }

    @Test
    void putResourcePolicy_missingContent_returnsInvalidInput() {
        organizations("PutResourcePolicy", "{}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidInputException"));
    }

    @Test
    void deleteResourcePolicy_whenMissing_returnsResourcePolicyNotFoundException() {
        organizations("DeleteResourcePolicy", "{}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourcePolicyNotFoundException"));
    }

    private static Response organizations(String action, String body) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AWSOrganizationsV20161128." + action)
                .header("Authorization", AUTH_HEADER)
                .body(body)
                .when()
                .post("/");
    }
}

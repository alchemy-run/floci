package io.github.hectorvent.floci.services.organizations;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

/**
 * JSON 1.1 Organizations coverage used by Alchemy's singleton
 * {@code Organization} resource: {@code DescribeOrganization} is the list/read
 * path. A default organization is seeded so local reconcilers observe a
 * management-account singleton.
 */
@QuarkusTest
class OrganizationsIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/organizations/aws4_request";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void describeOrganization_returnsSeededSingleton() {
        orgs("DescribeOrganization", "{}")
                .then()
                .statusCode(200)
                .body("Organization.Id", notNullValue())
                .body("Organization.Arn", startsWith("arn:aws:organizations::"))
                .body("Organization.AvailablePolicyTypes", hasSize(1))
                .body("Organization.AvailablePolicyTypes[0].Type", equalTo("SERVICE_CONTROL_POLICY"));
    }

    @Test
    void createOrganization_whenPresent_returnsAlreadyInOrganizationException() {
        orgs("DescribeOrganization", "{}").then().statusCode(200);
        orgs("CreateOrganization", "{}")
                .then()
                .statusCode(409)
                .body("__type", equalTo("AlreadyInOrganizationException"));
    }

    @Test
    void deleteThenCreateOrganization_roundTrip() {
        orgs("DeleteOrganization", "{}").then().statusCode(200);
        orgs("DescribeOrganization", "{}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("AWSOrganizationsNotInUseException"));

        orgs("CreateOrganization", "{\"FeatureSet\":\"ALL\"}")
                .then()
                .statusCode(200)
                .body("Organization.Id", notNullValue())
                .body("Organization.Arn", startsWith("arn:aws:organizations::"))
                .body("Organization.FeatureSet", equalTo("ALL"))
                .body("Organization.AvailablePolicyTypes", hasSize(1));
    }

    @Test
    void enableAllFeatures_upgradesConsolidatedBilling() {
        orgs("DeleteOrganization", "{}");
        orgs("CreateOrganization", "{\"FeatureSet\":\"CONSOLIDATED_BILLING\"}")
                .then()
                .statusCode(200)
                .body("Organization.FeatureSet", equalTo("CONSOLIDATED_BILLING"))
                .body("Organization.AvailablePolicyTypes", hasSize(0));

        orgs("EnableAllFeatures", "{}")
                .then()
                .statusCode(200);

        orgs("DescribeOrganization", "{}")
                .then()
                .statusCode(200)
                .body("Organization.FeatureSet", equalTo("ALL"))
                .body("Organization.AvailablePolicyTypes[0].Type", equalTo("SERVICE_CONTROL_POLICY"));
    }

    private static Response orgs(String action, String body) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AWSOrganizationsV20161128." + action)
                .header("Authorization", AUTH_HEADER)
                .body(body)
                .when()
                .post("/");
    }
}

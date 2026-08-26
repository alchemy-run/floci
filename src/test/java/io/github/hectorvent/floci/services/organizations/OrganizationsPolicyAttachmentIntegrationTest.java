package io.github.hectorvent.floci.services.organizations;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;

/**
 * JSON 1.1 Organizations policy-attachment coverage used by Alchemy
 * {@code PolicyAttachment.list()}: {@code ListPolicies} plus
 * {@code ListTargetsForPolicy} / attach / detach.
 */
@QuarkusTest
class OrganizationsPolicyAttachmentIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/organizations/aws4_request";
    private static final String ALLOW_ALL = "{\"Version\":\"2012-10-17\",\"Statement\":["
            + "{\"Effect\":\"Allow\",\"Action\":\"*\",\"Resource\":\"*\"}]}";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void listPolicies_missingFilter_returnsInvalidInput() {
        ensureOrganization();
        orgs("ListPolicies", "{}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidInputException"));
    }

    @Test
    void listPolicies_andListTargetsForPolicy_includeFullAwsAccessOnRoot() {
        ensureOrganization();

        orgs("ListPolicies", "{\"Filter\":\"SERVICE_CONTROL_POLICY\"}")
                .then()
                .statusCode(200)
                .body("Policies.Id", hasItem("p-FullAWSAccess"))
                .body("Policies.Name", hasItem("FullAWSAccess"));

        orgs("ListTargetsForPolicy", "{\"PolicyId\":\"p-FullAWSAccess\"}")
                .then()
                .statusCode(200)
                .body("Targets.TargetId", hasItem(OrganizationsService.DEFAULT_ROOT_ID))
                .body("Targets.Type", hasItem("ROOT"))
                .body("Targets.Arn", hasItem(startsWith("arn:aws:organizations::")));
    }

    @Test
    void listTargetsForPolicy_unknownPolicy_returnsPolicyNotFound() {
        ensureOrganization();
        orgs("ListTargetsForPolicy", "{\"PolicyId\":\"p-doesnotexist\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("PolicyNotFoundException"));
    }

    @Test
    void attachListDetachPolicy_roundTrip() {
        ensureOrganization();
        String escaped = ALLOW_ALL.replace("\"", "\\\"");
        String policyId = orgs("CreatePolicy", "{"
                + "\"Name\":\"alchemy-attachment-scp\","
                + "\"Type\":\"SERVICE_CONTROL_POLICY\","
                + "\"Description\":\"attachment coverage\","
                + "\"Content\":\"" + escaped + "\""
                + "}")
                .then()
                .statusCode(200)
                .body("Policy.PolicySummary.Id", startsWith("p-"))
                .extract().path("Policy.PolicySummary.Id");

        orgs("AttachPolicy", "{\"PolicyId\":\"" + policyId + "\",\"TargetId\":\""
                + OrganizationsService.DEFAULT_ROOT_ID + "\"}")
                .then()
                .statusCode(200);

        orgs("ListTargetsForPolicy", "{\"PolicyId\":\"" + policyId + "\"}")
                .then()
                .statusCode(200)
                .body("Targets.TargetId", hasItem(OrganizationsService.DEFAULT_ROOT_ID));

        orgs("AttachPolicy", "{\"PolicyId\":\"" + policyId + "\",\"TargetId\":\""
                + OrganizationsService.DEFAULT_ROOT_ID + "\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("DuplicatePolicyAttachmentException"));

        orgs("DetachPolicy", "{\"PolicyId\":\"" + policyId + "\",\"TargetId\":\""
                + OrganizationsService.DEFAULT_ROOT_ID + "\"}")
                .then()
                .statusCode(200);

        orgs("DetachPolicy", "{\"PolicyId\":\"" + policyId + "\",\"TargetId\":\""
                + OrganizationsService.DEFAULT_ROOT_ID + "\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("PolicyNotAttachedException"));

        orgs("DeletePolicy", "{\"PolicyId\":\"" + policyId + "\"}")
                .then()
                .statusCode(200);
    }

    private static void ensureOrganization() {
        Response created = orgs("CreateOrganization", "{\"FeatureSet\":\"ALL\"}");
        int status = created.statusCode();
        if (status != 200 && status != 409) {
            created.then().statusCode(200);
        }
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

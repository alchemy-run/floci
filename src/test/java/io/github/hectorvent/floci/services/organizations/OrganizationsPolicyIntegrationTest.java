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
 * JSON 1.1 Organizations policy coverage used by Alchemy {@code Policy}:
 * {@code ListPolicies} fans out per type and hydrates via {@code DescribePolicy}
 * plus tag APIs; create / update / delete round-trip a customer SCP.
 */
@QuarkusTest
class OrganizationsPolicyIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/organizations/aws4_request";
    private static final String SCP = "{\"Version\":\"2012-10-17\",\"Statement\":["
            + "{\"Sid\":\"AllowAll\",\"Effect\":\"Allow\",\"Action\":\"*\",\"Resource\":\"*\"}]}";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void listPolicies_missingFilter_invalidInput() {
        ensureOrganization();
        orgs("ListPolicies", "{}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidInputException"));
    }

    @Test
    void listAndDescribe_fullAwsAccessScp() {
        ensureOrganization();
        orgs("ListPolicies", "{\"Filter\":\"SERVICE_CONTROL_POLICY\"}")
                .then()
                .statusCode(200)
                .body("Policies.Id", hasItem("p-FullAWSAccess"))
                .body("Policies.find { it.Id == 'p-FullAWSAccess' }.Name", equalTo("FullAWSAccess"))
                .body("Policies.find { it.Id == 'p-FullAWSAccess' }.Type", equalTo("SERVICE_CONTROL_POLICY"))
                .body("Policies.find { it.Id == 'p-FullAWSAccess' }.AwsManaged", equalTo(true))
                .body("Policies.find { it.Id == 'p-FullAWSAccess' }.Arn",
                        startsWith("arn:aws:organizations::"));

        orgs("DescribePolicy", "{\"PolicyId\":\"p-FullAWSAccess\"}")
                .then()
                .statusCode(200)
                .body("Policy.PolicySummary.Id", equalTo("p-FullAWSAccess"))
                .body("Policy.PolicySummary.Name", equalTo("FullAWSAccess"))
                .body("Policy.Content", equalTo(
                        "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":\"*\",\"Resource\":\"*\"}]}"
                ));

        orgs("ListTagsForResource", "{\"ResourceId\":\"p-FullAWSAccess\"}")
                .then()
                .statusCode(200);
    }

    @Test
    void describePolicy_unknown_policyNotFound() {
        ensureOrganization();
        orgs("DescribePolicy", "{\"PolicyId\":\"p-missing0\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("PolicyNotFoundException"));
    }

    @Test
    void createDescribeTagUpdateAndDelete_customerScp() {
        ensureOrganization();
        String name = "floci-org-policy-" + System.nanoTime();
        String escaped = SCP.replace("\"", "\\\"");
        Response created = orgs("CreatePolicy", """
                {
                  "Name": "%s",
                  "Description": "floci policy test",
                  "Type": "SERVICE_CONTROL_POLICY",
                  "Content": "%s",
                  "Tags": [{"Key": "fixture", "Value": "org-policy"}]
                }
                """.formatted(name, escaped));
        created.then()
                .statusCode(200)
                .body("Policy.PolicySummary.Name", equalTo(name))
                .body("Policy.PolicySummary.Type", equalTo("SERVICE_CONTROL_POLICY"))
                .body("Policy.PolicySummary.AwsManaged", equalTo(false))
                .body("Policy.PolicySummary.Arn", startsWith("arn:aws:organizations::"))
                .body("Policy.Content", equalTo(SCP));
        String policyId = created.jsonPath().getString("Policy.PolicySummary.Id");

        orgs("DescribePolicy", "{\"PolicyId\":\"" + policyId + "\"}")
                .then()
                .statusCode(200)
                .body("Policy.PolicySummary.Id", equalTo(policyId))
                .body("Policy.Content", equalTo(SCP));

        orgs("ListTagsForResource", "{\"ResourceId\":\"" + policyId + "\"}")
                .then()
                .statusCode(200)
                .body("Tags.find { it.Key == 'fixture' }.Value", equalTo("org-policy"));

        orgs("TagResource",
                "{\"ResourceId\":\"" + policyId + "\",\"Tags\":[{\"Key\":\"wave\",\"Value\":\"policy\"}]}")
                .then()
                .statusCode(200);
        orgs("ListTagsForResource", "{\"ResourceId\":\"" + policyId + "\"}")
                .then()
                .statusCode(200)
                .body("Tags.find { it.Key == 'wave' }.Value", equalTo("policy"));

        String updated = "{\"Version\":\"2012-10-17\",\"Statement\":["
                + "{\"Sid\":\"DenyLeave\",\"Effect\":\"Deny\",\"Action\":\"organizations:LeaveOrganization\",\"Resource\":\"*\"}]}";
        orgs("UpdatePolicy", "{\"PolicyId\":\"" + policyId + "\",\"Name\":\"" + name
                + "\",\"Description\":\"updated\",\"Content\":\"" + updated.replace("\"", "\\\"") + "\"}")
                .then()
                .statusCode(200)
                .body("Policy.PolicySummary.Description", equalTo("updated"))
                .body("Policy.Content", equalTo(updated));

        orgs("DeletePolicy", "{\"PolicyId\":\"" + policyId + "\"}")
                .then()
                .statusCode(200);
        orgs("DescribePolicy", "{\"PolicyId\":\"" + policyId + "\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("PolicyNotFoundException"));
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
                .header("X-Amz-Target", OrganizationsService.TARGET_PREFIX + action)
                .header("Authorization", AUTH)
                .body(body)
                .when()
                .post("/");
    }
}

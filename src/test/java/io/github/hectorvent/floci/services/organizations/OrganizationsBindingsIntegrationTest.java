package io.github.hectorvent.floci.services.organizations;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.matchesPattern;

/**
 * Binding-path Organizations JSON 1.1: standing org reads, FullAWSAccess SCP,
 * typed not-found for handshake / create-account status, invalid invite target.
 */
@QuarkusTest
class OrganizationsBindingsIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/organizations/aws4_request";
    private static final String TARGET = OrganizationsService.TARGET_PREFIX;
    private static final String MISSING_HANDSHAKE = "h-abcd1234efgh5678";
    private static final String MISSING_CAR = "car-abcd1234abcd1234abcd1234abcd1234";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void describeOrganization_returnsSeededOrg() {
        invoke("DescribeOrganization", "{}")
                .then()
                .statusCode(200)
                .body("Organization.Id", matchesPattern("^o-.*"))
                .body("Organization.MasterAccountId", matchesPattern("^\\d{12}$"));
    }

    @Test
    void listRootsAccountsAndPolicies_coverBindingReads() {
        String rootId = invoke("ListRoots", "{}")
                .then()
                .statusCode(200)
                .body("Roots.size()", greaterThanOrEqualTo(1))
                .body("Roots[0].Id", matchesPattern("^r-.*"))
                .extract().path("Roots[0].Id");

        invoke("ListAccounts", "{}")
                .then()
                .statusCode(200)
                .body("Accounts.size()", greaterThanOrEqualTo(1));

        invoke("ListAccountsForParent", "{\"ParentId\":\"" + rootId + "\"}")
                .then()
                .statusCode(200)
                .body("Accounts.size()", greaterThanOrEqualTo(1));

        invoke("ListChildren", "{\"ParentId\":\"" + rootId + "\",\"ChildType\":\"ACCOUNT\"}")
                .then()
                .statusCode(200)
                .body("Children.size()", greaterThanOrEqualTo(1));

        invoke("ListPolicies", "{\"Filter\":\"SERVICE_CONTROL_POLICY\"}")
                .then()
                .statusCode(200)
                .body("Policies.Id", hasItem("p-FullAWSAccess"));

        invoke("ListPoliciesForTarget",
                "{\"TargetId\":\"" + rootId + "\",\"Filter\":\"SERVICE_CONTROL_POLICY\"}")
                .then()
                .statusCode(200)
                .body("Policies.Id", hasItem("p-FullAWSAccess"));

        invoke("ListTargetsForPolicy", "{\"PolicyId\":\"p-FullAWSAccess\"}")
                .then()
                .statusCode(200)
                .body("Targets.TargetId", hasItem(rootId));
    }

    @Test
    void handshakeAndInvite_typedErrors() {
        invoke("ListHandshakesForAccount", "{}")
                .then()
                .statusCode(200)
                .body("Handshakes.size()", greaterThanOrEqualTo(0));

        invoke("DescribeHandshake", "{\"HandshakeId\":\"" + MISSING_HANDSHAKE + "\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("HandshakeNotFoundException"));

        invoke("DeclineHandshake", "{\"HandshakeId\":\"" + MISSING_HANDSHAKE + "\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("HandshakeNotFoundException"));

        invoke("CancelHandshake", "{\"HandshakeId\":\"" + MISSING_HANDSHAKE + "\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("HandshakeNotFoundException"));

        invoke("InviteAccountToOrganization",
                "{\"Target\":{\"Id\":\"not-a-valid-account-id\",\"Type\":\"ACCOUNT\"}}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidInputException"));
    }

    @Test
    void createAccountStatusAndEffectivePolicy_typedErrors() {
        invoke("DescribeCreateAccountStatus",
                "{\"CreateAccountRequestId\":\"" + MISSING_CAR + "\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("CreateAccountStatusNotFoundException"));

        invoke("DescribeEffectivePolicy", "{\"PolicyType\":\"TAG_POLICY\"}")
                .then()
                .statusCode(409)
                .body("__type", equalTo("ConstraintViolationException"));
    }

    private static Response invoke(String action, String body) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + action)
                .header("Authorization", AUTH)
                .body(body)
                .when()
                .post("/");
    }
}

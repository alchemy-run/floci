package io.github.hectorvent.floci.services.fms;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;

/**
 * JSON 1.1 coverage for the Alchemy FMS bindings suite: list/get operations
 * return the live typed not-an-admin rejection when no default admin is
 * designated, and empty collections once one is associated.
 */
@QuarkusTest
class FmsBindingsIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/fms/aws4_request";
    private static final String ACCOUNT = "000000000000";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void resetAdmin() {
        fms("DisassociateAdminAccount", "{}");
    }

    @Test
    void listAdminsManagingAccount_withNoAdmin_returnsResourceNotFound() {
        fms("ListAdminsManagingAccount", "{}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void listAdminAccountsForOrganization_withNoAdmin_returnsInvalidOperation() {
        fms("ListAdminAccountsForOrganization", "{}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidOperationException"));
    }

    @Test
    void adminScopedLists_withNoAdmin_returnAccessDenied() {
        fms("ListPolicies", "{}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("AccessDeniedException"));
        fms("ListResourceSets", "{}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("AccessDeniedException"));
        fms("ListMemberAccounts", "{}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("AccessDeniedException"));
        fms("GetNotificationChannel", "{}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("AccessDeniedException"));
        fms("ListAppsLists", "{\"MaxResults\":10}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("AccessDeniedException"));
        fms("ListProtocolsLists", "{\"MaxResults\":10}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("AccessDeniedException"));
    }

    @Test
    void getThirdPartyFirewallAssociationStatus_missingVendor_returnsInvalidInput() {
        fms("GetThirdPartyFirewallAssociationStatus", "{}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidInputException"));
    }

    @Test
    void getThirdPartyFirewallAssociationStatus_withNoAdmin_returnsAccessDenied() {
        fms("GetThirdPartyFirewallAssociationStatus",
                "{\"ThirdPartyFirewall\":\"PALO_ALTO_NETWORKS_CLOUD_NGFW\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("AccessDeniedException"));
    }

    @Test
    void bindingReads_afterAssociate_returnEmptyCollections() {
        fms("AssociateAdminAccount", "{\"AdminAccount\":\"" + ACCOUNT + "\"}")
                .then()
                .statusCode(200);

        fms("ListAdminsManagingAccount", "{}")
                .then()
                .statusCode(200)
                .body("AdminAccounts", hasItem(ACCOUNT));

        fms("ListAdminAccountsForOrganization", "{}")
                .then()
                .statusCode(200)
                .body("AdminAccounts", hasSize(1))
                .body("AdminAccounts[0].AdminAccount", equalTo(ACCOUNT));

        fms("ListPolicies", "{}")
                .then()
                .statusCode(200)
                .body("PolicyList", hasSize(0));

        fms("ListResourceSets", "{}")
                .then()
                .statusCode(200)
                .body("ResourceSets", hasSize(0));

        fms("ListMemberAccounts", "{}")
                .then()
                .statusCode(200)
                .body("MemberAccounts", hasItem(ACCOUNT));

        fms("GetNotificationChannel", "{}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));

        fms("ListAppsLists", "{\"MaxResults\":10}")
                .then()
                .statusCode(200)
                .body("AppsLists", hasSize(0));

        fms("ListProtocolsLists", "{\"MaxResults\":10}")
                .then()
                .statusCode(200)
                .body("ProtocolsLists", hasSize(0));

        fms("GetThirdPartyFirewallAssociationStatus",
                "{\"ThirdPartyFirewall\":\"PALO_ALTO_NETWORKS_CLOUD_NGFW\"}")
                .then()
                .statusCode(200)
                .body("ThirdPartyFirewallStatus", equalTo("NOT_EXIST"))
                .body("MarketplaceOnboardingStatus", equalTo("NO_SUBSCRIPTION"));
    }

    private static Response fms(String action, String body) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AWSFMS_20180101." + action)
                .header("Authorization", AUTH_HEADER)
                .body(body)
                .when()
                .post("/");
    }
}

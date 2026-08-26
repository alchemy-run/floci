package io.github.hectorvent.floci.services.organizations;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

/**
 * JSON 1.1 Organizations account coverage used by Alchemy:
 * {@code ListAccounts} enumerates the seeded management account, then
 * {@code DescribeAccount} / {@code ListParents} / {@code ListTagsForResource}
 * hydrate the {@code Account.list()} Attributes shape.
 */
@QuarkusTest
class OrganizationsAccountIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/organizations/aws4_request";
    private static final String ACCOUNT = "000000000000";
    private static final String ORG_ID = OrganizationsService.DEFAULT_ORG_ID;
    private static final String ROOT_ID = OrganizationsService.DEFAULT_ROOT_ID;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void listAccounts_returnsSeededManagementAccount() {
        orgs("ListAccounts", "{}")
                .then()
                .statusCode(200)
                .body("Accounts.Id", hasItem(ACCOUNT))
                .body("Accounts.find { it.Id == '" + ACCOUNT + "' }.Arn",
                        equalTo("arn:aws:organizations::" + ACCOUNT + ":account/" + ORG_ID + "/" + ACCOUNT))
                .body("Accounts.find { it.Id == '" + ACCOUNT + "' }.Email",
                        equalTo(OrganizationsService.DEFAULT_MASTER_EMAIL))
                .body("Accounts.find { it.Id == '" + ACCOUNT + "' }.Name",
                        equalTo(OrganizationsService.DEFAULT_MASTER_NAME))
                .body("Accounts.find { it.Id == '" + ACCOUNT + "' }.Status", equalTo("ACTIVE"))
                .body("Accounts.find { it.Id == '" + ACCOUNT + "' }.JoinedMethod", equalTo("CREATED"));
    }

    @Test
    void describeAccount_listParents_andTags_hydrateList() {
        orgs("DescribeAccount", "{\"AccountId\":\"" + ACCOUNT + "\"}")
                .then()
                .statusCode(200)
                .body("Account.Id", equalTo(ACCOUNT))
                .body("Account.Arn", startsWith("arn:aws:organizations::"))
                .body("Account.JoinedTimestamp", notNullValue());

        orgs("ListParents", "{\"ChildId\":\"" + ACCOUNT + "\"}")
                .then()
                .statusCode(200)
                .body("Parents", hasSize(1))
                .body("Parents[0].Id", equalTo(ROOT_ID))
                .body("Parents[0].Type", equalTo("ROOT"));

        orgs("ListTagsForResource", "{\"ResourceId\":\"" + ACCOUNT + "\"}")
                .then()
                .statusCode(200)
                .body("Tags", hasSize(0));
    }

    @Test
    void createAccount_thenListIncludesMember() {
        String email = "member-" + System.nanoTime() + "@floci.local";
        Response created = orgs("CreateAccount",
                "{\"Email\":\"" + email + "\",\"AccountName\":\"member\"}");
        created.then()
                .statusCode(200)
                .body("CreateAccountStatus.State", equalTo("SUCCEEDED"))
                .body("CreateAccountStatus.AccountId", notNullValue());
        String memberId = created.path("CreateAccountStatus.AccountId");
        String requestId = created.path("CreateAccountStatus.Id");

        orgs("DescribeCreateAccountStatus",
                "{\"CreateAccountRequestId\":\"" + requestId + "\"}")
                .then()
                .statusCode(200)
                .body("CreateAccountStatus.State", equalTo("SUCCEEDED"))
                .body("CreateAccountStatus.AccountId", equalTo(memberId));

        orgs("ListAccounts", "{}")
                .then()
                .statusCode(200)
                .body("Accounts.Id", hasItem(memberId));

        orgs("DescribeAccount", "{\"AccountId\":\"" + memberId + "\"}")
                .then()
                .statusCode(200)
                .body("Account.Email", equalTo(email))
                .body("Account.Name", equalTo("member"))
                .body("Account.JoinedMethod", equalTo("CREATED"));

        orgs("ListParents", "{\"ChildId\":\"" + memberId + "\"}")
                .then()
                .statusCode(200)
                .body("Parents[0].Id", equalTo(ROOT_ID));

        orgs("RemoveAccountFromOrganization", "{\"AccountId\":\"" + memberId + "\"}")
                .then()
                .statusCode(200);

        orgs("DescribeAccount", "{\"AccountId\":\"" + memberId + "\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("AccountNotFoundException"));
    }

    @Test
    void describeAccount_unknown_returnsAccountNotFound() {
        orgs("DescribeAccount", "{\"AccountId\":\"999999999999\"}")
                .then()
                .statusCode(404)
                .body("__type", equalTo("AccountNotFoundException"));
    }

    private static Response orgs(String action, String body) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", OrganizationsService.TARGET_PREFIX + action)
                .header("Authorization", AUTH_HEADER)
                .body(body)
                .when()
                .post("/");
    }
}

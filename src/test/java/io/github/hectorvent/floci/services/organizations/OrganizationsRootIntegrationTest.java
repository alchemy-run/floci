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
import static org.hamcrest.Matchers.not;

/**
 * JSON 1.1 Organizations coverage used by Alchemy {@code Root.list()}:
 * {@code ListRoots} returns the seeded organization root, and
 * {@code ListTagsForResource} hydrates tags on that root id (not TargetNotFound).
 */
@QuarkusTest
class OrganizationsRootIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/organizations/aws4_request";
    private static final String ROOT_ID = OrganizationsService.DEFAULT_ROOT_ID;
    private static final String ORG_ID = OrganizationsService.DEFAULT_ORG_ID;
    private static final String ACCOUNT = "000000000000";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void listRoots_returnsSeededRoot() {
        orgs("ListRoots", "{}")
                .then()
                .statusCode(200)
                .body("Roots", hasSize(1))
                .body("Roots[0].Id", equalTo(ROOT_ID))
                .body("Roots[0].Arn", equalTo(
                        "arn:aws:organizations::" + ACCOUNT + ":root/" + ORG_ID + "/" + ROOT_ID))
                .body("Roots[0].Name", equalTo("Root"))
                .body("Roots[0].PolicyTypes", hasSize(1))
                .body("Roots[0].PolicyTypes[0].Type", equalTo("SERVICE_CONTROL_POLICY"))
                .body("Roots[0].PolicyTypes[0].Status", equalTo("ENABLED"));
    }

    @Test
    void listTagsForResource_onRoot_roundTrips() {
        orgs("ListTagsForResource", "{\"ResourceId\":\"" + ROOT_ID + "\"}")
                .then()
                .statusCode(200)
                .body("Tags", not(equalTo(null)));

        orgs("TagResource", "{"
                + "\"ResourceId\":\"" + ROOT_ID + "\","
                + "\"Tags\":[{\"Key\":\"env\",\"Value\":\"floci\"}]"
                + "}")
                .then()
                .statusCode(200);

        orgs("ListTagsForResource", "{\"ResourceId\":\"" + ROOT_ID + "\"}")
                .then()
                .statusCode(200)
                .body("Tags.Key", hasItem("env"));

        orgs("UntagResource", "{"
                + "\"ResourceId\":\"" + ROOT_ID + "\","
                + "\"TagKeys\":[\"env\"]"
                + "}")
                .then()
                .statusCode(200);

        orgs("ListTagsForResource", "{\"ResourceId\":\"" + ROOT_ID + "\"}")
                .then()
                .statusCode(200)
                .body("Tags.Key", not(hasItem("env")));
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

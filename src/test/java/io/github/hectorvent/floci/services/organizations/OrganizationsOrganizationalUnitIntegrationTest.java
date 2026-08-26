package io.github.hectorvent.floci.services.organizations;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

/**
 * JSON 1.1 Organizations coverage used by Alchemy OrganizationalUnit:
 * {@code ListRoots} plus OU create / describe / list-for-parent / list-parents /
 * tags / update / delete.
 */
@QuarkusTest
class OrganizationsOrganizationalUnitIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/organizations/aws4_request";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static void ensureOrganization() {
        orgs("CreateOrganization", "{\"FeatureSet\":\"ALL\"}");
    }

    @Test
    void listRoots_returnsDefaultRoot() {
        ensureOrganization();
        orgs("ListRoots", "{}")
                .then()
                .statusCode(200)
                .body("Roots[0].Id", equalTo(OrganizationsService.DEFAULT_ROOT_ID))
                .body("Roots[0].Arn", startsWith("arn:aws:organizations::"))
                .body("Roots[0].Name", equalTo("Root"));
    }

    @Test
    void listOrganizationalUnitsForParent_missingParent_returnsParentNotFound() {
        ensureOrganization();
        orgs("ListOrganizationalUnitsForParent", "{\"ParentId\":\"r-nope\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ParentNotFoundException"));
    }

    @Test
    void organizationalUnit_roundTripListHydrateTagsUpdateAndDelete() {
        ensureOrganization();
        String name = "ou-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        String ouId = orgs("CreateOrganizationalUnit", "{"
                + "\"ParentId\":\"" + OrganizationsService.DEFAULT_ROOT_ID + "\","
                + "\"Name\":\"" + name + "\","
                + "\"Tags\":[{\"Key\":\"purpose\",\"Value\":\"alchemy-test\"}]"
                + "}")
                .then()
                .statusCode(200)
                .body("OrganizationalUnit.Id", startsWith("ou-"))
                .body("OrganizationalUnit.Arn", startsWith("arn:aws:organizations::"))
                .body("OrganizationalUnit.Name", equalTo(name))
                .extract().path("OrganizationalUnit.Id");

        orgs("ListOrganizationalUnitsForParent",
                "{\"ParentId\":\"" + OrganizationsService.DEFAULT_ROOT_ID + "\"}")
                .then()
                .statusCode(200)
                .body("OrganizationalUnits.Id", hasItem(ouId))
                .body("OrganizationalUnits.Name", hasItem(name));

        orgs("DescribeOrganizationalUnit", "{\"OrganizationalUnitId\":\"" + ouId + "\"}")
                .then()
                .statusCode(200)
                .body("OrganizationalUnit.Id", equalTo(ouId))
                .body("OrganizationalUnit.Name", equalTo(name))
                .body("OrganizationalUnit.Arn", notNullValue());

        orgs("ListParents", "{\"ChildId\":\"" + ouId + "\"}")
                .then()
                .statusCode(200)
                .body("Parents[0].Id", equalTo(OrganizationsService.DEFAULT_ROOT_ID))
                .body("Parents[0].Type", equalTo("ROOT"));

        orgs("ListTagsForResource", "{\"ResourceId\":\"" + ouId + "\"}")
                .then()
                .statusCode(200)
                .body("Tags.Key", hasItem("purpose"));

        orgs("TagResource", "{"
                + "\"ResourceId\":\"" + ouId + "\","
                + "\"Tags\":[{\"Key\":\"alchemy::id\",\"Value\":\"TestOU\"}]"
                + "}")
                .then()
                .statusCode(200);

        orgs("ListTagsForResource", "{\"ResourceId\":\"" + ouId + "\"}")
                .then()
                .statusCode(200)
                .body("Tags.Key", hasItem("alchemy::id"));

        String renamed = name + "-renamed";
        orgs("UpdateOrganizationalUnit", "{"
                + "\"OrganizationalUnitId\":\"" + ouId + "\","
                + "\"Name\":\"" + renamed + "\""
                + "}")
                .then()
                .statusCode(200)
                .body("OrganizationalUnit.Name", equalTo(renamed));

        orgs("DeleteOrganizationalUnit", "{\"OrganizationalUnitId\":\"" + ouId + "\"}")
                .then()
                .statusCode(200);

        orgs("DescribeOrganizationalUnit", "{\"OrganizationalUnitId\":\"" + ouId + "\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("OrganizationalUnitNotFoundException"));

        orgs("ListOrganizationalUnitsForParent",
                "{\"ParentId\":\"" + OrganizationsService.DEFAULT_ROOT_ID + "\"}")
                .then()
                .statusCode(200)
                .body("OrganizationalUnits.Id", not(hasItem(ouId)));
    }

    @Test
    void createOrganizationalUnit_duplicateName_returnsDuplicateOrganizationalUnit() {
        ensureOrganization();
        String name = "dup-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String body = "{"
                + "\"ParentId\":\"" + OrganizationsService.DEFAULT_ROOT_ID + "\","
                + "\"Name\":\"" + name + "\""
                + "}";
        orgs("CreateOrganizationalUnit", body).then().statusCode(200);
        orgs("CreateOrganizationalUnit", body)
                .then()
                .statusCode(400)
                .body("__type", equalTo("DuplicateOrganizationalUnitException"));
    }

    @Test
    void describeOrganizationalUnit_missing_returnsNotFound() {
        ensureOrganization();
        orgs("DescribeOrganizationalUnit", "{\"OrganizationalUnitId\":\"ou-flci-missing1\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("OrganizationalUnitNotFoundException"));
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

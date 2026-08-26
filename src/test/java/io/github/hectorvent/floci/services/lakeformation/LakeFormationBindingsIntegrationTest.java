package io.github.hectorvent.floci.services.lakeformation;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * restJson1 Lake Formation coverage used by Alchemy Bindings.test.ts:
 * GetDataLakePrincipal, List/Get LF-tags, ListPermissions, tag search,
 * GetResourceLFTags, GetEffectivePermissionsForPath, and credential vending.
 */
@QuarkusTest
class LakeFormationBindingsIntegrationTest {

    private static final String CONTENT_TYPE = "application/json";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/lakeformation/aws4_request";
    private static final String MISSING_TAG = "alchemy-lf-bindings-missing";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getDataLakePrincipal_returnsIdentity() {
        lakeformation("GetDataLakePrincipal", "{}")
                .then()
                .statusCode(200)
                .body("Identity", notNullValue());
    }

    @Test
    void listLfTags_returnsArray() {
        lakeformation("ListLFTags", "{}")
                .then()
                .statusCode(200)
                .body("LFTags.size()", greaterThanOrEqualTo(0));
    }

    @Test
    void getLfTag_missingKey_returnsEntityNotFoundException() {
        lakeformation("GetLFTag", "{\"TagKey\":\"" + MISSING_TAG + "\"}")
                .then()
                .statusCode(400)
                .header("X-Amzn-Errortype", equalTo("EntityNotFoundException"))
                .body("__type", equalTo("EntityNotFoundException"));
        lakeformation("GetLFTag", "{\"tagKey\":\"" + MISSING_TAG + "\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("EntityNotFoundException"));
    }

    @Test
    void listPermissions_returnsArray() {
        lakeformation("ListPermissions", "{}")
                .then()
                .statusCode(200)
                .body("PrincipalResourcePermissions.size()", greaterThanOrEqualTo(0));
    }

    @Test
    void searchDatabasesByLfTags_unknownKey_returnsEmptyList() {
        lakeformation("SearchDatabasesByLFTags",
                "{\"Expression\":[{\"TagKey\":\"" + MISSING_TAG + "\",\"TagValues\":[\"x\"]}]}")
                .then()
                .statusCode(200)
                .body("DatabaseList.size()", equalTo(0));
    }

    @Test
    void searchTablesByLfTags_unknownKey_returnsEmptyList() {
        lakeformation("SearchTablesByLFTags",
                "{\"Expression\":[{\"TagKey\":\"" + MISSING_TAG + "\",\"TagValues\":[\"x\"]}]}")
                .then()
                .statusCode(200)
                .body("TableList.size()", equalTo(0));
    }

    @Test
    void getResourceLfTags_database_returnsEmptyTags() {
        lakeformation("GetResourceLFTags",
                "{\"Resource\":{\"Database\":{\"Name\":\"alchemy_lf_bindings_fixture\"}}}")
                .then()
                .statusCode(200)
                .body("LFTagOnDatabase.size()", greaterThanOrEqualTo(0));
    }

    @Test
    void getEffectivePermissionsForPath_unregistered_returnsEmptyPermissions() {
        lakeformation("GetEffectivePermissionsForPath",
                "{\"ResourceArn\":\"arn:aws:s3:::alchemy-lf-bindings-nonexistent\"}")
                .then()
                .statusCode(200)
                .body("Permissions.size()", equalTo(0));
    }

    @Test
    void getTemporaryGlueTableCredentials_missingTable_returnsEntityNotFoundException() {
        lakeformation("GetTemporaryGlueTableCredentials",
                "{\"TableArn\":\"arn:aws:glue:us-east-1:123456789012:table/missing_db/missing_table\","
                        + "\"Permissions\":[\"SELECT\"],"
                        + "\"SupportedPermissionTypes\":[\"COLUMN_PERMISSION\"]}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("EntityNotFoundException"));
    }

    @Test
    void getTemporaryGluePartitionCredentials_missingTable_returnsEntityNotFoundException() {
        lakeformation("GetTemporaryGluePartitionCredentials",
                "{\"TableArn\":\"arn:aws:glue:us-east-1:123456789012:table/missing_db/missing_table\","
                        + "\"Partition\":{\"Values\":[\"x\"]},"
                        + "\"Permissions\":[\"SELECT\"]}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("EntityNotFoundException"));
    }

    @Test
    void getTemporaryDataLocationCredentials_unregistered_returnsEntityNotFoundException() {
        lakeformation("GetTemporaryDataLocationCredentials",
                "{\"DataLocations\":[\"arn:aws:s3:::alchemy-lf-bindings-nonexistent\"],"
                        + "\"CredentialsScope\":\"READ\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("EntityNotFoundException"));
    }

    @Test
    void createGetDeleteLfTag_roundTrip() {
        lakeformation("CreateLFTag",
                "{\"TagKey\":\"alchemy-lf-bindings-roundtrip\",\"TagValues\":[\"dev\",\"prod\"]}")
                .then()
                .statusCode(200);

        lakeformation("GetLFTag", "{\"TagKey\":\"alchemy-lf-bindings-roundtrip\"}")
                .then()
                .statusCode(200)
                .body("TagKey", equalTo("alchemy-lf-bindings-roundtrip"))
                .body("TagValues.size()", equalTo(2));

        lakeformation("DeleteLFTag", "{\"TagKey\":\"alchemy-lf-bindings-roundtrip\"}")
                .then()
                .statusCode(200);

        lakeformation("GetLFTag", "{\"TagKey\":\"alchemy-lf-bindings-roundtrip\"}")
                .then()
                .statusCode(400)
                .body("__type", equalTo("EntityNotFoundException"));
    }

    private static Response lakeformation(String action, String body) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("Authorization", AUTH_HEADER)
                .body(body)
                .when()
                .post("/" + action);
    }
}

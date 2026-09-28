package io.github.hectorvent.floci.services.s3tables;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class S3TablesIntegrationTest {

    private static final String JSON_CONTENT_TYPE = "application/json";
    private static final String BUCKET_NAME = "s3tables-integration-bucket";
    private static final String NAMESPACE = "analytics";
    private static final String TABLE_NAME = "events";
    private static final String ACCOUNT_ID = "000000000000";
    private static final String REGION = "us-east-1";
    private static final String BUCKET_ARN = "arn:aws:s3tables:%s:%s:bucket/%s"
            .formatted(REGION, ACCOUNT_ID, BUCKET_NAME);
    private static final String ENCODED_BUCKET_ARN = URLEncoder.encode(BUCKET_ARN, StandardCharsets.UTF_8);

    private String versionToken;
    private String tableArn;
    private String warehouseLocation;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void createsTableBucketThroughTheS3TablesRoute() {
        given()
                .urlEncodingEnabled(false)
                .contentType(JSON_CONTENT_TYPE)
                .body("""
                        { "name": "%s" }
                        """.formatted(BUCKET_NAME))
        .when()
                .put("/buckets")
        .then()
                .statusCode(200)
                .contentType(containsString(JSON_CONTENT_TYPE))
                .body("arn", equalTo(BUCKET_ARN));
    }

    @Test
    @Order(2)
    void managesNamespaceAndTableThroughEncodedBucketArnRoutes() {
        given()
                .urlEncodingEnabled(false)
                .contentType(JSON_CONTENT_TYPE)
                .body("""
                        { "namespace": ["%s"] }
                        """.formatted(NAMESPACE))
        .when()
                .put("/namespaces/" + ENCODED_BUCKET_ARN)
        .then()
                .statusCode(200)
                .contentType(containsString(JSON_CONTENT_TYPE))
                .body("namespace", contains(NAMESPACE))
                .body("tableBucketARN", equalTo(BUCKET_ARN));

        versionToken = given()
                .urlEncodingEnabled(false)
                .contentType(JSON_CONTENT_TYPE)
                .body("""
                        {
                          "name": "%s",
                          "format": "ICEBERG",
                          "metadata": { "iceberg": { "schema": { "fields": [{ "name": "id", "type": "long" }] } } }
                        }
                        """.formatted(TABLE_NAME))
        .when()
                .put("/tables/" + ENCODED_BUCKET_ARN + "/" + NAMESPACE)
        .then()
                .statusCode(200)
                .contentType(containsString(JSON_CONTENT_TYPE))
                .body("tableARN", startsWith(BUCKET_ARN + "/table/"))
                .body("versionToken", not(""))
                .extract()
                .path("versionToken");

        tableArn = given()
                .queryParam("tableBucketARN", BUCKET_ARN)
                .queryParam("namespace", NAMESPACE)
                .queryParam("name", TABLE_NAME)
        .when()
                .get("/get-table")
        .then()
                .statusCode(200)
                .body("warehouseLocation", startsWith("s3://"))
                .body("versionToken", equalTo(versionToken))
                .body("$", not(hasKey("metadataLocation")))
                .extract().path("tableARN");
        warehouseLocation = given()
                .queryParam("tableArn", tableArn)
        .when()
                .get("/get-table")
        .then()
                .statusCode(200)
                .body("tableARN", equalTo(tableArn))
                .extract().path("warehouseLocation");
        given()
                .urlEncodingEnabled(false)
        .when()
                .get("/tables/" + ENCODED_BUCKET_ARN + "/" + NAMESPACE + "/" + TABLE_NAME + "/metadata-location")
        .then()
                .statusCode(200)
                .body("warehouseLocation", equalTo(warehouseLocation))
                .body("versionToken", equalTo(versionToken));
    }

    @Test
    @Order(3)
    void updatesMetadataLocationUsingOptimisticVersionTokens() {
        String replacementToken = given()
                .urlEncodingEnabled(false)
                .contentType(JSON_CONTENT_TYPE)
                .body("""
                        {
                          "metadataLocation": "s3://warehouse/events/metadata/v2.json",
                          "versionToken": "%s"
                        }
                        """.formatted(versionToken))
        .when()
                .put("/tables/" + ENCODED_BUCKET_ARN + "/" + NAMESPACE + "/" + TABLE_NAME + "/metadata-location")
        .then()
                .statusCode(200)
                .body("metadataLocation", equalTo("s3://warehouse/events/metadata/v2.json"))
                .body("versionToken", not(equalTo(versionToken)))
                .extract()
                .path("versionToken");

        given()
                .urlEncodingEnabled(false)
                .contentType(JSON_CONTENT_TYPE)
                .body("""
                        {
                          "metadataLocation": "s3://warehouse/events/metadata/v3.json",
                          "versionToken": "%s"
                        }
                        """.formatted(versionToken))
        .when()
                .put("/tables/" + ENCODED_BUCKET_ARN + "/" + NAMESPACE + "/" + TABLE_NAME + "/metadata-location")
        .then()
                .statusCode(409)
                .body("__type", equalTo("ConflictException"));

        versionToken = replacementToken;
    }

    @Test
    @Order(4)
    void roundTripsTableBucketPolicy() {
        given()
                .urlEncodingEnabled(false)
                .contentType(JSON_CONTENT_TYPE)
                .body("""
                        { "resourcePolicy": "{\\\"Version\\\":\\\"2012-10-17\\\"}" }
                        """)
        .when()
                .put("/buckets/" + ENCODED_BUCKET_ARN + "/policy")
        .then()
                .statusCode(200);

        given()
                .urlEncodingEnabled(false)
        .when()
                .get("/buckets/" + ENCODED_BUCKET_ARN + "/policy")
        .then()
                .statusCode(200)
                .contentType(containsString(JSON_CONTENT_TYPE))
                .body("resourcePolicy", equalTo("{\"Version\":\"2012-10-17\"}"));
    }

    @Test
    @Order(5)
    void roundTripsTypedMaintenanceConfigurationMaps() {
        given()
                .urlEncodingEnabled(false)
                .contentType(JSON_CONTENT_TYPE)
                .body("{ \"value\": { \"status\": \"enabled\" } }")
        .when()
                .put("/buckets/" + ENCODED_BUCKET_ARN + "/maintenance/icebergUnreferencedFileRemoval")
        .then()
                .statusCode(200);

        given()
                .urlEncodingEnabled(false)
        .when()
                .get("/buckets/" + ENCODED_BUCKET_ARN + "/maintenance")
        .then()
                .statusCode(200)
                .body("configuration.icebergUnreferencedFileRemoval.status", equalTo("enabled"));

        given()
                .urlEncodingEnabled(false)
                .contentType(JSON_CONTENT_TYPE)
                .body("{ \"value\": { \"status\": \"enabled\" } }")
        .when()
                .put("/tables/" + ENCODED_BUCKET_ARN + "/" + NAMESPACE + "/" + TABLE_NAME
                        + "/maintenance/icebergCompaction")
        .then()
                .statusCode(200);

        given()
                .urlEncodingEnabled(false)
        .when()
                .get("/tables/" + ENCODED_BUCKET_ARN + "/" + NAMESPACE + "/" + TABLE_NAME + "/maintenance")
        .then()
                .statusCode(200)
                .body("configuration.icebergCompaction.status", equalTo("enabled"));
    }

    @Test
    @Order(6)
    void maintenanceJobStatusReportsNoInventedRunsAndTracksDisabledConfiguration() {
        String path = "/tables/" + ENCODED_BUCKET_ARN + "/" + NAMESPACE + "/" + TABLE_NAME;
        given()
                .urlEncodingEnabled(false)
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=test/20260921/us-east-1/s3tables/aws4_request, "
                        + "SignedHeaders=host, Signature=test")
        .when()
                .get(path + "/maintenance-job-status")
        .then()
                .statusCode(200)
                .contentType(containsString(JSON_CONTENT_TYPE))
                .body("tableARN", equalTo(tableArn))
                .body("status.icebergCompaction.status", equalTo("Not_Yet_Run"))
                .body("status.icebergSnapshotManagement.status", equalTo("Not_Yet_Run"))
                .body("status.icebergUnreferencedFileRemoval.status", equalTo("Not_Yet_Run"))
                .body("status.icebergCompaction", not(hasKey("lastRunTimestamp")))
                .body("status.icebergCompaction", not(hasKey("failureMessage")));
        given()
                .urlEncodingEnabled(false)
                .contentType(JSON_CONTENT_TYPE)
                .body("{\"value\":{\"status\":\"disabled\"}}")
        .when()
                .put(path + "/maintenance/icebergCompaction")
        .then()
                .statusCode(200);
        given()
                .urlEncodingEnabled(false)
        .when()
                .get(path + "/maintenance-job-status")
        .then()
                .statusCode(200)
                .body("status.icebergCompaction.status", equalTo("Disabled"))
                .body("status.icebergCompaction", not(hasKey("lastRunTimestamp")));
        given()
                .urlEncodingEnabled(false)
        .when()
                .get("/tables/" + ENCODED_BUCKET_ARN + "/" + NAMESPACE + "/missing/maintenance-job-status")
        .then()
                .statusCode(404)
                .body("__type", equalTo("NotFoundException"));
    }

    @Test
    @Order(7)
    void tagsAndConditionalDeletionUseTheAwsWireContract() {
        String tagPath = "/tag/" + URLEncoder.encode(tableArn, StandardCharsets.UTF_8);
        given()
                .urlEncodingEnabled(false)
                .contentType(JSON_CONTENT_TYPE)
                .body("{\"tags\":{\"owner\":\"analytics\",\"remove\":\"yes\"}}")
        .when()
                .post(tagPath)
        .then()
                .statusCode(200);
        given()
                .urlEncodingEnabled(false)
        .when()
                .delete(tagPath + "?tagKeys=remove&tagKeys=absent")
        .then()
                .statusCode(204);
        given()
                .urlEncodingEnabled(false)
        .when()
                .get(tagPath)
        .then()
                .statusCode(200)
                .body("tags.owner", equalTo("analytics"))
                .body("tags", not(hasKey("remove")));

        given()
                .urlEncodingEnabled(false)
        .when()
                .delete("/buckets/" + ENCODED_BUCKET_ARN)
        .then()
                .statusCode(400)
                .body("__type", equalTo("BadRequestException"));
        given()
                .urlEncodingEnabled(false)
        .when()
                .delete("/tables/" + ENCODED_BUCKET_ARN + "/" + NAMESPACE + "/" + TABLE_NAME + "?versionToken=stale")
        .then()
                .statusCode(409)
                .body("__type", equalTo("ConflictException"));
        String currentWarehouse = given()
                .urlEncodingEnabled(false)
        .when()
                .get("/tables/" + ENCODED_BUCKET_ARN + "/" + NAMESPACE + "/" + TABLE_NAME + "/metadata-location")
        .then()
                .statusCode(200)
                .body("versionToken", equalTo(versionToken))
                .extract().path("warehouseLocation");
        assertEquals(warehouseLocation, currentWarehouse);
    }

    @Test
    @Order(8)
    void maintenanceAndTagsRejectOtherAccountsAndRegions() {
        String maintenancePath = "/tables/" + ENCODED_BUCKET_ARN + "/" + NAMESPACE + "/" + TABLE_NAME
                + "/maintenance-job-status";
        String tagPath = "/tag/" + URLEncoder.encode(tableArn, StandardCharsets.UTF_8);
        for (String scope : new String[]{"111111111111/20260921/us-east-1", "000000000000/20260921/eu-west-1"}) {
            String authorization = "AWS4-HMAC-SHA256 Credential=" + scope
                    + "/s3tables/aws4_request, SignedHeaders=host, Signature=test";
            given().urlEncodingEnabled(false).header("Authorization", authorization)
                    .when().get(maintenancePath)
                    .then().statusCode(404).body("__type", equalTo("NotFoundException"));
            given().urlEncodingEnabled(false).header("Authorization", authorization)
                    .when().get(tagPath)
                    .then().statusCode(404).body("__type", equalTo("NotFoundException"));
            given().urlEncodingEnabled(false).header("Authorization", authorization)
                    .contentType(JSON_CONTENT_TYPE).body("{\"tags\":{\"owner\":\"other\"}}")
                    .when().post(tagPath)
                    .then().statusCode(404).body("__type", equalTo("NotFoundException"));
        }
        given().urlEncodingEnabled(false).when().get(tagPath)
                .then().statusCode(200).body("tags.owner", equalTo("analytics"));
    }

    @Test
    @Order(9)
    void explicitChildDeletionAllowsParentCleanup() {
        String tablePath = "/tables/" + ENCODED_BUCKET_ARN + "/" + NAMESPACE + "/" + TABLE_NAME;
        given().urlEncodingEnabled(false).when().delete(tablePath).then().statusCode(204);
        given().urlEncodingEnabled(false).when().get(tablePath + "/maintenance-job-status")
                .then().statusCode(404).body("__type", equalTo("NotFoundException"));
        given().urlEncodingEnabled(false).when().get("/tag/" + URLEncoder.encode(tableArn, StandardCharsets.UTF_8))
                .then().statusCode(404).body("__type", equalTo("NotFoundException"));
        given().urlEncodingEnabled(false).when().delete("/namespaces/" + ENCODED_BUCKET_ARN + "/" + NAMESPACE)
                .then().statusCode(204);
        given().urlEncodingEnabled(false).when().delete("/buckets/" + ENCODED_BUCKET_ARN)
                .then().statusCode(204);
    }
}

package io.github.hectorvent.floci.services.dataexchange;

import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;

/** Verifies Data Exchange restJson1 data-set, revision, import-job, and tag lifecycle. */
@QuarkusTest
class DataExchangeIntegrationTest {

    private static final String EAST = "us-east-1";

    @Inject
    S3Service s3Service;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getDataSetOnANonexistentIdFailsWithResourceNotFoundException() {
        given()
                .header("Authorization", auth(EAST))
                .when()
                .get("/v1/data-sets/ffffffffffffffffffffffffffffffff")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createGetListUpdateTagsImportNotifyAndDeleteLifecycle() {
        String authorization = auth(EAST);
        String bucket = "dataexchange-it-" + UUID.randomUUID().toString().substring(0, 8);
        s3Service.createBucket(bucket, EAST);
        s3Service.putObject(bucket, "prices.csv", "date,price\n2026-07-14,42.5\n".getBytes(StandardCharsets.UTF_8),
                "text/csv", Map.of());

        String dataSetId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "AssetType":"S3_SNAPSHOT",
                          "Name":"commodity-prices",
                          "Description":"Daily commodity price snapshots",
                          "Tags":{"Environment":"test"}
                        }
                        """)
                .when()
                .post("/v1/data-sets")
                .then()
                .statusCode(200)
                .body("Id", notNullValue())
                .body("Arn", notNullValue())
                .body("AssetType", equalTo("S3_SNAPSHOT"))
                .body("Origin", equalTo("OWNED"))
                .body("Name", equalTo("commodity-prices"))
                .extract().path("Id");

        String dataSetArn = given()
                .header("Authorization", authorization)
                .when()
                .get("/v1/data-sets/" + dataSetId)
                .then()
                .statusCode(200)
                .body("Id", equalTo(dataSetId))
                .body("Description", equalTo("Daily commodity price snapshots"))
                .extract().path("Arn");

        given()
                .header("Authorization", authorization)
                .when()
                .get("/v1/data-sets?origin=OWNED")
                .then()
                .statusCode(200)
                .body("DataSets.find { it.Id == '" + dataSetId + "' }.Name", equalTo("commodity-prices"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/tags/" + encode(dataSetArn))
                .then()
                .statusCode(200)
                .body("tags.Environment", equalTo("test"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"Name\":\"hourly-prices\",\"Description\":\"Hourly commodity price snapshots\"}")
                .when()
                .patch("/v1/data-sets/" + dataSetId)
                .then()
                .statusCode(200)
                .body("Description", equalTo("Hourly commodity price snapshots"))
                .body("Id", equalTo(dataSetId));

        String revisionId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"Comment\":\"Initial snapshot\",\"Tags\":{\"Environment\":\"test\"}}")
                .when()
                .post("/v1/data-sets/" + dataSetId + "/revisions")
                .then()
                .statusCode(200)
                .body("Id", notNullValue())
                .body("Finalized", equalTo(false))
                .extract().path("Id");

        given()
                .header("Authorization", authorization)
                .when()
                .get("/v1/data-sets/" + dataSetId + "/revisions")
                .then()
                .statusCode(200)
                .body("Revisions.Id", hasItem(revisionId));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/v1/data-sets/" + dataSetId + "/revisions/" + revisionId)
                .then()
                .statusCode(200)
                .body("Comment", equalTo("Initial snapshot"))
                .body("Finalized", equalTo(false));

        String jobId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "Type":"IMPORT_ASSETS_FROM_S3",
                          "Details":{
                            "ImportAssetsFromS3":{
                              "DataSetId":"%s",
                              "RevisionId":"%s",
                              "AssetSources":[{"Bucket":"%s","Key":"prices.csv"}]
                            }
                          }
                        }
                        """.formatted(dataSetId, revisionId, bucket))
                .when()
                .post("/v1/jobs")
                .then()
                .statusCode(200)
                .body("Id", notNullValue())
                .body("State", equalTo("WAITING"))
                .extract().path("Id");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .patch("/v1/jobs/" + jobId)
                .then()
                .statusCode(202);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/v1/jobs/" + jobId)
                .then()
                .statusCode(200)
                .body("State", equalTo("COMPLETED"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/v1/jobs?dataSetId=" + dataSetId)
                .then()
                .statusCode(200)
                .body("Jobs.State", hasItem("COMPLETED"));

        String assetId = given()
                .header("Authorization", authorization)
                .when()
                .get("/v1/data-sets/" + dataSetId + "/revisions/" + revisionId + "/assets")
                .then()
                .statusCode(200)
                .body("Assets.size()", greaterThanOrEqualTo(1))
                .body("Assets[0].Name", equalTo("prices.csv"))
                .extract().path("Assets[0].Id");

        given()
                .header("Authorization", authorization)
                .when()
                .get("/v1/data-sets/" + dataSetId + "/revisions/" + revisionId + "/assets/" + assetId)
                .then()
                .statusCode(200)
                .body("Name", equalTo("prices.csv"))
                .body("AssetType", equalTo("S3_SNAPSHOT"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"Type\":\"DATA_UPDATE\",\"Comment\":\"bindings fixture notification\"}")
                .when()
                .post("/v1/data-sets/" + dataSetId + "/notification")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", org.hamcrest.Matchers.containsString("not configured for AWS Marketplace"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/v1/data-grants")
                .then()
                .statusCode(200)
                .body("DataGrantSummaries.size()", greaterThanOrEqualTo(0));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/v1/received-data-grants")
                .then()
                .statusCode(200)
                .body("DataGrantSummaries.size()", greaterThanOrEqualTo(0));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/v1/event-actions")
                .then()
                .statusCode(200)
                .body("EventActions.size()", greaterThanOrEqualTo(0));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/v1/data-sets/" + dataSetId + "/revisions/" + revisionId)
                .then()
                .statusCode(204);

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/v1/data-sets/" + dataSetId)
                .then()
                .statusCode(204);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/v1/data-sets/" + dataSetId)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createEventActionAgainstAnOwnedDataSetFailsWithValidationException() {
        String authorization = auth(EAST);
        String dataSetId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "AssetType":"S3_SNAPSHOT",
                          "Name":"alchemy-test-dataexchange-eventaction-probe",
                          "Description":"event action entitlement probe"
                        }
                        """)
                .when()
                .post("/v1/data-sets")
                .then()
                .statusCode(200)
                .extract()
                .path("Id");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "Action":{
                            "ExportRevisionToS3":{
                              "RevisionDestination":{"Bucket":"alchemy-nonexistent-bucket"}
                            }
                          },
                          "Event":{"RevisionPublished":{"DataSetId":"%s"}}
                        }
                        """.formatted(dataSetId))
                .when()
                .post("/v1/event-actions")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/v1/data-sets/" + dataSetId)
                .then()
                .statusCode(204);
    }

    private static String auth(String region) {
        return "AWS4-HMAC-SHA256 Credential=AKID/20260205/" + region + "/dataexchange/aws4_request";
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}

package io.floci.aws.services.dataexchange;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Year;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * AWS Data Exchange over REST: data sets, revisions and assets moved in and out by jobs (S3, signed URLs), data grants
 * that give another account an entitled copy kept current as revisions are finalized and revoked, auto-export event
 * actions, notifications, the Heartbeat data set and tags.
 */
@QuarkusTest
class DataExchangeIntegrationTest {

    private static final String PROVIDER = "000000000000";
    private static final String RECEIVER = "111122223333";

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static RequestSpecification dx(String account) {
        return given().header("Authorization", "AWS4-HMAC-SHA256 Credential=" + account
                + "/20260101/us-east-1/dataexchange/aws4_request").contentType("application/json");
    }

    private static RequestSpecification s3(String account) {
        return given().header("Authorization", "AWS4-HMAC-SHA256 Credential=" + account
                + "/20260101/us-east-1/s3/aws4_request");
    }

    private static ValidatableResponse post(String account, String path, String body) {
        return dx(account).body(body).post(path).then();
    }

    private static String bucket(String account) {
        String bucket = "dx-" + UUID.randomUUID().toString().substring(0, 12);
        s3(account).put("/" + bucket).then().statusCode(200);
        return bucket;
    }

    private static String dataSet(String account, String name) {
        return post(account, "/v1/data-sets", "{\"AssetType\":\"S3_SNAPSHOT\",\"Name\":\"" + name + "\","
                + "\"Description\":\"" + name + " data\",\"Tags\":{\"team\":\"data\"}}").statusCode(201)
                .body("Origin", equalTo("OWNED")).extract().path("Id");
    }

    private static String revision(String account, String dataSetId) {
        return post(account, "/v1/data-sets/" + dataSetId + "/revisions", "{\"Comment\":\"rev\"}").statusCode(201)
                .body("Finalized", equalTo(false)).extract().path("Id");
    }

    private static String runJob(String account, String body) {
        String jobId = post(account, "/v1/jobs", body).statusCode(201).body("State", equalTo("WAITING")).extract()
                .path("Id");
        dx(account).patch("/v1/jobs/" + jobId).then().statusCode(202);
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200)).until(() -> List.of("COMPLETED",
                "ERROR").contains(dx(account).get("/v1/jobs/" + jobId).then().extract().<String>path("State")));
        return jobId;
    }

    private static String importS3(String account, String dataSetId, String revisionId, String bucket,
                                   String key, String content) {
        s3(account).body(content.getBytes(StandardCharsets.UTF_8)).put("/" + bucket + "/" + key).then()
                .statusCode(200);
        String jobId = runJob(account, "{\"Type\":\"IMPORT_ASSETS_FROM_S3\",\"Details\":{\"ImportAssetsFromS3\":"
                + "{\"DataSetId\":\"" + dataSetId + "\",\"RevisionId\":\"" + revisionId + "\",\"AssetSources\":"
                + "[{\"Bucket\":\"" + bucket + "\",\"Key\":\"" + key + "\"}]}}}");
        dx(account).get("/v1/jobs/" + jobId).then().body("State", equalTo("COMPLETED"));
        return dx(account).get("/v1/data-sets/" + dataSetId + "/revisions/" + revisionId + "/assets").then()
                .extract().path("Assets.find { it.Name == '" + key + "' }.Id");
    }

    private static void finalize(String account, String dataSetId, String revisionId) {
        dx(account).body("{\"Finalized\":true}").patch("/v1/data-sets/" + dataSetId + "/revisions/" + revisionId)
                .then().statusCode(200).body("Finalized", equalTo(true));
    }

    @Test
    void dataSetsRevisionsAndS3Jobs() {
        String bucket = bucket(PROVIDER);
        String dataSetId = dataSet(PROVIDER, "prices");
        dx(PROVIDER).body("{\"Description\":\"hourly prices\"}").patch("/v1/data-sets/" + dataSetId).then()
                .statusCode(200).body("Description", equalTo("hourly prices"));
        dx(PROVIDER).queryParam("origin", "OWNED").get("/v1/data-sets").then().statusCode(200)
                .body("DataSets.Id", hasItem(dataSetId));
        String revisionId = revision(PROVIDER, dataSetId);
        dx(PROVIDER).body("{\"Finalized\":true}").patch("/v1/data-sets/" + dataSetId + "/revisions/" + revisionId)
                .then().statusCode(400).body("message", containsString("at least one asset"));

        String assetId = importS3(PROVIDER, dataSetId, revisionId, bucket, "prices.csv", "date,price\n1,2\n");
        dx(PROVIDER).get("/v1/data-sets/" + dataSetId + "/revisions/" + revisionId + "/assets/" + assetId).then()
                .statusCode(200).body("AssetType", equalTo("S3_SNAPSHOT"))
                .body("AssetDetails.S3SnapshotAsset.Size", equalTo(15));

        String missing = post(PROVIDER, "/v1/jobs", "{\"Type\":\"IMPORT_ASSETS_FROM_S3\",\"Details\":"
                + "{\"ImportAssetsFromS3\":{\"DataSetId\":\"" + dataSetId + "\",\"RevisionId\":\"" + revisionId + "\","
                + "\"AssetSources\":[{\"Bucket\":\"" + bucket + "\",\"Key\":\"absent.csv\"}]}}}").statusCode(201)
                .extract().path("Id");
        dx(PROVIDER).delete("/v1/jobs/" + missing).then().statusCode(204);
        dx(PROVIDER).patch("/v1/jobs/" + missing).then().statusCode(409);
        String failed = runJob(PROVIDER, "{\"Type\":\"IMPORT_ASSETS_FROM_S3\",\"Details\":{\"ImportAssetsFromS3\":"
                + "{\"DataSetId\":\"" + dataSetId + "\",\"RevisionId\":\"" + revisionId + "\",\"AssetSources\":"
                + "[{\"Bucket\":\"" + bucket + "\",\"Key\":\"absent.csv\"}]}}}");
        dx(PROVIDER).get("/v1/jobs/" + failed).then().body("State", equalTo("ERROR"))
                .body("Errors[0].Code", equalTo("RESOURCE_NOT_FOUND_EXCEPTION"))
                .body("Errors[0].Details.ImportAssetsFromS3JobErrorDetails[0].Key", equalTo("absent.csv"));

        finalize(PROVIDER, dataSetId, revisionId);
        dx(PROVIDER).delete("/v1/data-sets/" + dataSetId + "/revisions/" + revisionId + "/assets/" + assetId)
                .then().statusCode(409);
        String exported = runJob(PROVIDER, "{\"Type\":\"EXPORT_REVISIONS_TO_S3\",\"Details\":{\"ExportRevisionsToS3\":"
                + "{\"DataSetId\":\"" + dataSetId + "\",\"RevisionDestinations\":[{\"Bucket\":\"" + bucket + "\","
                + "\"RevisionId\":\"" + revisionId + "\",\"KeyPattern\":\"out/${Revision.Id}/${Asset.Name}\"}]}}}");
        dx(PROVIDER).get("/v1/jobs/" + exported).then().body("State", equalTo("COMPLETED"));
        assertEquals("date,price\n1,2\n", s3(PROVIDER).get("/" + bucket + "/out/" + revisionId + "/prices.csv")
                .then().statusCode(200).extract().asString());
        runJob(PROVIDER, "{\"Type\":\"EXPORT_ASSETS_TO_S3\",\"Details\":{\"ExportAssetsToS3\":{\"DataSetId\":\""
                + dataSetId + "\",\"RevisionId\":\"" + revisionId + "\",\"AssetDestinations\":[{\"AssetId\":\""
                + assetId + "\",\"Bucket\":\"" + bucket + "\",\"Key\":\"copy.csv\"}]}}}");
        assertEquals("date,price\n1,2\n", s3(PROVIDER).get("/" + bucket + "/copy.csv").then().extract().asString());
        dx(PROVIDER).queryParam("dataSetId", dataSetId).get("/v1/jobs").then().statusCode(200)
                .body("Jobs.Id", hasItem(exported));

        String arn = dx(PROVIDER).get("/v1/data-sets/" + dataSetId).then().extract().path("Arn");
        String encoded = URLEncoder.encode(arn, StandardCharsets.UTF_8);
        dx(PROVIDER).urlEncodingEnabled(false).body("{\"tags\":{\"env\":\"test\"}}").post("/tags/" + encoded).then()
                .statusCode(204);
        dx(PROVIDER).urlEncodingEnabled(false).delete("/tags/" + encoded + "?tagKeys=team").then().statusCode(204);
        dx(PROVIDER).urlEncodingEnabled(false).get("/tags/" + encoded).then().statusCode(200)
                .body("tags.env", equalTo("test")).body("tags.team", equalTo(null));

        dx(PROVIDER).delete("/v1/data-sets/" + dataSetId).then().statusCode(204);
        dx(PROVIDER).get("/v1/data-sets/" + dataSetId).then().statusCode(404)
                .body("ResourceType", equalTo("DATA_SET"));
    }

    @Test
    void signedUrlImportsAndExports() throws Exception {
        String dataSetId = dataSet(PROVIDER, "uploads");
        String revisionId = revision(PROVIDER, dataSetId);
        byte[] file = "{\"hello\":\"world\"}".getBytes(StandardCharsets.UTF_8);
        String job = post(PROVIDER, "/v1/jobs", "{\"Type\":\"IMPORT_ASSET_FROM_SIGNED_URL\",\"Details\":"
                + "{\"ImportAssetFromSignedUrl\":{\"DataSetId\":\"" + dataSetId + "\",\"RevisionId\":\"" + revisionId
                + "\",\"AssetName\":\"hello.json\",\"Md5Hash\":\"" + DataExchangeJobs.md5(file) + "\"}}}")
                .statusCode(201).extract().path("Id");
        String upload = dx(PROVIDER).get("/v1/jobs/" + job).then()
                .body("Details.ImportAssetFromSignedUrl.SignedUrlExpiresAt", notNullValue()).extract()
                .path("Details.ImportAssetFromSignedUrl.SignedUrl");
        given().body(file).put(URI.create(upload).getPath()).then().statusCode(200);
        dx(PROVIDER).patch("/v1/jobs/" + job).then().statusCode(202);
        await().atMost(Duration.ofSeconds(10)).until(() -> "COMPLETED".equals(dx(PROVIDER).get("/v1/jobs/" + job)
                .then().extract().path("State")));
        String assetId = dx(PROVIDER).get("/v1/data-sets/" + dataSetId + "/revisions/" + revisionId + "/assets")
                .then().body("Assets", hasSize(1)).extract().path("Assets[0].Id");

        String wrongHash = post(PROVIDER, "/v1/jobs", "{\"Type\":\"IMPORT_ASSET_FROM_SIGNED_URL\",\"Details\":"
                + "{\"ImportAssetFromSignedUrl\":{\"DataSetId\":\"" + dataSetId + "\",\"RevisionId\":\"" + revisionId
                + "\",\"AssetName\":\"bad.json\",\"Md5Hash\":\"AAAAAAAAAAAAAAAAAAAAAA==\"}}}").statusCode(201)
                .extract().path("Id");
        String badUpload = dx(PROVIDER).get("/v1/jobs/" + wrongHash).then().extract()
                .path("Details.ImportAssetFromSignedUrl.SignedUrl");
        given().body(file).put(URI.create(badUpload).getPath()).then().statusCode(200);
        dx(PROVIDER).patch("/v1/jobs/" + wrongHash).then().statusCode(202);
        await().atMost(Duration.ofSeconds(10)).until(() -> "ERROR".equals(dx(PROVIDER).get("/v1/jobs/" + wrongHash)
                .then().extract().path("State")));
        dx(PROVIDER).get("/v1/jobs/" + wrongHash).then().body("Errors[0].Message", containsString("MD5"));

        String export = runJob(PROVIDER, "{\"Type\":\"EXPORT_ASSET_TO_SIGNED_URL\",\"Details\":"
                + "{\"ExportAssetToSignedUrl\":{\"DataSetId\":\"" + dataSetId + "\",\"RevisionId\":\"" + revisionId
                + "\",\"AssetId\":\"" + assetId + "\"}}}");
        String download = dx(PROVIDER).get("/v1/jobs/" + export).then().body("State", equalTo("COMPLETED"))
                .extract().path("Details.ExportAssetToSignedUrl.SignedUrl");
        assertEquals("{\"hello\":\"world\"}", given().get(URI.create(download).getPath()).then().statusCode(200)
                .extract().asString());
        given().get("/_dataexchange-transfer/unknown").then().statusCode(403);
    }

    @Test
    void dataGrantsShareRevisionsWithTheReceiver() {
        String bucket = bucket(PROVIDER);
        String dataSetId = dataSet(PROVIDER, "shared");
        String first = revision(PROVIDER, dataSetId);
        importS3(PROVIDER, dataSetId, first, bucket, "a.csv", "a\n1\n");
        finalize(PROVIDER, dataSetId, first);
        post(PROVIDER, "/v1/data-sets/" + dataSetId + "/notification", "{\"Type\":\"DATA_UPDATE\"}")
                .statusCode(400).body("message", containsString("not configured for AWS Marketplace"));
        post(PROVIDER, "/v1/data-grants", "{\"Name\":\"self\",\"GrantDistributionScope\":\"NONE\","
                + "\"ReceiverPrincipal\":\"" + PROVIDER + "\",\"SourceDataSetId\":\"" + dataSetId + "\"}")
                .statusCode(400);
        String grantArn = post(PROVIDER, "/v1/data-grants", "{\"Name\":\"to receiver\",\"GrantDistributionScope\":"
                + "\"NONE\",\"ReceiverPrincipal\":\"" + RECEIVER + "\",\"SourceDataSetId\":\"" + dataSetId + "\"}")
                .statusCode(201).body("AcceptanceState", equalTo("PENDING_RECEIVER_ACCEPTANCE"))
                .body("SenderPrincipal", equalTo(PROVIDER)).extract().path("Arn");
        String grantId = grantArn.substring(grantArn.lastIndexOf('/') + 1);
        dx(PROVIDER).get("/v1/data-grants").then().body("DataGrantSummaries.Id", hasItem(grantId));
        dx(RECEIVER).queryParam("acceptanceState", "PENDING_RECEIVER_ACCEPTANCE").get("/v1/received-data-grants")
                .then().statusCode(200).body("DataGrantSummaries.Id", hasItem(grantId));

        String entitled = dx(RECEIVER).urlEncodingEnabled(false)
                .post("/v1/data-grants/" + URLEncoder.encode(grantArn, StandardCharsets.UTF_8) + "/accept").then()
                .statusCode(200).body("AcceptanceState", equalTo("ACCEPTED")).extract().path("DataSetId");
        dx(PROVIDER).get("/v1/data-grants/" + grantId).then().body("AcceptanceState", equalTo("ACCEPTED"));
        dx(RECEIVER).get("/v1/data-sets/" + entitled).then().statusCode(200).body("Origin", equalTo("ENTITLED"))
                .body("OriginDetails.DataGrantId", equalTo(grantId)).body("SourceId", equalTo(dataSetId));
        dx(RECEIVER).get("/v1/data-sets/" + entitled + "/revisions").then()
                .body("Revisions", hasSize(1)).body("Revisions[0].SourceId", equalTo(first));
        dx(RECEIVER).body("{\"Name\":\"mine\"}").patch("/v1/data-sets/" + entitled).then().statusCode(400);

        String exports = bucket(RECEIVER);
        String action = post(RECEIVER, "/v1/event-actions", "{\"Action\":{\"ExportRevisionToS3\":{"
                + "\"RevisionDestination\":{\"Bucket\":\"" + exports + "\",\"KeyPattern\":\"${Revision.CreatedAt.Year}/"
                + "${Asset.Name}\"}}},\"Event\":{\"RevisionPublished\":{\"DataSetId\":\"" + entitled + "\"}}}")
                .statusCode(201).extract().path("Id");
        dx(RECEIVER).queryParam("eventSourceId", entitled).get("/v1/event-actions").then()
                .body("EventActions.Id", equalTo(List.of(action)));

        String second = revision(PROVIDER, dataSetId);
        importS3(PROVIDER, dataSetId, second, bucket, "b.csv", "b\n2\n");
        finalize(PROVIDER, dataSetId, second);
        String year = String.valueOf(Year.now(ZoneOffset.UTC).getValue());
        await().atMost(Duration.ofSeconds(10)).until(() -> s3(RECEIVER).get("/" + exports + "/" + year + "/b.csv")
                .then().extract().statusCode() == 200);
        assertEquals("b\n2\n", s3(RECEIVER).get("/" + exports + "/" + year + "/b.csv").then().extract().asString());
        String copied = dx(RECEIVER).get("/v1/data-sets/" + entitled + "/revisions").then().body("Revisions",
                hasSize(2)).extract().path("Revisions.find { it.SourceId == '" + second + "' }.Id");

        post(PROVIDER, "/v1/data-sets/" + dataSetId + "/notification", "{\"Type\":\"DATA_UPDATE\",\"Comment\":"
                + "\"new data\"}").statusCode(202);
        post(PROVIDER, "/v1/data-sets/" + dataSetId + "/revisions/" + second + "/revoke",
                "{\"RevocationComment\":\"published by mistake\"}").statusCode(200).body("Revoked", equalTo(true));
        dx(RECEIVER).get("/v1/data-sets/" + entitled + "/revisions/" + copied).then().body("Revoked", equalTo(true))
                .body("RevocationComment", equalTo("published by mistake"));
        dx(PROVIDER).delete("/v1/data-sets/" + dataSetId).then().statusCode(409);

        dx(PROVIDER).delete("/v1/data-grants/" + grantId).then().statusCode(204);
        dx(RECEIVER).get("/v1/data-sets/" + entitled).then().statusCode(404);
        dx(RECEIVER).get("/v1/received-data-grants").then().body("DataGrantSummaries.Id", not(hasItem(grantId)));
        dx(RECEIVER).delete("/v1/event-actions/" + action).then().statusCode(204);
        dx(PROVIDER).delete("/v1/data-sets/" + dataSetId).then().statusCode(204);
    }

    @Test
    void heartbeatAndEventActionRules() {
        String heartbeat = DataExchangeService.HEARTBEAT_ID;
        dx(PROVIDER).queryParam("origin", "ENTITLED").get("/v1/data-sets").then().statusCode(200)
                .body("DataSets.Id", hasItem(heartbeat));
        String revisionId = dx(PROVIDER).get("/v1/data-sets/" + heartbeat + "/revisions").then()
                .body("Revisions[0].Finalized", equalTo(true)).extract().path("Revisions[0].Id");
        dx(PROVIDER).get("/v1/data-sets/" + heartbeat + "/revisions/" + revisionId + "/assets").then()
                .body("Assets[0].Name", equalTo("Epoch Time"));
        dx(PROVIDER).delete("/v1/data-sets/" + heartbeat).then().statusCode(400);

        String owned = dataSet(PROVIDER, "probe");
        post(PROVIDER, "/v1/event-actions", "{\"Action\":{\"ExportRevisionToS3\":{\"RevisionDestination\":"
                + "{\"Bucket\":\"b\"}}},\"Event\":{\"RevisionPublished\":{\"DataSetId\":\"" + owned + "\"}}}")
                .statusCode(400).body("message", containsString("entitled"));
        post(PROVIDER, "/v1/event-actions", "{\"Action\":{\"ExportRevisionToS3\":{\"RevisionDestination\":"
                + "{\"Bucket\":\"b\",\"KeyPattern\":\"static\"}}},\"Event\":{\"RevisionPublished\":{\"DataSetId\":\""
                + heartbeat + "\"}}}").statusCode(400).body("message", containsString("KeyPattern"));
        String action = post(PROVIDER, "/v1/event-actions", "{\"Action\":{\"ExportRevisionToS3\":"
                + "{\"RevisionDestination\":{\"Bucket\":\"b\"}}},\"Event\":{\"RevisionPublished\":{\"DataSetId\":\""
                + heartbeat + "\"}},\"Tags\":{\"k\":\"v\"}}").statusCode(201).extract().path("Id");
        dx(PROVIDER).body("{\"Action\":{\"ExportRevisionToS3\":{\"RevisionDestination\":{\"Bucket\":\"b\","
                + "\"KeyPattern\":\"x/${Asset.Name}\"}}}}").patch("/v1/event-actions/" + action).then().statusCode(200)
                .body("Action.ExportRevisionToS3.RevisionDestination.KeyPattern", equalTo("x/${Asset.Name}"));
        dx(PROVIDER).delete("/v1/event-actions/" + action).then().statusCode(204);
        dx(PROVIDER).get("/v1/event-actions/" + action).then().statusCode(404);
        dx(PROVIDER).delete("/v1/data-sets/" + owned).then().statusCode(204);
    }
}

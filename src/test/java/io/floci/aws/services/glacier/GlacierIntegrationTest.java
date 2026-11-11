package io.floci.aws.services.glacier;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Amazon Glacier's vault API over REST: vaults and their settings, archives uploaded whole and in parts against
 * their SHA-256 tree hashes, retrieval, inventory and select jobs, vault locks that hold deletes back, the data
 * retrieval policy and provisioned capacity.
 */
@QuarkusTest
class GlacierIntegrationTest {

    private static final String ACCOUNT = "000000000000";

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static RequestSpecification glacier() {
        return given().header("Authorization", "AWS4-HMAC-SHA256 Credential=" + ACCOUNT
                + "/20260101/us-east-1/glacier/aws4_request").header("x-amz-glacier-version", "2012-06-01");
    }

    private static RequestSpecification s3() {
        return given().header("Authorization", "AWS4-HMAC-SHA256 Credential=" + ACCOUNT
                + "/20260101/us-east-1/s3/aws4_request");
    }

    private static byte[] data(int size, int seed) {
        byte[] bytes = new byte[size];
        for (int i = 0; i < size; i++) {
            bytes[i] = (byte) (i * 31 + seed);
        }
        return bytes;
    }

    private static String upload(String vault, byte[] body, String description) {
        return glacier().header("x-amz-archive-description", description)
                .header("x-amz-sha256-tree-hash", GlacierTreeHash.of(body))
                .contentType("application/octet-stream").body(body).post("/-/vaults/" + vault + "/archives").then()
                .statusCode(201).header("x-amz-sha256-tree-hash", GlacierTreeHash.of(body))
                .extract().header("x-amz-archive-id");
    }

    private static String job(String vault, String parameters) {
        return glacier().contentType("application/json").body(parameters).post("/-/vaults/" + vault + "/jobs")
                .then().statusCode(202).extract().header("x-amz-job-id");
    }

    @Test
    void vaultsHoldArchivesAndTheirSettings() {
        glacier().put("/-/vaults/bad%20name").then().statusCode(400)
                .body("code", equalTo("InvalidParameterValueException"));
        glacier().get("/-/vaults/records").then().statusCode(404).header("x-amzn-ErrorType",
                "ResourceNotFoundException").body("code", equalTo("ResourceNotFoundException"))
                .body("type", equalTo("Client"));
        glacier().put("/-/vaults/records").then().statusCode(201)
                .header("Location", "/" + ACCOUNT + "/vaults/records");
        glacier().put("/-/vaults/records").then().statusCode(201);
        glacier().get("/" + ACCOUNT + "/vaults/records").then().statusCode(200)
                .body("VaultARN", equalTo("arn:aws:glacier:us-east-1:" + ACCOUNT + ":vaults/records"))
                .body("NumberOfArchives", equalTo(0)).body("LastInventoryDate", nullValue());
        glacier().get("/-/vaults").then().statusCode(200).body("VaultList.VaultName", hasItem("records"));
        glacier().contentType("application/json").body("{\"Type\":\"inventory-retrieval\"}")
                .post("/-/vaults/records/jobs").then().statusCode(404).body("message", containsString("not yet"));

        glacier().contentType("application/json").body("{\"Tags\":{\"team\":\"ops\",\"stage\":\"one\"}}")
                .post("/-/vaults/records/tags?operation=add").then().statusCode(204);
        glacier().contentType("application/json").body("{\"TagKeys\":[\"stage\"]}")
                .post("/-/vaults/records/tags?operation=remove").then().statusCode(204);
        glacier().get("/-/vaults/records/tags").then().statusCode(200).body("Tags.team", equalTo("ops"))
                .body("Tags.stage", nullValue());

        glacier().get("/-/vaults/records/access-policy").then().statusCode(404);
        String policy = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"AWS\":"
                + "\"arn:aws:iam::111122223333:root\"},\"Action\":\"glacier:ListJobs\",\"Resource\":"
                + "\"arn:aws:glacier:us-east-1:" + ACCOUNT + ":vaults/records\"}]}";
        glacier().contentType("application/json").body("{\"Policy\":" + quote(policy) + "}")
                .put("/-/vaults/records/access-policy").then().statusCode(204);
        glacier().get("/-/vaults/records/access-policy").then().statusCode(200).body("Policy", equalTo(policy));
        glacier().contentType("application/json").body("{\"Policy\":\"not json\"}")
                .put("/-/vaults/records/access-policy").then().statusCode(400);
        glacier().delete("/-/vaults/records/access-policy").then().statusCode(204);

        glacier().contentType("application/json").body("{\"SNSTopic\":\"arn:aws:sns:us-east-1:" + ACCOUNT
                + ":done\",\"Events\":[\"Nope\"]}").put("/-/vaults/records/notification-configuration").then()
                .statusCode(400);
        glacier().contentType("application/json").body("{\"SNSTopic\":\"arn:aws:sns:us-east-1:" + ACCOUNT
                + ":done\",\"Events\":[\"ArchiveRetrievalCompleted\"]}")
                .put("/-/vaults/records/notification-configuration").then().statusCode(204);
        glacier().get("/-/vaults/records/notification-configuration").then().statusCode(200)
                .body("Events", hasItem("ArchiveRetrievalCompleted"));
        glacier().delete("/-/vaults/records/notification-configuration").then().statusCode(204);
        glacier().get("/-/vaults/records/notification-configuration").then().statusCode(404);

        byte[] small = "hello glacier".getBytes(StandardCharsets.UTF_8);
        glacier().header("x-amz-sha256-tree-hash", "0".repeat(64)).contentType("application/octet-stream")
                .body(small).post("/-/vaults/records/archives").then().statusCode(400)
                .body("message", containsString("Checksum mismatch"));
        glacier().contentType("application/octet-stream").body(small).post("/-/vaults/records/archives").then()
                .statusCode(400).body("code", equalTo("MissingParameterValueException"));
        byte[] big = data(3 * GlacierTreeHash.MEBIBYTE + 5, 7);
        String bigId = upload("records", big, "three megabytes and a bit");
        String smallId = upload("records", small, "hello");
        assertEquals(138, bigId.length());
        glacier().get("/-/vaults/records").then().statusCode(200).body("NumberOfArchives", equalTo(2))
                .body("SizeInBytes", equalTo(big.length + small.length));
        glacier().delete("/-/vaults/records").then().statusCode(400).body("message", containsString("not empty"));

        // An archive in parts: two whole parts of 1 MiB and a short last one, the tree hash over all of them.
        byte[] parted = data(2 * GlacierTreeHash.MEBIBYTE + 100, 3);
        glacier().header("x-amz-part-size", "1").post("/-/vaults/records/multipart-uploads").then().statusCode(400)
                .body("message", containsString("power of two"));
        String uploadId = glacier().header("x-amz-part-size", Integer.toString(GlacierTreeHash.MEBIBYTE))
                .header("x-amz-archive-description", "parted").post("/-/vaults/records/multipart-uploads").then()
                .statusCode(201).extract().header("x-amz-multipart-upload-id");
        for (int start = 0; start < parted.length; start += GlacierTreeHash.MEBIBYTE) {
            byte[] part = Arrays.copyOfRange(parted, start, Math.min(parted.length, start + GlacierTreeHash.MEBIBYTE));
            glacier().header("Content-Range", "bytes " + start + "-" + (start + part.length - 1) + "/*")
                    .header("x-amz-sha256-tree-hash", GlacierTreeHash.of(part)).contentType("application/octet-stream")
                    .body(part).put("/-/vaults/records/multipart-uploads/" + uploadId).then().statusCode(204);
        }
        glacier().header("Content-Range", "bytes 100-199/*").header("x-amz-sha256-tree-hash", "0".repeat(64))
                .contentType("application/octet-stream").body(new byte[100])
                .put("/-/vaults/records/multipart-uploads/" + uploadId).then().statusCode(400);
        glacier().get("/-/vaults/records/multipart-uploads/" + uploadId + "?limit=2").then().statusCode(200)
                .body("Parts", hasSize(2)).body("Parts[1].RangeInBytes", equalTo("1048576-2097151"))
                .body("Marker", equalTo(Integer.toString(2 * GlacierTreeHash.MEBIBYTE)));
        glacier().get("/-/vaults/records/multipart-uploads").then().statusCode(200)
                .body("UploadsList.MultipartUploadId", hasItem(uploadId));
        glacier().header("x-amz-archive-size", Integer.toString(parted.length)).header("x-amz-sha256-tree-hash",
                "0".repeat(64)).post("/-/vaults/records/multipart-uploads/" + uploadId).then().statusCode(400);
        String partedId = glacier().header("x-amz-archive-size", Integer.toString(parted.length))
                .header("x-amz-sha256-tree-hash", GlacierTreeHash.of(parted))
                .post("/-/vaults/records/multipart-uploads/" + uploadId).then().statusCode(201)
                .extract().header("x-amz-archive-id");
        glacier().get("/-/vaults/records/multipart-uploads/" + uploadId).then().statusCode(404);

        // Retrieval: the whole archive, then its second megabyte, which is tree-hash aligned.
        String whole = job("records", "{\"Type\":\"archive-retrieval\",\"ArchiveId\":\"" + partedId + "\","
                + "\"Tier\":\"Bulk\",\"Description\":\"restore\"}");
        glacier().get("/-/vaults/records/jobs/" + whole).then().statusCode(200).body("StatusCode",
                equalTo("Succeeded")).body("Action", equalTo("ArchiveRetrieval"))
                .body("SHA256TreeHash", equalTo(GlacierTreeHash.of(parted))).body("Tier", equalTo("Bulk"));
        ExtractableResponse<Response> output = glacier().get("/-/vaults/records/jobs/" + whole + "/output").then()
                .statusCode(200).header("x-amz-archive-description", "parted").extract();
        assertArrayEquals(parted, output.asByteArray());
        assertEquals(GlacierTreeHash.of(parted), output.header("x-amz-sha256-tree-hash"));
        byte[] range = glacier().header("Range", "bytes=1048576-2097151").get("/-/vaults/records/jobs/" + whole
                + "/output").then().statusCode(206).header("Content-Range", "bytes 1048576-2097151/" + parted.length)
                .header("x-amz-sha256-tree-hash", GlacierTreeHash.of(Arrays.copyOfRange(parted,
                        GlacierTreeHash.MEBIBYTE, 2 * GlacierTreeHash.MEBIBYTE))).extract().asByteArray();
        assertEquals(GlacierTreeHash.MEBIBYTE, range.length);
        glacier().contentType("application/json").body("{\"Type\":\"archive-retrieval\",\"ArchiveId\":\"" + bigId
                + "\",\"RetrievalByteRange\":\"5-100\"}").post("/-/vaults/records/jobs").then().statusCode(400);
        String second = job("records", "{\"Type\":\"archive-retrieval\",\"ArchiveId\":\"" + bigId + "\","
                + "\"RetrievalByteRange\":\"1048576-2097151\"}");
        glacier().get("/-/vaults/records/jobs/" + second).then().statusCode(200)
                .body("RetrievalByteRange", equalTo("1048576-2097151"))
                .body("SHA256TreeHash", equalTo(GlacierTreeHash.of(big, GlacierTreeHash.MEBIBYTE,
                        GlacierTreeHash.MEBIBYTE)));

        String inventory = job("records", "{\"Type\":\"inventory-retrieval\",\"Format\":\"JSON\"}");
        glacier().get("/-/vaults/records/jobs/" + inventory + "/output").then().statusCode(200)
                .body("VaultARN", containsString(":vaults/records")).body("ArchiveList", hasSize(3))
                .body("ArchiveList.ArchiveDescription", hasItem("parted"));
        String csv = job("records", "{\"Type\":\"inventory-retrieval\",\"Format\":\"CSV\","
                + "\"InventoryRetrievalParameters\":{\"Limit\":\"1\"}}");
        String inventoryCsv = glacier().get("/-/vaults/records/jobs/" + csv + "/output").then().statusCode(200)
                .extract().asString();
        assertEquals(2, inventoryCsv.lines().count());
        glacier().get("/-/vaults/records/jobs/" + csv).then().body("InventoryRetrievalParameters.Marker",
                equalTo(smallId));
        glacier().get("/-/vaults/records/jobs?statuscode=Succeeded&limit=2").then().statusCode(200)
                .body("JobList", hasSize(2)).body("Marker", equalTo(inventory));
        glacier().get("/-/vaults/records/jobs?statuscode=Nope").then().statusCode(400);

        // A select job reads a CSV archive and writes what it selects to S3.
        String people = upload("records", "name,city\nada,london\ngrace,arlington\nalan,london\n"
                .getBytes(StandardCharsets.UTF_8), "people.csv");
        s3().put("/glacier-select").then().statusCode(200);
        String select = glacier().contentType("application/json").body("{\"Type\":\"select\",\"ArchiveId\":\""
                + people + "\",\"SelectParameters\":{\"ExpressionType\":\"SQL\",\"Expression\":\"SELECT s.name FROM "
                + "archive s WHERE s.city = 'london'\",\"InputSerialization\":{\"csv\":{\"FileHeaderInfo\":\"USE\"}},"
                + "\"OutputSerialization\":{\"csv\":{}}},\"OutputLocation\":{\"S3\":{\"BucketName\":"
                + "\"glacier-select\",\"Prefix\":\"out\"}}}").post("/-/vaults/records/jobs").then().statusCode(202)
                .header("x-amz-job-output-path", containsString("out/")).extract().header("x-amz-job-id");
        glacier().get("/-/vaults/records/jobs/" + select).then().body("StatusCode", equalTo("Succeeded"));
        List<String> keys = s3().queryParam("list-type", "2").queryParam("prefix", "out/" + select + "/results/")
                .get("/glacier-select").then().statusCode(200).extract().xmlPath()
                .getList("ListBucketResult.Contents.Key");
        String selected = s3().get("/glacier-select/" + keys.get(0)).then().statusCode(200).extract().asString();
        assertEquals(List.of("ada", "alan"), selected.lines().toList());

        for (String archive : List.of(bigId, smallId, partedId, people)) {
            glacier().delete("/-/vaults/records/archives/" + archive).then().statusCode(204);
        }
        glacier().delete("/-/vaults/records/archives/" + smallId).then().statusCode(404);
        glacier().get("/-/vaults/records").then().body("NumberOfArchives", equalTo(0));
        glacier().delete("/-/vaults/records").then().statusCode(204);
        glacier().get("/-/vaults/records").then().statusCode(404);
    }

    @Test
    void aVaultLockHoldsArchivesItsPolicyProtects() {
        glacier().put("/-/vaults/locked").then().statusCode(201);
        String archive = upload("locked", "keep me".getBytes(StandardCharsets.UTF_8), "kept");
        String deny = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Deny\",\"Principal\":\"*\","
                + "\"Action\":\"glacier:DeleteArchive\",\"Resource\":\"arn:aws:glacier:us-east-1:" + ACCOUNT
                + ":vaults/locked\",\"Condition\":{\"NumericLessThan\":{\"glacier:ArchiveAgeInDays\":\"365\"}}}]}";
        glacier().get("/-/vaults/locked/lock-policy").then().statusCode(404);
        String lockId = glacier().contentType("application/json").body("{\"Policy\":" + quote(deny) + "}")
                .post("/-/vaults/locked/lock-policy").then().statusCode(201).extract().header("x-amz-lock-id");
        glacier().get("/-/vaults/locked/lock-policy").then().statusCode(200).body("State", equalTo("InProgress"))
                .body("ExpirationDate", containsString("T"));
        glacier().contentType("application/json").body("{\"Policy\":" + quote(deny) + "}")
                .post("/-/vaults/locked/lock-policy").then().statusCode(400).body("message", containsString("progress"));
        glacier().delete("/-/vaults/locked/archives/" + archive).then().statusCode(403)
                .body("code", equalTo("AccessDeniedException"));
        glacier().delete("/-/vaults/locked/lock-policy").then().statusCode(204);

        lockId = glacier().contentType("application/json").body("{\"Policy\":" + quote(deny) + "}")
                .post("/-/vaults/locked/lock-policy").then().statusCode(201).extract().header("x-amz-lock-id");
        glacier().post("/-/vaults/locked/lock-policy/wrong").then().statusCode(400);
        glacier().post("/-/vaults/locked/lock-policy/" + lockId).then().statusCode(204);
        glacier().get("/-/vaults/locked/lock-policy").then().statusCode(200).body("State", equalTo("Locked"))
                .body("ExpirationDate", nullValue());
        glacier().delete("/-/vaults/locked/lock-policy").then().statusCode(400);
        glacier().delete("/-/vaults/locked/archives/" + archive).then().statusCode(403);
        glacier().get("/-/vaults/locked").then().body("NumberOfArchives", equalTo(1));
    }

    @Test
    void retrievalPolicyAndProvisionedCapacity() {
        glacier().get("/-/policies/data-retrieval").then().statusCode(200)
                .body("Policy.Rules[0].Strategy", equalTo("None"));
        glacier().contentType("application/json").body("{\"Policy\":{\"Rules\":[{\"Strategy\":\"FreeTier\","
                + "\"BytesPerHour\":5}]}}").put("/-/policies/data-retrieval").then().statusCode(400);
        glacier().contentType("application/json").body("{\"Policy\":{\"Rules\":[{\"Strategy\":\"BytesPerHour\","
                + "\"BytesPerHour\":1}]}}").put("/-/policies/data-retrieval").then().statusCode(204);
        glacier().put("/-/vaults/limited").then().statusCode(201);
        String archive = upload("limited", data(64, 1), "small");
        glacier().contentType("application/json").body("{\"Type\":\"archive-retrieval\",\"ArchiveId\":\"" + archive
                + "\"}").post("/-/vaults/limited/jobs").then().statusCode(400)
                .body("code", equalTo("PolicyEnforcedException"));
        job("limited", "{\"Type\":\"archive-retrieval\",\"ArchiveId\":\"" + archive + "\",\"Tier\":\"Bulk\"}");
        glacier().contentType("application/json").body("{\"Policy\":{\"Rules\":[{\"Strategy\":\"None\"}]}}")
                .put("/-/policies/data-retrieval").then().statusCode(204);

        glacier().post("/-/provisioned-capacity").then().statusCode(201).header("x-amz-capacity-id",
                containsString(""));
        glacier().post("/-/provisioned-capacity").then().statusCode(201);
        glacier().post("/-/provisioned-capacity").then().statusCode(400).body("code", equalTo("LimitExceededException"));
        glacier().get("/-/provisioned-capacity").then().statusCode(200).body("ProvisionedCapacityList", hasSize(2));

        glacier().get("/-/vaults/limited/jobs/nope").then().statusCode(404);
        glacier().get("/-/vaults/limited/jobs/nope/output").then().statusCode(404);
        glacier().get("/-/vaults/limited/multipart-uploads/nope").then().statusCode(404);
        glacier().header("Content-Range", "bytes 0-1048575/*").put("/-/vaults/limited/multipart-uploads/nope").then()
                .statusCode(404);
        glacier().post("/-/vaults/limited/multipart-uploads/nope").then().statusCode(404);
        glacier().delete("/-/vaults/limited/multipart-uploads/nope").then().statusCode(404);
        glacier().get("/12345/vaults").then().statusCode(400);
        assertTrue(glacier().get("/-/vaults/missing/jobs").then().statusCode(404).extract().asString()
                .contains("vaults/missing"));
    }

    private static String quote(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}

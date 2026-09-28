package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S3EncryptionIntegrationTest {

    private static final String BUCKET = "encryption-int-test";
    private static final String SSE_KMS_XML = """
            <ServerSideEncryptionConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                <Rule>
                    <ApplyServerSideEncryptionByDefault>
                        <SSEAlgorithm>aws:kms</SSEAlgorithm>
                        <KMSMasterKeyID>arn:aws:kms:us-east-1:000000000000:key/abc</KMSMasterKeyID>
                    </ApplyServerSideEncryptionByDefault>
                    <BucketKeyEnabled>true</BucketKeyEnabled>
                </Rule>
            </ServerSideEncryptionConfiguration>
            """;

    @Test
    @Order(1)
    void createBucket() {
        given()
        .when()
            .put("/" + BUCKET)
        .then()
            .statusCode(200);
    }

    /**
     * Since January 2023 AWS applies SSE-S3 (AES256) as the base level of encryption on every
     * bucket, and {@code GetBucketEncryption} returns that default configuration rather than
     * {@code 404 ServerSideEncryptionConfigurationNotFoundError}. The ACK s3-controller reads
     * {@code getEncryptionResponse.ServerSideEncryptionConfiguration.Rules} without a nil guard,
     * so a 404 here crashes it; returning the default keeps Floci AWS-faithful and unblocks ACK.
     */
    @Test
    @Order(2)
    void getEncryptionBeforePutReturnsDefaultSseS3() {
        given()
        .when()
            .get("/" + BUCKET + "?encryption")
        .then()
            .statusCode(200)
            .body(containsString("<ServerSideEncryptionConfiguration"))
            .body(containsString("<SSEAlgorithm>AES256</SSEAlgorithm>"))
            .body(containsString("<EncryptionType>NONE</EncryptionType>"))
            .body(containsString("<BucketKeyEnabled>false</BucketKeyEnabled>"))
            .body(not(containsString("ServerSideEncryptionConfigurationNotFoundError")));
    }

    @Test
    @Order(3)
    void putEncryptionReturns200() {
        given()
            .body(SSE_KMS_XML)
        .when()
            .put("/" + BUCKET + "?encryption")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(4)
    void getEncryptionReturnsStoredConfiguration() {
        given()
        .when()
            .get("/" + BUCKET + "?encryption")
        .then()
            .statusCode(200)
            .body(containsString("<SSEAlgorithm>aws:kms</SSEAlgorithm>"))
            .body(containsString("<KMSMasterKeyID>arn:aws:kms:us-east-1:000000000000:key/abc</KMSMasterKeyID>"))
            .body(containsString("<BucketKeyEnabled>true</BucketKeyEnabled>"))
            .body(containsString("<EncryptionType>NONE</EncryptionType>"));
    }

    @Test
    @Order(5)
    void getEncryptionOnMissingBucketReturns404() {
        given()
        .when()
            .get("/this-bucket-does-not-exist-enc?encryption")
        .then()
            .statusCode(404)
            .body(containsString("NoSuchBucket"));
    }

    /**
     * After DeleteBucketEncryption clears the explicit config, GetBucketEncryption must fall back
     * to the AWS default SSE-S3 (AES256) — not the previously stored KMS config and not a 404.
     */
    @Test
    @Order(6)
    void getEncryptionAfterDeleteReturnsDefaultSseS3() {
        given()
        .when()
            .delete("/" + BUCKET + "?encryption")
        .then()
            .statusCode(204);
        given()
        .when()
            .get("/" + BUCKET + "?encryption")
        .then()
            .statusCode(200)
            .body(containsString("<SSEAlgorithm>AES256</SSEAlgorithm>"))
            .body(containsString("<EncryptionType>NONE</EncryptionType>"))
            .body(containsString("<BucketKeyEnabled>false</BucketKeyEnabled>"))
            .body(not(containsString("aws:kms")))
            .body(not(containsString("KMSMasterKeyID")));
    }

    @Test
    void normalizesNamespacedEncryptionAndRemovesOldKmsSettings() {
        String bucket = newBucket();
        given().body(SSE_KMS_XML).put("/" + bucket + "?encryption").then().statusCode(200);
        given().body("""
                <s3:ServerSideEncryptionConfiguration xmlns:s3="http://s3.amazonaws.com/doc/2006-03-01/">
                  <s3:Rule>
                    <s3:ApplyServerSideEncryptionByDefault><s3:SSEAlgorithm> AES256 </s3:SSEAlgorithm></s3:ApplyServerSideEncryptionByDefault>
                    <s3:BlockedEncryptionTypes><s3:EncryptionType> NONE </s3:EncryptionType></s3:BlockedEncryptionTypes>
                  </s3:Rule>
                </s3:ServerSideEncryptionConfiguration>
                """).put("/" + bucket + "?encryption").then().statusCode(200);
        given().get("/" + bucket + "?encryption").then().statusCode(200)
                .body("ServerSideEncryptionConfiguration.Rule.ApplyServerSideEncryptionByDefault.SSEAlgorithm", equalTo("AES256"))
                .body("ServerSideEncryptionConfiguration.Rule.BlockedEncryptionTypes.EncryptionType", equalTo("NONE"))
                .body("ServerSideEncryptionConfiguration.Rule.BucketKeyEnabled", equalTo("false"))
                .body(not(containsString("KMSMasterKeyID")));
        given().delete("/" + bucket).then().statusCode(204);
    }

    @Test
    void sseS3BucketKeyFlagRoundTripsWithoutChangingEncryptionAlgorithm() {
        String bucket = newBucket();
        for (boolean bucketKey : new boolean[]{false, true, false}) {
            given().body(encryptionRule("<ApplyServerSideEncryptionByDefault><SSEAlgorithm>AES256</SSEAlgorithm>"
                            + "</ApplyServerSideEncryptionByDefault><BucketKeyEnabled>" + bucketKey + "</BucketKeyEnabled>"))
                    .put("/" + bucket + "?encryption").then().statusCode(200);
            given().get("/" + bucket + "?encryption").then().statusCode(200)
                    .body("ServerSideEncryptionConfiguration.Rule.ApplyServerSideEncryptionByDefault.SSEAlgorithm", equalTo("AES256"))
                    .body("ServerSideEncryptionConfiguration.Rule.BucketKeyEnabled", equalTo(Boolean.toString(bucketKey)))
                    .body("ServerSideEncryptionConfiguration.Rule.BlockedEncryptionTypes.EncryptionType", equalTo("NONE"))
                    .body(not(containsString("KMSMasterKeyID")))
                    .body(not(containsString("aws:kms")));
            given().header("x-amz-server-side-encryption", "AES256").body("SSE-S3 object")
                    .put("/" + bucket + "/object").then().statusCode(200)
                    .header("x-amz-server-side-encryption", equalTo("AES256"));
            given().head("/" + bucket + "/object").then().statusCode(200)
                    .header("x-amz-server-side-encryption", equalTo("AES256"))
                    .header("x-amz-server-side-encryption-aws-kms-key-id", nullValue());
        }
        given().delete("/" + bucket + "/object").then().statusCode(204);
        given().delete("/" + bucket).then().statusCode(204);
    }

    @Test
    void rejectsMalformedEncryptionWithoutChangingStoredConfiguration() {
        String bucket = newBucket();
        given().body(SSE_KMS_XML).put("/" + bucket + "?encryption").then().statusCode(200);
        for (String invalid : new String[]{
                "", "<WrongRoot/>", "<ServerSideEncryptionConfiguration><Rule>",
                encryptionRule("<Unexpected>AES256</Unexpected>"),
                encryptionRule("<ApplyServerSideEncryptionByDefault><SSEAlgorithm>AES256</SSEAlgorithm>"
                        + "<SSEAlgorithm>aws:kms</SSEAlgorithm></ApplyServerSideEncryptionByDefault>"),
                encryptionRule("<BucketKeyEnabled>yes</BucketKeyEnabled>"),
                encryptionRule("<BlockedEncryptionTypes><EncryptionType>NONE</EncryptionType>"
                        + "<EncryptionType>SSE-C</EncryptionType></BlockedEncryptionTypes>"),
                encryptionRule("<BlockedEncryptionTypes/>")}) {
            given().body(invalid).put("/" + bucket + "?encryption").then().statusCode(400)
                    .body(containsString("<Code>MalformedXML</Code>"));
        }
        for (String invalid : new String[]{
                encryptionRule("<ApplyServerSideEncryptionByDefault><SSEAlgorithm>invalid</SSEAlgorithm></ApplyServerSideEncryptionByDefault>"),
                encryptionRule("<BlockedEncryptionTypes><EncryptionType>AES256</EncryptionType></BlockedEncryptionTypes>"),
                encryptionRule("<ApplyServerSideEncryptionByDefault><SSEAlgorithm>AES256</SSEAlgorithm>"
                        + "<KMSMasterKeyID>key</KMSMasterKeyID></ApplyServerSideEncryptionByDefault>")}) {
            given().body(invalid).put("/" + bucket + "?encryption").then().statusCode(400)
                    .body(containsString("<Code>InvalidArgument</Code>"));
        }
        given().get("/" + bucket + "?encryption").then().statusCode(200)
                .body(containsString("<SSEAlgorithm>aws:kms</SSEAlgorithm>"))
                .body(containsString("<BucketKeyEnabled>true</BucketKeyEnabled>"));
        given().delete("/" + bucket).then().statusCode(204);
    }

    @Test
    void blocksSseCustomerWritesButKeepsExistingObjectsReadable() throws Exception {
        String bucket = newBucket();
        customerRequest().body("original").put("/" + bucket + "/existing").then().statusCode(200);
        given().body("plain").put("/" + bucket + "/source").then().statusCode(200);
        setBlocked(bucket, "SSE-C");

        customerRequest().body("replacement").put("/" + bucket + "/existing").then().statusCode(403)
                .body(containsString("<Code>AccessDenied</Code>"));
        customerRequest().header("x-amz-copy-source", "/" + bucket + "/source")
                .put("/" + bucket + "/copy").then().statusCode(403)
                .body(containsString("<Code>AccessDenied</Code>"));
        customerRequest().post("/" + bucket + "/multipart?uploads").then().statusCode(403)
                .body(containsString("<Code>AccessDenied</Code>"));
        customerPost(bucket, "posted", 403);
        customerRequest().get("/" + bucket + "/existing").then().statusCode(200).body(equalTo("original"));
        customerRequest().head("/" + bucket + "/existing").then().statusCode(200);
        given().get("/" + bucket + "/existing").then().statusCode(400);
        given().get("/" + bucket + "?list-type=2").then().statusCode(200)
                .body(containsString("<Key>existing</Key>"));
        given().head("/" + bucket + "/copy").then().statusCode(404);
        given().head("/" + bucket + "/posted").then().statusCode(404);
        given().body("allowed").put("/" + bucket + "/plain").then().statusCode(200);

        setBlocked(bucket, "NONE");
        customerRequest().body("replacement").put("/" + bucket + "/existing").then().statusCode(200);
        customerPost(bucket, "posted", 204);
        customerRequest().get("/" + bucket + "/posted").then().statusCode(200).body(equalTo("posted body"));
        setBlocked(bucket, "SSE-C");
        given().delete("/" + bucket + "?encryption").then().statusCode(204);
        customerRequest().body("reset").put("/" + bucket + "/existing").then().statusCode(200);
        for (String key : new String[]{"existing", "source", "plain", "posted"}) {
            given().delete("/" + bucket + "/" + key).then().statusCode(204);
        }
        given().delete("/" + bucket).then().statusCode(204);
    }

    @Test
    void blocksInFlightSseCustomerMultipartWritesUntilUnblocked() throws Exception {
        String bucket = newBucket();
        String uploadId = customerRequest().post("/" + bucket + "/object?uploads").then().statusCode(200)
                .extract().xmlPath().getString("InitiateMultipartUploadResult.UploadId");
        String path = "/" + bucket + "/object?uploadId=" + uploadId;
        String etag = customerRequest().body("first").put(path + "&partNumber=1").then().statusCode(200)
                .extract().header("ETag");
        String complete = "<CompleteMultipartUpload><Part><PartNumber>1</PartNumber><ETag>"
                + etag + "</ETag></Part></CompleteMultipartUpload>";
        setBlocked(bucket, "SSE-C");
        customerRequest().body("second").put(path + "&partNumber=2").then().statusCode(403)
                .body(containsString("<Code>AccessDenied</Code>"));
        given().body("source").put("/" + bucket + "/source").then().statusCode(200);
        customerRequest().header("x-amz-copy-source", "/" + bucket + "/source")
                .put(path + "&partNumber=2").then().statusCode(403);
        customerRequest().body(complete).post(path).then().statusCode(403)
                .body(containsString("<Code>AccessDenied</Code>"));
        given().head("/" + bucket + "/object").then().statusCode(404);
        setBlocked(bucket, "NONE");
        customerRequest().body(complete).post(path).then().statusCode(200);
        customerRequest().get("/" + bucket + "/object").then().statusCode(200).body(equalTo("first"));
        given().delete("/" + bucket + "/object").then().statusCode(204);
        given().delete("/" + bucket + "/source").then().statusCode(204);
        given().delete("/" + bucket).then().statusCode(204);
    }

    @Test
    void expectedOwnerProtectsBucketAndObjectOperationsWithoutAuthEnforcement() {
        String bucket = newBucket();
        String wrongOwner = "999999999999";
        for (String query : new String[]{"", "?location", "?encryption", "?tagging", "?versioning", "?policy",
                "?acl", "?logging", "?cors", "?lifecycle", "?notification", "?publicAccessBlock",
                "?ownershipControls", "?website", "?replication", "?requestPayment", "?accelerate",
                "?metrics", "?analytics", "?inventory", "?intelligent-tiering", "?uploads", "?versions"}) {
            given().header("x-amz-expected-bucket-owner", wrongOwner).get("/" + bucket + query)
                    .then().statusCode(403).body(containsString("<Code>AccessDenied</Code>"));
        }
        given().header("x-amz-expected-bucket-owner", wrongOwner).head("/" + bucket).then().statusCode(403);
        given().header("x-amz-expected-bucket-owner", "000000000000")
                .get("/" + bucket + "?location").then().statusCode(200);
        given().header("x-amz-expected-bucket-owner", wrongOwner).body(SSE_KMS_XML)
                .put("/" + bucket + "?encryption").then().statusCode(403);
        given().get("/" + bucket + "?encryption").then().statusCode(200)
                .body(containsString("<SSEAlgorithm>AES256</SSEAlgorithm>"));
        given().header("x-amz-expected-bucket-owner", "000000000000").body(SSE_KMS_XML)
                .put("/" + bucket + "?encryption").then().statusCode(200);
        given().header("x-amz-expected-bucket-owner", wrongOwner)
                .delete("/" + bucket + "?encryption").then().statusCode(403);
        given().get("/" + bucket + "?encryption").then().statusCode(200)
                .body(containsString("<SSEAlgorithm>aws:kms</SSEAlgorithm>"));
        given().body("kept").put("/" + bucket + "/object").then().statusCode(200);
        given().header("x-amz-expected-bucket-owner", wrongOwner).get("/" + bucket + "/object").then().statusCode(403);
        given().header("x-amz-expected-bucket-owner", wrongOwner).head("/" + bucket + "/object").then().statusCode(403);
        given().header("x-amz-expected-bucket-owner", wrongOwner).body("changed")
                .put("/" + bucket + "/object").then().statusCode(403);
        given().header("x-amz-expected-bucket-owner", wrongOwner).delete("/" + bucket + "/object").then().statusCode(403);
        given().header("x-amz-expected-bucket-owner", wrongOwner)
                .body("<Delete><Object><Key>object</Key></Object></Delete>")
                .post("/" + bucket + "?delete").then().statusCode(403);
        given().header("x-amz-copy-source", "/" + bucket + "/object")
                .header("x-amz-source-expected-bucket-owner", wrongOwner)
                .put("/" + bucket + "/copy").then().statusCode(403);
        given().get("/" + bucket + "/object").then().statusCode(200).body(equalTo("kept"));
        given().header("x-amz-expected-bucket-owner", wrongOwner).delete("/" + bucket).then().statusCode(403);
        given().delete("/" + bucket + "/object").then().statusCode(204);
        given().header("x-amz-expected-bucket-owner", "000000000000").delete("/" + bucket).then().statusCode(204);
        given().header("x-amz-expected-bucket-owner", "000000000000")
                .get("/" + bucket + "?location").then().statusCode(404)
                .body(containsString("<Code>NoSuchBucket</Code>"));
    }

    private static String newBucket() {
        String bucket = "encryption-" + UUID.randomUUID();
        given().put("/" + bucket).then().statusCode(200);
        return bucket;
    }

    private static String encryptionRule(String rule) {
        return "<ServerSideEncryptionConfiguration xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"><Rule>"
                + rule + "</Rule></ServerSideEncryptionConfiguration>";
    }

    private static void setBlocked(String bucket, String type) {
        given().body(encryptionRule("<BlockedEncryptionTypes><EncryptionType>" + type
                + "</EncryptionType></BlockedEncryptionTypes>"))
                .put("/" + bucket + "?encryption").then().statusCode(200);
        given().get("/" + bucket + "?encryption").then().statusCode(200)
                .body("ServerSideEncryptionConfiguration.Rule.BlockedEncryptionTypes.EncryptionType", equalTo(type));
    }

    private static RequestSpecification customerRequest() throws Exception {
        byte[] key = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        return given().header("x-amz-server-side-encryption-customer-algorithm", "AES256")
                .header("x-amz-server-side-encryption-customer-key", Base64.getEncoder().encodeToString(key))
                .header("x-amz-server-side-encryption-customer-key-MD5",
                        Base64.getEncoder().encodeToString(MessageDigest.getInstance("MD5").digest(key)));
    }

    private static void customerPost(String bucket, String objectKey, int status) throws Exception {
        byte[] key = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        given().multiPart("key", objectKey)
                .multiPart("x-amz-server-side-encryption-customer-algorithm", "AES256")
                .multiPart("x-amz-server-side-encryption-customer-key", Base64.getEncoder().encodeToString(key))
                .multiPart("x-amz-server-side-encryption-customer-key-MD5",
                        Base64.getEncoder().encodeToString(MessageDigest.getInstance("MD5").digest(key)))
                .multiPart("file", "object.txt", "posted body".getBytes(StandardCharsets.UTF_8))
                .post("/" + bucket).then().statusCode(status);
    }
}

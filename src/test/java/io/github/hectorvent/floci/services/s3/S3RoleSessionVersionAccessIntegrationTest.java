package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.IamService.RoleSessionCredentials;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * Least-privilege Lambda execution roles (assumed-role sessions) against versioned objects, with
 * global IAM enforcement off. As on S3: a caller without {@code s3:ListBucket} is told AccessDenied
 * instead of whether a version exists (RestoreObject still reports a selected delete marker as
 * 405), a copy source the role cannot read is denied, and a presigned URL whose signed
 * {@code versionId} was rewritten no longer verifies.
 */
@QuarkusTest
class S3RoleSessionVersionAccessIntegrationTest {

    private static final String REGION = "us-east-1";
    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    @Inject
    IamService iamService;

    @Test
    void missingVersionsAndDeleteMarkersAreDeniedWithoutListBucket() {
        String bucket = versionedBucket("conceal");
        String old = put(bucket, "key", "old");
        put(bucket, "key", "current");
        delete(bucket, "key", old);
        String marker = given().when().delete("/" + bucket + "/key").then().statusCode(204)
                .extract().header("x-amz-version-id");
        String tagsOnly = roleSession(bucket, "\"s3:GetObjectTagging\",\"s3:GetObjectVersionTagging\"", false);
        String tagsAndList = roleSession(bucket, "\"s3:GetObjectTagging\",\"s3:GetObjectVersionTagging\"", true);

        for (String versionId : new String[] {old, "null", marker}) {
            given().header("Authorization", auth(tagsOnly))
                    .queryParam("tagging", "").queryParam("versionId", versionId)
                    .when().get("/" + bucket + "/key")
                    .then().statusCode(403)
                    .body(containsString("<Code>AccessDenied</Code>"));
        }
        given().header("Authorization", auth(tagsAndList))
                .queryParam("tagging", "").queryParam("versionId", old)
                .when().get("/" + bucket + "/key")
                .then().statusCode(404)
                .body(containsString("<Code>NoSuchVersion</Code>"));
        given().header("Authorization", auth(tagsAndList))
                .queryParam("tagging", "").queryParam("versionId", marker)
                .when().get("/" + bucket + "/key")
                .then().statusCode(405)
                .body(containsString("<Code>MethodNotAllowed</Code>"));
    }

    @Test
    void taggingWritesToMissingVersionsAreDeniedWithoutListBucket() {
        String bucket = versionedBucket("conceal-write");
        String old = put(bucket, "key", "old");
        put(bucket, "key", "current");
        delete(bucket, "key", old);
        String session = roleSession(bucket,
                "\"s3:PutObjectTagging\",\"s3:PutObjectVersionTagging\","
                        + "\"s3:DeleteObjectTagging\",\"s3:DeleteObjectVersionTagging\"", false);

        given().header("Authorization", auth(session))
                .queryParam("tagging", "").queryParam("versionId", old)
                .body("<Tagging><TagSet/></Tagging>")
                .when().put("/" + bucket + "/key")
                .then().statusCode(403)
                .body(containsString("<Code>AccessDenied</Code>"));
        given().header("Authorization", auth(session))
                .queryParam("tagging", "").queryParam("versionId", old)
                .when().delete("/" + bucket + "/key")
                .then().statusCode(403)
                .body(containsString("<Code>AccessDenied</Code>"));
    }

    @Test
    void restoreConcealsMissingVersionsButReportsSelectedDeleteMarkers() {
        String bucket = versionedBucket("restore");
        String old = put(bucket, "key", "old");
        put(bucket, "key", "current");
        delete(bucket, "key", old);
        String marker = given().when().delete("/" + bucket + "/key").then().statusCode(204)
                .extract().header("x-amz-version-id");
        String session = roleSession(bucket, "\"s3:RestoreObject\"", false);
        String request = "<RestoreRequest><Days>1</Days></RestoreRequest>";

        given().header("Authorization", auth(session))
                .queryParam("restore", "").queryParam("versionId", old).body(request)
                .when().post("/" + bucket + "/key")
                .then().statusCode(403)
                .body(containsString("<Code>AccessDenied</Code>"));
        given().header("Authorization", auth(session))
                .queryParam("restore", "").queryParam("versionId", marker).body(request)
                .when().post("/" + bucket + "/key")
                .then().statusCode(405)
                .body(containsString("<Code>MethodNotAllowed</Code>"));
    }

    @Test
    void rootCallersStillSeeMissingVersions() {
        String bucket = versionedBucket("root");
        String old = put(bucket, "key", "old");
        put(bucket, "key", "current");
        delete(bucket, "key", old);

        given().queryParam("tagging", "").queryParam("versionId", old)
                .when().get("/" + bucket + "/key")
                .then().statusCode(404)
                .body(containsString("<Code>NoSuchVersion</Code>"));
    }

    @Test
    void copySourcesTheRoleCannotReadAreDenied() {
        String destination = versionedBucket("copy-dest");
        String unbound = versionedBucket("copy-unbound");
        String old = put(unbound, "source.txt", "unbound source");
        put(unbound, "source.txt", "unbound current");
        String session = roleSession(destination, "\"s3:PutObject\",\"s3:GetObject\",\"s3:GetObjectVersion\"", false);
        String uploadId = given().when().post("/" + destination + "/part.txt?uploads")
                .then().statusCode(200)
                .extract().xmlPath().getString("InitiateMultipartUploadResult.UploadId");

        given().header("Authorization", auth(session))
                .header("x-amz-copy-source", "/" + unbound + "/source.txt?versionId=" + old)
                .when().put("/" + destination + "/part.txt?uploadId=" + uploadId + "&partNumber=1")
                .then().statusCode(403)
                .body(containsString("<Code>AccessDenied</Code>"));
        given().header("Authorization", auth(session))
                .header("x-amz-copy-source", "/" + unbound + "/source.txt?versionId=" + old)
                .when().put("/" + destination + "/copy.txt")
                .then().statusCode(403)
                .body(containsString("<Code>AccessDenied</Code>"));
        given().when().get("/" + destination + "/copy.txt").then().statusCode(404);

        String own = put(destination, "own.txt", "own source");
        given().header("Authorization", auth(session))
                .header("x-amz-copy-source", "/" + destination + "/own.txt?versionId=" + own)
                .when().put("/" + destination + "/part.txt?uploadId=" + uploadId + "&partNumber=1")
                .then().statusCode(200)
                .header("x-amz-copy-source-version-id", own);
    }

    @Test
    void presignedUrlWithARewrittenVersionIdNoLongerVerifies() {
        String bucket = versionedBucket("presign");
        String old = put(bucket, "key.txt", "old body");
        String current = put(bucket, "key.txt", "current body");
        IamRole role = createRole(bucket, "\"s3:GetObject\",\"s3:GetObjectVersion\"", false);
        RoleSessionCredentials credentials = iamService.mintRoleSession(role.getArn());

        Map<String, String> query = presignQuery(credentials, old);
        String signature = presignSignature(credentials, "/" + bucket + "/key.txt", query);
        given().urlEncodingEnabled(false)
                .when().get("/" + bucket + "/key.txt?" + S3PresignedSignature.encodeQuery(query)
                        + "&X-Amz-Signature=" + signature)
                .then().statusCode(200)
                .header("x-amz-version-id", old)
                .body(equalTo("old body"));

        Map<String, String> tampered = new LinkedHashMap<>(query);
        tampered.put("versionId", current);
        given().urlEncodingEnabled(false)
                .when().get("/" + bucket + "/key.txt?" + S3PresignedSignature.encodeQuery(tampered)
                        + "&X-Amz-Signature=" + signature)
                .then().statusCode(403)
                .body(containsString("<Code>SignatureDoesNotMatch</Code>"));
    }

    private String roleSession(String bucket, String objectActions, boolean listBucket) {
        IamRole role = createRole(bucket, objectActions, listBucket);
        return iamService.mintRoleSession(role.getArn()).accessKeyId();
    }

    private IamRole createRole(String bucket, String objectActions, boolean listBucket) {
        String roleName = "s3-version-role-" + UUID.randomUUID().toString().substring(0, 8);
        IamRole role = iamService.createRole(roleName, "/", """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                 "Principal":{"Service":"lambda.amazonaws.com"},"Action":"sts:AssumeRole"}]}
                """, null, 3600, Map.of());
        String statements = "{\"Effect\":\"Allow\",\"Action\":[" + objectActions + "],"
                + "\"Resource\":\"arn:aws:s3:::" + bucket + "/*\"}"
                + (listBucket
                        ? ",{\"Effect\":\"Allow\",\"Action\":\"s3:ListBucket\",\"Resource\":\"arn:aws:s3:::" + bucket + "\"}"
                        : "");
        iamService.putRolePolicy(roleName, "Binding",
                "{\"Version\":\"2012-10-17\",\"Statement\":[" + statements + "]}");
        return role;
    }

    private static Map<String, String> presignQuery(RoleSessionCredentials credentials, String versionId) {
        String amzDate = AMZ_DATE.format(Instant.now());
        Map<String, String> query = new LinkedHashMap<>();
        query.put("X-Amz-Algorithm", "AWS4-HMAC-SHA256");
        query.put("X-Amz-Credential", credentials.accessKeyId() + "/" + amzDate.substring(0, 8) + "/" + REGION
                + "/s3/aws4_request");
        query.put("X-Amz-Date", amzDate);
        query.put("X-Amz-Expires", "900");
        query.put("X-Amz-Security-Token", credentials.sessionToken());
        query.put("X-Amz-SignedHeaders", "host");
        query.put("versionId", versionId);
        return query;
    }

    private static String presignSignature(RoleSessionCredentials credentials, String path,
                                           Map<String, String> query) {
        String credential = query.get("X-Amz-Credential");
        return S3PresignedSignature.signature("GET",
                S3PresignedSignature.encodeS3Path(path),
                S3PresignedSignature.encodeQuery(query),
                S3PresignedSignature.canonicalHeaders("host", "localhost:" + RestAssured.port, Map.of()),
                "host",
                query.get("X-Amz-Date"),
                S3PresignedSignature.credentialScope(credential),
                credentials.secretAccessKey());
    }

    private static String versionedBucket(String name) {
        String bucket = "role-" + name + "-" + UUID.randomUUID().toString().substring(0, 8);
        given().when().put("/" + bucket).then().statusCode(200);
        given().body("<VersioningConfiguration><Status>Enabled</Status></VersioningConfiguration>")
                .when().put("/" + bucket + "?versioning")
                .then().statusCode(200);
        return bucket;
    }

    private static String put(String bucket, String key, String body) {
        return given().contentType("text/plain").body(body)
                .when().put("/" + bucket + "/" + key)
                .then().statusCode(200)
                .extract().header("x-amz-version-id");
    }

    private static void delete(String bucket, String key, String versionId) {
        given().queryParam("versionId", versionId)
                .when().delete("/" + bucket + "/" + key)
                .then().statusCode(204);
    }

    private static String auth(String accessKeyId) {
        return "AWS4-HMAC-SHA256 Credential=" + accessKeyId + "/20260720/" + REGION
                + "/s3/aws4_request, SignedHeaders=host, Signature=abc";
    }
}

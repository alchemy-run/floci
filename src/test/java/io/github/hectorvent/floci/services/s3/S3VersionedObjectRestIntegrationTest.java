package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Wire behaviour of version selection: delete-marker versions, per-version tagging, the null
 * version, version-pinned copy sources, notification ids and restores.
 */
@QuarkusTest
class S3VersionedObjectRestIntegrationTest {

    @Test
    void selectedDeleteMarkerIsMethodNotAllowedWithMarkerHeaders() {
        String bucket = versionedBucket("marker");
        put(bucket, "key", "data");
        String marker = given().when().delete("/" + bucket + "/key")
                .then().statusCode(204)
                .header("x-amz-delete-marker", "true")
                .extract().header("x-amz-version-id");

        given().queryParam("versionId", marker)
                .when().get("/" + bucket + "/key")
                .then().statusCode(405)
                .header("x-amz-delete-marker", "true")
                .header("x-amz-version-id", marker)
                .header("Last-Modified", matchesPattern("\\w{3}, \\d{2} \\w{3} \\d{4} \\d{2}:\\d{2}:\\d{2} GMT"))
                .body(containsString("<Code>MethodNotAllowed</Code>"));
        given().queryParam("versionId", marker)
                .when().head("/" + bucket + "/key")
                .then().statusCode(405)
                .header("x-amz-delete-marker", "true")
                .header("Last-Modified", notNullValue());
        given().when().get("/" + bucket + "/key")
                .then().statusCode(404)
                .header("x-amz-delete-marker", "true")
                .header("Last-Modified", nullValue())
                .body(containsString("<Code>NoSuchKey</Code>"));
    }

    @Test
    void taggingSelectsAVersionAndReportsIt() {
        String bucket = versionedBucket("tagging");
        String old = put(bucket, "key", "old");
        String current = put(bucket, "key", "current");

        given().queryParam("tagging", "").queryParam("versionId", old)
                .body("<Tagging><TagSet><Tag><Key>generation</Key><Value>old</Value></Tag></TagSet></Tagging>")
                .when().put("/" + bucket + "/key")
                .then().statusCode(200)
                .header("x-amz-version-id", old);

        given().queryParam("tagging", "").queryParam("versionId", old)
                .when().get("/" + bucket + "/key")
                .then().statusCode(200)
                .header("x-amz-version-id", old)
                .body(containsString("<Value>old</Value>"));
        given().queryParam("tagging", "")
                .when().get("/" + bucket + "/key")
                .then().statusCode(200)
                .header("x-amz-version-id", current)
                .body(not(containsString("<Value>old</Value>")));

        given().queryParam("tagging", "").queryParam("versionId", old)
                .when().delete("/" + bucket + "/key")
                .then().statusCode(204)
                .header("x-amz-version-id", old);
        given().queryParam("tagging", "").queryParam("versionId", old)
                .when().get("/" + bucket + "/key")
                .then().statusCode(200)
                .body(not(containsString("<Tag>")));
    }

    @Test
    void nullVersionStaysListedAndSelectableAfterVersioningIsReEnabled() {
        String bucket = versionedBucket("null");
        setVersioning(bucket, "Suspended");
        put(bucket, "key", "null version");
        setVersioning(bucket, "Enabled");
        String current = put(bucket, "key", "current");

        given().queryParam("versions", "").queryParam("prefix", "key")
                .when().get("/" + bucket)
                .then().statusCode(200)
                .body(containsString("<VersionId>null</VersionId>"))
                .body(containsString("<VersionId>" + current + "</VersionId>"));
        given().queryParam("versionId", "null")
                .when().get("/" + bucket + "/key")
                .then().statusCode(200)
                .header("x-amz-version-id", "null")
                .body(equalTo("null version"));
        given().when().get("/" + bucket + "/key")
                .then().statusCode(200)
                .header("x-amz-version-id", current)
                .body(equalTo("current"));
    }

    @Test
    void copiesReportTheSourceVersionAndRejectASelectedDeleteMarker() {
        String bucket = versionedBucket("copy");
        String old = put(bucket, "source-key.txt", "old source");
        put(bucket, "source-key.txt", "new source");

        given().header("x-amz-copy-source", "/" + bucket + "/source-key.txt?versionId=" + old)
                .when().put("/" + bucket + "/copy.txt")
                .then().statusCode(200)
                .header("x-amz-copy-source-version-id", old)
                .header("x-amz-version-id", notNullValue());
        given().when().get("/" + bucket + "/copy.txt").then().statusCode(200).body(equalTo("old source"));

        String uploadId = given().when().post("/" + bucket + "/multipart.txt?uploads")
                .then().statusCode(200)
                .extract().xmlPath().getString("InitiateMultipartUploadResult.UploadId");
        given().header("x-amz-copy-source", "/" + bucket + "/source-key.txt?versionId=" + old)
                .when().put("/" + bucket + "/multipart.txt?uploadId=" + uploadId + "&partNumber=1")
                .then().statusCode(200)
                .header("x-amz-copy-source-version-id", old)
                .body(containsString("<ETag>"));

        String marker = given().when().delete("/" + bucket + "/source-key.txt")
                .then().statusCode(204).extract().header("x-amz-version-id");
        given().header("x-amz-copy-source", "/" + bucket + "/source-key.txt?versionId=" + marker)
                .when().put("/" + bucket + "/rejected.txt")
                .then().statusCode(400)
                .header("x-amz-delete-marker", nullValue())
                .body(containsString("<Code>InvalidRequest</Code>"));
        given().header("x-amz-copy-source", "/" + bucket + "/source-key.txt?versionId=" + marker)
                .when().put("/" + bucket + "/multipart.txt?uploadId=" + uploadId + "&partNumber=2")
                .then().statusCode(400)
                .body(containsString("<Code>InvalidRequest</Code>"));
        given().header("x-amz-copy-source", "/" + bucket + "/source-key.txt")
                .when().put("/" + bucket + "/rejected.txt")
                .then().statusCode(404)
                .body(containsString("<Code>NoSuchKey</Code>"));
    }

    @Test
    void notificationConfigurationsWithoutAnIdAreAssignedOne() {
        String bucket = versionedBucket("notif-id");
        given().body("""
                        <NotificationConfiguration>
                          <QueueConfiguration>
                            <Queue>arn:aws:sqs:us-east-1:000000000000:notif-id</Queue>
                            <Event>s3:ObjectCreated:*</Event>
                          </QueueConfiguration>
                        </NotificationConfiguration>""")
                .when().put("/" + bucket + "?notification")
                .then().statusCode(200);

        Response response = given().when().get("/" + bucket + "?notification");
        response.then().statusCode(200);
        String id = response.xmlPath().getString("NotificationConfiguration.QueueConfiguration.Id");
        assertThat(id, not(emptyString()));
    }

    @Test
    void restoringAnArchivedVersionReportsItsRestoreStatus() {
        String bucket = versionedBucket("restore");
        String archived = given().header("x-amz-storage-class", "GLACIER").body("archived")
                .when().put("/" + bucket + "/key")
                .then().statusCode(200).extract().header("x-amz-version-id");
        put(bucket, "key", "standard");
        String restoreRequest = "<RestoreRequest><Days>1</Days></RestoreRequest>";

        given().queryParam("restore", "").queryParam("versionId", archived).body(restoreRequest)
                .when().post("/" + bucket + "/key")
                .then().statusCode(202);
        given().queryParam("versionId", archived)
                .when().head("/" + bucket + "/key")
                .then().statusCode(200)
                .header("x-amz-storage-class", "GLACIER")
                .header("x-amz-restore", containsString("ongoing-request=\"false\""))
                .header("x-amz-restore", containsString("expiry-date=\""));
        given().when().head("/" + bucket + "/key")
                .then().statusCode(200)
                .header("x-amz-restore", nullValue());
        given().queryParam("restore", "").queryParam("versionId", archived).body(restoreRequest)
                .when().post("/" + bucket + "/key")
                .then().statusCode(200);
        given().queryParam("restore", "").body(restoreRequest)
                .when().post("/" + bucket + "/key")
                .then().statusCode(403)
                .body(containsString("<Code>InvalidObjectState</Code>"));
    }

    private static String versionedBucket(String name) {
        String bucket = "ver-" + name + "-" + UUID.randomUUID().toString().substring(0, 8);
        given().when().put("/" + bucket).then().statusCode(200);
        setVersioning(bucket, "Enabled");
        return bucket;
    }

    private static void setVersioning(String bucket, String status) {
        given().body("<VersioningConfiguration><Status>" + status + "</Status></VersioningConfiguration>")
                .when().put("/" + bucket + "?versioning")
                .then().statusCode(200);
    }

    private static String put(String bucket, String key, String body) {
        return given().contentType("text/plain").body(body)
                .when().put("/" + bucket + "/" + key)
                .then().statusCode(200)
                .extract().header("x-amz-version-id");
    }
}

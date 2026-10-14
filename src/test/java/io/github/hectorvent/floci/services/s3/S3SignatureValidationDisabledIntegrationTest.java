package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * S3 enforce-auth with the global {@code floci.auth.validate-signatures} switch off: signatures
 * are never verified cryptographically (as for every other Floci service), yet authorization
 * still runs against the caller identity named by the credential's access key, so bucket-policy
 * denies keep applying.
 */
@QuarkusTest
@TestProfile(S3SignatureValidationDisabledIntegrationTest.EnforceAuthWithoutSignatureValidation.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S3SignatureValidationDisabledIntegrationTest {

    public static final class EnforceAuthWithoutSignatureValidation implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci.services.s3.enforce-auth", "true",
                    "floci.auth.validate-signatures", "false");
        }
    }

    private static final String BUCKET = "sigoff-open-bucket";
    private static final String DENY_BUCKET = "sigoff-deny-bucket";
    private static final String USER_DENY_BUCKET = "sigoff-user-deny-bucket";
    private static final String KEY = "object.txt";
    private static final String USER_NAME = "sigoff-denied-user";
    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private static String deniedUserAccessKeyId;

    /** A structurally valid SigV4 header with a dummy signature, a stale date and no content hash. */
    private static RequestSpecification dummySigned(String accessKeyId, String service) {
        return given()
                .header("x-amz-date", "20260101T000000Z")
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=" + accessKeyId
                        + "/20260101/us-east-1/" + service
                        + "/aws4_request, SignedHeaders=host;x-amz-date, Signature=dummy");
    }

    private static String denyGetObject(String principal, String bucket) {
        return """
                {"Version":"2012-10-17","Statement":[{"Effect":"Deny","Principal":%s,
                  "Action":"s3:GetObject","Resource":"arn:aws:s3:::%s/*"}]}
                """.formatted(principal, bucket);
    }

    private static void createBucketWithObject(String bucket) {
        dummySigned("test", "s3").when().put("/" + bucket).then().statusCode(200);
        dummySigned("test", "s3").contentType("text/plain").body("payload")
                .when().put("/" + bucket + "/" + KEY).then().statusCode(200);
    }

    @Test
    @Order(1)
    void dummyHeaderSignatureWithoutContentHashIsAccepted() {
        createBucketWithObject(BUCKET);

        dummySigned("test", "s3")
        .when()
            .get("/" + BUCKET)
        .then()
            .statusCode(200)
            .body(containsString("<Key>" + KEY + "</Key>"));

        dummySigned("test", "s3")
        .when()
            .get("/" + BUCKET + "/" + KEY)
        .then()
            .statusCode(200)
            .body(equalTo("payload"));
    }

    @Test
    @Order(2)
    void anonymousRequestIsStillDeniedOnPrivateBucket() {
        given()
        .when()
            .get("/" + BUCKET + "/" + KEY)
        .then()
            .statusCode(403)
            .body(containsString("AccessDenied"));
    }

    @Test
    @Order(3)
    void bucketPolicyDenyAppliesToDummySignedCaller() {
        createBucketWithObject(DENY_BUCKET);
        dummySigned("test", "s3")
            .contentType("application/json")
            .body(denyGetObject("\"*\"", DENY_BUCKET))
        .when()
            .put("/" + DENY_BUCKET + "?policy")
        .then()
            .statusCode(200);

        dummySigned("test", "s3")
        .when()
            .get("/" + DENY_BUCKET + "/" + KEY)
        .then()
            .statusCode(403)
            .body(containsString("AccessDenied"));

        dummySigned("test", "s3")
        .when()
            .get("/" + DENY_BUCKET)
        .then()
            .statusCode(200);
    }

    @Test
    @Order(4)
    void bucketPolicyDenyTargetsTheCredentialAccessKeyIdentity() {
        dummySigned("000000000000", "iam")
            .formParam("Action", "CreateUser")
            .formParam("UserName", USER_NAME)
        .when()
            .post("/")
        .then()
            .statusCode(200);
        deniedUserAccessKeyId = dummySigned("000000000000", "iam")
            .formParam("Action", "CreateAccessKey")
            .formParam("UserName", USER_NAME)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract()
            .path("CreateAccessKeyResponse.CreateAccessKeyResult.AccessKey.AccessKeyId");

        createBucketWithObject(USER_DENY_BUCKET);
        dummySigned("test", "s3")
            .contentType("application/json")
            .body(denyGetObject("{\"AWS\":\"arn:aws:iam::000000000000:user/" + USER_NAME + "\"}",
                    USER_DENY_BUCKET))
        .when()
            .put("/" + USER_DENY_BUCKET + "?policy")
        .then()
            .statusCode(200);

        dummySigned(deniedUserAccessKeyId, "s3")
        .when()
            .get("/" + USER_DENY_BUCKET + "/" + KEY)
        .then()
            .statusCode(403)
            .body(containsString("AccessDenied"));

        dummySigned("test", "s3")
        .when()
            .get("/" + USER_DENY_BUCKET + "/" + KEY)
        .then()
            .statusCode(200)
            .body(equalTo("payload"));
    }

    @Test
    @Order(5)
    void presignedUrlSignatureIsNotVerifiedButStillAuthorized() {
        String now = AMZ_DATE.format(Instant.now());
        String credential = "test/" + now.substring(0, 8) + "/us-east-1/s3/aws4_request";

        given()
            .queryParam("X-Amz-Algorithm", "AWS4-HMAC-SHA256")
            .queryParam("X-Amz-Credential", credential)
            .queryParam("X-Amz-Date", now)
            .queryParam("X-Amz-Expires", "300")
            .queryParam("X-Amz-SignedHeaders", "host")
            .queryParam("X-Amz-Signature", "dummy")
        .when()
            .get("/" + BUCKET + "/" + KEY)
        .then()
            .statusCode(200)
            .body(equalTo("payload"));

        given()
            .queryParam("X-Amz-Algorithm", "AWS4-HMAC-SHA256")
            .queryParam("X-Amz-Credential", credential)
            .queryParam("X-Amz-Date", now)
            .queryParam("X-Amz-Expires", "300")
            .queryParam("X-Amz-SignedHeaders", "host")
            .queryParam("X-Amz-Signature", "dummy")
        .when()
            .get("/" + DENY_BUCKET + "/" + KEY)
        .then()
            .statusCode(403)
            .body(containsString("AccessDenied"));
    }
}

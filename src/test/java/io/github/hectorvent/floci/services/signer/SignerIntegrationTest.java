package io.github.hectorvent.floci.services.signer;

import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RestJson1 coverage used by Alchemy Signer bindings: platforms, profiles,
 * StartSigningJob / Describe / List / Revoke, SignPayload, GetRevocationStatus.
 */
@QuarkusTest
class SignerIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String ACCOUNT = "000000000000";

    @Inject
    S3Service s3Service;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getSigningProfileOnAMissingNameReturnsResourceNotFoundException() {
        given()
                .contentType("application/json")
                .header("Authorization", auth())
                .when()
                .get("/signing-profiles/missing_profile_name")
                .then()
                .statusCode(404)
                .header("X-Amzn-Errortype", equalTo("ResourceNotFoundException"))
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void listAndGetSigningPlatformsIncludeLambdaAndNotation() {
        given()
                .header("Authorization", auth())
                .queryParam("maxResults", 25)
                .when()
                .get("/signing-platforms")
                .then()
                .statusCode(200)
                .body("platforms.platformId", hasItems("AWSLambda-SHA384-ECDSA", "Notation-OCI-SHA384-ECDSA"));

        given()
                .header("Authorization", auth())
                .when()
                .get("/signing-platforms/AWSLambda-SHA384-ECDSA")
                .then()
                .statusCode(200)
                .body("platformId", equalTo("AWSLambda-SHA384-ECDSA"))
                .body("revocationSupported", equalTo(true));
    }

    @Test
    void putGetListCancelSigningProfileAndTags() {
        String name = "Alchemy_Bindings_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String authorization = auth();

        String arn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "platformId":"AWSLambda-SHA384-ECDSA",
                          "tags":{"alchemy::id":"BindingsProfile"}
                        }
                        """)
                .when()
                .put("/signing-profiles/" + name)
                .then()
                .statusCode(200)
                .body("arn", notNullValue())
                .body("profileVersion", notNullValue())
                .extract().path("arn");

        given()
                .header("Authorization", authorization)
                .when()
                .get("/signing-profiles/" + name)
                .then()
                .statusCode(200)
                .body("profileName", equalTo(name))
                .body("platformId", equalTo("AWSLambda-SHA384-ECDSA"))
                .body("platformDisplayName", equalTo("AWS Lambda"))
                .body("status", equalTo("Active"))
                .body("signatureValidityPeriod.value", equalTo(135))
                .body("signatureValidityPeriod.type", equalTo("MONTHS"));
        Map<String, String> tags = given()
                .header("Authorization", authorization)
                .when()
                .get("/signing-profiles/" + name)
                .then()
                .statusCode(200)
                .extract().path("tags");
        org.junit.jupiter.api.Assertions.assertEquals("BindingsProfile", tags.get("alchemy::id"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"platformId\":\"AWSLambda-SHA384-ECDSA\"}")
                .when()
                .put("/signing-profiles/" + name)
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/signing-profiles")
                .then()
                .statusCode(200)
                .body("profiles.profileName", hasItem(name));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"tags\":{\"env\":\"test\"}}")
                .when()
                .post("/tags/" + arn)
                .then()
                .statusCode(204);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/signing-profiles/" + name)
                .then()
                .statusCode(200)
                .body("tags.env", equalTo("test"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/signing-profiles/" + name)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/signing-profiles/" + name)
                .then()
                .statusCode(200)
                .body("status", equalTo("Canceled"));
    }

    @Test
    void putSigningProfileHonorsValidityPeriodTagsAndCancelIsIdempotent() {
        String first = "FlociSignerIT_valid_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String second = "FlociSignerIT_repl_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String authorization = auth();

        String firstArn = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "platformId":"AWSLambda-SHA384-ECDSA",
                          "signatureValidityPeriod":{"value":12,"type":"MONTHS"},
                          "tags":{"purpose":"alchemy-test","alchemy::id":"ReleaseProfile"}
                        }
                        """)
                .when()
                .put("/signing-profiles/" + first)
                .then()
                .statusCode(200)
                .body("arn", notNullValue())
                .body("profileVersionArn", notNullValue())
                .extract().path("arn");
        assertTrue(firstArn.contains(first));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/signing-profiles/" + first)
                .then()
                .statusCode(200)
                .body("status", equalTo("Active"))
                .body("signatureValidityPeriod.value", equalTo(12))
                .body("signatureValidityPeriod.type", equalTo("MONTHS"))
                .body("tags.purpose", equalTo("alchemy-test"))
                .body("tags['alchemy::id']", equalTo("ReleaseProfile"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"tags\":{\"stage\":\"two\"}}")
                .when()
                .post("/tags/" + firstArn)
                .then()
                .statusCode(204);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/signing-profiles/" + first)
                .then()
                .statusCode(200)
                .body("tags.stage", equalTo("two"))
                .body("tags.purpose", equalTo("alchemy-test"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "platformId":"AWSLambda-SHA384-ECDSA",
                          "signatureValidityPeriod":{"value":24,"type":"MONTHS"},
                          "tags":{"purpose":"alchemy-test","stage":"two"}
                        }
                        """)
                .when()
                .put("/signing-profiles/" + second)
                .then()
                .statusCode(200)
                .body("arn", notNullValue());

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/signing-profiles/" + first)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/signing-profiles/" + first)
                .then()
                .statusCode(200)
                .body("status", equalTo("Canceled"));

        given()
                .header("Authorization", authorization)
                .when()
                .get("/signing-profiles/" + second)
                .then()
                .statusCode(200)
                .body("status", equalTo("Active"))
                .body("signatureValidityPeriod.value", equalTo(24))
                .body("signatureValidityPeriod.type", equalTo("MONTHS"));

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/signing-profiles/" + first)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .delete("/signing-profiles/" + second)
                .then()
                .statusCode(200);
    }

    @Test
    void startSigningJobCopiesObjectThenRevokeAndRevocationStatus() {
        String name = "Alchemy_Job_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String src = "signer-src-" + UUID.randomUUID().toString().substring(0, 8);
        String dst = "signer-dst-" + UUID.randomUUID().toString().substring(0, 8);
        s3Service.createBucket(src, EAST);
        s3Service.createBucket(dst, EAST);
        s3Service.putBucketVersioning(src, "Enabled");
        var uploaded = s3Service.putObject(src, "code.zip", "exports.handler=async()=>({})".getBytes(StandardCharsets.UTF_8),
                "application/zip", Map.of());
        String version = uploaded.getVersionId();
        assertTrue(version != null && !version.isBlank());

        String authorization = auth();
        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"platformId\":\"AWSLambda-SHA384-ECDSA\"}")
                .when()
                .put("/signing-profiles/" + name)
                .then()
                .statusCode(200);

        String jobId = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "profileName":"%s",
                          "clientRequestToken":"%s",
                          "source":{"s3":{"bucketName":"%s","key":"code.zip","version":"%s"}},
                          "destination":{"s3":{"bucketName":"%s","prefix":"signed/"}}
                        }
                        """.formatted(name, UUID.randomUUID(), src, version, dst))
                .when()
                .post("/signing-jobs")
                .then()
                .statusCode(200)
                .body("jobId", notNullValue())
                .extract().path("jobId");

        Response described = given()
                .header("Authorization", authorization)
                .when()
                .get("/signing-jobs/" + jobId)
                .then()
                .statusCode(200)
                .body("status", equalTo("Succeeded"))
                .body("signedObject.s3.key", equalTo("signed/code.zip"))
                .extract().response();

        given()
                .header("Authorization", authorization)
                .when()
                .get("/signing-jobs")
                .then()
                .statusCode(200)
                .body("jobs.jobId", hasItem(jobId));

        String jobArn = "arn:aws:signer:" + EAST + ":" + ACCOUNT + ":/signing-jobs/" + jobId;
        Object completedAt = described.path("completedAt");
        Object profileVersion = described.path("profileVersion");
        String profileVersionArn = SignerService.profileArn(EAST, ACCOUNT, name) + "/"
                + (profileVersion == null ? "version" : String.valueOf(profileVersion));
        String signatureTimestamp = completedAt == null ? "0" : String.valueOf(completedAt);

        given()
                .header("Authorization", authorization)
                .queryParam("signatureTimestamp", signatureTimestamp)
                .queryParam("platformId", "AWSLambda-SHA384-ECDSA")
                .queryParam("profileVersionArn", profileVersionArn)
                .queryParam("jobArn", jobArn)
                .when()
                .get("/revocations")
                .then()
                .statusCode(200)
                .body("revokedEntities.size()", equalTo(0));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"reason\":\"alchemy-test\"}")
                .when()
                .put("/signing-jobs/" + jobId + "/revoke")
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .queryParam("signatureTimestamp", signatureTimestamp)
                .queryParam("platformId", "AWSLambda-SHA384-ECDSA")
                .queryParam("profileVersionArn", profileVersionArn)
                .queryParam("jobArn", jobArn)
                .when()
                .get("/revocations")
                .then()
                .statusCode(200)
                .body("revokedEntities", hasItem(jobArn));
    }

    @Test
    void signPayloadOnNotationProfileReturnsSignature() {
        String name = "Alchemy_Notation_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String authorization = auth();
        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"platformId\":\"Notation-OCI-SHA384-ECDSA\"}")
                .when()
                .put("/signing-profiles/" + name)
                .then()
                .statusCode(200);

        String payload = Base64.getEncoder().encodeToString(
                "{\"targetArtifact\":{\"mediaType\":\"application/vnd.oci.image.manifest.v1+json\"}}"
                        .getBytes(StandardCharsets.UTF_8));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "profileName":"%s",
                          "payload":"%s",
                          "payloadFormat":"application/vnd.cncf.notary.payload.v1+json"
                        }
                        """.formatted(name, payload))
                .when()
                .post("/signing-jobs/with-payload")
                .then()
                .statusCode(200)
                .body("jobId", notNullValue())
                .body("signature", notNullValue());
    }

    @Test
    void addListUpdateRemoveProfilePermissionLifecycle() {
        String name = "PermProfile_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String authorization = auth();
        String principal = ACCOUNT;
        String statement = "CiCanSign";

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"platformId\":\"AWSLambda-SHA384-ECDSA\"}")
                .when()
                .put("/signing-profiles/" + name)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", authorization)
                .when()
                .get("/signing-profiles/" + name + "/permissions")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("message", org.hamcrest.Matchers.containsString("No policies associated with profile"));

        String firstRevision = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "action":"signer:StartSigningJob",
                          "principal":"%s",
                          "statementId":"%s"
                        }
                        """.formatted(principal, statement))
                .when()
                .post("/signing-profiles/" + name + "/permissions")
                .then()
                .statusCode(200)
                .body("revisionId", notNullValue())
                .extract()
                .path("revisionId");

        given()
                .header("Authorization", authorization)
                .when()
                .get("/signing-profiles/" + name + "/permissions")
                .then()
                .statusCode(200)
                .body("revisionId", equalTo(firstRevision))
                .body("permissions[0].statementId", equalTo(statement))
                .body("permissions[0].action", equalTo("signer:StartSigningJob"))
                .body("permissions[0].principal", equalTo(principal));

        given()
                .header("Authorization", authorization)
                .queryParam("revisionId", firstRevision)
                .when()
                .delete("/signing-profiles/" + name + "/permissions/" + statement)
                .then()
                .statusCode(200)
                .body("revisionId", notNullValue());

        given()
                .header("Authorization", authorization)
                .when()
                .get("/signing-profiles/" + name + "/permissions")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "action":"signer:GetSigningProfile",
                          "principal":"%s",
                          "statementId":"%s"
                        }
                        """.formatted(principal, statement))
                .when()
                .post("/signing-profiles/" + name + "/permissions")
                .then()
                .statusCode(200)
                .body("revisionId", notNullValue());

        given()
                .header("Authorization", authorization)
                .when()
                .get("/signing-profiles/" + name + "/permissions")
                .then()
                .statusCode(200)
                .body("permissions.size()", equalTo(1))
                .body("permissions[0].action", equalTo("signer:GetSigningProfile"))
                .body("permissions[0].statementId", equalTo(statement));
    }

    private static String auth() {
        return "AWS4-HMAC-SHA256 Credential=" + ACCOUNT + "/20260205/" + EAST + "/signer/aws4_request";
    }
}

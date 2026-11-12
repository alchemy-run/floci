package io.floci.aws.services.signer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AWS Signer: profiles with their own keys, permissions, a signing job over a versioned S3 object whose signed .zip
 * Lambda accepts under an Enforce code signing configuration (and rejects unsigned, tampered or revoked), Notation
 * JWS signatures that verify against their own certificate, and revocation.
 */
@QuarkusTest
class SignerIntegrationTest {

    static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    static String auth(String service) {
        return "AWS4-HMAC-SHA256 Credential=000000000000/20260101/us-east-1/" + service + "/aws4_request";
    }

    static RequestSpecification signer() {
        return given().header("Authorization", auth("signer")).contentType("application/json");
    }

    static RequestSpecification lambda() {
        return given().header("Authorization", auth("lambda")).contentType("application/json");
    }

    static RequestSpecification s3() {
        return given().header("Authorization", auth("s3"));
    }

    static byte[] zip(String source) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            out.putNextEntry(new ZipEntry("index.js"));
            out.write(source.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return bytes.toByteArray();
    }

    static String createFunction(String name, String csc, byte[] code) {
        String role = "arn:aws:iam::000000000000:role/signer-test";
        return "{\"FunctionName\":\"" + name + "\",\"Runtime\":\"nodejs20.x\",\"Handler\":\"index.handler\","
                + "\"Role\":\"" + role + "\",\"CodeSigningConfigArn\":\"" + csc + "\",\"Code\":{\"ZipFile\":\""
                + Base64.getEncoder().encodeToString(code) + "\"}}";
    }

    @Test
    void signsCodeThatLambdaVerifies() throws Exception {
        signer().get("/signing-platforms").then().statusCode(200)
                .body("platforms.platformId", hasItem("AWSLambda-SHA384-ECDSA"));
        signer().get("/signing-platforms/AWSLambda-SHA384-ECDSA").then().statusCode(200)
                .body("revocationSupported", equalTo(true));

        String versionArn = signer().body("{\"platformId\":\"AWSLambda-SHA384-ECDSA\",\"signatureValidityPeriod\":"
                + "{\"value\":12,\"type\":\"MONTHS\"},\"tags\":{\"team\":\"rel\"}}").put("/signing-profiles/release")
                .then().statusCode(200).body("arn", containsString(":/signing-profiles/release")).extract()
                .path("profileVersionArn");
        signer().body("{\"platformId\":\"AWSLambda-SHA384-ECDSA\"}").put("/signing-profiles/release").then()
                .statusCode(400).body("message", containsString("already exists"));
        signer().body("{\"platformId\":\"AWSLambda-SHA384-ECDSA\"}")
                .put("/signing-profiles/a_profile_name_far_longer_than_a_certificate_common_name_can").then()
                .statusCode(200);
        signer().get("/signing-profiles/not-a-valid-profile-name").then().statusCode(404)
                .body("__type", containsString("ResourceNotFoundException"));

        // Permissions are revisioned.
        String revision = signer().body("{\"action\":\"signer:StartSigningJob\",\"principal\":\"111122223333\","
                + "\"statementId\":\"ci\"}").post("/signing-profiles/release/permissions").then().statusCode(200)
                .extract().path("revisionId");
        signer().body("{\"action\":\"signer:Delete\",\"principal\":\"111122223333\",\"statementId\":\"x\"}")
                .post("/signing-profiles/release/permissions").then().statusCode(400);
        signer().delete("/signing-profiles/release/permissions/ci?revisionId=stale").then().statusCode(409);
        signer().get("/signing-profiles/release/permissions").then().body("revisionId", equalTo(revision))
                .body("permissions.statementId", equalTo(List.of("ci")));

        // Sign a .zip from a versioned bucket.
        byte[] code = zip("exports.handler = async () => 'signed';");
        s3().put("/signer-src").then().statusCode(200);
        s3().put("/signer-dst").then().statusCode(200);
        s3().body(code).put("/signer-src/code.zip").then().statusCode(200);
        signer().body("{\"profileName\":\"release\",\"clientRequestToken\":\"t0\",\"source\":{\"s3\":{\"bucketName\":"
                + "\"signer-src\",\"key\":\"code.zip\",\"version\":\"null\"}},\"destination\":{\"s3\":{\"bucketName\":"
                + "\"signer-dst\",\"prefix\":\"signed/\"}}}").post("/signing-jobs").then().statusCode(400)
                .body("message", containsString("versioning"));
        s3().contentType("application/xml").body("<VersioningConfiguration><Status>Enabled</Status>"
                + "</VersioningConfiguration>").put("/signer-src?versioning").then().statusCode(200);
        String version = s3().body(code).put("/signer-src/code.zip").then().statusCode(200).extract()
                .header("x-amz-version-id");
        String job = signer().body("{\"profileName\":\"release\",\"clientRequestToken\":\"t1\",\"source\":{\"s3\":"
                + "{\"bucketName\":\"signer-src\",\"key\":\"code.zip\",\"version\":\"" + version + "\"}},"
                + "\"destination\":{\"s3\":{\"bucketName\":\"signer-dst\",\"prefix\":\"signed/\"}}}")
                .post("/signing-jobs").then().statusCode(200).extract().path("jobId");
        String signedKey = signer().get("/signing-jobs/" + job).then().statusCode(200)
                .body("status", equalTo("Succeeded")).body("profileVersion", equalTo(versionArn
                        .substring(versionArn.lastIndexOf('/') + 1))).extract().path("signedObject.s3.key");
        assertEquals("signed/" + job + ".zip", signedKey);
        byte[] signed = s3().get("/signer-dst/" + signedKey).then().statusCode(200).extract().asByteArray();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(signed))) {
            assertEquals("index.js", in.getNextEntry().getName());
            assertTrue(new String(in.readAllBytes(), StandardCharsets.UTF_8).contains("signed"));
        }

        // Lambda takes only code signed by an allowed profile version.
        String csc = lambda().body("{\"AllowedPublishers\":{\"SigningProfileVersionArns\":[\"" + versionArn + "\"]},"
                + "\"CodeSigningPolicies\":{\"UntrustedArtifactOnDeployment\":\"Enforce\"}}")
                .post("/2020-04-22/code-signing-configs").then().statusCode(201).extract()
                .path("CodeSigningConfig.CodeSigningConfigArn");
        lambda().body(createFunction("unsigned", csc, code)).post("/2015-03-31/functions").then().statusCode(400)
                .body("message", containsString("isn't signed"));
        byte[] tampered = signed.clone();
        tampered[40] ^= 0x01;
        lambda().body(createFunction("tampered", csc, tampered)).post("/2015-03-31/functions").then()
                .statusCode(400).body("message", containsString("doesn't match"));
        lambda().body(createFunction("trusted", csc, signed)).post("/2015-03-31/functions").then().statusCode(201);

        // Revoking the job's signature makes its code untrusted.
        signer().body("{\"reason\":\"leaked\"}").put("/signing-jobs/" + job + "/revoke").then().statusCode(200);
        lambda().body("{\"ZipFile\":\"" + Base64.getEncoder().encodeToString(signed) + "\"}")
                .put("/2015-03-31/functions/trusted/code").then().statusCode(400)
                .body("message", containsString("revoked"));
        String jobArn = "arn:aws:signer:us-east-1:000000000000:/signing-jobs/" + job;
        signer().queryParam("signatureTimestamp", Instant.now().toString())
                .queryParam("platformId", "AWSLambda-SHA384-ECDSA").queryParam("profileVersionArn", versionArn)
                .queryParam("jobArn", jobArn).queryParam("certificateHashes", "00")
                .get("/revocations").then().statusCode(200).body("revokedEntities", equalTo(List.of(jobArn)));

        // Notation payloads get a JWS that verifies against the certificate it carries.
        signer().body("{\"platformId\":\"Notation-OCI-SHA384-ECDSA\"}").put("/signing-profiles/images").then()
                .statusCode(200);
        String payload = "{\"targetArtifact\":{\"mediaType\":\"application/vnd.oci.image.manifest.v1+json\","
                + "\"digest\":\"sha256:73c8\",\"size\":1024}}";
        String signature = signer().body("{\"profileName\":\"images\",\"payloadFormat\":"
                + "\"application/vnd.cncf.notary.payload.v1+json\",\"payload\":\"" + Base64.getEncoder()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + "\"}").post("/signing-jobs/with-payload")
                .then().statusCode(200).body("metadata.signingProfileVersionArn", containsString("images"))
                .extract().path("signature");
        JsonNode jws = MAPPER.readTree(Base64.getDecoder().decode(signature));
        assertArrayEquals(payload.getBytes(StandardCharsets.UTF_8),
                Base64.getUrlDecoder().decode(jws.path("payload").asText()));
        X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(
                new ByteArrayInputStream(Base64.getDecoder().decode(jws.path("header").path("x5c").get(0).asText())));
        Signature verifier = Signature.getInstance("SHA384withECDSAinP1363Format");
        verifier.initVerify(cert.getPublicKey());
        verifier.update((jws.path("protected").asText() + "." + jws.path("payload").asText())
                .getBytes(StandardCharsets.US_ASCII));
        assertTrue(verifier.verify(Base64.getUrlDecoder().decode(jws.path("signature").asText())));

        // Revoking and canceling profiles.
        signer().body("{\"profileVersion\":\"" + versionArn.substring(versionArn.lastIndexOf('/') + 1)
                + "\",\"reason\":\"rotated\",\"effectiveTime\":" + (Instant.now().getEpochSecond() - 60) + "}")
                .put("/signing-profiles/release/revoke").then().statusCode(200);
        signer().get("/signing-profiles/release").then().body("status", equalTo("Revoked"))
                .body("tags.team", equalTo("rel"));
        signer().delete("/signing-profiles/images").then().statusCode(200);
        signer().get("/signing-profiles").then().body("profiles.profileName", not(hasItem("images")));
        signer().get("/signing-profiles?includeCanceled=true").then().body("profiles.profileName", hasItem("images"));
    }
}

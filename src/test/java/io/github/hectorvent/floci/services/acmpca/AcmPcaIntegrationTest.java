package io.github.hectorvent.floci.services.acmpca;

import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AcmPcaIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH =
        "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/acm-pca/aws4_request";
    private static final String MISSING_CA =
        "arn:aws:acm-pca:us-east-1:000000000000:certificate-authority/00000000-0000-0000-0000-000000000000";
    private static final String NON_CA_ARN =
        "arn:aws:acm-pca:us-east-1:000000000000:something/else";

    private static final String LEAF_CSR = """
            -----BEGIN CERTIFICATE REQUEST-----
            MIICgTCCAWkCAQAwPDEjMCEGA1UEAwwabGVhZi5hbGNoZW15LXRlc3QuaW50ZXJu
            YWwxFTATBgNVBAoMDEFsY2hlbXkgVGVzdDCCASIwDQYJKoZIhvcNAQEBBQADggEP
            ADCCAQoCggEBALGadvvgETlb8XMOiqRBUz/L+C3Ilc2d43TRYROvMXKnbzewzyA2
            EyBhXYUvWvewXH6chrC8GqcgbHGLs4GyHfLE3qO6TAF8mBzdvZo8J0aKGBMZLASA
            yq/JwwSawzGuQp818h/Qs85nxqEk8oxOQPMK3e6N7z81JC/nWEV/c9bQa5HGPiGF
            0sMbNik5wPaMxxmt6Njx3P9DwbJWjcDaoslpY44iQrtWON3kiGVIFSE7jPX5B/Na
            i3RZJofI/UhMZkS5sHrAboNuZ8p03AS7O+zCkLTvvD948N4FPIWUP0khZocaR+k1
            xWgv/lFU0OBy3+SGifqb9QwcM5aD8ExN1UsCAwEAAaAAMA0GCSqGSIb3DQEBCwUA
            A4IBAQAIk42ecyyxlCHTA6xarCx74SgptR3VyCvgFj4qErEJA/ST3l26Emx5uuAC
            59hfZq42szQ/6VZwJTq71ThwUFPP+x3LpXRJnuHAOP2ORZ0x4LJwHlPiEZR2GFnZ
            CWuYpynAYgAKUh0ubeFzyp/odXwemlbaFc52JuxA9gUPVuThsiFqIWH780cfA9NM
            VIhBWsnInHBw0ffVQDbxcIRqP9qxwOIH9SHGKPXz1F5CfvSII9Z5QqeMuZwdXu7k
            Sy1wRo1UPY6ot0Kstqs8TJVA6R220wEk5iUEg/d3Ft1W0T17I60Gx5P6LgxjTJbp
            WDtemfBJScvPotEJ+xTh9kn81whi
            -----END CERTIFICATE REQUEST-----
            """;

    @Inject
    S3Service s3Service;

    private String createdArn;
    private String bindingsCaArn;
    private String bindingsCsr;
    private String leafCertificateArn;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void describeCertificateAuthority_missingCa_resourceNotFound() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "ACMPrivateCA.DescribeCertificateAuthority")
            .header("Authorization", AUTH)
            .body("{\"CertificateAuthorityArn\":\"" + MISSING_CA + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(2)
    void getPolicy_missingCa_resourceNotFound() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "ACMPrivateCA.GetPolicy")
            .header("Authorization", AUTH)
            .body("{\"ResourceArn\":\"" + MISSING_CA + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(3)
    void describeCertificateAuthority_nonCaArn_invalidArn() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "ACMPrivateCA.DescribeCertificateAuthority")
            .header("Authorization", AUTH)
            .body("{\"CertificateAuthorityArn\":\"" + NON_CA_ARN + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidArnException"));
    }

    @Test
    @Order(4)
    void createDescribeTagPermissionPolicyAndDelete() {
        createdArn = given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "ACMPrivateCA.CreateCertificateAuthority")
            .header("Authorization", AUTH)
            .body("""
                {
                  "CertificateAuthorityConfiguration": {
                    "KeyAlgorithm": "RSA_2048",
                    "SigningAlgorithm": "SHA256WITHRSA",
                    "Subject": { "CommonName": "alchemy-test.internal" }
                  },
                  "CertificateAuthorityType": "ROOT",
                  "UsageMode": "SHORT_LIVED_CERTIFICATE",
                  "Tags": [{ "Key": "fixture", "Value": "acmpca" }]
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CertificateAuthorityArn", startsWith("arn:aws:acm-pca:"))
            .body("CertificateAuthorityArn", containsString(":certificate-authority/"))
            .extract().jsonPath().getString("CertificateAuthorityArn");

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "ACMPrivateCA.DescribeCertificateAuthority")
            .header("Authorization", AUTH)
            .body("{\"CertificateAuthorityArn\":\"" + createdArn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CertificateAuthority.Status", equalTo("PENDING_CERTIFICATE"))
            .body("CertificateAuthority.UsageMode", equalTo("SHORT_LIVED_CERTIFICATE"))
            .body("CertificateAuthority.CertificateAuthorityConfiguration.Subject.CommonName",
                equalTo("alchemy-test.internal"));

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "ACMPrivateCA.ListCertificateAuthorities")
            .header("Authorization", AUTH)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CertificateAuthorities.Arn", hasItem(createdArn))
            .body("CertificateAuthorities.find { it.Arn == '" + createdArn + "' }.Status",
                equalTo("PENDING_CERTIFICATE"));

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "ACMPrivateCA.TagCertificateAuthority")
            .header("Authorization", AUTH)
            .body("{\"CertificateAuthorityArn\":\"" + createdArn
                + "\",\"Tags\":[{\"Key\":\"updated\",\"Value\":\"true\"}]}")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "ACMPrivateCA.ListTags")
            .header("Authorization", AUTH)
            .body("{\"CertificateAuthorityArn\":\"" + createdArn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Tags.find { it.Key == 'updated' }.Value", equalTo("true"))
            .body("Tags.find { it.Key == 'fixture' }.Value", equalTo("acmpca"));

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "ACMPrivateCA.CreatePermission")
            .header("Authorization", AUTH)
            .body("{\"CertificateAuthorityArn\":\"" + createdArn
                + "\",\"Principal\":\"acm.amazonaws.com\",\"Actions\":[\"IssueCertificate\",\"GetCertificate\",\"ListPermissions\"]}")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "ACMPrivateCA.ListPermissions")
            .header("Authorization", AUTH)
            .body("{\"CertificateAuthorityArn\":\"" + createdArn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Permissions[0].Principal", equalTo("acm.amazonaws.com"))
            .body("Permissions[0].Actions", hasItems("IssueCertificate", "GetCertificate", "ListPermissions"));

        String policy = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"AWS\":\"arn:aws:iam::000000000000:root\"},\"Action\":[\"acm-pca:DescribeCertificateAuthority\"],\"Resource\":\""
            + createdArn + "\"}]}";
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "ACMPrivateCA.PutPolicy")
            .header("Authorization", AUTH)
            .body("{\"ResourceArn\":\"" + createdArn + "\",\"Policy\":" + toJsonString(policy) + "}")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "ACMPrivateCA.GetPolicy")
            .header("Authorization", AUTH)
            .body("{\"ResourceArn\":\"" + createdArn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Policy", containsString("acm-pca:DescribeCertificateAuthority"));

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "ACMPrivateCA.DeleteCertificateAuthority")
            .header("Authorization", AUTH)
            .body("{\"CertificateAuthorityArn\":\"" + createdArn + "\",\"PermanentDeletionTimeInDays\":7}")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "ACMPrivateCA.DescribeCertificateAuthority")
            .header("Authorization", AUTH)
            .body("{\"CertificateAuthorityArn\":\"" + createdArn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CertificateAuthority.Status", equalTo("DELETED"))
            .body("CertificateAuthority.Status", not(equalTo("PENDING_CERTIFICATE")));
    }

    @Test
    @Order(5)
    void getCsrIssueImportRevokeAndAuditReport() throws Exception {
        bindingsCaArn = given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "ACMPrivateCA.CreateCertificateAuthority")
            .header("Authorization", AUTH)
            .body("""
                {
                  "CertificateAuthorityConfiguration": {
                    "KeyAlgorithm": "RSA_2048",
                    "SigningAlgorithm": "SHA256WITHRSA",
                    "Subject": { "CommonName": "bindings.alchemy-test.internal" }
                  },
                  "CertificateAuthorityType": "ROOT",
                  "UsageMode": "SHORT_LIVED_CERTIFICATE"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().jsonPath().getString("CertificateAuthorityArn");

        bindingsCsr = given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "ACMPrivateCA.GetCertificateAuthorityCsr")
            .header("Authorization", AUTH)
            .body("{\"CertificateAuthorityArn\":\"" + bindingsCaArn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Csr", containsString("BEGIN CERTIFICATE REQUEST"))
            .extract().jsonPath().getString("Csr");

        String rootCertificateArn = given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "ACMPrivateCA.IssueCertificate")
            .header("Authorization", AUTH)
            .body("{"
                + "\"CertificateAuthorityArn\":\"" + bindingsCaArn + "\","
                + "\"Csr\":\"" + b64(bindingsCsr) + "\","
                + "\"SigningAlgorithm\":\"SHA256WITHRSA\","
                + "\"TemplateArn\":\"arn:aws:acm-pca:::template/RootCACertificate/V1\","
                + "\"Validity\":{\"Type\":\"YEARS\",\"Value\":10}"
                + "}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CertificateArn", containsString(bindingsCaArn))
            .body("CertificateArn", containsString("/certificate/"))
            .extract().jsonPath().getString("CertificateArn");

        String certificate = given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "ACMPrivateCA.GetCertificate")
            .header("Authorization", AUTH)
            .body("{\"CertificateAuthorityArn\":\"" + bindingsCaArn
                + "\",\"CertificateArn\":\"" + rootCertificateArn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Certificate", containsString("BEGIN CERTIFICATE"))
            .extract().jsonPath().getString("Certificate");

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "ACMPrivateCA.ImportCertificateAuthorityCertificate")
            .header("Authorization", AUTH)
            .body("{\"CertificateAuthorityArn\":\"" + bindingsCaArn
                + "\",\"Certificate\":\"" + b64(certificate) + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "ACMPrivateCA.DescribeCertificateAuthority")
            .header("Authorization", AUTH)
            .body("{\"CertificateAuthorityArn\":\"" + bindingsCaArn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CertificateAuthority.Status", equalTo("ACTIVE"));

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "ACMPrivateCA.GetCertificateAuthorityCertificate")
            .header("Authorization", AUTH)
            .body("{\"CertificateAuthorityArn\":\"" + bindingsCaArn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Certificate", containsString("BEGIN CERTIFICATE"));

        leafCertificateArn = given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "ACMPrivateCA.IssueCertificate")
            .header("Authorization", AUTH)
            .body("{"
                + "\"CertificateAuthorityArn\":\"" + bindingsCaArn + "\","
                + "\"Csr\":\"" + b64(LEAF_CSR) + "\","
                + "\"SigningAlgorithm\":\"SHA256WITHRSA\","
                + "\"Validity\":{\"Type\":\"DAYS\",\"Value\":5}"
                + "}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CertificateArn", containsString("/certificate/"))
            .extract().jsonPath().getString("CertificateArn");

        String leafPem = given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "ACMPrivateCA.GetCertificate")
            .header("Authorization", AUTH)
            .body("{\"CertificateAuthorityArn\":\"" + bindingsCaArn
                + "\",\"CertificateArn\":\"" + leafCertificateArn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Certificate", containsString("BEGIN CERTIFICATE"))
            .extract().jsonPath().getString("Certificate");

        String serial = AcmPcaCertificates.toSerialHex(
            AcmPcaCertificates.parseCertificate(leafPem).getSerialNumber());

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "ACMPrivateCA.RevokeCertificate")
            .header("Authorization", AUTH)
            .body("{\"CertificateAuthorityArn\":\"" + bindingsCaArn
                + "\",\"CertificateSerial\":\"" + serial
                + "\",\"RevocationReason\":\"CESSATION_OF_OPERATION\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        s3Service.createBucket("alchemy-test-acmpca-audit-reports", "us-east-1");
        String reportId = given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "ACMPrivateCA.CreateCertificateAuthorityAuditReport")
            .header("Authorization", AUTH)
            .body("{\"CertificateAuthorityArn\":\"" + bindingsCaArn
                + "\",\"S3BucketName\":\"alchemy-test-acmpca-audit-reports\","
                + "\"AuditReportResponseFormat\":\"JSON\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("AuditReportId", notNullValue())
            .body("S3Key", notNullValue())
            .extract().jsonPath().getString("AuditReportId");

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "ACMPrivateCA.DescribeCertificateAuthorityAuditReport")
            .header("Authorization", AUTH)
            .body("{\"CertificateAuthorityArn\":\"" + bindingsCaArn
                + "\",\"AuditReportId\":\"" + reportId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("AuditReportStatus", equalTo("SUCCESS"));
    }

    private static String toJsonString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String b64(String pem) {
        return Base64.getEncoder().encodeToString(pem.getBytes(StandardCharsets.UTF_8));
    }
}

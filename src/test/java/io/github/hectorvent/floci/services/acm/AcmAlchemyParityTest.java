package io.github.hectorvent.floci.services.acm;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Operations Alchemy's ACM suite exercises that were previously
 * {@code UnknownOperationException}: search, options sync, renew, resend, revoke.
 */
@QuarkusTest
class AcmAlchemyParityTest {

    private static final String ACM_CONTENT_TYPE = "application/x-amz-json-1.1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void requestPersistsExportOption() {
        String arn = requestCertificate("""
            {
                "DomainName": "export-enabled.example.com",
                "Options": { "Export": "ENABLED" }
            }
            """);

        given()
            .header("X-Amz-Target", "CertificateManager.DescribeCertificate")
            .contentType(ACM_CONTENT_TYPE)
            .body("{\"CertificateArn\":\"" + arn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Certificate.Status", equalTo("PENDING_VALIDATION"))
            .body("Certificate.Options.Export", equalTo("ENABLED"));
    }

    @Test
    void updateCertificateOptionsChangesCtLogging() {
        String arn = requestCertificate("""
            { "DomainName": "ct-logging.example.com" }
            """);

        given()
            .header("X-Amz-Target", "CertificateManager.UpdateCertificateOptions")
            .contentType(ACM_CONTENT_TYPE)
            .body("""
                {
                    "CertificateArn": "%s",
                    "Options": { "CertificateTransparencyLoggingPreference": "DISABLED" }
                }
                """.formatted(arn))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", "CertificateManager.DescribeCertificate")
            .contentType(ACM_CONTENT_TYPE)
            .body("{\"CertificateArn\":\"" + arn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Certificate.Options.CertificateTransparencyLoggingPreference", equalTo("DISABLED"))
            .body("Certificate.Options.Export", equalTo("DISABLED"));
    }

    @Test
    void updateCertificateOptionsRejectsExportChange() {
        String arn = requestCertificate("""
            {
                "DomainName": "export-fixed.example.com",
                "Options": { "Export": "ENABLED" }
            }
            """);

        given()
            .header("X-Amz-Target", "CertificateManager.UpdateCertificateOptions")
            .contentType(ACM_CONTENT_TYPE)
            .body("""
                {
                    "CertificateArn": "%s",
                    "Options": { "Export": "DISABLED" }
                }
                """.formatted(arn))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidStateException"));
    }

    @Test
    void searchCertificatesFindsByArn() {
        String arn = requestCertificate("""
            { "DomainName": "search-arn.example.com" }
            """);

        given()
            .header("X-Amz-Target", "CertificateManager.SearchCertificates")
            .contentType(ACM_CONTENT_TYPE)
            .body("""
                {
                    "FilterStatement": { "Filter": { "CertificateArn": "%s" } }
                }
                """.formatted(arn))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Results.size()", equalTo(1))
            .body("Results[0].CertificateArn", equalTo(arn));
    }

    @Test
    void searchCertificatesFiltersByStatus() {
        requestCertificate("""
            { "DomainName": "search-status.example.com" }
            """);

        given()
            .header("X-Amz-Target", "CertificateManager.SearchCertificates")
            .contentType(ACM_CONTENT_TYPE)
            .body("""
                {
                    "FilterStatement": {
                        "Filter": { "AcmCertificateMetadataFilter": { "Status": "PENDING_VALIDATION" } }
                    }
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Results.CertificateArn", hasItem(containsString(":certificate/")));
    }

    @Test
    void renewCertificatePendingFails() {
        String arn = requestCertificate("""
            { "DomainName": "renew-pending.example.com" }
            """);

        given()
            .header("X-Amz-Target", "CertificateManager.RenewCertificate")
            .contentType(ACM_CONTENT_TYPE)
            .body("{\"CertificateArn\":\"" + arn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("RequestInProgressException"));
    }

    @Test
    void resendValidationEmailOnDnsFails() {
        String arn = requestCertificate("""
            {
                "DomainName": "resend-dns.example.com",
                "ValidationMethod": "DNS"
            }
            """);

        given()
            .header("X-Amz-Target", "CertificateManager.ResendValidationEmail")
            .contentType(ACM_CONTENT_TYPE)
            .body("""
                {
                    "CertificateArn": "%s",
                    "Domain": "resend-dns.example.com",
                    "ValidationDomain": "example.com"
                }
                """.formatted(arn))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidStateException"));
    }

    @Test
    void revokeNeverExportedFails() {
        String arn = requestCertificate("""
            { "DomainName": "revoke-pending.example.com" }
            """);

        given()
            .header("X-Amz-Target", "CertificateManager.RevokeCertificate")
            .contentType(ACM_CONTENT_TYPE)
            .body("""
                {
                    "CertificateArn": "%s",
                    "RevocationReason": "UNSPECIFIED"
                }
                """.formatted(arn))
        .when()
            .post("/")
        .then()
            .statusCode(409)
            .body("__type", equalTo("ConflictException"));
    }

    @Test
    void revokeAfterExportSucceeds() {
        String arn = requestCertificate("""
            {
                "DomainName": "revoke-exported.example.com",
                "CertificateAuthorityArn": "arn:aws:acm-pca:us-east-1:123456789012:certificate-authority/12345678-1234-1234-1234-123456789012"
            }
            """);

        String passphrase = Base64.getEncoder().encodeToString("testpassphrase".getBytes());
        given()
            .header("X-Amz-Target", "CertificateManager.ExportCertificate")
            .contentType(ACM_CONTENT_TYPE)
            .body("""
                {
                    "CertificateArn": "%s",
                    "Passphrase": "%s"
                }
                """.formatted(arn, passphrase))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", "CertificateManager.RevokeCertificate")
            .contentType(ACM_CONTENT_TYPE)
            .body("""
                {
                    "CertificateArn": "%s",
                    "RevocationReason": "UNSPECIFIED"
                }
                """.formatted(arn))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CertificateArn", equalTo(arn));

        given()
            .header("X-Amz-Target", "CertificateManager.DescribeCertificate")
            .contentType(ACM_CONTENT_TYPE)
            .body("{\"CertificateArn\":\"" + arn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Certificate.Status", equalTo("REVOKED"));
    }

    private static String requestCertificate(String body) {
        return given()
            .header("X-Amz-Target", "CertificateManager.RequestCertificate")
            .contentType(ACM_CONTENT_TYPE)
            .body(body)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().jsonPath().getString("CertificateArn");
    }
}

package io.floci.aws.testing;

import io.restassured.response.ValidatableResponse;
import org.awaitility.Awaitility;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

import static io.restassured.RestAssured.given;

/** Creates AWS Private CAs over the wire, and activates a root CA with its own self-signed certificate. */
public final class PrivateCaFixture {
    private PrivateCaFixture() {
    }

    public static ValidatableResponse call(String action, String body) {
        return call("000000000000", "us-east-1", action, body);
    }

    public static ValidatableResponse call(String account, String region, String action, String body) {
        return given().contentType("application/x-amz-json-1.1")
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=" + account + "/20260101/" + region
                        + "/acm-pca/aws4_request")
                .header("X-Amz-Target", "ACMPrivateCA." + action).body(body.getBytes(StandardCharsets.UTF_8))
                .when().post("/").then();
    }

    /** A new CA of {@code type}, settled out of CREATING into PENDING_CERTIFICATE. */
    public static String create(String type, String commonName, String extra) {
        String arn = call("CreateCertificateAuthority", "{\"CertificateAuthorityConfiguration\":{\"KeyAlgorithm\":"
                + "\"RSA_2048\",\"SigningAlgorithm\":\"SHA256WITHRSA\",\"Subject\":{\"CommonName\":\"" + commonName
                + "\",\"Organization\":\"Floci\"}},\"CertificateAuthorityType\":\"" + type + "\"" + extra + "}")
                .statusCode(200).extract().path("CertificateAuthorityArn");
        Awaitility.await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(200)).until(() ->
                "PENDING_CERTIFICATE".equals(call("DescribeCertificateAuthority",
                        "{\"CertificateAuthorityArn\":\"" + arn + "\"}").extract().path("CertificateAuthority.Status")));
        return arn;
    }

    /** Issues the root CA's own certificate from its CSR and imports it, making the CA ACTIVE. */
    public static void activateRoot(String arn) {
        String csr = call("GetCertificateAuthorityCsr", "{\"CertificateAuthorityArn\":\"" + arn + "\"}")
                .statusCode(200).extract().path("Csr");
        String certificateArn = call("IssueCertificate", "{\"CertificateAuthorityArn\":\"" + arn + "\",\"Csr\":\""
                + blob(csr) + "\",\"SigningAlgorithm\":\"SHA256WITHRSA\",\"TemplateArn\":"
                + "\"arn:aws:acm-pca:::template/RootCACertificate/V1\",\"Validity\":{\"Type\":\"YEARS\",\"Value\":10}}") // partition-literal: the template ARN of the test's partition
                .statusCode(200).extract().path("CertificateArn");
        String certificate = call("GetCertificate", "{\"CertificateAuthorityArn\":\"" + arn + "\",\"CertificateArn\":\""
                + certificateArn + "\"}").statusCode(200).extract().path("Certificate");
        call("ImportCertificateAuthorityCertificate", "{\"CertificateAuthorityArn\":\"" + arn + "\",\"Certificate\":\""
                + blob(certificate) + "\"}").statusCode(200);
    }

    /** An ACTIVE root CA. */
    public static String activeRoot(String commonName) {
        String arn = create("ROOT", commonName, "");
        activateRoot(arn);
        return arn;
    }

    public static String blob(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }
}

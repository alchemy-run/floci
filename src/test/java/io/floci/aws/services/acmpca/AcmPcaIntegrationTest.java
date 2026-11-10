package io.floci.aws.services.acmpca;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.ExtensionsGenerator;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.X509CRLHolder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;
import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemWriter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static io.floci.aws.testing.PrivateCaFixture.activateRoot;
import static io.floci.aws.testing.PrivateCaFixture.activeRoot;
import static io.floci.aws.testing.PrivateCaFixture.blob;
import static io.floci.aws.testing.PrivateCaFixture.call;
import static io.floci.aws.testing.PrivateCaFixture.create;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** AWS Private CA: CA lifecycle, issuance, revocation with CRLs, audit reports, sharing, and ACM private certificates. */
@QuarkusTest
class AcmPcaIntegrationTest {
    private static final String TEMPLATE = "arn:aws:acm-pca:::template/"; // partition-literal: the test's partition

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static String ca(String arn) {
        return "{\"CertificateAuthorityArn\":\"" + arn + "\"";
    }

    private static String leafCsr(String commonName, String san) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keys = generator.generateKeyPair();
        JcaPKCS10CertificationRequestBuilder builder = new JcaPKCS10CertificationRequestBuilder(
                new X500Name("CN=" + commonName + ",O=Floci Test"), keys.getPublic());
        ExtensionsGenerator extensions = new ExtensionsGenerator();
        extensions.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(
                new GeneralName(GeneralName.dNSName, san)));
        builder.addAttribute(PKCSObjectIdentifiers.pkcs_9_at_extensionRequest, extensions.generate());
        byte[] der = builder.build(new JcaContentSignerBuilder("SHA256withRSA").build(keys.getPrivate())).getEncoded();
        StringWriter out = new StringWriter();
        try (PemWriter pem = new PemWriter(out)) {
            pem.writeObject(new PemObject("CERTIFICATE REQUEST", der));
        }
        return out.toString();
    }

    private static List<X509Certificate> certificates(String pem) throws Exception {
        Collection<? extends Certificate> all = CertificateFactory.getInstance("X.509")
                .generateCertificates(new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8)));
        return all.stream().map(c -> (X509Certificate) c).toList();
    }

    private static String issue(String caArn, String csr, String template, String validity) {
        return call("IssueCertificate", ca(caArn) + ",\"Csr\":\"" + blob(csr) + "\",\"SigningAlgorithm\":"
                + "\"SHA256WITHRSA\"" + (template == null ? "" : ",\"TemplateArn\":\"" + TEMPLATE + template + "\"")
                + ",\"Validity\":" + validity + "}").statusCode(200).extract().path("CertificateArn");
    }

    @Test
    void aRootCaIsActivatedIssuesRevokesAndAudits() throws Exception {
        String bucket = "pca-crl-" + UUID.randomUUID().toString().substring(0, 8);
        given().put("/" + bucket).then().statusCode(200);
        String arn = create("ROOT", "root.floci.test", ",\"RevocationConfiguration\":{\"CrlConfiguration\":"
                + "{\"Enabled\":true,\"S3BucketName\":\"" + bucket + "\"}},\"Tags\":[{\"Key\":\"team\",\"Value\":\"pki\"}]");
        call("DescribeCertificateAuthority", ca(arn) + "}").statusCode(200)
                .body("CertificateAuthority.Status", equalTo("PENDING_CERTIFICATE"))
                .body("CertificateAuthority.Type", equalTo("ROOT"))
                .body("CertificateAuthority.UsageMode", equalTo("GENERAL_PURPOSE"))
                .body("CertificateAuthority.CertificateAuthorityConfiguration.Subject.CommonName",
                        equalTo("root.floci.test"))
                .body("CertificateAuthority.RevocationConfiguration.CrlConfiguration.ExpirationInDays", equalTo(7));
        call("GetCertificateAuthorityCsr", ca(arn) + "}").statusCode(200)
                .body("Csr", containsString("BEGIN CERTIFICATE REQUEST"));
        call("GetCertificateAuthorityCertificate", ca(arn) + "}").statusCode(400)
                .body("__type", equalTo("InvalidStateException"));
        call("IssueCertificate", ca(arn) + ",\"Csr\":\"" + blob(leafCsr("early.floci.test", "early.floci.test"))
                + "\",\"SigningAlgorithm\":\"SHA256WITHRSA\",\"Validity\":{\"Type\":\"DAYS\",\"Value\":5}}")
                .statusCode(400).body("__type", equalTo("InvalidStateException"));

        activateRoot(arn);
        call("DescribeCertificateAuthority", ca(arn) + "}").statusCode(200)
                .body("CertificateAuthority.Status", equalTo("ACTIVE"))
                .body("CertificateAuthority.Serial", notNullValue());
        String caPem = call("GetCertificateAuthorityCertificate", ca(arn) + "}").statusCode(200)
                .extract().path("Certificate");
        X509Certificate root = certificates(caPem).getFirst();
        assertEquals(root.getSubjectX500Principal(), root.getIssuerX500Principal());
        assertTrue(root.getBasicConstraints() >= 0, "the root is a CA");

        String leafArn = issue(arn, leafCsr("app.floci.test", "app.floci.test"), null,
                "{\"Type\":\"DAYS\",\"Value\":30}");
        assertTrue(leafArn.startsWith(arn + "/certificate/"), leafArn);
        String leafPem = call("GetCertificate", ca(arn) + ",\"CertificateArn\":\"" + leafArn + "\"}").statusCode(200)
                .body("CertificateChain", containsString("BEGIN CERTIFICATE")).extract().path("Certificate");
        X509Certificate leaf = certificates(leafPem).getFirst();
        leaf.verify(root.getPublicKey());
        assertEquals(-1, leaf.getBasicConstraints());
        assertTrue(leaf.getExtendedKeyUsage().containsAll(List.of("1.3.6.1.5.5.7.3.1", "1.3.6.1.5.5.7.3.2")));
        assertNotNull(leaf.getExtensionValue("2.5.29.31"), "the CRL distribution point is written");
        assertTrue(leaf.getSubjectAlternativeNames().stream().anyMatch(n -> n.get(1).equals("app.floci.test")));

        call("IssueCertificate", ca(arn) + ",\"Csr\":\"" + blob(leafCsr("long.floci.test", "long.floci.test"))
                + "\",\"SigningAlgorithm\":\"SHA256WITHRSA\",\"Validity\":{\"Type\":\"YEARS\",\"Value\":20}}")
                .statusCode(400).body("message", containsString("exceeds the certificate authority validity"));
        call("IssueCertificate", ca(arn) + ",\"Csr\":\"" + blob(leafCsr("ecdsa.floci.test", "ecdsa.floci.test"))
                + "\",\"SigningAlgorithm\":\"SHA256WITHECDSA\",\"Validity\":{\"Type\":\"DAYS\",\"Value\":5}}")
                .statusCode(400).body("__type", equalTo("InvalidArgsException"));
        call("IssueCertificate", ca(arn) + ",\"Csr\":\"" + blob("not a csr") + "\",\"SigningAlgorithm\":"
                + "\"SHA256WITHRSA\",\"Validity\":{\"Type\":\"DAYS\",\"Value\":5}}").statusCode(400)
                .body("__type", equalTo("MalformedCSRException"));

        String serial = leaf.getSerialNumber().toString(16);
        call("RevokeCertificate", ca(arn) + ",\"CertificateSerial\":\"" + serial + "\",\"RevocationReason\":"
                + "\"KEY_COMPROMISE\"}").statusCode(200);
        call("RevokeCertificate", ca(arn) + ",\"CertificateSerial\":\"" + serial + "\",\"RevocationReason\":"
                + "\"KEY_COMPROMISE\"}").statusCode(400).body("__type", equalTo("RequestAlreadyProcessedException"));
        String caId = arn.substring(arn.lastIndexOf('/') + 1);
        byte[] crl = given().get("/" + bucket + "/crl/" + caId + ".crl").then().statusCode(200).extract().asByteArray();
        X509CRLHolder holder = new X509CRLHolder(crl);
        assertNotNull(holder.getRevokedCertificate(leaf.getSerialNumber()), "the CRL lists the revoked serial");

        String reportId = call("CreateCertificateAuthorityAuditReport", ca(arn) + ",\"S3BucketName\":\"" + bucket
                + "\",\"AuditReportResponseFormat\":\"JSON\"}").statusCode(200)
                .body("S3Key", containsString("audit-report/" + caId + "/")).extract().path("AuditReportId");
        String key = call("DescribeCertificateAuthorityAuditReport", ca(arn) + ",\"AuditReportId\":\"" + reportId
                + "\"}").statusCode(200).body("AuditReportStatus", notNullValue()).extract().path("S3Key");
        given().get("/" + bucket + "/" + key).then().statusCode(200)
                .body(containsString(leafArn)).body(containsString("KEY_COMPROMISE"));

        call("ListTags", ca(arn) + "}").statusCode(200).body("Tags.Key", hasItem("team"));
        call("ListCertificateAuthorities", "{}").statusCode(200).body("CertificateAuthorities.Arn", hasItem(arn));

        call("DeleteCertificateAuthority", ca(arn) + "}").statusCode(400)
                .body("__type", equalTo("InvalidStateException"));
        call("UpdateCertificateAuthority", ca(arn) + ",\"Status\":\"DISABLED\"}").statusCode(200);
        call("IssueCertificate", ca(arn) + ",\"Csr\":\"" + blob(leafCsr("off.floci.test", "off.floci.test"))
                + "\",\"SigningAlgorithm\":\"SHA256WITHRSA\",\"Validity\":{\"Type\":\"DAYS\",\"Value\":5}}")
                .statusCode(400).body("__type", equalTo("InvalidStateException"));
        call("DeleteCertificateAuthority", ca(arn) + ",\"PermanentDeletionTimeInDays\":7}").statusCode(200);
        call("DescribeCertificateAuthority", ca(arn) + "}").statusCode(200)
                .body("CertificateAuthority.Status", equalTo("DELETED"))
                .body("CertificateAuthority.RestorableUntil", notNullValue());
        call("RestoreCertificateAuthority", ca(arn) + "}").statusCode(200);
        call("DescribeCertificateAuthority", ca(arn) + "}").statusCode(200)
                .body("CertificateAuthority.Status", equalTo("DISABLED"));
    }

    @Test
    void aSubordinateCaIsCertifiedByItsParentWithTheChain() throws Exception {
        String root = activeRoot("parent.floci.test");
        String sub = create("SUBORDINATE", "issuing.floci.test", "");
        String csr = call("GetCertificateAuthorityCsr", ca(sub) + "}").statusCode(200).extract().path("Csr");
        String subCertArn = issue(root, csr, "SubordinateCACertificate_PathLen0/V1", "{\"Type\":\"YEARS\",\"Value\":5}");
        String subCert = call("GetCertificate", ca(root) + ",\"CertificateArn\":\"" + subCertArn + "\"}")
                .statusCode(200).extract().path("Certificate");
        String rootCert = call("GetCertificateAuthorityCertificate", ca(root) + "}").statusCode(200)
                .extract().path("Certificate");

        call("ImportCertificateAuthorityCertificate", ca(sub) + ",\"Certificate\":\"" + blob(subCert) + "\"}")
                .statusCode(400).body("__type", equalTo("InvalidRequestException"));
        call("ImportCertificateAuthorityCertificate", ca(sub) + ",\"Certificate\":\"" + blob(rootCert)
                + "\",\"CertificateChain\":\"" + blob(rootCert) + "\"}")
                .statusCode(400).body("__type", equalTo("CertificateMismatchException"));
        call("ImportCertificateAuthorityCertificate", ca(sub) + ",\"Certificate\":\"" + blob(subCert)
                + "\",\"CertificateChain\":\"" + blob(rootCert) + "\"}").statusCode(200);
        call("DescribeCertificateAuthority", ca(sub) + "}").statusCode(200)
                .body("CertificateAuthority.Status", equalTo("ACTIVE"));
        assertEquals(0, certificates(subCert).getFirst().getBasicConstraints());

        // A path length of 0 forbids another CA below it.
        String nested = create("SUBORDINATE", "nested.floci.test", "");
        String nestedCsr = call("GetCertificateAuthorityCsr", ca(nested) + "}").extract().path("Csr");
        call("IssueCertificate", ca(sub) + ",\"Csr\":\"" + blob(nestedCsr) + "\",\"SigningAlgorithm\":"
                + "\"SHA256WITHRSA\",\"TemplateArn\":\"" + TEMPLATE + "SubordinateCACertificate_PathLen0/V1\","
                + "\"Validity\":{\"Type\":\"YEARS\",\"Value\":1}}").statusCode(400)
                .body("__type", equalTo("InvalidArgsException"));

        String leafArn = issue(sub, leafCsr("svc.floci.test", "svc.floci.test"), "EndEntityServerAuthCertificate/V1",
                "{\"Type\":\"DAYS\",\"Value\":10}");
        String chain = call("GetCertificate", ca(sub) + ",\"CertificateArn\":\"" + leafArn + "\"}").statusCode(200)
                .extract().path("CertificateChain");
        List<X509Certificate> chainCerts = certificates(chain);
        assertEquals(2, chainCerts.size(), "the chain is the issuing CA and its root");
        assertEquals(List.of("1.3.6.1.5.5.7.3.1"), certificates(call("GetCertificate", ca(sub)
                + ",\"CertificateArn\":\"" + leafArn + "\"}").extract().<String>path("Certificate")).getFirst()
                .getExtendedKeyUsage());
    }

    @Test
    void shortLivedCasPermissionsPoliciesAndErrors() throws Exception {
        String arn = create("ROOT", "short.floci.test", ",\"UsageMode\":\"SHORT_LIVED_CERTIFICATE\"");
        activateRoot(arn);
        call("IssueCertificate", ca(arn) + ",\"Csr\":\"" + blob(leafCsr("week.floci.test", "week.floci.test"))
                + "\",\"SigningAlgorithm\":\"SHA256WITHRSA\",\"Validity\":{\"Type\":\"DAYS\",\"Value\":8}}")
                .statusCode(400).body("message", containsString("at most 7 days"));
        issue(arn, leafCsr("day.floci.test", "day.floci.test"), null, "{\"Type\":\"DAYS\",\"Value\":7}");

        call("CreatePermission", ca(arn) + ",\"Principal\":\"acm.amazonaws.com\",\"Actions\":[\"IssueCertificate\"]}")
                .statusCode(400).body("message", containsString("all three actions"));
        call("CreatePermission", ca(arn) + ",\"Principal\":\"acm.amazonaws.com\",\"Actions\":[\"IssueCertificate\","
                + "\"GetCertificate\",\"ListPermissions\"]}").statusCode(200);
        call("CreatePermission", ca(arn) + ",\"Principal\":\"acm.amazonaws.com\",\"Actions\":[\"IssueCertificate\","
                + "\"GetCertificate\",\"ListPermissions\"]}").statusCode(400)
                .body("__type", equalTo("PermissionAlreadyExistsException"));
        call("ListPermissions", ca(arn) + "}").statusCode(200)
                .body("Permissions[0].Actions", hasItems("IssueCertificate", "GetCertificate", "ListPermissions"))
                .body("Permissions[0].SourceAccount", equalTo("000000000000"));
        call("DeletePermission", ca(arn) + ",\"Principal\":\"acm.amazonaws.com\"}").statusCode(200);

        call("GetPolicy", "{\"ResourceArn\":\"" + arn + "\"}").statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
        String policy = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"AWS\":"
                + "\"111122223333\"},\"Action\":\"acm-pca:IssueCertificate\",\"Resource\":\"" + arn + "\"}]}";
        call("PutPolicy", "{\"ResourceArn\":\"" + arn + "\",\"Policy\":" + quoted(policy) + "}").statusCode(200);
        call("GetPolicy", "{\"ResourceArn\":\"" + arn + "\"}").statusCode(200).body("Policy", equalTo(policy));
        // The account the policy names sees the shared CA.
        call("111122223333", "us-east-1", "ListCertificateAuthorities", "{\"ResourceOwner\":\"OTHER_ACCOUNTS\"}")
                .statusCode(200).body("CertificateAuthorities.Arn", hasItem(arn));
        call("PutPolicy", "{\"ResourceArn\":\"" + arn + "\",\"Policy\":\"not json\"}").statusCode(400)
                .body("__type", equalTo("InvalidPolicyException"));
        call("DeletePolicy", "{\"ResourceArn\":\"" + arn + "\"}").statusCode(200);

        String missing = "arn:aws:acm-pca:us-east-1:000000000000:certificate-authority/" + UUID.randomUUID(); // partition-literal: test ARN
        call("DescribeCertificateAuthority", ca(missing) + "}").statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
        call("DescribeCertificateAuthority", ca("arn:aws:acm-pca:us-east-1:000000000000:something/else") + "}") // partition-literal: test ARN
                .statusCode(400).body("__type", equalTo("InvalidArnException"));
        call("DescribeCertificateAuthority", ca("nope") + "}").statusCode(400)
                .body("__type", equalTo("ValidationException"));
    }

    @Test
    void acmPrivateCertificatesAreSignedByThePrivateCa() throws Exception {
        String arn = activeRoot("acm.floci.test");
        String certArn = given().contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", "CertificateManager.RequestCertificate")
                .body("{\"DomainName\":\"private.floci.test\",\"CertificateAuthorityArn\":\"" + arn + "\"}")
                .post("/").then().statusCode(200).extract().path("CertificateArn");
        String chain = given().contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", "CertificateManager.GetCertificate")
                .body("{\"CertificateArn\":\"" + certArn + "\"}").post("/").then().statusCode(200)
                .extract().path("CertificateChain");
        String caPem = call("GetCertificateAuthorityCertificate", ca(arn) + "}").extract().path("Certificate");
        assertEquals(certificates(caPem).getFirst(), certificates(chain).getFirst());

        String pending = create("ROOT", "pending.floci.test", "");
        String failed = given().contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", "CertificateManager.RequestCertificate")
                .body("{\"DomainName\":\"failed.floci.test\",\"CertificateAuthorityArn\":\"" + pending + "\"}")
                .post("/").then().statusCode(200).extract().path("CertificateArn");
        given().contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", "CertificateManager.DescribeCertificate")
                .body("{\"CertificateArn\":\"" + failed + "\"}").post("/").then().statusCode(200)
                .body("Certificate.Status", equalTo("FAILED"))
                .body("Certificate.FailureReason", equalTo("PCA_INVALID_STATE"));
    }

    private static String quoted(String json) {
        return "\"" + json.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}

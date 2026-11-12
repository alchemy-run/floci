package io.floci.aws.services.rolesanywhere;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.CRLReason;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v2CRLBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

/**
 * IAM Roles Anywhere end to end: a trust anchor on a CA certificate, a profile for a role that trusts Roles Anywhere,
 * and CreateSession signed with an end-entity certificate's key, whose credentials STS then recognizes; a revoking
 * CRL, a disabled anchor, a wrong signature and a role the profile doesn't list are refused, and subjects record it.
 */
@QuarkusTest
class RolesAnywhereIntegrationTest {

    private static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC);

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    static String auth(String service) {
        return "AWS4-HMAC-SHA256 Credential=000000000000/20260101/us-east-1/" + service + "/aws4_request";
    }

    static RequestSpecification ra() {
        return given().header("Authorization", auth("rolesanywhere")).contentType("application/json");
    }

    record Issued(KeyPair keys, X509Certificate certificate) {
    }

    static KeyPair rsa() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    static X509Certificate certificate(X509CertificateHolder holder) throws Exception {
        return new JcaX509CertificateConverter().getCertificate(holder);
    }

    static Issued ca() throws Exception {
        KeyPair keys = rsa();
        X500Name name = new X500Name("CN=Floci Test CA");
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(name, BigInteger.ONE,
                Date.from(Instant.now().minusSeconds(3600)), Date.from(Instant.now().plusSeconds(86400)), name,
                keys.getPublic());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
        builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(keys.getPrivate());
        return new Issued(keys, certificate(builder.build(signer)));
    }

    static Issued leaf(Issued ca, long serial, String cn) throws Exception {
        KeyPair keys = rsa();
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(ca.certificate(),
                BigInteger.valueOf(serial), Date.from(Instant.now().minusSeconds(3600)),
                Date.from(Instant.now().plusSeconds(86400)), new X500Name("CN=" + cn + ",OU=dev"), keys.getPublic());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
        builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature));
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(ca.keys().getPrivate());
        return new Issued(keys, certificate(builder.build(signer)));
    }

    static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(der) + "\n-----END " + type + "-----\n";
    }

    static String crl(Issued ca, long revoked) throws Exception {
        X509v2CRLBuilder builder = new X509v2CRLBuilder(new X500Name("CN=Floci Test CA"),
                Date.from(Instant.now().minusSeconds(60)));
        builder.setNextUpdate(Date.from(Instant.now().plusSeconds(86400)));
        builder.addCRLEntry(BigInteger.valueOf(revoked), new Date(), CRLReason.keyCompromise);
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(ca.keys().getPrivate());
        return pem("X509 CRL", builder.build(signer).getEncoded());
    }

    static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    /** CreateSession, signed with AWS4-X509-RSA-SHA256 by {@code signer}'s key for {@code presented}. */
    static ValidatableResponse session(Issued presented, Issued signer, String body)
            throws Exception {
        String amzDate = AMZ_DATE.format(Instant.now());
        String x509 = Base64.getEncoder().encodeToString(presented.certificate().getEncoded());
        String host = "localhost:" + RestAssured.port;
        String signedHeaders = "host;x-amz-date;x-amz-x509";
        String canonical = "POST\n/sessions\n\nhost:" + host + "\nx-amz-date:"
                + amzDate + "\nx-amz-x509:" + x509 + "\n\n" + signedHeaders + "\n"
                + sha256(body.getBytes(StandardCharsets.UTF_8));
        String scope = amzDate.substring(0, 8) + "/us-east-1/rolesanywhere/aws4_request";
        String toSign = "AWS4-X509-RSA-SHA256\n" + amzDate + "\n" + scope + "\n"
                + sha256(canonical.getBytes(StandardCharsets.UTF_8));
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(signer.keys().getPrivate());
        signature.update(toSign.getBytes(StandardCharsets.UTF_8));
        String authorization = "AWS4-X509-RSA-SHA256 Credential=" + presented.certificate().getSerialNumber() + "/"
                + scope + ", SignedHeaders=" + signedHeaders + ", Signature="
                + HexFormat.of().formatHex(signature.sign());
        return given().header("Authorization", authorization).header("X-Amz-Date", amzDate)
                .header("X-Amz-X509", x509).contentType("application/json").body(body).post("/sessions").then();
    }

    @Test
    void certificatesExchangeForRoleCredentials() throws Exception {
        Issued ca = ca();
        Issued good = leaf(ca, 1001, "build-agent");
        Issued revoked = leaf(ca, 1002, "old-agent");
        Issued stranger = leaf(ca(), 1003, "stranger");

        ra().body("{\"name\":\"bad\",\"source\":{\"sourceType\":\"CERTIFICATE_BUNDLE\",\"sourceData\":{"
                + "\"x509CertificateData\":\"not a certificate\"}}}").post("/trustanchors").then().statusCode(400);
        ra().body("{\"name\":\"leaf\",\"source\":{\"sourceType\":\"CERTIFICATE_BUNDLE\",\"sourceData\":{"
                + "\"x509CertificateData\":" + json(pem("CERTIFICATE", good.certificate().getEncoded())) + "}}}")
                .post("/trustanchors").then().statusCode(400).body("message", containsString("CA"));
        String anchorArn = ra().body("{\"name\":\"ci\",\"enabled\":true,\"source\":{\"sourceType\":"
                + "\"CERTIFICATE_BUNDLE\",\"sourceData\":{\"x509CertificateData\":"
                + json(pem("CERTIFICATE", ca.certificate().getEncoded())) + "}},\"notificationSettings\":[{\"enabled\":"
                + "true,\"event\":\"CA_CERTIFICATE_EXPIRY\",\"threshold\":30}],\"tags\":[{\"key\":\"team\",\"value\":"
                + "\"ci\"}]}").post("/trustanchors").then().statusCode(201)
                .body("trustAnchor.notificationSettings.find { it.event == 'CA_CERTIFICATE_EXPIRY' }.threshold",
                        equalTo(30)).extract().path("trustAnchor.trustAnchorArn");
        String anchorId = anchorArn.substring(anchorArn.lastIndexOf('/') + 1);

        String trust = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"Service\":"
                + "\"rolesanywhere.amazonaws.com\"},\"Action\":[\"sts:AssumeRole\",\"sts:TagSession\"]}]}";
        String roleArn = given().header("Authorization", auth("iam")).formParam("Action", "CreateRole")
                .formParam("RoleName", "ra-build").formParam("AssumeRolePolicyDocument", trust).post("/").then()
                .statusCode(200).extract().xmlPath().getString("CreateRoleResponse.CreateRoleResult.Role.Arn");
        String profileArn = ra().body("{\"name\":\"ci\",\"enabled\":true,\"roleArns\":[\"" + roleArn
                + "\"],\"durationSeconds\":3600}").post("/profiles").then().statusCode(201)
                .body("profile.attributeMappings.certificateField", hasItem("x509Subject"))
                .extract().path("profile.profileArn");

        String request = "{\"trustAnchorArn\":\"" + anchorArn + "\",\"profileArn\":\"" + profileArn
                + "\",\"roleArn\":\"" + roleArn + "\"}";
        String accessKey = session(good, good, request).statusCode(201)
                .body("credentialSet[0].roleArn", equalTo(roleArn))
                .body("credentialSet[0].sourceIdentity", equalTo("CN=build-agent"))
                .body("credentialSet[0].credentials.accessKeyId", startsWith("ASIA"))
                .extract().path("credentialSet[0].credentials.accessKeyId");
        given().header("Authorization", "AWS4-HMAC-SHA256 Credential=" + accessKey
                + "/20260101/us-east-1/sts/aws4_request").formParam("Action", "GetCallerIdentity").post("/").then()
                .statusCode(200).body(containsString("assumed-role/ra-build/"));

        // Refusals.
        session(good, revoked, request).statusCode(403).body("message", containsString("signature"));
        session(stranger, stranger, request).statusCode(403).body("message", containsString("chain"));
        session(good, good, request.replace(roleArn, roleArn + "x")).statusCode(403);
        session(good, good, request.replace("}", ",\"durationSeconds\":7200}")).statusCode(400);
        String crlId = ra().body("{\"name\":\"revocations\",\"enabled\":true,\"trustAnchorArn\":\"" + anchorArn
                + "\",\"crlData\":\"" + Base64.getEncoder().encodeToString(crl(ca, 1002).getBytes(StandardCharsets.UTF_8))
                + "\"}").post("/crls").then().statusCode(201).extract().path("crl.crlId");
        session(revoked, revoked, request).statusCode(403).body("message", containsString("revoked"));
        session(good, good, request).statusCode(201);
        ra().post("/crl/" + crlId + "/disable").then().statusCode(200).body("crl.enabled", equalTo(false));
        session(revoked, revoked, request).statusCode(201);
        ra().post("/trustanchor/" + anchorId + "/disable").then().statusCode(200);
        session(good, good, request).statusCode(403).body("message", containsString("disabled"));

        // Subjects record each certificate's use.
        String subjectId = ra().get("/subjects").then().statusCode(200)
                .body("subjects.x509Subject", hasItem(containsString("CN=build-agent"))).extract()
                .path("subjects.find { it.x509Subject.contains('build-agent') }.subjectId");
        ra().get("/subject/" + subjectId).then().statusCode(200).body("subject.credentials[0].serialNumber",
                equalTo("1001"));

        // Notifications, mappings and tags.
        ra().body("{\"trustAnchorId\":\"" + anchorId + "\",\"notificationSettingKeys\":[{\"event\":"
                + "\"CA_CERTIFICATE_EXPIRY\"}]}").patch("/reset-notifications-settings").then().statusCode(200)
                .body("trustAnchor.notificationSettings.find { it.event == 'CA_CERTIFICATE_EXPIRY' }.threshold",
                        equalTo(45));
        String profileId = profileArn.substring(profileArn.lastIndexOf('/') + 1);
        ra().body("{\"certificateField\":\"x509Subject\",\"mappingRules\":[{\"specifier\":\"CN\"}]}")
                .put("/profiles/" + profileId + "/mappings").then().statusCode(200);
        ra().queryParam("certificateField", "x509Subject").queryParam("specifiers", "CN")
                .delete("/profiles/" + profileId + "/mappings").then().statusCode(200)
                .body("profile.attributeMappings.find { it.certificateField == 'x509Subject' }.mappingRules",
                        not(hasItem(containsString("CN"))));
        ra().queryParam("resourceArn", anchorArn).get("/ListTagsForResource").then().body("tags.key", hasItem("team"));
        ra().delete("/trustanchor/" + anchorId).then().statusCode(200);
        ra().get("/crl/" + crlId).then().statusCode(404);
    }

    static String json(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }
}

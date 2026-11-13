package io.floci.aws.services.acmpca;

import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AWS Private CA's certificate templates, as the Private CA User Guide's "Template definitions"
 * describe them: the basic constraints, key usage and extended key usage each one writes, whether
 * it issues a CA certificate (and with what path length), and whether it takes extensions from the
 * {@code ApiPassthrough} parameter, the CSR, or both.
 */
final class AcmPcaTemplates {
    static final String DEFAULT = "EndEntityCertificate/V1";

    private static final Pattern NAME = Pattern.compile(
            "(Blank)?(EndEntity|EndEntityClientAuth|EndEntityServerAuth|CodeSigning|OCSPSigning|RootCA|"
                    + "SubordinateCA)Certificate(?:_PathLen([0-3]))?(_CriticalBasicConstraints)?"
                    + "(_APIPassthrough|_CSRPassthrough|_APICSRPassthrough)?/V1");

    /** A template's effect on the certificate it issues. */
    record Template(String name, BasicConstraints basicConstraints, int keyUsage, List<KeyPurposeId> extendedKeyUsage,
                    boolean ca, boolean root, Integer pathLength, boolean apiPassthrough, boolean csrPassthrough) {}

    private AcmPcaTemplates() {
    }

    /** The template a {@code TemplateArn} names ({@code arn:<partition>:acm-pca:::template/<name>}). */
    static Template forArn(String templateArn) {
        if (templateArn == null || templateArn.isEmpty()) {
            return byName(DEFAULT);
        }
        if (!AwsArnUtils.isArnFor(templateArn, "acm-pca")) {
            throw new AwsException("InvalidArnException", "The template ARN " + templateArn + " is not valid.", 400);
        }
        String resource = AwsArnUtils.parse(templateArn).resource();
        if (!resource.startsWith("template/")) {
            throw new AwsException("InvalidArnException", "The template ARN " + templateArn + " is not valid.", 400);
        }
        return byName(resource.substring("template/".length()));
    }

    static Template byName(String name) {
        Matcher matcher = NAME.matcher(name);
        if (!matcher.matches()) {
            throw AcmPcaService.invalidArgs("The template " + name + " does not exist.");
        }
        boolean blank = matcher.group(1) != null;
        String kind = matcher.group(2);
        Integer pathLength = matcher.group(3) == null ? null : Integer.parseInt(matcher.group(3));
        String passthrough = matcher.group(5) == null ? "" : matcher.group(5);
        boolean api = passthrough.contains("API");
        boolean csr = passthrough.contains("CSR");
        if ("SubordinateCA".equals(kind) && pathLength == null) {
            throw AcmPcaService.invalidArgs("The template " + name + " does not exist.");
        }
        if (!"SubordinateCA".equals(kind) && pathLength != null) {
            throw AcmPcaService.invalidArgs("The template " + name + " does not exist.");
        }
        if (blank && !api) {
            throw AcmPcaService.invalidArgs("The template " + name + " does not exist.");
        }
        boolean root = "RootCA".equals(kind);
        boolean ca = root || "SubordinateCA".equals(kind);
        if (blank) {
            // Blank templates write only basic constraints; everything else comes through ApiPassthrough.
            BasicConstraints constraints = !ca ? new BasicConstraints(false)
                    : pathLength == null ? new BasicConstraints(true) : new BasicConstraints(pathLength);
            return new Template(name, constraints, 0, List.of(), ca, root, pathLength, true, csr);
        }
        int caUsage = KeyUsage.keyCertSign | KeyUsage.cRLSign | KeyUsage.digitalSignature;
        return switch (kind) {
            case "EndEntity" -> new Template(name, new BasicConstraints(false),
                    KeyUsage.digitalSignature | KeyUsage.keyEncipherment,
                    List.of(KeyPurposeId.id_kp_serverAuth, KeyPurposeId.id_kp_clientAuth), false, false, null, api,
                    csr);
            case "EndEntityClientAuth" -> new Template(name, new BasicConstraints(false),
                    KeyUsage.digitalSignature | KeyUsage.keyAgreement, List.of(KeyPurposeId.id_kp_clientAuth), false,
                    false, null, api, csr);
            case "EndEntityServerAuth" -> new Template(name, new BasicConstraints(false),
                    KeyUsage.digitalSignature | KeyUsage.keyEncipherment, List.of(KeyPurposeId.id_kp_serverAuth),
                    false, false, null, api, csr);
            case "CodeSigning" -> new Template(name, new BasicConstraints(false), KeyUsage.digitalSignature,
                    List.of(KeyPurposeId.id_kp_codeSigning), false, false, null, api, csr);
            case "OCSPSigning" -> new Template(name, new BasicConstraints(false), KeyUsage.digitalSignature,
                    List.of(KeyPurposeId.id_kp_OCSPSigning), false, false, null, api, csr);
            case "RootCA" -> new Template(name, new BasicConstraints(true), caUsage, List.of(), true, true, null, api,
                    csr);
            default -> new Template(name, new BasicConstraints(pathLength), caUsage, List.of(), true, false,
                    pathLength, api, csr);
        };
    }

    /** The key purpose of an {@code ExtendedKeyUsageType}. */
    static KeyPurposeId purpose(String type) {
        return switch (type) {
            case "SERVER_AUTH" -> KeyPurposeId.id_kp_serverAuth;
            case "CLIENT_AUTH" -> KeyPurposeId.id_kp_clientAuth;
            case "CODE_SIGNING" -> KeyPurposeId.id_kp_codeSigning;
            case "EMAIL_PROTECTION" -> KeyPurposeId.id_kp_emailProtection;
            case "TIME_STAMPING" -> KeyPurposeId.id_kp_timeStamping;
            case "OCSP_SIGNING" -> KeyPurposeId.id_kp_OCSPSigning;
            case "SMART_CARD_LOGIN" -> KeyPurposeId.id_kp_smartcardlogon;
            case "DOCUMENT_SIGNING" -> KeyPurposeId.getInstance(
                    new ASN1ObjectIdentifier("1.3.6.1.4.1.311.10.3.12"));
            case "CERTIFICATE_TRANSPARENCY" -> KeyPurposeId.getInstance(
                    new ASN1ObjectIdentifier("1.3.6.1.4.1.11129.2.4.4"));
            default -> throw AcmPcaService.invalidArgs("ExtendedKeyUsageType " + type + " is not supported.");
        };
    }
}

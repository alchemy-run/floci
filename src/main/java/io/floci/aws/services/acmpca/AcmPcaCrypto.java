package io.floci.aws.services.acmpca;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.core.common.AwsException;
import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.DERUTF8String;
import org.bouncycastle.asn1.pkcs.Attribute;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x509.AccessDescription;
import org.bouncycastle.asn1.x509.AuthorityInformationAccess;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.CRLDistPoint;
import org.bouncycastle.asn1.x509.CRLNumber;
import org.bouncycastle.asn1.x509.CRLReason;
import org.bouncycastle.asn1.x509.CertificatePolicies;
import org.bouncycastle.asn1.x509.DistributionPoint;
import org.bouncycastle.asn1.x509.DistributionPointName;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.Extensions;
import org.bouncycastle.asn1.x509.ExtensionsGenerator;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.asn1.x509.PolicyInformation;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.cert.X509CRLHolder;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v2CRLBuilder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaContentVerifierProviderBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;
import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemWriter;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Security;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The X.509 side of AWS Private CA: a CA's key pair and CSR, certificates signed from the CA's key
 * with the extensions of AWS's certificate templates (and what API and CSR passthrough add), and
 * CRLs. Keys are generated with the CA's key algorithm, and signatures use the requested signing
 * algorithm, which must belong to the key's family.
 */
final class AcmPcaCrypto {
    static {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    private static final Map<String, String> SIGNING = Map.of(
            "SHA256WITHRSA", "SHA256withRSA", "SHA384WITHRSA", "SHA384withRSA", "SHA512WITHRSA", "SHA512withRSA",
            "SHA256WITHECDSA", "SHA256withECDSA", "SHA384WITHECDSA", "SHA384withECDSA",
            "SHA512WITHECDSA", "SHA512withECDSA", "SM3WITHSM2", "SM3withSM2");

    private static final List<String[]> SUBJECT_FIELDS = List.of(
            new String[]{"Country", "C"}, new String[]{"Organization", "O"},
            new String[]{"OrganizationalUnit", "OU"}, new String[]{"DistinguishedNameQualifier", "DN"},
            new String[]{"State", "ST"}, new String[]{"CommonName", "CN"}, new String[]{"SerialNumber", "SERIALNUMBER"},
            new String[]{"Locality", "L"}, new String[]{"Title", "T"}, new String[]{"Surname", "SURNAME"},
            new String[]{"GivenName", "GIVENNAME"}, new String[]{"Initials", "INITIALS"},
            new String[]{"Pseudonym", "PSEUDONYM"}, new String[]{"GenerationQualifier", "GENERATION"});

    private static final SecureRandom RANDOM = new SecureRandom();

    private AcmPcaCrypto() {
    }

    // ── Keys ───────────────────────────────────────────────────────────────

    static KeyPair generate(String keyAlgorithm) {
        try {
            KeyPairGenerator generator;
            switch (keyAlgorithm) {
                case "RSA_2048", "RSA_3072", "RSA_4096" -> {
                    generator = KeyPairGenerator.getInstance("RSA");
                    generator.initialize(Integer.parseInt(keyAlgorithm.substring(4)), RANDOM);
                }
                case "EC_prime256v1", "EC_secp384r1", "EC_secp521r1" -> {
                    generator = KeyPairGenerator.getInstance("EC");
                    String curve = keyAlgorithm.substring(3).replace("prime256v1", "secp256r1");
                    generator.initialize(new ECGenParameterSpec(curve), RANDOM);
                }
                case "SM2" -> {
                    generator = KeyPairGenerator.getInstance("EC", BouncyCastleProvider.PROVIDER_NAME);
                    generator.initialize(new ECGenParameterSpec("sm2p256v1"), RANDOM);
                }
                default -> throw AcmPcaService.invalidArgs("KeyAlgorithm " + keyAlgorithm + " is not supported.");
            }
            return generator.generateKeyPair();
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Could not generate a " + keyAlgorithm + " key", e);
        }
    }

    static String encodeKey(PrivateKey key) {
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }

    static PrivateKey decodeKey(String encoded, String keyAlgorithm) {
        try {
            String family = keyAlgorithm.startsWith("RSA") ? "RSA" : "EC";
            KeyFactory factory = "SM2".equals(keyAlgorithm)
                    ? KeyFactory.getInstance(family, BouncyCastleProvider.PROVIDER_NAME) : KeyFactory.getInstance(family);
            return factory.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(encoded)));
        } catch (Exception e) {
            throw new IllegalStateException("The CA's stored key could not be read", e);
        }
    }

    /** The JCA signature algorithm for an AWS signing algorithm, checked against the key family. */
    static String signatureAlgorithm(String signing, String keyAlgorithm) {
        String jca = SIGNING.get(signing);
        if (jca == null) {
            throw AcmPcaService.invalidArgs("SigningAlgorithm " + signing + " is not supported.");
        }
        boolean rsaKey = keyAlgorithm.startsWith("RSA");
        boolean sm2 = "SM2".equals(keyAlgorithm);
        if (rsaKey != signing.endsWith("RSA") || sm2 != "SM3WITHSM2".equals(signing)) {
            throw AcmPcaService.invalidArgs("The signing algorithm " + signing + " is not compatible with the CA's "
                    + keyAlgorithm + " key.");
        }
        return jca;
    }

    private static ContentSigner signer(String jca, PrivateKey key) {
        try {
            JcaContentSignerBuilder builder = new JcaContentSignerBuilder(jca);
            if (jca.startsWith("SM3")) {
                builder.setProvider(BouncyCastleProvider.PROVIDER_NAME);
            }
            return builder.build(key);
        } catch (Exception e) {
            throw new IllegalStateException("Could not sign with " + jca, e);
        }
    }

    // ── Names ──────────────────────────────────────────────────────────────

    /** An X.500 name from an ASN1Subject. */
    static X500Name subject(JsonNode subject) {
        X500NameBuilder builder = new X500NameBuilder(BCStyle.INSTANCE);
        int fields = 0;
        for (String[] field : SUBJECT_FIELDS) {
            if (subject.hasNonNull(field[0]) && !subject.get(field[0]).asText().isEmpty()) {
                builder.addRDN(BCStyle.INSTANCE.attrNameToOID(field[1]), subject.get(field[0]).asText());
                fields++;
            }
        }
        for (JsonNode custom : subject.path("CustomAttributes")) {
            builder.addRDN(new ASN1ObjectIdentifier(custom.path("ObjectIdentifier").asText()),
                    new DERUTF8String(custom.path("Value").asText()));
            fields++;
        }
        if (fields == 0) {
            throw AcmPcaService.invalidArgs("The Subject must have at least one attribute.");
        }
        return builder.build();
    }

    // ── CSRs ───────────────────────────────────────────────────────────────

    /** The CA's CSR, with the key usage and subject information access its configuration asks for. */
    static String csr(KeyPair keys, X500Name subject, String signing, String keyAlgorithm, JsonNode csrExtensions) {
        try {
            JcaPKCS10CertificationRequestBuilder builder = new JcaPKCS10CertificationRequestBuilder(subject,
                    keys.getPublic());
            ExtensionsGenerator extensions = new ExtensionsGenerator();
            extensions.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
            JsonNode keyUsage = csrExtensions.path("KeyUsage");
            extensions.addExtension(Extension.keyUsage, true, keyUsage.isObject() ? keyUsage(keyUsage)
                    : new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign | KeyUsage.digitalSignature));
            JsonNode sia = csrExtensions.path("SubjectInformationAccess");
            if (sia.isArray() && !sia.isEmpty()) {
                List<AccessDescription> access = new ArrayList<>();
                for (JsonNode description : sia) {
                    access.add(new AccessDescription(accessMethod(description.path("AccessMethod")),
                            generalName(description.path("AccessLocation"))));
                }
                extensions.addExtension(Extension.subjectInfoAccess, false, new DERSequence(
                        access.toArray(new ASN1Encodable[0])));
            }
            builder.addAttribute(PKCSObjectIdentifiers.pkcs_9_at_extensionRequest, extensions.generate());
            return pem("CERTIFICATE REQUEST", builder.build(signer(signatureAlgorithm(signing, keyAlgorithm),
                    keys.getPrivate())).getEncoded());
        } catch (IOException e) {
            throw new IllegalStateException("Could not build the CA's CSR", e);
        }
    }

    static PKCS10CertificationRequest parseCsr(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8).trim();
        try {
            PKCS10CertificationRequest csr;
            if (text.startsWith("-----BEGIN")) {
                try (PEMParser parser = new PEMParser(new StringReader(text))) {
                    Object parsed = parser.readObject();
                    if (!(parsed instanceof PKCS10CertificationRequest request)) {
                        throw new IOException("not a CSR");
                    }
                    csr = request;
                }
            } else {
                csr = new PKCS10CertificationRequest(bytes);
            }
            if (!csr.isSignatureValid(new JcaContentVerifierProviderBuilder()
                    .setProvider(BouncyCastleProvider.PROVIDER_NAME).build(csr.getSubjectPublicKeyInfo()))) {
                throw new AwsException("MalformedCSRException", "The CSR's signature is not valid.", 400);
            }
            return csr;
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new AwsException("MalformedCSRException", "The certificate signing request is invalid.", 400);
        }
    }

    // ── Certificates ───────────────────────────────────────────────────────

    /** What a signed certificate comes from: template, CSR, passthrough, validity and the issuer. */
    record Issue(PKCS10CertificationRequest csr, AcmPcaTemplates.Template template, JsonNode apiPassthrough,
                 Instant notBefore, Instant notAfter, BigInteger serial, X500Name issuer, PublicKey issuerKey,
                 PrivateKey signingKey, String signatureAlgorithm, List<String> crlUrls, String ocspUrl) {}

    static String sign(Issue issue) {
        try {
            SubjectPublicKeyInfo publicKey = issue.csr().getSubjectPublicKeyInfo();
            X500Name subject = issue.apiPassthrough().path("Subject").isObject() && issue.template().apiPassthrough()
                    ? subject(issue.apiPassthrough().path("Subject")) : issue.csr().getSubject();
            if (subject.getRDNs().length == 0 && sanFromPassthrough(issue.apiPassthrough()) == null) {
                throw AcmPcaService.invalidArgs("The certificate needs a subject or subject alternative names.");
            }
            X509v3CertificateBuilder builder = new X509v3CertificateBuilder(issue.issuer(), issue.serial(),
                    Date.from(issue.notBefore()), Date.from(issue.notAfter()), subject, publicKey);
            JcaX509ExtensionUtils utils = new JcaX509ExtensionUtils();
            Map<ASN1ObjectIdentifier, Extension> extensions = new LinkedHashMap<>();
            AcmPcaTemplates.Template template = issue.template();
            if (template.basicConstraints() != null) {
                extensions.put(Extension.basicConstraints, new Extension(Extension.basicConstraints, true,
                        template.basicConstraints().getEncoded()));
            }
            if (template.keyUsage() != 0) {
                extensions.put(Extension.keyUsage, new Extension(Extension.keyUsage, true,
                        new KeyUsage(template.keyUsage()).getEncoded()));
            }
            if (!template.extendedKeyUsage().isEmpty()) {
                extensions.put(Extension.extendedKeyUsage, new Extension(Extension.extendedKeyUsage, false,
                        new ExtendedKeyUsage(template.extendedKeyUsage().toArray(new KeyPurposeId[0])).getEncoded()));
            }
            if (template.csrPassthrough()) {
                Extensions requested = requestedExtensions(issue.csr());
                if (requested != null) {
                    for (ASN1ObjectIdentifier oid : requested.getExtensionOIDs()) {
                        if (!Extension.basicConstraints.equals(oid) || template.basicConstraints() == null) {
                            extensions.putIfAbsent(oid, requested.getExtension(oid));
                        }
                    }
                }
            }
            if (template.apiPassthrough()) {
                applyPassthrough(issue.apiPassthrough().path("Extensions"), extensions);
            }
            if (!extensions.containsKey(Extension.subjectAlternativeName) && !template.ca()) {
                GeneralNames sans = sanFromCsr(issue.csr());
                if (sans != null) {
                    extensions.put(Extension.subjectAlternativeName, new Extension(Extension.subjectAlternativeName,
                            false, sans.getEncoded()));
                }
            }
            extensions.put(Extension.subjectKeyIdentifier, new Extension(Extension.subjectKeyIdentifier, false,
                    utils.createSubjectKeyIdentifier(publicKey).getEncoded()));
            extensions.put(Extension.authorityKeyIdentifier, new Extension(Extension.authorityKeyIdentifier, false,
                    utils.createAuthorityKeyIdentifier(SubjectPublicKeyInfo.getInstance(
                            issue.issuerKey().getEncoded())).getEncoded()));
            if (!issue.crlUrls().isEmpty()) {
                DistributionPoint[] points = issue.crlUrls().stream().map(url -> new DistributionPoint(
                        new DistributionPointName(new GeneralNames(new GeneralName(
                                GeneralName.uniformResourceIdentifier, url))), null, null))
                        .toArray(DistributionPoint[]::new);
                extensions.put(Extension.cRLDistributionPoints, new Extension(Extension.cRLDistributionPoints, false,
                        new CRLDistPoint(points).getEncoded()));
            }
            if (issue.ocspUrl() != null) {
                extensions.put(Extension.authorityInfoAccess, new Extension(Extension.authorityInfoAccess, false,
                        new AuthorityInformationAccess(AccessDescription.id_ad_ocsp, new GeneralName(
                                GeneralName.uniformResourceIdentifier, issue.ocspUrl())).getEncoded()));
            }
            for (Extension extension : extensions.values()) {
                builder.addExtension(extension);
            }
            X509CertificateHolder holder = builder.build(signer(issue.signatureAlgorithm(), issue.signingKey()));
            return pem("CERTIFICATE", holder.getEncoded());
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Could not sign the certificate", e);
        }
    }

    private static Extensions requestedExtensions(PKCS10CertificationRequest csr) {
        for (Attribute attribute : csr.getAttributes(PKCSObjectIdentifiers.pkcs_9_at_extensionRequest)) {
            return Extensions.getInstance(attribute.getAttrValues().getObjectAt(0));
        }
        return null;
    }

    private static GeneralNames sanFromCsr(PKCS10CertificationRequest csr) {
        Extensions requested = requestedExtensions(csr);
        if (requested == null || requested.getExtension(Extension.subjectAlternativeName) == null) {
            return null;
        }
        return GeneralNames.getInstance(requested.getExtension(Extension.subjectAlternativeName).getParsedValue());
    }

    private static JsonNode sanFromPassthrough(JsonNode passthrough) {
        JsonNode sans = passthrough.path("Extensions").path("SubjectAlternativeNames");
        return sans.isArray() && !sans.isEmpty() ? sans : null;
    }

    private static void applyPassthrough(JsonNode extensions, Map<ASN1ObjectIdentifier, Extension> out)
            throws IOException {
        if (extensions.path("KeyUsage").isObject()) {
            out.put(Extension.keyUsage, new Extension(Extension.keyUsage, true,
                    keyUsage(extensions.get("KeyUsage")).getEncoded()));
        }
        JsonNode eku = extensions.path("ExtendedKeyUsage");
        if (eku.isArray() && !eku.isEmpty()) {
            List<KeyPurposeId> purposes = new ArrayList<>();
            for (JsonNode usage : eku) {
                purposes.add(usage.hasNonNull("ExtendedKeyUsageObjectIdentifier")
                        ? KeyPurposeId.getInstance(new ASN1ObjectIdentifier(
                                usage.get("ExtendedKeyUsageObjectIdentifier").asText()))
                        : AcmPcaTemplates.purpose(usage.path("ExtendedKeyUsageType").asText()));
            }
            out.put(Extension.extendedKeyUsage, new Extension(Extension.extendedKeyUsage, false,
                    new ExtendedKeyUsage(purposes.toArray(new KeyPurposeId[0])).getEncoded()));
        }
        JsonNode sans = extensions.path("SubjectAlternativeNames");
        if (sans.isArray() && !sans.isEmpty()) {
            List<GeneralName> names = new ArrayList<>();
            sans.forEach(name -> names.add(generalName(name)));
            out.put(Extension.subjectAlternativeName, new Extension(Extension.subjectAlternativeName, false,
                    new GeneralNames(names.toArray(new GeneralName[0])).getEncoded()));
        }
        JsonNode policies = extensions.path("CertificatePolicies");
        if (policies.isArray() && !policies.isEmpty()) {
            List<PolicyInformation> info = new ArrayList<>();
            policies.forEach(policy -> info.add(new PolicyInformation(new ASN1ObjectIdentifier(
                    policy.path("CertPolicyId").asText()))));
            out.put(Extension.certificatePolicies, new Extension(Extension.certificatePolicies, false,
                    new CertificatePolicies(info.toArray(new PolicyInformation[0])).getEncoded()));
        }
        for (JsonNode custom : extensions.path("CustomExtensions")) {
            byte[] value = Base64.getDecoder().decode(custom.path("Value").asText(""));
            ASN1ObjectIdentifier oid = new ASN1ObjectIdentifier(custom.path("ObjectIdentifier").asText());
            out.put(oid, new Extension(oid, custom.path("Critical").asBoolean(false), value));
        }
    }

    static KeyUsage keyUsage(JsonNode usage) {
        int bits = 0;
        Object[][] flags = {{"DigitalSignature", KeyUsage.digitalSignature}, {"NonRepudiation", KeyUsage.nonRepudiation},
            {"KeyEncipherment", KeyUsage.keyEncipherment}, {"DataEncipherment", KeyUsage.dataEncipherment},
            {"KeyAgreement", KeyUsage.keyAgreement}, {"KeyCertSign", KeyUsage.keyCertSign},
            {"CRLSign", KeyUsage.cRLSign}, {"EncipherOnly", KeyUsage.encipherOnly},
            {"DecipherOnly", KeyUsage.decipherOnly}};
        for (Object[] flag : flags) {
            if (usage.path((String) flag[0]).asBoolean(false)) {
                bits |= (Integer) flag[1];
            }
        }
        return new KeyUsage(bits);
    }

    private static ASN1ObjectIdentifier accessMethod(JsonNode method) {
        if (method.hasNonNull("CustomObjectIdentifier")) {
            return new ASN1ObjectIdentifier(method.get("CustomObjectIdentifier").asText());
        }
        return switch (method.path("AccessMethodType").asText("")) {
            case "CA_REPOSITORY" -> new ASN1ObjectIdentifier("1.3.6.1.5.5.7.48.5");
            case "RESOURCE_PKI_MANIFEST" -> new ASN1ObjectIdentifier("1.3.6.1.5.5.7.48.10");
            case "RESOURCE_PKI_NOTIFY" -> new ASN1ObjectIdentifier("1.3.6.1.5.5.7.48.13");
            default -> throw AcmPcaService.invalidArgs("An AccessMethod needs an AccessMethodType or a "
                    + "CustomObjectIdentifier.");
        };
    }

    static GeneralName generalName(JsonNode name) {
        if (name.hasNonNull("DnsName")) {
            return new GeneralName(GeneralName.dNSName, name.get("DnsName").asText());
        }
        if (name.hasNonNull("Rfc822Name")) {
            return new GeneralName(GeneralName.rfc822Name, name.get("Rfc822Name").asText());
        }
        if (name.hasNonNull("UniformResourceIdentifier")) {
            return new GeneralName(GeneralName.uniformResourceIdentifier, name.get("UniformResourceIdentifier")
                    .asText());
        }
        if (name.hasNonNull("IpAddress")) {
            return new GeneralName(GeneralName.iPAddress, name.get("IpAddress").asText());
        }
        if (name.hasNonNull("RegisteredId")) {
            return new GeneralName(GeneralName.registeredID, name.get("RegisteredId").asText());
        }
        if (name.path("DirectoryName").isObject()) {
            return new GeneralName(subject(name.get("DirectoryName")));
        }
        throw AcmPcaService.invalidArgs("A GeneralName needs one of its name members.");
    }

    // ── Parsing ────────────────────────────────────────────────────────────

    /** Every certificate in a PEM bundle, in order. */
    static List<X509CertificateHolder> parseCertificates(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        List<X509CertificateHolder> out = new ArrayList<>();
        try (PEMParser parser = new PEMParser(new StringReader(text))) {
            Object parsed;
            while ((parsed = parser.readObject()) != null) {
                if (!(parsed instanceof X509CertificateHolder holder)) {
                    throw new IOException("not a certificate");
                }
                out.add(holder);
            }
        } catch (Exception e) {
            throw new AwsException("MalformedCertificateException", "The certificate is not a valid PEM-encoded "
                    + "X.509 certificate.", 400);
        }
        if (out.isEmpty()) {
            throw new AwsException("MalformedCertificateException", "The certificate is not a valid PEM-encoded "
                    + "X.509 certificate.", 400);
        }
        return out;
    }

    static X509CertificateHolder parseCertificate(String pem) {
        return parseCertificates(pem.getBytes(StandardCharsets.UTF_8)).getFirst();
    }

    static boolean signedBy(X509CertificateHolder certificate, SubjectPublicKeyInfo issuerKey) {
        try {
            return certificate.isSignatureValid(new JcaContentVerifierProviderBuilder()
                    .setProvider(BouncyCastleProvider.PROVIDER_NAME).build(issuerKey));
        } catch (Exception e) {
            return false;
        }
    }

    static boolean isCa(X509CertificateHolder certificate) {
        Extension extension = certificate.getExtension(Extension.basicConstraints);
        return extension != null && BasicConstraints.getInstance(extension.getParsedValue()).isCA();
    }

    /** The path length a CA certificate allows below it, or null when it sets none. */
    static BigInteger pathLength(X509CertificateHolder certificate) {
        Extension extension = certificate.getExtension(Extension.basicConstraints);
        return extension == null ? null : BasicConstraints.getInstance(extension.getParsedValue()).getPathLenConstraint();
    }

    static String pem(String type, byte[] der) {
        try {
            StringWriter writer = new StringWriter();
            try (PemWriter pem = new PemWriter(writer)) {
                pem.writeObject(new PemObject(type, der));
            }
            return writer.toString();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static BigInteger serial() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        bytes[0] &= 0x7f;
        if (bytes[0] == 0) {
            bytes[0] = 1;
        }
        return new BigInteger(bytes);
    }

    /** A serial as AWS prints and accepts it: lowercase hex, colon-separated pairs. */
    static String serialText(BigInteger serial) {
        String hex = serial.toString(16);
        if (hex.length() % 2 == 1) {
            hex = "0" + hex;
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < hex.length(); i += 2) {
            if (i > 0) {
                out.append(':');
            }
            out.append(hex, i, i + 2);
        }
        return out.toString().toLowerCase(Locale.ROOT);
    }

    /** A serial in any of the forms callers send: hex with or without colons. */
    static BigInteger parseSerial(String text) {
        try {
            return new BigInteger(text.replace(":", "").trim(), 16);
        } catch (NumberFormatException e) {
            throw AcmPcaService.invalidArgs("The certificate serial " + text + " is not valid.");
        }
    }

    // ── CRLs ───────────────────────────────────────────────────────────────

    record Revoked(BigInteger serial, Instant at, String reason) {}

    static byte[] crl(X500Name issuer, PublicKey issuerKey, PrivateKey key, String signatureAlgorithm,
                      List<Revoked> revoked, long number, int expirationDays) {
        try {
            Instant now = Instant.now();
            X509v2CRLBuilder builder = new X509v2CRLBuilder(issuer, Date.from(now));
            builder.setNextUpdate(Date.from(now.plusSeconds(expirationDays * 86_400L)));
            for (Revoked entry : revoked) {
                builder.addCRLEntry(entry.serial(), Date.from(entry.at()), reasonCode(entry.reason()));
            }
            builder.addExtension(Extension.cRLNumber, false, new CRLNumber(BigInteger.valueOf(number)));
            builder.addExtension(Extension.authorityKeyIdentifier, false, new JcaX509ExtensionUtils()
                    .createAuthorityKeyIdentifier(SubjectPublicKeyInfo.getInstance(issuerKey.getEncoded())));
            X509CRLHolder holder = builder.build(signer(signatureAlgorithm, key));
            return holder.getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("Could not build the CRL", e);
        }
    }

    private static int reasonCode(String reason) {
        return switch (reason) {
            case "KEY_COMPROMISE" -> CRLReason.keyCompromise;
            case "CERTIFICATE_AUTHORITY_COMPROMISE" -> CRLReason.cACompromise;
            case "AFFILIATION_CHANGED" -> CRLReason.affiliationChanged;
            case "SUPERSEDED" -> CRLReason.superseded;
            case "CESSATION_OF_OPERATION" -> CRLReason.cessationOfOperation;
            case "PRIVILEGE_WITHDRAWN" -> CRLReason.privilegeWithdrawn;
            case "A_A_COMPROMISE" -> CRLReason.aACompromise;
            default -> CRLReason.unspecified;
        };
    }
}

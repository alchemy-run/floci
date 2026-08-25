package io.github.hectorvent.floci.services.acmpca;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequest;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;

import java.io.StringReader;
import java.io.StringWriter;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Map;

/**
 * CSR and certificate helpers for ACM PCA. Isolated so the service stays a
 * thin orchestrator over storage and AWS state rules.
 */
final class AcmPcaCertificates {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    record KeyMaterial(String csrPem, String privateKeyPem) {}

    record Issued(String pem, String serialHex) {}

    private AcmPcaCertificates() {
    }

    static KeyMaterial generateKeyMaterial(Map<String, Object> configuration) {
        String keyAlgorithm = stringField(configuration, "KeyAlgorithm", "RSA_2048");
        String signingAlgorithm = stringField(configuration, "SigningAlgorithm", "SHA256WITHRSA");
        @SuppressWarnings("unchecked")
        Map<String, Object> subject = configuration.get("Subject") instanceof Map<?, ?> map
            ? (Map<String, Object>) map
            : Map.of();
        try {
            KeyPair keyPair = generateKeyPair(keyAlgorithm);
            X500Name x500 = toX500Name(subject);
            JcaPKCS10CertificationRequestBuilder csrBuilder =
                new JcaPKCS10CertificationRequestBuilder(x500, keyPair.getPublic());
            ContentSigner signer = new JcaContentSignerBuilder(javaSignatureAlgorithm(signingAlgorithm))
                .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                .build(keyPair.getPrivate());
            return new KeyMaterial(toPem(csrBuilder.build(signer)), toPem(keyPair.getPrivate()));
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new AwsException("InvalidArgsException",
                "Failed to generate certificate authority key material: " + e.getMessage(), 400);
        }
    }

    static Issued issue(String csrPem, String signingAlgorithm, boolean asCa,
                        String issuerPem, String caCsrPem, String caPrivateKeyPem,
                        Map<String, Object> validity) {
        try {
            PKCS10CertificationRequest csr = parseCsr(csrPem);
            JcaPKCS10CertificationRequest jcaCsr = new JcaPKCS10CertificationRequest(csr)
                .setProvider(BouncyCastleProvider.PROVIDER_NAME);
            PublicKey subjectPublicKey = jcaCsr.getPublicKey();
            X500Name subject = csr.getSubject();
            X500Name issuer = asCa || issuerPem == null
                ? subject
                : X500Name.getInstance(parseCertificate(issuerPem).getSubjectX500Principal().getEncoded());
            PrivateKey signingKey = parsePrivateKey(caPrivateKeyPem);
            PublicKey caPublicKey = issuerPem != null
                ? parseCertificate(issuerPem).getPublicKey()
                : new JcaPKCS10CertificationRequest(parseCsr(caCsrPem))
                    .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                    .getPublicKey();

            Instant now = Instant.now();
            Instant notBefore = now.minus(1, ChronoUnit.MINUTES);
            Instant notAfter = validityInstant(validity, now);
            BigInteger serial = new BigInteger(128, SECURE_RANDOM);

            X509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(
                issuer,
                serial,
                java.util.Date.from(notBefore),
                java.util.Date.from(notAfter),
                subject,
                subjectPublicKey
            );
            int keyUsageBits = asCa
                ? (KeyUsage.keyCertSign | KeyUsage.cRLSign)
                : (KeyUsage.digitalSignature | KeyUsage.keyEncipherment);
            certBuilder.addExtension(Extension.basicConstraints, true, new BasicConstraints(asCa));
            certBuilder.addExtension(Extension.keyUsage, true, new KeyUsage(keyUsageBits));
            JcaX509ExtensionUtils extUtils = new JcaX509ExtensionUtils();
            certBuilder.addExtension(Extension.subjectKeyIdentifier, false,
                extUtils.createSubjectKeyIdentifier(subjectPublicKey));
            certBuilder.addExtension(Extension.authorityKeyIdentifier, false,
                extUtils.createAuthorityKeyIdentifier(caPublicKey));

            ContentSigner signer = new JcaContentSignerBuilder(javaSignatureAlgorithm(signingAlgorithm))
                .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                .build(signingKey);
            X509CertificateHolder holder = certBuilder.build(signer);
            X509Certificate cert = new JcaX509CertificateConverter()
                .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                .getCertificate(holder);
            return new Issued(toPem(cert), toSerialHex(cert.getSerialNumber()));
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new AwsException("MalformedCSRException",
                "The CSR could not be parsed or signed: " + e.getMessage(), 400);
        }
    }

    static X509Certificate parseCertificate(String pem) throws Exception {
        try (PEMParser parser = new PEMParser(new StringReader(pem))) {
            Object obj = parser.readObject();
            if (obj instanceof X509CertificateHolder holder) {
                return new JcaX509CertificateConverter()
                    .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                    .getCertificate(holder);
            }
            throw new IllegalArgumentException("Not an X.509 certificate");
        }
    }

    static PKCS10CertificationRequest parseCsr(String pem) throws Exception {
        try (PEMParser parser = new PEMParser(new StringReader(pem))) {
            Object obj = parser.readObject();
            if (obj instanceof PKCS10CertificationRequest csr) {
                return csr;
            }
            throw new IllegalArgumentException("Not a certificate request");
        }
    }

    static PrivateKey parsePrivateKey(String pem) throws Exception {
        try (PEMParser parser = new PEMParser(new StringReader(pem))) {
            Object obj = parser.readObject();
            JcaPEMKeyConverter converter = new JcaPEMKeyConverter()
                .setProvider(BouncyCastleProvider.PROVIDER_NAME);
            if (obj instanceof PEMKeyPair pemKeyPair) {
                return converter.getKeyPair(pemKeyPair).getPrivate();
            }
            if (obj instanceof org.bouncycastle.asn1.pkcs.PrivateKeyInfo info) {
                return converter.getPrivateKey(info);
            }
            throw new IllegalArgumentException("Not a private key");
        }
    }

    static String toPem(Object obj) throws Exception {
        StringWriter sw = new StringWriter();
        try (JcaPEMWriter pemWriter = new JcaPEMWriter(sw)) {
            pemWriter.writeObject(obj);
        }
        return sw.toString();
    }

    static String toSerialHex(BigInteger serial) {
        String hex = serial.toString(16);
        if ((hex.length() & 1) == 1) {
            hex = "0" + hex;
        }
        return hex.toLowerCase(Locale.ROOT);
    }

    static String normalizeSerial(String serial) {
        return serial.replace(":", "").replace(" ", "").toLowerCase(Locale.ROOT);
    }

    static Instant validityInstant(Map<String, Object> validity, Instant now) {
        if (validity == null || validity.get("Type") == null || validity.get("Value") == null) {
            throw new AwsException("InvalidArgsException",
                "Value null at 'validity' failed to satisfy constraint: Member must not be null", 400);
        }
        String type = String.valueOf(validity.get("Type"));
        Number value = toNumber(validity.get("Value"));
        if (value == null) {
            throw new AwsException("InvalidArgsException", "Validity.Value must be a number", 400);
        }
        long v = value.longValue();
        return switch (type) {
            case "DAYS" -> now.plus(v, ChronoUnit.DAYS);
            case "MONTHS" -> now.plus(v * 30, ChronoUnit.DAYS);
            case "YEARS" -> now.plus(v * 365, ChronoUnit.DAYS);
            case "ABSOLUTE", "END_DATE" -> Instant.ofEpochSecond(v);
            default -> now.plus(v, ChronoUnit.DAYS);
        };
    }

    static X500Name toX500Name(Map<String, Object> subject) {
        X500NameBuilder builder = new X500NameBuilder(BCStyle.INSTANCE);
        addRdn(builder, BCStyle.C, subject.get("Country"));
        addRdn(builder, BCStyle.ST, subject.get("State"));
        addRdn(builder, BCStyle.L, subject.get("Locality"));
        addRdn(builder, BCStyle.O, subject.get("Organization"));
        addRdn(builder, BCStyle.OU, subject.get("OrganizationalUnit"));
        addRdn(builder, BCStyle.CN, subject.get("CommonName"));
        addRdn(builder, BCStyle.SERIALNUMBER, subject.get("SerialNumber"));
        addRdn(builder, BCStyle.T, subject.get("Title"));
        addRdn(builder, BCStyle.SURNAME, subject.get("Surname"));
        addRdn(builder, BCStyle.GIVENNAME, subject.get("GivenName"));
        addRdn(builder, BCStyle.INITIALS, subject.get("Initials"));
        addRdn(builder, BCStyle.PSEUDONYM, subject.get("Pseudonym"));
        addRdn(builder, BCStyle.DN_QUALIFIER, subject.get("DistinguishedNameQualifier"));
        addRdn(builder, BCStyle.GENERATION, subject.get("GenerationQualifier"));
        try {
            return builder.build();
        } catch (IllegalArgumentException e) {
            throw new AwsException("InvalidArgsException",
                "Certificate authority subject must contain at least one attribute", 400);
        }
    }

    private static void addRdn(X500NameBuilder builder, org.bouncycastle.asn1.ASN1ObjectIdentifier oid, Object value) {
        if (value instanceof String s && !s.isBlank()) {
            builder.addRDN(oid, s);
        }
    }

    private static KeyPair generateKeyPair(String keyAlgorithm) throws Exception {
        KeyAlgorithm alg = KeyAlgorithm.fromAwsName(keyAlgorithm);
        KeyPairGenerator keyGen;
        if ("EC".equals(alg.getAlgorithm())) {
            keyGen = KeyPairGenerator.getInstance("EC", BouncyCastleProvider.PROVIDER_NAME);
            keyGen.initialize(new ECGenParameterSpec(alg.getCurveName()), SECURE_RANDOM);
        } else {
            keyGen = KeyPairGenerator.getInstance("RSA", BouncyCastleProvider.PROVIDER_NAME);
            keyGen.initialize(alg.getKeySize(), SECURE_RANDOM);
        }
        return keyGen.generateKeyPair();
    }

    private static String javaSignatureAlgorithm(String aws) {
        if (aws == null) {
            return "SHA256withRSA";
        }
        return switch (aws) {
            case "SHA256WITHRSA" -> "SHA256withRSA";
            case "SHA384WITHRSA" -> "SHA384withRSA";
            case "SHA512WITHRSA" -> "SHA512withRSA";
            case "SHA256WITHECDSA" -> "SHA256withECDSA";
            case "SHA384WITHECDSA" -> "SHA384withECDSA";
            case "SHA512WITHECDSA" -> "SHA512withECDSA";
            default -> "SHA256withRSA";
        };
    }

    private static String stringField(Map<String, Object> map, String key, String fallback) {
        Object value = map == null ? null : map.get(key);
        return value instanceof String s && !s.isBlank() ? s : fallback;
    }

    private static Number toNumber(Object value) {
        if (value instanceof Number n) {
            return n;
        }
        if (value instanceof String s) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}

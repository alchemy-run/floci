package io.floci.aws.services.signer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;

/**
 * The cryptography behind AWS Signer in Floci: an ECDSA P-384 key and a self-signed certificate per signing profile
 * version, code signatures embedded in a .zip's archive comment, and Notation JWS signatures.
 *
 * <p>A signed .zip is the original archive whose end-of-central-directory comment carries
 * {@code aws-signer:} and a base64 JSON envelope: the profile version and job ARNs, the certificate, and an
 * SHA384withECDSA signature over every byte of the archive up to the comment. Archive readers ignore the comment,
 * so the signed package deploys and runs like the original.
 */
final class SignerCrypto {

    static final String MARKER = "aws-signer:";
    private static final SecureRandom RANDOM = new SecureRandom();

    record KeyMaterial(String privateKey, String certificate) {
    }

    record Envelope(String profileVersionArn, String jobArn, String certificate, byte[] signature) {
    }

    private SignerCrypto() {
    }

    static KeyMaterial newKey(String subject) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp384r1"), RANDOM);
            KeyPair pair = generator.generateKeyPair();
            Instant now = Instant.now();
            X500Name name = new X500Name("CN=" + subject.replaceAll("[,=+<>#;\"\\\\]", "_") + ",O=AWS Signer");
            X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(name,
                    new BigInteger(64, RANDOM).abs(), Date.from(now), Date.from(now.plusSeconds(20L * 365 * 86400)),
                    name, pair.getPublic());
            ContentSigner contentSigner = new JcaContentSignerBuilder("SHA384withECDSA").build(pair.getPrivate());
            X509Certificate certificate = new JcaX509CertificateConverter().getCertificate(builder.build(contentSigner));
            return new KeyMaterial(Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()),
                    Base64.getEncoder().encodeToString(certificate.getEncoded()));
        } catch (Exception e) {
            throw new IllegalStateException("Couldn't create a signing key", e);
        }
    }

    static byte[] sign(KeyMaterial key, byte[] data) {
        try {
            PrivateKey privateKey = KeyFactory.getInstance("EC")
                    .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(key.privateKey())));
            Signature signature = Signature.getInstance("SHA384withECDSA");
            signature.initSign(privateKey);
            signature.update(data);
            return signature.sign();
        } catch (Exception e) {
            throw new IllegalStateException("Couldn't sign", e);
        }
    }

    static boolean verify(String certificate, byte[] data, byte[] signatureBytes) {
        try {
            X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(Base64.getDecoder().decode(certificate)));
            Signature signature = Signature.getInstance("SHA384withECDSA");
            signature.initVerify(cert.getPublicKey());
            signature.update(data);
            return signature.verify(signatureBytes);
        } catch (Exception e) {
            return false;
        }
    }

    /** The hex SHA-256 of a certificate, as revocation checks name certificates. */
    static String certificateHash(String certificate) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(Base64.getDecoder().decode(certificate)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Where the end-of-central-directory record starts, or -1 when the bytes aren't a .zip. */
    private static int eocd(byte[] zip) {
        for (int i = zip.length - 22; i >= Math.max(0, zip.length - 22 - 65535); i--) {
            if (zip[i] == 0x50 && zip[i + 1] == 0x4b && zip[i + 2] == 0x05 && zip[i + 3] == 0x06) {
                int commentLength = (zip[i + 20] & 0xff) | ((zip[i + 21] & 0xff) << 8);
                if (i + 22 + commentLength == zip.length) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** The bytes a code signature covers: the archive up to, not including, its comment length. */
    static byte[] signedRange(byte[] zip) {
        int at = eocd(zip);
        if (at < 0) {
            throw new IllegalArgumentException("not a zip archive");
        }
        return Arrays.copyOfRange(zip, 0, at + 20);
    }

    static boolean isZip(byte[] bytes) {
        return eocd(bytes) >= 0;
    }

    /** The archive with its comment replaced by the signature envelope. */
    static byte[] embed(byte[] zip, Envelope envelope, ObjectMapper mapper) {
        ObjectNode json = mapper.createObjectNode().put("profileVersionArn", envelope.profileVersionArn())
                .put("jobArn", envelope.jobArn()).put("certificate", envelope.certificate())
                .put("signature", Base64.getEncoder().encodeToString(envelope.signature()));
        byte[] comment = (MARKER + Base64.getEncoder().encodeToString(json.toString()
                .getBytes(StandardCharsets.UTF_8))).getBytes(StandardCharsets.US_ASCII);
        byte[] head = signedRange(zip);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(head);
        out.write(comment.length & 0xff);
        out.write((comment.length >> 8) & 0xff);
        out.writeBytes(comment);
        return out.toByteArray();
    }

    /** The signature envelope in the archive's comment, or null when it has none. */
    static Envelope envelope(byte[] zip, ObjectMapper mapper) {
        int at = eocd(zip);
        if (at < 0) {
            return null;
        }
        String comment = new String(zip, at + 22, zip.length - at - 22, StandardCharsets.US_ASCII);
        if (!comment.startsWith(MARKER)) {
            return null;
        }
        try {
            JsonNode json = mapper.readTree(Base64.getDecoder().decode(comment.substring(MARKER.length())));
            return new Envelope(json.path("profileVersionArn").asText(), json.path("jobArn").asText(),
                    json.path("certificate").asText(), Base64.getDecoder().decode(json.path("signature").asText()));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * A Notation JWS signature (application/jose+json) over the payload: an ES384 signature in its raw R||S form, with
     * the certificate as the {@code x5c} chain.
     */
    static byte[] notationJws(KeyMaterial key, byte[] payload, String payloadFormat, ObjectMapper mapper) {
        Base64.Encoder url = Base64.getUrlEncoder().withoutPadding();
        ObjectNode header = mapper.createObjectNode().put("alg", "ES384").put("cty", payloadFormat)
                .put("io.cncf.notary.signingScheme", "notary.x509")
                .put("io.cncf.notary.signingTime", Instant.now().toString());
        header.putArray("crit").add("io.cncf.notary.signingScheme");
        String protectedHeader = url.encodeToString(header.toString().getBytes(StandardCharsets.UTF_8));
        String encodedPayload = url.encodeToString(payload);
        byte[] der = sign(key, (protectedHeader + "." + encodedPayload).getBytes(StandardCharsets.US_ASCII));
        ObjectNode jws = mapper.createObjectNode().put("payload", encodedPayload).put("protected", protectedHeader)
                .put("signature", url.encodeToString(rawSignature(der, 48)));
        jws.putObject("header").putArray("x5c").add(key.certificate());
        return jws.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** A DER ECDSA signature as the fixed-width R||S form JWS uses. */
    private static byte[] rawSignature(byte[] der, int size) {
        int offset = 2;
        if ((der[1] & 0xff) > 0x80) {
            offset += (der[1] & 0x7f);
        }
        int rLength = der[offset + 1];
        byte[] r = Arrays.copyOfRange(der, offset + 2, offset + 2 + rLength);
        offset += 2 + rLength;
        int sLength = der[offset + 1];
        byte[] s = Arrays.copyOfRange(der, offset + 2, offset + 2 + sLength);
        byte[] out = new byte[size * 2];
        copyTail(r, out, 0, size);
        copyTail(s, out, size, size);
        return out;
    }

    private static void copyTail(byte[] value, byte[] out, int at, int size) {
        int start = Math.max(0, value.length - size);
        int length = value.length - start;
        System.arraycopy(value, start, out, at + size - length, length);
    }
}

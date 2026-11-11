package io.floci.aws.services.ivs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Participant tokens: JWTs signed with ES384. The ones {@code CreateParticipantToken} issues are signed with a key of
 * the account and Region that Floci keeps ({@code kid} {@link #ISSUER_KID}); the ones an application signs name the
 * imported public key's ARN as their {@code kid}. The payload is the one IVS documents: {@code exp}, {@code iat},
 * {@code jti} (the participant ID), {@code resource} (the stage ARN), {@code topic} (its ID), {@code events_url},
 * {@code whip_url}, {@code capabilities}, {@code attributes}, {@code user_id} and {@code version} 1.0.
 */
final class IvsRealtimeTokens {

    static final String ISSUER_KID = "ivs-realtime";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private IvsRealtimeTokens() {
    }

    /** A new P-384 key pair, as base64 PKCS #8 and X.509. */
    static String[] newKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp384r1"));
            KeyPair pair = generator.generateKeyPair();
            return new String[] {Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()),
                Base64.getEncoder().encodeToString(pair.getPublic().getEncoded())};
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot generate a P-384 key", e);
        }
    }

    static String sign(ObjectMapper mapper, String kid, ObjectNode payload, String privateKey) {
        ObjectNode header = mapper.createObjectNode().put("alg", "ES384").put("typ", "JWT").put("kid", kid);
        String signingInput = ENCODER.encodeToString(header.toString().getBytes(StandardCharsets.UTF_8)) + "."
                + ENCODER.encodeToString(payload.toString().getBytes(StandardCharsets.UTF_8));
        try {
            PrivateKey key = KeyFactory.getInstance("EC").generatePrivate(
                    new PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateKey)));
            Signature signature = Signature.getInstance("SHA384withECDSAinP1363Format");
            signature.initSign(key);
            signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signingInput + "." + ENCODER.encodeToString(signature.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot sign a participant token", e);
        }
    }

    /** A token's header and payload, or null when it is not a JWT. */
    static JsonNode[] parse(ObjectMapper mapper, String token) {
        String[] parts = token == null ? new String[0] : token.split("\\.");
        if (parts.length != 3) {
            return null;
        }
        try {
            return new JsonNode[] {mapper.readTree(Base64.getUrlDecoder().decode(parts[0])),
                mapper.readTree(Base64.getUrlDecoder().decode(parts[1]))};
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
    }

    /** Whether a token's ES384 signature verifies with a base64 X.509 public key. */
    static boolean verifies(String token, String publicKey) {
        String[] parts = token.split("\\.");
        try {
            PublicKey key = KeyFactory.getInstance("EC").generatePublic(
                    new X509EncodedKeySpec(Base64.getDecoder().decode(publicKey)));
            Signature signature = Signature.getInstance("SHA384withECDSAinP1363Format");
            signature.initVerify(key);
            signature.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            return signature.verify(Base64.getUrlDecoder().decode(parts[2]));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }
}

package io.github.hectorvent.floci.services.msk;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SASL/IAM credentials in both MSK mechanisms authenticate only when their SigV4 presign verifies
 * against the caller's secret key, names kafka-cluster:Connect, and is unexpired.
 */
class MskIamAuthenticatorTest {

    private static final String REGION = "us-east-1";
    private static final String BROKER = "boot-abc12345.kafka-serverless.us-east-1.localhost.floci.io";
    private static final Instant NOW = Instant.parse("2026-09-23T12:00:00Z");
    private static final String SESSION_TOKEN = "FwoGZXIvYXdzEBYaDH+session/token==";

    private final MskIamAuthenticator authenticator = new MskIamAuthenticator(
            (accessKeyId, sessionToken) -> MskIamSigning.ACCESS_KEY_ID.equals(accessKeyId)
                    ? Optional.of(MskIamSigning.SECRET_KEY)
                    : Optional.empty(),
            new ObjectMapper(),
            Clock.fixed(NOW, ZoneOffset.UTC));

    private static Map<String, String> presign(Instant signedAt, String action, String secret) {
        return MskIamSigning.presign(BROKER, REGION, signedAt, action, null, secret);
    }

    @Test
    void validAwsMskIamPayloadAuthenticates() {
        Map<String, String> presigned = presign(NOW.minusSeconds(5), "kafka-cluster:Connect", MskIamSigning.SECRET_KEY);
        assertEquals(Optional.of(MskIamSigning.ACCESS_KEY_ID),
                authenticator.authenticateIamPayload(MskIamSigning.iamPayload(BROKER, presigned)));
    }

    @Test
    void sessionCredentialsAreSignedWithTheirToken() {
        Map<String, String> presigned = MskIamSigning.presign(BROKER, REGION, NOW, "kafka-cluster:Connect",
                SESSION_TOKEN, MskIamSigning.SECRET_KEY);
        assertEquals(Optional.of(MskIamSigning.ACCESS_KEY_ID),
                authenticator.authenticateIamPayload(MskIamSigning.iamPayload(BROKER, presigned)));
        assertEquals(Optional.of(MskIamSigning.ACCESS_KEY_ID),
                authenticator.authenticateOAuthBearer(MskIamSigning.oauthBearerMessage(REGION,
                        MskIamSigning.presign("kafka." + REGION + ".amazonaws.com", REGION, NOW,
                                "kafka-cluster:Connect", SESSION_TOKEN, MskIamSigning.SECRET_KEY))));
    }

    @Test
    void payloadSignedForAnotherHostOrWithTheWrongSecretIsRejected() {
        Map<String, String> presigned = presign(NOW, "kafka-cluster:Connect", MskIamSigning.SECRET_KEY);
        assertTrue(authenticator.authenticateIamPayload(
                MskIamSigning.iamPayload("b-1.other.kafka.us-east-1.localhost.floci.io", presigned)).isEmpty());

        Map<String, String> wrongSecret = presign(NOW, "kafka-cluster:Connect", "not-the-secret");
        assertTrue(authenticator.authenticateIamPayload(MskIamSigning.iamPayload(BROKER, wrongSecret)).isEmpty());

        Map<String, String> tampered = new TreeMap<>(presigned);
        tampered.put("X-Amz-Expires", "3600");
        assertTrue(authenticator.authenticateIamPayload(MskIamSigning.iamPayload(BROKER, tampered)).isEmpty());
    }

    @Test
    void expiredOrOtherActionCredentialsAreRejected() {
        Map<String, String> expired = presign(NOW.minus(Duration.ofMinutes(16)), "kafka-cluster:Connect",
                MskIamSigning.SECRET_KEY);
        assertTrue(authenticator.authenticateIamPayload(MskIamSigning.iamPayload(BROKER, expired)).isEmpty());

        Map<String, String> otherAction = presign(NOW, "kafka-cluster:ReadData", MskIamSigning.SECRET_KEY);
        assertTrue(authenticator.authenticateIamPayload(MskIamSigning.iamPayload(BROKER, otherAction)).isEmpty());
    }

    @Test
    void unknownAccessKeyIsRejected() {
        MskIamAuthenticator noKeys = new MskIamAuthenticator((accessKeyId, sessionToken) -> Optional.empty(),
                new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));
        Map<String, String> presigned = presign(NOW, "kafka-cluster:Connect", MskIamSigning.SECRET_KEY);
        assertTrue(noKeys.authenticateIamPayload(MskIamSigning.iamPayload(BROKER, presigned)).isEmpty());
    }

    @Test
    void oauthBearerTokenAuthenticatesIgnoringTheUserAgentAddedAfterSigning() {
        Map<String, String> presigned = MskIamSigning.presign("kafka." + REGION + ".amazonaws.com", REGION, NOW,
                "kafka-cluster:Connect", null, MskIamSigning.SECRET_KEY);
        assertEquals(Optional.of(MskIamSigning.ACCESS_KEY_ID),
                authenticator.authenticateOAuthBearer(MskIamSigning.oauthBearerMessage(REGION, presigned)));

        Map<String, String> wrongSecret = MskIamSigning.presign("kafka." + REGION + ".amazonaws.com", REGION, NOW,
                "kafka-cluster:Connect", null, "not-the-secret");
        assertTrue(authenticator.authenticateOAuthBearer(MskIamSigning.oauthBearerMessage(REGION, wrongSecret))
                .isEmpty());
    }

    @Test
    void bearerTokenIsReadFromTheGs2Message() {
        assertEquals("abc.def", MskIamAuthenticator.bearerToken("n,,\u0001auth=Bearer abc.def\u0001\u0001"));
        assertEquals("tok", MskIamAuthenticator.bearerToken("n,a=user,\u0001host=x\u0001auth=Bearer tok\u0001\u0001"));
        assertNull(MskIamAuthenticator.bearerToken("n,,\u0001auth=Basic abc\u0001\u0001"));
        assertTrue(authenticator.authenticateOAuthBearer(new byte[] {1, 2, 3}).isEmpty());
        assertTrue(authenticator.authenticateIamPayload("not json".getBytes(StandardCharsets.UTF_8)).isEmpty());
    }
}

package io.github.hectorvent.floci.services.msk;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Kafka SASL exchange of the MSK SASL/IAM listener: ApiVersions is answered by the broker,
 * SaslHandshake offers the IAM mechanisms, and SaslAuthenticate admits only a verified credential.
 */
class KafkaSaslServerTest {

    private static final String REGION = "us-east-1";
    private static final String BROKER = "boot-abc12345.kafka-serverless.us-east-1.localhost.floci.io";
    private static final Instant NOW = Instant.parse("2026-09-23T12:00:00Z");
    private static final byte[] API_VERSIONS_RESPONSE = {0, 0, 0, 1, 0, 0};

    private final KafkaSaslServer server = new KafkaSaslServer(new MskIamAuthenticator(
            (accessKeyId, sessionToken) -> MskIamSigning.ACCESS_KEY_ID.equals(accessKeyId)
                    ? Optional.of(MskIamSigning.SECRET_KEY)
                    : Optional.empty(),
            new ObjectMapper(),
            Clock.fixed(NOW, ZoneOffset.UTC)));

    private final List<byte[]> forwarded = new ArrayList<>();

    private boolean run(byte[] clientBytes, ByteArrayOutputStream clientOut) throws IOException {
        return server.authenticate(new ByteArrayInputStream(clientBytes), clientOut, frame -> {
            forwarded.add(frame);
            return API_VERSIONS_RESPONSE;
        });
    }

    @Test
    void awsMskIamClientAuthenticatesAfterApiVersionsAndHandshake() throws Exception {
        byte[] payload = MskIamSigning.iamPayload(BROKER,
                MskIamSigning.presign(BROKER, REGION, NOW, "kafka-cluster:Connect", null, MskIamSigning.SECRET_KEY));
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        writeRequest(wire, KafkaSaslServer.API_API_VERSIONS, (short) 3, 1, new byte[] {0});
        writeRequest(wire, KafkaSaslServer.API_SASL_HANDSHAKE, (short) 1, 2, string("AWS_MSK_IAM"));
        writeRequest(wire, KafkaSaslServer.API_SASL_AUTHENTICATE, (short) 1, 3, bytes(payload));

        ByteArrayOutputStream clientOut = new ByteArrayOutputStream();
        assertTrue(run(wire.toByteArray(), clientOut));

        assertEquals(1, forwarded.size(), "only ApiVersions reaches the broker before authentication");
        DataInputStream responses = new DataInputStream(new ByteArrayInputStream(clientOut.toByteArray()));
        assertArrayEquals(API_VERSIONS_RESPONSE, KafkaSaslServer.readFrame(responses));

        ByteBuffer handshake = ByteBuffer.wrap(KafkaSaslServer.readFrame(responses));
        assertEquals(2, handshake.getInt());
        assertEquals(KafkaSaslServer.ERROR_NONE, handshake.getShort());
        assertEquals(2, handshake.getInt());

        ByteBuffer authenticate = ByteBuffer.wrap(KafkaSaslServer.readFrame(responses));
        assertEquals(3, authenticate.getInt());
        assertEquals(KafkaSaslServer.ERROR_NONE, authenticate.getShort());
        assertEquals(-1, authenticate.getShort(), "no error message");
        byte[] serverFinal = new byte[authenticate.getInt()];
        authenticate.get(serverFinal);
        assertTrue(new String(serverFinal, StandardCharsets.UTF_8).contains("\"version\":\"2020_10_22\""));
        assertEquals(0L, authenticate.getLong());
    }

    @Test
    void oauthBearerClientAuthenticatesWithFlexibleSaslAuthenticate() throws Exception {
        byte[] message = MskIamSigning.oauthBearerMessage(REGION, MskIamSigning.presign(
                "kafka." + REGION + ".amazonaws.com", REGION, NOW, "kafka-cluster:Connect", null,
                MskIamSigning.SECRET_KEY));
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        writeRequest(wire, KafkaSaslServer.API_SASL_HANDSHAKE, (short) 1, 7, string("OAUTHBEARER"));
        writeFlexibleAuthenticate(wire, 8, message);

        ByteArrayOutputStream clientOut = new ByteArrayOutputStream();
        assertTrue(run(wire.toByteArray(), clientOut));

        DataInputStream responses = new DataInputStream(new ByteArrayInputStream(clientOut.toByteArray()));
        KafkaSaslServer.readFrame(responses);
        ByteBuffer authenticate = ByteBuffer.wrap(KafkaSaslServer.readFrame(responses));
        assertEquals(8, authenticate.getInt());
        assertEquals(0, authenticate.get(), "empty response header tagged fields");
        assertEquals(KafkaSaslServer.ERROR_NONE, authenticate.getShort());
        assertEquals(0, authenticate.get(), "null error message");
        assertEquals(1, authenticate.get(), "empty server-final message");
    }

    @Test
    void badCredentialFailsAuthenticationAndClosesTheConnection() throws Exception {
        byte[] payload = MskIamSigning.iamPayload(BROKER,
                MskIamSigning.presign(BROKER, REGION, NOW, "kafka-cluster:Connect", null, "not-the-secret"));
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        writeRequest(wire, KafkaSaslServer.API_SASL_HANDSHAKE, (short) 1, 1, string("AWS_MSK_IAM"));
        writeRequest(wire, KafkaSaslServer.API_SASL_AUTHENTICATE, (short) 0, 2, bytes(payload));

        ByteArrayOutputStream clientOut = new ByteArrayOutputStream();
        assertFalse(run(wire.toByteArray(), clientOut));

        DataInputStream responses = new DataInputStream(new ByteArrayInputStream(clientOut.toByteArray()));
        KafkaSaslServer.readFrame(responses);
        ByteBuffer authenticate = ByteBuffer.wrap(KafkaSaslServer.readFrame(responses));
        assertEquals(2, authenticate.getInt());
        assertEquals(KafkaSaslServer.ERROR_SASL_AUTHENTICATION_FAILED, authenticate.getShort());
    }

    @Test
    void unsupportedMechanismIsRefused() throws Exception {
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        writeRequest(wire, KafkaSaslServer.API_SASL_HANDSHAKE, (short) 1, 4, string("SCRAM-SHA-512"));

        ByteArrayOutputStream clientOut = new ByteArrayOutputStream();
        assertFalse(run(wire.toByteArray(), clientOut));

        ByteBuffer handshake = ByteBuffer.wrap(KafkaSaslServer.readFrame(
                new DataInputStream(new ByteArrayInputStream(clientOut.toByteArray()))));
        assertEquals(4, handshake.getInt());
        assertEquals(KafkaSaslServer.ERROR_UNSUPPORTED_SASL_MECHANISM, handshake.getShort());
    }

    @Test
    void otherRequestsBeforeAuthenticationNeverReachTheBroker() throws Exception {
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        writeRequest(wire, (short) 3, (short) 12, 1, new byte[0]);

        assertFalse(run(wire.toByteArray(), new ByteArrayOutputStream()));
        assertTrue(forwarded.isEmpty());
    }

    /** A request with a v1 header (non-flexible) carrying client id "test-client". */
    private static void writeRequest(ByteArrayOutputStream wire, short apiKey, short apiVersion, int correlationId,
                                     byte[] body) throws IOException {
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(frame);
        out.writeShort(apiKey);
        out.writeShort(apiVersion);
        out.writeInt(correlationId);
        out.write(string("test-client"));
        out.write(body);
        KafkaSaslServer.writeFrame(wire, frame.toByteArray());
    }

    /** SaslAuthenticate v2: request header v2 and a compact-bytes body, each with empty tagged fields. */
    private static void writeFlexibleAuthenticate(ByteArrayOutputStream wire, int correlationId, byte[] authBytes)
            throws IOException {
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(frame);
        out.writeShort(KafkaSaslServer.API_SASL_AUTHENTICATE);
        out.writeShort(2);
        out.writeInt(correlationId);
        out.write(string("test-client"));
        KafkaSaslServer.writeUnsignedVarint(out, 0);
        KafkaSaslServer.writeUnsignedVarint(out, authBytes.length + 1);
        out.write(authBytes);
        KafkaSaslServer.writeUnsignedVarint(out, 0);
        KafkaSaslServer.writeFrame(wire, frame.toByteArray());
    }

    private static byte[] string(String value) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        out.writeShort(encoded.length);
        out.write(encoded);
        return bytes.toByteArray();
    }

    private static byte[] bytes(byte[] value) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(value.length);
        out.write(value);
        return bytes.toByteArray();
    }
}

package io.github.hectorvent.floci.services.msk;

import org.jboss.logging.Logger;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The server side of Kafka's SASL exchange for the MSK SASL/IAM listener, run on a new
 * connection before any other traffic reaches the broker.
 *
 * <p>As on a Kafka broker, only {@code ApiVersions} (relayed to the broker, which answers it),
 * {@code SaslHandshake} and {@code SaslAuthenticate} are accepted before authentication; any
 * other request, or a failed authentication, ends the connection. Once {@link #authenticate}
 * returns true the caller relays the connection to the broker byte for byte.
 */
final class KafkaSaslServer {

    private static final Logger LOG = Logger.getLogger(KafkaSaslServer.class);

    static final short API_SASL_HANDSHAKE = 17;
    static final short API_API_VERSIONS = 18;
    static final short API_SASL_AUTHENTICATE = 36;

    static final short ERROR_NONE = 0;
    static final short ERROR_UNSUPPORTED_SASL_MECHANISM = 33;
    static final short ERROR_ILLEGAL_SASL_STATE = 34;
    static final short ERROR_UNSUPPORTED_VERSION = 35;
    static final short ERROR_SASL_AUTHENTICATION_FAILED = 58;

    static final String MECHANISM_AWS_MSK_IAM = "AWS_MSK_IAM";
    static final String MECHANISM_OAUTHBEARER = "OAUTHBEARER";
    static final List<String> MECHANISMS = List.of(MECHANISM_AWS_MSK_IAM, MECHANISM_OAUTHBEARER);

    /** A request frame larger than this before authentication is not a SASL exchange. */
    private static final int MAX_UNAUTHENTICATED_FRAME = 1024 * 1024;

    /** Sends one request frame (without its length prefix) to the broker and returns its response frame. */
    @FunctionalInterface
    interface Broker {
        byte[] exchange(byte[] requestFrame) throws IOException;
    }

    private final MskIamAuthenticator authenticator;

    KafkaSaslServer(MskIamAuthenticator authenticator) {
        this.authenticator = authenticator;
    }

    /**
     * Runs the pre-authentication exchange on a client connection.
     *
     * @return true once the client authenticated; false when the connection must be closed
     */
    boolean authenticate(InputStream clientIn, OutputStream clientOut, Broker broker) throws IOException {
        DataInputStream in = new DataInputStream(clientIn);
        String mechanism = null;
        while (true) {
            byte[] frame = readFrame(in);
            if (frame == null) {
                return false;
            }
            if (frame.length < 8) {
                return false;
            }
            ByteBuffer request = ByteBuffer.wrap(frame);
            short apiKey = request.getShort();
            short apiVersion = request.getShort();
            int correlationId = request.getInt();

            if (apiKey == API_API_VERSIONS) {
                writeFrame(clientOut, broker.exchange(frame));
                continue;
            }
            if (apiKey == API_SASL_HANDSHAKE) {
                if (apiVersion < 0 || apiVersion > 1) {
                    writeFrame(clientOut, handshakeResponse(correlationId, ERROR_UNSUPPORTED_VERSION));
                    return false;
                }
                skipClientId(request);
                String requested = readString(request);
                if (!MECHANISMS.contains(requested)) {
                    writeFrame(clientOut, handshakeResponse(correlationId, ERROR_UNSUPPORTED_SASL_MECHANISM));
                    return false;
                }
                writeFrame(clientOut, handshakeResponse(correlationId, ERROR_NONE));
                mechanism = requested;
                if (apiVersion == 0) {
                    // v0: the SASL tokens follow as bare length-prefixed frames, not Kafka requests.
                    byte[] token = readFrame(in);
                    if (token == null) {
                        return false;
                    }
                    Optional<byte[]> challenge = verify(mechanism, token);
                    if (challenge.isEmpty()) {
                        return false;
                    }
                    writeFrame(clientOut, challenge.get());
                    return true;
                }
                continue;
            }
            if (apiKey == API_SASL_AUTHENTICATE) {
                if (apiVersion < 0 || apiVersion > 2) {
                    writeFrame(clientOut, authenticateResponse(correlationId, apiVersion,
                            ERROR_UNSUPPORTED_VERSION, "Unsupported SaslAuthenticate version", new byte[0]));
                    return false;
                }
                if (mechanism == null) {
                    writeFrame(clientOut, authenticateResponse(correlationId, apiVersion, ERROR_ILLEGAL_SASL_STATE,
                            "SaslAuthenticate received before SaslHandshake", new byte[0]));
                    return false;
                }
                boolean flexible = apiVersion >= 2;
                skipClientId(request);
                if (flexible) {
                    skipTaggedFields(request);
                }
                byte[] authBytes = flexible ? readCompactBytes(request) : readBytes(request);
                Optional<byte[]> challenge = verify(mechanism, authBytes);
                if (challenge.isEmpty()) {
                    writeFrame(clientOut, authenticateResponse(correlationId, apiVersion,
                            ERROR_SASL_AUTHENTICATION_FAILED,
                            "Access denied", new byte[0]));
                    return false;
                }
                writeFrame(clientOut, authenticateResponse(correlationId, apiVersion, ERROR_NONE, null,
                        challenge.get()));
                return true;
            }
            LOG.debugv("Closing MSK SASL/IAM connection: API key {0} sent before authentication", apiKey);
            return false;
        }
    }

    /** The server's final SASL message on success, empty when the credential does not authenticate. */
    private Optional<byte[]> verify(String mechanism, byte[] token) {
        if (MECHANISM_AWS_MSK_IAM.equals(mechanism)) {
            return authenticator.authenticateIamPayload(token).map(principal ->
                    ("{\"version\":\"" + MskIamAuthenticator.IAM_PAYLOAD_VERSION + "\",\"request-id\":\""
                            + UUID.randomUUID() + "\"}").getBytes(StandardCharsets.UTF_8));
        }
        return authenticator.authenticateOAuthBearer(token).map(principal -> new byte[0]);
    }

    static byte[] handshakeResponse(int correlationId, short errorCode) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(correlationId);
        out.writeShort(errorCode);
        out.writeInt(MECHANISMS.size());
        for (String mechanism : MECHANISMS) {
            byte[] name = mechanism.getBytes(StandardCharsets.UTF_8);
            out.writeShort(name.length);
            out.write(name);
        }
        return bytes.toByteArray();
    }

    static byte[] authenticateResponse(int correlationId, short apiVersion, short errorCode, String errorMessage,
                                       byte[] authBytes) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(correlationId);
        if (apiVersion >= 2) {
            // Response header v1 carries (empty) tagged fields; the body uses compact encodings.
            writeUnsignedVarint(out, 0);
            out.writeShort(errorCode);
            if (errorMessage == null) {
                writeUnsignedVarint(out, 0);
            } else {
                byte[] message = errorMessage.getBytes(StandardCharsets.UTF_8);
                writeUnsignedVarint(out, message.length + 1);
                out.write(message);
            }
            writeUnsignedVarint(out, authBytes.length + 1);
            out.write(authBytes);
            out.writeLong(0L);
            writeUnsignedVarint(out, 0);
            return bytes.toByteArray();
        }
        out.writeShort(errorCode);
        if (errorMessage == null) {
            out.writeShort(-1);
        } else {
            byte[] message = errorMessage.getBytes(StandardCharsets.UTF_8);
            out.writeShort(message.length);
            out.write(message);
        }
        out.writeInt(authBytes.length);
        out.write(authBytes);
        if (apiVersion >= 1) {
            // No re-authentication is required of the session.
            out.writeLong(0L);
        }
        return bytes.toByteArray();
    }

    /** Reads one length-prefixed frame; null at a clean end of stream. */
    static byte[] readFrame(DataInputStream in) throws IOException {
        int size;
        try {
            size = in.readInt();
        } catch (EOFException e) {
            return null;
        }
        if (size < 0 || size > MAX_UNAUTHENTICATED_FRAME) {
            throw new IOException("Invalid Kafka frame size " + size);
        }
        byte[] frame = new byte[size];
        in.readFully(frame);
        return frame;
    }

    static void writeFrame(OutputStream out, byte[] frame) throws IOException {
        DataOutputStream data = new DataOutputStream(out);
        data.writeInt(frame.length);
        data.write(frame);
        data.flush();
    }

    /** Skips the request header's nullable {@code client_id} string. */
    private static void skipClientId(ByteBuffer request) {
        short length = request.getShort();
        if (length > 0) {
            request.position(request.position() + length);
        }
    }

    private static String readString(ByteBuffer request) {
        short length = request.getShort();
        if (length < 0) {
            return null;
        }
        byte[] value = new byte[length];
        request.get(value);
        return new String(value, StandardCharsets.UTF_8);
    }

    private static byte[] readBytes(ByteBuffer request) {
        int length = request.getInt();
        byte[] value = new byte[Math.max(length, 0)];
        request.get(value);
        return value;
    }

    private static byte[] readCompactBytes(ByteBuffer request) {
        int length = readUnsignedVarint(request) - 1;
        byte[] value = new byte[Math.max(length, 0)];
        request.get(value);
        return value;
    }

    private static void skipTaggedFields(ByteBuffer request) {
        int count = readUnsignedVarint(request);
        for (int i = 0; i < count; i++) {
            readUnsignedVarint(request);
            int size = readUnsignedVarint(request);
            request.position(request.position() + size);
        }
    }

    static int readUnsignedVarint(ByteBuffer buffer) {
        int value = 0;
        int shift = 0;
        while (true) {
            byte b = buffer.get();
            value |= (b & 0x7f) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
            shift += 7;
            if (shift > 28) {
                throw new IllegalArgumentException("Varint is too long");
            }
        }
    }

    static void writeUnsignedVarint(DataOutputStream out, int value) throws IOException {
        int remaining = value;
        while ((remaining & ~0x7f) != 0) {
            out.writeByte((remaining & 0x7f) | 0x80);
            remaining >>>= 7;
        }
        out.writeByte(remaining);
    }
}

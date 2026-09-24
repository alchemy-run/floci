package io.github.hectorvent.floci.services.msk;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.FlociCertificateAuthority;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The SASL/IAM listener end to end over real sockets: TLS with a certificate for the broker
 * hostname the client asked for, the SASL exchange, then a byte relay to the broker listener.
 */
class MskIamGatewayTest {

    private static final String REGION = "us-east-1";
    private static final String BROKER = "boot-abc12345.kafka-serverless.us-east-1.localhost.floci.io";
    private static final byte[] API_VERSIONS_RESPONSE = {0, 0, 0, 1, 0, 0};

    @TempDir
    Path tlsDir;

    private MskIamGateway gateway;
    private ServerSocket broker;

    @AfterEach
    void tearDown() throws IOException {
        if (gateway != null) {
            gateway.stop();
        }
        if (broker != null) {
            broker.close();
        }
    }

    @Test
    void authenticatedClientIsRelayedToTheBrokerOfItsServerName() throws Exception {
        FlociCertificateAuthority ca = FlociCertificateAuthority.loadOrCreate(tlsDir);
        startBroker();
        startGateway(ca);

        try (SSLSocket client = connect(ca, BROKER)) {
            X509Certificate leaf = (X509Certificate) client.getSession().getPeerCertificates()[0];
            assertTrue(leaf.getSubjectX500Principal().getName().contains(BROKER));

            DataInputStream in = new DataInputStream(client.getInputStream());
            OutputStream out = client.getOutputStream();

            KafkaSaslServer.writeFrame(out, request(KafkaSaslServer.API_API_VERSIONS, (short) 0, 1, new byte[0]));
            assertArrayEquals(API_VERSIONS_RESPONSE, KafkaSaslServer.readFrame(in));

            KafkaSaslServer.writeFrame(out, request(KafkaSaslServer.API_SASL_HANDSHAKE, (short) 1, 2,
                    string("OAUTHBEARER")));
            ByteBuffer handshake = ByteBuffer.wrap(KafkaSaslServer.readFrame(in));
            assertEquals(2, handshake.getInt());
            assertEquals(KafkaSaslServer.ERROR_NONE, handshake.getShort());

            byte[] token = MskIamSigning.oauthBearerMessage(REGION, MskIamSigning.presign(
                    "kafka." + REGION + ".amazonaws.com", REGION, Instant.now(), "kafka-cluster:Connect", null,
                    MskIamSigning.SECRET_KEY));
            KafkaSaslServer.writeFrame(out, request(KafkaSaslServer.API_SASL_AUTHENTICATE, (short) 1, 3,
                    bytes(token)));
            ByteBuffer authenticate = ByteBuffer.wrap(KafkaSaslServer.readFrame(in));
            assertEquals(3, authenticate.getInt());
            assertEquals(KafkaSaslServer.ERROR_NONE, authenticate.getShort());

            byte[] payload = "relayed-bytes".getBytes(StandardCharsets.UTF_8);
            out.write(payload);
            out.flush();
            byte[] echoed = new byte[payload.length];
            in.readFully(echoed);
            assertArrayEquals(payload, echoed);
        }
    }

    @Test
    void unknownServerNameGetsNoCertificate() throws Exception {
        FlociCertificateAuthority ca = FlociCertificateAuthority.loadOrCreate(tlsDir);
        startBroker();
        startGateway(ca);

        assertThrows(IOException.class, () -> {
            try (SSLSocket client = connect(ca, "boot-unknown.kafka-serverless.us-east-1.localhost.floci.io")) {
                client.getInputStream().read();
            }
        });
    }

    private void startGateway(FlociCertificateAuthority ca) {
        MskIamAuthenticator authenticator = new MskIamAuthenticator(
                (accessKeyId, sessionToken) -> MskIamSigning.ACCESS_KEY_ID.equals(accessKeyId)
                        ? Optional.of(MskIamSigning.SECRET_KEY)
                        : Optional.empty(),
                new ObjectMapper(), Clock.systemUTC());
        gateway = new MskIamGateway(ca, authenticator, 0);
        int brokerPort = broker.getLocalPort();
        assertTrue(gateway.ensureStarted(host -> BROKER.equals(host)
                ? Optional.of("127.0.0.1:" + brokerPort)
                : Optional.empty()));
    }

    /** A broker that answers one ApiVersions request, then echoes everything it receives. */
    private void startBroker() throws IOException {
        broker = new ServerSocket(0);
        Thread.ofPlatform().daemon(true).start(() -> {
            try (Socket connection = broker.accept()) {
                DataInputStream in = new DataInputStream(connection.getInputStream());
                OutputStream out = connection.getOutputStream();
                KafkaSaslServer.readFrame(in);
                KafkaSaslServer.writeFrame(out, API_VERSIONS_RESPONSE);
                InputStream raw = connection.getInputStream();
                byte[] buffer = new byte[1024];
                int read;
                while ((read = raw.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    out.flush();
                }
            } catch (IOException ignored) {
                // The test closed the connection.
            }
        });
    }

    private SSLSocket connect(FlociCertificateAuthority ca, String serverName) throws Exception {
        KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
        trust.load(null, null);
        trust.setCertificateEntry("floci-ca", ca.certificate());
        TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagers.init(trust);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trustManagers.getTrustManagers(), null);
        SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket("127.0.0.1", gateway.localPort());
        socket.setSoTimeout(10_000);
        SSLParameters parameters = socket.getSSLParameters();
        parameters.setServerNames(List.of(new SNIHostName(serverName)));
        socket.setSSLParameters(parameters);
        socket.startHandshake();
        return socket;
    }

    private static byte[] request(short apiKey, short apiVersion, int correlationId, byte[] body) throws IOException {
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(frame);
        out.writeShort(apiKey);
        out.writeShort(apiVersion);
        out.writeInt(correlationId);
        out.write(string("gateway-test"));
        out.write(body);
        return frame.toByteArray();
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

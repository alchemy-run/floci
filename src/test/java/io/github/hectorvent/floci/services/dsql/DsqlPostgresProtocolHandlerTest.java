package io.github.hectorvent.floci.services.dsql;

import io.github.hectorvent.floci.services.dsql.proxy.DsqlPostgresProtocolHandler;
import io.github.hectorvent.floci.services.dsql.proxy.DsqlSigV4Validator;
import io.github.hectorvent.floci.testutil.IamServiceTestHelper;
import io.github.hectorvent.floci.testutil.SigV4TokenTestHelper;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Wire-level coverage for Alchemy {@code DSQL.Connect}: TLS negotiation, then
 * an IAM {@code DbConnectAdmin} token as the Postgres password.
 */
class DsqlPostgresProtocolHandlerTest {

    private static final int SSL_REQUEST_CODE = 80877103;
    private static final int STARTUP_PROTOCOL_VERSION = 196608;
    private static final String HOST = "ac0a36b1677f42a68a3b84dde8.dsql.us-east-1.on.aws";
    private static final String ACCESS_KEY = "ASIAEXAMPLEKEY0001";
    private static final String SECRET = "session-secret-value";
    private static final String SESSION = "FwoGZXIvYXdzEJr//////////wEaDN+example/session+token";

    @Test
    void acceptsSslThenIamDbConnectAdminToken() throws Exception {
        AtomicReference<String> backendDatabase = new AtomicReference<>();
        AtomicReference<String> backendUser = new AtomicReference<>();
        DsqlSigV4Validator validator = new DsqlSigV4Validator(
                IamServiceTestHelper.iamServiceWithSessionCredential(ACCESS_KEY, SECRET));
        String token = SigV4TokenTestHelper.createDsqlToken(
                HOST, "DbConnectAdmin", ACCESS_KEY, SECRET, Instant.now(), 900, SESSION);

        try (ServerSocket backendServer = new ServerSocket(0);
             ServerSocket clientServer = new ServerSocket(0)) {

            Thread backendThread = Thread.ofVirtual().start(() -> {
                try {
                    mockBackendStartup(backendServer, backendUser, backendDatabase);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });

            try (Socket ourClient = new Socket("localhost", clientServer.getLocalPort())) {
                ourClient.setSoTimeout(5_000);
                Socket proxyClient = clientServer.accept();
                Socket backend = new Socket("localhost", backendServer.getLocalPort());

                Thread authThread = Thread.ofVirtual().start(() -> {
                    try {
                        DsqlPostgresProtocolHandler.handleAuth(
                                proxyClient, backend,
                                "admin", "container-master-pass", "postgres",
                                true, validator,
                                (user, pass) -> false);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });

                DataOutputStream clientOut = new DataOutputStream(ourClient.getOutputStream());
                DataInputStream clientIn = new DataInputStream(ourClient.getInputStream());

                writeSslRequest(clientOut);
                assertEquals('S', clientIn.readUnsignedByte());

                SSLSocket sslClient = trustedClientSocket(ourClient);
                sslClient.startHandshake();
                clientOut = new DataOutputStream(sslClient.getOutputStream());
                clientIn = new DataInputStream(sslClient.getInputStream());

                writeStartup(clientOut, "admin", "postgres");
                readCleartextPasswordChallenge(clientIn);
                writePassword(clientOut, token);
                readAuthenticationOk(clientIn);
                readReadyForQuery(clientIn);

                sslClient.close();
                proxyClient.close();
                authThread.join(5_000);
                backendThread.join(5_000);
                assertFalse(authThread.isAlive(), "authThread did not terminate");
                assertFalse(backendThread.isAlive(), "backendThread did not terminate");
            }

            assertEquals("postgres", backendDatabase.get());
            assertEquals("admin", backendUser.get());
        }
    }

    private static void mockBackendStartup(ServerSocket server, AtomicReference<String> backendUser,
                                           AtomicReference<String> backendDatabase) throws IOException {
        try (Socket socket = server.accept()) {
            DataInputStream in = new DataInputStream(socket.getInputStream());
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());

            int length = in.readInt();
            int proto = in.readInt();
            assertEquals(STARTUP_PROTOCOL_VERSION, proto);
            byte[] payload = in.readNBytes(length - 8);
            Map<String, String> params = parseStartupParams(payload);
            backendUser.set(params.get("user"));
            backendDatabase.set(params.get("database"));

            out.writeByte('R');
            out.writeInt(8);
            out.writeInt(3);
            out.flush();

            assertEquals('p', in.readByte());
            int pwLength = in.readInt();
            in.readNBytes(pwLength - 4);

            out.writeByte('R');
            out.writeInt(8);
            out.writeInt(0);
            out.writeByte('Z');
            out.writeInt(5);
            out.writeByte('I');
            out.flush();
        }
    }

    private static void writeStartup(DataOutputStream out, String user, String database) throws IOException {
        byte[] userKey = "user".getBytes(StandardCharsets.UTF_8);
        byte[] userVal = user.getBytes(StandardCharsets.UTF_8);
        byte[] dbKey = "database".getBytes(StandardCharsets.UTF_8);
        byte[] dbVal = database.getBytes(StandardCharsets.UTF_8);

        int length = 4 + 4
                + userKey.length + 1 + userVal.length + 1
                + dbKey.length + 1 + dbVal.length + 1
                + 1;

        out.writeInt(length);
        out.writeInt(STARTUP_PROTOCOL_VERSION);
        out.write(userKey);
        out.writeByte(0);
        out.write(userVal);
        out.writeByte(0);
        out.write(dbKey);
        out.writeByte(0);
        out.write(dbVal);
        out.writeByte(0);
        out.writeByte(0);
        out.flush();
    }

    private static void writeSslRequest(DataOutputStream out) throws IOException {
        out.writeInt(8);
        out.writeInt(SSL_REQUEST_CODE);
        out.flush();
    }

    private static SSLSocket trustedClientSocket(Socket socket) throws Exception {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[] {TrustAll.INSTANCE}, null);
        SSLSocket sslSocket = (SSLSocket) context.getSocketFactory()
                .createSocket(socket, socket.getInetAddress().getHostAddress(), socket.getPort(), true);
        sslSocket.setUseClientMode(true);
        return sslSocket;
    }

    private static final class TrustAll implements X509TrustManager {
        static final TrustAll INSTANCE = new TrustAll();

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {}

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {}

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }

    private static void writePassword(DataOutputStream out, String password) throws IOException {
        byte[] pw = password.getBytes(StandardCharsets.UTF_8);
        out.writeByte('p');
        out.writeInt(4 + pw.length + 1);
        out.write(pw);
        out.writeByte(0);
        out.flush();
    }

    private static void readCleartextPasswordChallenge(DataInputStream in) throws IOException {
        assertEquals('R', in.readByte());
        assertEquals(8, in.readInt());
        assertEquals(3, in.readInt());
    }

    private static void readAuthenticationOk(DataInputStream in) throws IOException {
        assertEquals('R', in.readByte());
        assertEquals(8, in.readInt());
        assertEquals(0, in.readInt());
    }

    private static void readReadyForQuery(DataInputStream in) throws IOException {
        assertEquals('Z', in.readByte());
        assertEquals(5, in.readInt());
        assertEquals('I', in.readByte());
    }

    private static Map<String, String> parseStartupParams(byte[] data) {
        Map<String, String> params = new HashMap<>();
        int i = 0;
        while (i < data.length) {
            int keyStart = i;
            while (i < data.length && data[i] != 0) {
                i++;
            }
            if (i >= data.length) {
                break;
            }
            String key = new String(data, keyStart, i - keyStart, StandardCharsets.UTF_8);
            i++;
            if (key.isEmpty()) {
                break;
            }
            int valStart = i;
            while (i < data.length && data[i] != 0) {
                i++;
            }
            String value = new String(data, valStart, i - valStart, StandardCharsets.UTF_8);
            i++;
            params.put(key, value);
        }
        return params;
    }
}

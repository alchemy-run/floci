package io.github.hectorvent.floci.services.docdb.proxy;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.services.rds.proxy.RdsProxyTlsCertificates;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static io.github.hectorvent.floci.services.docdb.proxy.MongoAuthDatabaseRewriterTest.littleEndianInt;
import static io.github.hectorvent.floci.services.docdb.proxy.MongoAuthDatabaseRewriterTest.opMsg;
import static io.github.hectorvent.floci.services.docdb.proxy.MongoAuthDatabaseRewriterTest.opMsgBody;
import static io.github.hectorvent.floci.services.docdb.proxy.MongoAuthDatabaseRewriterTest.topLevel;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DocDbProxyTest {

    private static final String DOCS = "docs.cluster-abcdefghijkl.us-east-1.docdb.localhost.floci.io";
    private static final String MORE = "more.cluster-abcdefghijkl.us-east-1.docdb.localhost.floci.io";

    @TempDir
    Path tempDir;

    @Test
    void serverNameIsReadFromTheClientHello() {
        assertEquals(DOCS, DocDbProxy.serverName(clientHello(DOCS)));
        assertNull(DocDbProxy.serverName(clientHello(null)));
        assertNull(DocDbProxy.serverName(new byte[] {0x16, 0x03, 0x01, 0x00, 0x01, 0x02}));
    }

    @Test
    void connectionsAreRoutedByServerNameAndOnlyInTheModeTheClusterSpeaks() {
        DocDbProxy listener = new DocDbProxy(0, () -> null);
        listener.putRoute(new DocDbProxy.Route("docs", true, Set.of(DOCS), "127.0.0.1", 1));
        listener.putRoute(new DocDbProxy.Route("more", true, Set.of(MORE), "127.0.0.1", 2));

        assertEquals("docs", listener.select(true, DOCS.toUpperCase()).clusterKey());
        assertEquals("more", listener.select(true, MORE).clusterKey());
        assertNull(listener.select(true, null), "two clusters on the port and no name to choose by");
        assertNull(listener.select(false, null), "a TLS-enabled cluster refuses plaintext");

        DocDbProxy alone = new DocDbProxy(0, () -> null);
        alone.putRoute(new DocDbProxy.Route("docs", true, Set.of(DOCS), "127.0.0.1", 1));
        assertEquals("docs", alone.select(true, "127.0.0.1").clusterKey(), "one cluster answers any name");
        alone.putRoute(new DocDbProxy.Route("docs", true, Set.of(DOCS), null, 0));
        assertNull(alone.select(true, DOCS), "a cluster whose container is not up yet");

        DocDbProxy plain = new DocDbProxy(0, () -> null);
        plain.putRoute(new DocDbProxy.Route("plain", false, Set.of(DOCS), "127.0.0.1", 1));
        assertEquals("plain", plain.select(false, null).clusterKey());
        assertNull(plain.select(true, DOCS), "a cluster with TLS disabled does not speak TLS");
        assertFalse(plain.canShareWith(true));
        assertTrue(listener.canShareWith(true));
        assertFalse(listener.canShareWith(false));
    }

    @Test
    void tlsClustersShareThePortAndEachReceivesItsOwnConnectionsWithAuthenticationSentToAdmin()
            throws Exception {
        RdsProxyTlsCertificates certificates = certificates();
        DocDbProxyManager manager = new DocDbProxyManager(certificates);
        try (EchoBackend docs = new EchoBackend(); EchoBackend more = new EchoBackend()) {
            int port = freePort();
            assertEquals(port, manager.reserve("docs", port, true));
            assertEquals(port, manager.reserve("more", port, true));
            manager.attach("docs", List.of(DOCS), "127.0.0.1", docs.port());
            manager.attach("more", List.of(MORE), "127.0.0.1", more.port());

            byte[] saslStart = opMsg(1, new MongoAuthDatabaseRewriterTest.Bson().int32("saslStart", 1)
                    .string("$db", "alchemy_test").build());
            try (SSLSocket client = tlsClient(port, MORE)) {
                client.startHandshake();
                X509Certificate served = (X509Certificate) client.getSession().getPeerCertificates()[0];
                assertTrue(sanNames(served).contains(MORE), "the served certificate names the endpoint");

                client.getOutputStream().write(saslStart);
                client.getOutputStream().flush();
                byte[] echoedHeader = client.getInputStream().readNBytes(4);
                int length = littleEndianInt(echoedHeader, 0);
                byte[] echoed = MongoAuthDatabaseRewriterTest.concat(echoedHeader,
                        client.getInputStream().readNBytes(length - 4));
                assertEquals("admin", topLevel(opMsgBody(echoed)).get("$db"),
                        "DocumentDB users authenticate against admin whatever database the client names");
            }
            assertEquals(1, more.connections());
            assertEquals(0, docs.connections());
        } finally {
            manager.stopAll();
        }
    }

    @Test
    void aTlsEnabledClusterDisconnectsAPlaintextClient() throws IOException {
        DocDbProxyManager manager = new DocDbProxyManager(certificates());
        try (EchoBackend docs = new EchoBackend()) {
            int port = freePort();
            manager.reserve("docs", port, true);
            manager.attach("docs", List.of(DOCS), "127.0.0.1", docs.port());

            try (Socket client = new Socket("127.0.0.1", port)) {
                client.setSoTimeout(5_000);
                client.getOutputStream().write(opMsg(1, new MongoAuthDatabaseRewriterTest.Bson()
                        .int32("hello", 1).string("$db", "admin").build()));
                client.getOutputStream().flush();
                assertEquals(-1, readOrEndOfStream(client.getInputStream()));
            }
            assertEquals(0, docs.connections());
        } finally {
            manager.stopAll();
        }
    }

    @Test
    void aClusterWithTlsDisabledHasAPortOfItsOwnAndSpeaksPlaintext() throws IOException {
        DocDbProxyManager manager = new DocDbProxyManager(certificates());
        try (EchoBackend plain = new EchoBackend()) {
            int port = freePort();
            assertEquals(port, manager.reserve("docs", port, true));
            int plainPort = manager.reserve("plain", port, false);
            assertNotEquals(port, plainPort);
            assertEquals(plainPort, manager.portOf("plain"));
            manager.attach("plain", List.of(DOCS), "127.0.0.1", plain.port());

            byte[] saslContinue = opMsg(1, new MongoAuthDatabaseRewriterTest.Bson().int32("saslContinue", 1)
                    .string("$db", "alchemy_test").build());
            try (Socket client = new Socket("127.0.0.1", plainPort)) {
                client.setSoTimeout(5_000);
                client.getOutputStream().write(saslContinue);
                client.getOutputStream().flush();
                byte[] header = client.getInputStream().readNBytes(4);
                byte[] echoed = MongoAuthDatabaseRewriterTest.concat(header,
                        client.getInputStream().readNBytes(littleEndianInt(header, 0) - 4));
                assertEquals("admin", topLevel(opMsgBody(echoed)).get("$db"));
            }
        } finally {
            manager.stopAll();
        }
    }

    @Test
    void anInstanceEndpointReachesItsCluster() throws Exception {
        DocDbProxyManager manager = new DocDbProxyManager(certificates());
        try (EchoBackend docs = new EchoBackend(); EchoBackend more = new EchoBackend()) {
            int port = freePort();
            manager.reserve("docs", port, true);
            manager.reserve("more", port, true);
            manager.attach("docs", List.of(DOCS), "127.0.0.1", docs.port());
            manager.attach("more", List.of(MORE), "127.0.0.1", more.port());
            String instance = "writer.abcdefghijkl.us-east-1.docdb.localhost.floci.io";
            manager.addHostname("docs", instance);

            try (SSLSocket client = tlsClient(port, instance)) {
                client.startHandshake();
                client.getOutputStream().write(opMsg(1, new MongoAuthDatabaseRewriterTest.Bson()
                        .int32("hello", 1).string("$db", "admin").build()));
                client.getOutputStream().flush();
                assertEquals(4, client.getInputStream().readNBytes(4).length);
            }
            assertEquals(1, docs.connections());
            assertEquals(0, more.connections());
        } finally {
            manager.stopAll();
        }
    }

    @Test
    void releasingTheLastClusterClosesTheListener() throws IOException {
        DocDbProxyManager manager = new DocDbProxyManager(certificates());
        int port = freePort();
        manager.reserve("docs", port, true);
        manager.reserve("more", port, true);

        manager.release("docs");
        assertThrows(IOException.class, () -> new ServerSocket(port).close(), "still serving 'more'");
        manager.release("more");
        new ServerSocket(port).close();
        assertNull(manager.portOf("more"));
    }

    private RdsProxyTlsCertificates certificates() {
        EmulatorConfig.StorageConfig storage = mock(EmulatorConfig.StorageConfig.class);
        when(storage.persistentPath()).thenReturn(tempDir.toString());
        EmulatorConfig config = mock(EmulatorConfig.class);
        when(config.storage()).thenReturn(storage);
        return new RdsProxyTlsCertificates(config, new CertificateGenerator());
    }

    /** A TLS client that trusts Floci's RDS CA and names {@code serverName} in its ClientHello. */
    private SSLSocket tlsClient(int port, String serverName) throws Exception {
        X509Certificate ca;
        try (InputStream pem = Files.newInputStream(tempDir.resolve("tls").resolve("rds-ca.crt"))) {
            ca = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(pem);
        }
        KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
        trust.load(null, null);
        trust.setCertificateEntry("floci-rds-ca", ca);
        TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagers.init(trust);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trustManagers.getTrustManagers(), null);

        SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket("127.0.0.1", port);
        socket.setSoTimeout(5_000);
        SSLParameters parameters = socket.getSSLParameters();
        parameters.setServerNames(List.of(new SNIHostName(serverName)));
        socket.setSSLParameters(parameters);
        return socket;
    }

    private static Set<String> sanNames(X509Certificate certificate) throws Exception {
        Collection<List<?>> names = certificate.getSubjectAlternativeNames();
        return names == null ? Set.of() : names.stream()
                .map(entry -> String.valueOf(entry.get(1)))
                .collect(Collectors.toSet());
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static int readOrEndOfStream(InputStream in) throws IOException {
        try {
            return in.read();
        } catch (SocketTimeoutException e) {
            return -2;
        } catch (IOException e) {
            // A reset connection is a disconnect too.
            return -1;
        }
    }

    /** A TLS record carrying a ClientHello, with a server_name extension when a name is given. */
    static byte[] clientHello(String serverName) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(3);
        body.write(3);
        body.writeBytes(new byte[32]);
        body.write(0);
        u16(body, 2);
        body.write(0x13);
        body.write(0x01);
        body.write(1);
        body.write(0);
        ByteArrayOutputStream extensions = new ByteArrayOutputStream();
        u16(extensions, 0x002b);
        u16(extensions, 3);
        extensions.writeBytes(new byte[] {2, 3, 4});
        if (serverName != null) {
            byte[] name = serverName.getBytes(StandardCharsets.US_ASCII);
            u16(extensions, 0x0000);
            u16(extensions, name.length + 5);
            u16(extensions, name.length + 3);
            extensions.write(0);
            u16(extensions, name.length);
            extensions.writeBytes(name);
        }
        byte[] extensionBytes = extensions.toByteArray();
        u16(body, extensionBytes.length);
        body.writeBytes(extensionBytes);
        byte[] hello = body.toByteArray();

        ByteArrayOutputStream record = new ByteArrayOutputStream();
        record.write(0x16);
        record.write(3);
        record.write(1);
        u16(record, hello.length + 4);
        record.write(0x01);
        record.write((hello.length >> 16) & 0xFF);
        u16(record, hello.length & 0xFFFF);
        record.writeBytes(hello);
        return record.toByteArray();
    }

    private static void u16(ByteArrayOutputStream out, int value) {
        out.write((value >> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    /** A backend that echoes whatever it receives and counts the connections it accepted. */
    private static final class EchoBackend implements AutoCloseable {
        private final ServerSocket serverSocket = new ServerSocket(0);
        private final AtomicInteger connections = new AtomicInteger();

        EchoBackend() throws IOException {
            Thread acceptor = new Thread(() -> {
                while (!serverSocket.isClosed()) {
                    try {
                        Socket socket = serverSocket.accept();
                        connections.incrementAndGet();
                        Thread.ofVirtual().start(() -> echo(socket));
                    } catch (IOException expected) {
                        // The test closes the server socket when it is done with it.
                    }
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();
        }

        private static void echo(Socket socket) {
            try (socket) {
                InputStream in = socket.getInputStream();
                OutputStream out = socket.getOutputStream();
                byte[] buffer = new byte[1024];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    out.write(buffer, 0, n);
                    out.flush();
                }
            } catch (IOException expected) {
                // The proxy closes the connection when the client does.
            }
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        int connections() {
            return connections.get();
        }

        @Override
        public void close() throws IOException {
            serverSocket.close();
        }
    }
}

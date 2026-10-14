package io.github.hectorvent.floci.services.rds.proxy;

import io.github.hectorvent.floci.services.rds.model.DatabaseEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Many DB instances listen on one configured port (AWS gives each its own DNS name); the router
 * must hand every connection to the resource its host name selects, and refuse rather than guess
 * when nothing does.
 */
class RdsEndpointRouterTest {

    private final RdsEndpointRouter router = new RdsEndpointRouter(2000, 2000, 10);
    private final List<ServerSocket> backends = new java.util.ArrayList<>();

    @AfterEach
    void tearDown() throws IOException {
        router.closeAll();
        for (ServerSocket backend : backends) {
            backend.close();
        }
    }

    @Test
    void aPortWithOneResourceRelaysTheConnectionUntouched() throws Exception {
        ServerSocket backend = backend();
        CompletableFuture<byte[]> received = acceptAndRead(backend, 5, false);
        int port = freePort();
        assertTrue(router.register("db-a", port, List.of("a.rds.localhost.floci.io"),
                backend.getLocalPort(), DatabaseEngine.POSTGRES));

        try (Socket client = connect(port)) {
            client.getOutputStream().write("hello".getBytes(StandardCharsets.US_ASCII));
            client.getOutputStream().flush();
            assertArrayEquals("hello".getBytes(StandardCharsets.US_ASCII), received.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void postgresSslRequestIsRoutedByTheClientHelloServerName() throws Exception {
        byte[] hello = clientHello("B.rds.localhost.floci.io.", false);
        ServerSocket backendA = backend();
        ServerSocket backendB = backend();
        CompletableFuture<byte[]> atA = acceptAndRead(backendA, 8 + hello.length, true);
        CompletableFuture<byte[]> atB = acceptAndRead(backendB, 8 + hello.length, true);
        int port = freePort();
        router.register("db-a", port, List.of("a.rds.localhost.floci.io"), backendA.getLocalPort(),
                DatabaseEngine.POSTGRES);
        router.register("db-b", port, List.of("b.rds.localhost.floci.io"), backendB.getLocalPort(),
                DatabaseEngine.POSTGRES);

        try (Socket client = connect(port)) {
            OutputStream out = client.getOutputStream();
            out.write(sslRequest());
            out.flush();
            assertEquals('S', client.getInputStream().read());
            out.write(hello);
            out.flush();

            byte[] forwarded = atB.get(5, TimeUnit.SECONDS);
            assertArrayEquals(sslRequest(), Arrays.copyOfRange(forwarded, 0, 8));
            assertArrayEquals(hello, Arrays.copyOfRange(forwarded, 8, forwarded.length));
            assertThrows(TimeoutException.class, () -> atA.get(300, TimeUnit.MILLISECONDS));
        }
    }

    @Test
    void directTlsIsRoutedByServerNameAndForwardedWhole() throws Exception {
        byte[] hello = clientHello("b.rds.localhost.floci.io", true);
        ServerSocket backendA = backend();
        ServerSocket backendB = backend();
        acceptAndRead(backendA, 1, false);
        CompletableFuture<byte[]> atB = acceptAndRead(backendB, hello.length, false);
        int port = freePort();
        router.register("db-a", port, List.of("a.rds.localhost.floci.io"), backendA.getLocalPort(),
                DatabaseEngine.POSTGRES);
        router.register("db-b", port, List.of("b.rds.localhost.floci.io"), backendB.getLocalPort(),
                DatabaseEngine.POSTGRES);

        try (Socket client = connect(port)) {
            client.getOutputStream().write(hello);
            client.getOutputStream().flush();
            assertArrayEquals(hello, atB.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void plaintextPostgresOnASharedPortIsRefusedWithAnErrorResponse() throws Exception {
        int port = freePort();
        router.register("db-a", port, List.of("a.host"), backend().getLocalPort(), DatabaseEngine.POSTGRES);
        router.register("db-b", port, List.of("b.host"), backend().getLocalPort(), DatabaseEngine.POSTGRES);

        try (Socket client = connect(port)) {
            client.getOutputStream().write(startupMessage());
            client.getOutputStream().flush();
            DataInputStream in = new DataInputStream(client.getInputStream());
            assertEquals('E', in.read());
            byte[] body = new byte[in.readInt() - 4];
            in.readFully(body);
            String text = new String(body, StandardCharsets.UTF_8);
            assertTrue(text.contains("08004"), text);
            assertTrue(text.contains("share port " + port), text);
        }
    }

    @Test
    void plaintextPostgresGoesToTheOnlyPostgresResourceOnAMixedPort() throws Exception {
        ServerSocket postgres = backend();
        CompletableFuture<byte[]> atPostgres = acceptAndRead(postgres, startupMessage().length, false);
        int port = freePort();
        router.register("pg", port, List.of("pg.host"), postgres.getLocalPort(), DatabaseEngine.POSTGRES);
        router.register("my", port, List.of("my.host"), backend().getLocalPort(), DatabaseEngine.MYSQL);

        try (Socket client = connect(port)) {
            client.getOutputStream().write(startupMessage());
            client.getOutputStream().flush();
            assertArrayEquals(startupMessage(), atPostgres.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void mysqlClientsOnASharedPortGetAnErrPacketInsteadOfAGreeting() throws Exception {
        int port = freePort();
        router.register("my-a", port, List.of("a.host"), backend().getLocalPort(), DatabaseEngine.MYSQL);
        router.register("my-b", port, List.of("b.host"), backend().getLocalPort(), DatabaseEngine.MYSQL);

        try (Socket client = connect(port)) {
            client.setSoTimeout(5000);
            InputStream in = client.getInputStream();
            byte[] header = new DataInputStream(in).readNBytes(4);
            int length = (header[0] & 0xFF) | ((header[1] & 0xFF) << 8) | ((header[2] & 0xFF) << 16);
            byte[] payload = in.readNBytes(length);
            assertEquals(0xFF, payload[0] & 0xFF);
            assertEquals(1043, (payload[1] & 0xFF) | ((payload[2] & 0xFF) << 8));
        }
    }

    @Test
    void theListenerClosesWhenItsLastRouteLeaves() throws Exception {
        int port = freePort();
        router.register("db-a", port, List.of("a.host"), backend().getLocalPort(), DatabaseEngine.POSTGRES);
        router.register("db-b", port, List.of("b.host"), backend().getLocalPort(), DatabaseEngine.POSTGRES);

        router.unregister("db-a");
        assertTrue(router.isListening(port));
        router.unregister("db-b");
        assertFalse(router.isListening(port));
        try (ServerSocket rebound = new ServerSocket()) {
            rebound.setReuseAddress(true);
            rebound.bind(new InetSocketAddress(port));
        }
    }

    @Test
    void reRegisteringOnANewPortMovesTheRoute() throws Exception {
        int first = freePort();
        int second = freePort();
        int target = backend().getLocalPort();
        router.register("db-a", first, List.of("a.host"), target, DatabaseEngine.POSTGRES);

        router.register("db-a", second, List.of("a.host"), target, DatabaseEngine.POSTGRES);

        assertFalse(router.isListening(first));
        assertTrue(router.isListening(second));
    }

    @Test
    void aPortHeldByAnotherProcessIsReportedNotThrown() throws Exception {
        try (ServerSocket taken = new ServerSocket(0)) {
            assertFalse(router.register("db-a", taken.getLocalPort(), List.of("a.host"),
                    backend().getLocalPort(), DatabaseEngine.POSTGRES));
            assertFalse(router.isListening(taken.getLocalPort()));
        }
    }

    @Test
    void clientHelloServerNameIsReadAcrossRecordsAndEveryByteIsKept() throws Exception {
        byte[] whole = clientHello("DB.Example.", false);
        byte[] fragmented = fragment(whole);

        TlsClientHello.Result result = TlsClientHello.read(new ByteArrayInputStream(fragmented), new byte[0]);

        assertEquals("db.example", result.serverName());
        assertArrayEquals(fragmented, result.consumed());
    }

    @Test
    void clientHelloWithoutServerNameYieldsNull() throws Exception {
        byte[] hello = clientHello(null, false);

        TlsClientHello.Result result = TlsClientHello.read(
                new ByteArrayInputStream(hello, 3, hello.length - 3), Arrays.copyOf(hello, 3));

        assertNull(result.serverName());
        assertArrayEquals(hello, result.consumed());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private ServerSocket backend() throws IOException {
        ServerSocket socket = new ServerSocket(0);
        backends.add(socket);
        return socket;
    }

    /** Accepts one connection and reads {@code length} bytes, answering an SSLRequest with 'S'. */
    private static CompletableFuture<byte[]> acceptAndRead(ServerSocket server, int length, boolean answerSsl) {
        CompletableFuture<byte[]> future = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            try (Socket socket = server.accept()) {
                DataInputStream in = new DataInputStream(socket.getInputStream());
                ByteArrayOutputStream read = new ByteArrayOutputStream();
                if (answerSsl) {
                    byte[] request = new byte[8];
                    in.readFully(request);
                    read.writeBytes(request);
                    socket.getOutputStream().write('S');
                    socket.getOutputStream().flush();
                }
                byte[] rest = new byte[length - read.size()];
                in.readFully(rest);
                read.writeBytes(rest);
                future.complete(read.toByteArray());
            } catch (IOException e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    private static Socket connect(int port) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", port), 2000);
        return socket;
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static byte[] sslRequest() {
        return ByteBuffer.allocate(8).putInt(8).putInt(RdsEndpointRouter.POSTGRES_SSL_REQUEST).array();
    }

    private static byte[] startupMessage() {
        byte[] params = "user\0app\0\0".getBytes(StandardCharsets.US_ASCII);
        return ByteBuffer.allocate(8 + params.length).putInt(8 + params.length)
                .putInt(RdsEndpointRouter.POSTGRES_PROTOCOL_3).put(params).array();
    }

    /** A minimal TLS 1.3-shaped ClientHello record, with an SNI extension when a name is given. */
    static byte[] clientHello(String serverName, boolean withAlpn) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(0x03);
        body.write(0x03);
        body.writeBytes(new byte[32]);
        body.write(0);                                  // session id
        body.writeBytes(new byte[]{0, 2, 0x13, 0x01});  // one cipher suite
        body.writeBytes(new byte[]{1, 0});              // null compression
        ByteArrayOutputStream extensions = new ByteArrayOutputStream();
        if (withAlpn) {
            byte[] protocol = "postgresql".getBytes(StandardCharsets.US_ASCII);
            writeShort(extensions, 16);
            writeShort(extensions, 2 + 1 + protocol.length);
            writeShort(extensions, 1 + protocol.length);
            extensions.write(protocol.length);
            extensions.writeBytes(protocol);
        }
        if (serverName != null) {
            byte[] name = serverName.getBytes(StandardCharsets.US_ASCII);
            writeShort(extensions, 0);
            writeShort(extensions, 2 + 3 + name.length);
            writeShort(extensions, 3 + name.length);
            extensions.write(0);
            writeShort(extensions, name.length);
            extensions.writeBytes(name);
        }
        writeShort(body, extensions.size());
        body.writeBytes(extensions.toByteArray());
        byte[] handshake = handshake(body.toByteArray());
        return record(handshake);
    }

    private static byte[] handshake(byte[] body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(1);
        out.write((body.length >> 16) & 0xFF);
        out.write((body.length >> 8) & 0xFF);
        out.write(body.length & 0xFF);
        out.writeBytes(body);
        return out.toByteArray();
    }

    private static byte[] record(byte[] fragment) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x16);
        out.write(0x03);
        out.write(0x01);
        writeShort(out, fragment.length);
        out.writeBytes(fragment);
        return out.toByteArray();
    }

    /** The same handshake split over two records, as TLS allows. */
    private static byte[] fragment(byte[] singleRecord) {
        byte[] handshake = Arrays.copyOfRange(singleRecord, 5, singleRecord.length);
        int split = 10;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(record(Arrays.copyOfRange(handshake, 0, split)));
        out.writeBytes(record(Arrays.copyOfRange(handshake, split, handshake.length)));
        return out.toByteArray();
    }

    private static void writeShort(ByteArrayOutputStream out, int value) {
        out.write((value >> 8) & 0xFF);
        out.write(value & 0xFF);
    }
}

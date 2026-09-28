package io.github.hectorvent.floci.services.elasticache.container;

import io.github.hectorvent.floci.services.elasticache.model.ServerlessCacheSnapshotEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ServerlessCacheDataPlaneTest {

    private static final long NOW = 1_800_000_000_000L;

    private final ServerlessCacheDataPlane dataPlane =
            new ServerlessCacheDataPlane(Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));
    private final List<List<String>> received = new CopyOnWriteArrayList<>();
    private ServerSocket server;

    @AfterEach
    void stop() throws IOException {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void respCaptureDumpsEveryScannedKeyWithItsExpiry() throws Exception {
        int port = serve(this::answerResp);

        List<ServerlessCacheSnapshotEntry> entries = dataPlane.capture("valkey", "127.0.0.1", port);

        assertEquals(2, entries.size());
        assertEquals("k1", decode(entries.get(0).getKey()));
        assertEquals("dump-k1", decode(entries.get(0).getValue()));
        assertNull(entries.get(0).getExpiresAtMillis());
        assertEquals("k2", decode(entries.get(1).getKey()));
        assertEquals(NOW + 5_000, entries.get(1).getExpiresAtMillis());
    }

    @Test
    void respRestoreReplacesKeysWithAbsoluteExpiryAndSkipsExpiredOnes() throws Exception {
        int port = serve(this::answerResp);

        dataPlane.restore("redis", "127.0.0.1", port, List.of(
                entry("k1", "dump-k1", null, 0),
                entry("k2", "dump-k2", NOW + 60_000, 0),
                entry("old", "dump-old", NOW - 1, 0)));

        List<List<String>> restores = received.stream().filter(c -> c.getFirst().equals("RESTORE")).toList();
        assertEquals(List.of(
                List.of("RESTORE", "k1", "0", "dump-k1", "REPLACE", "ABSTTL"),
                List.of("RESTORE", "k2", String.valueOf(NOW + 60_000), "dump-k2", "REPLACE", "ABSTTL")), restores);
    }

    @Test
    void memcachedCaptureReadsMetadumpAndItemFlags() throws Exception {
        int port = serve(this::answerMemcached);

        List<ServerlessCacheSnapshotEntry> entries = dataPlane.capture("memcached", "127.0.0.1", port);

        assertEquals(1, entries.size());
        assertEquals("user:1", decode(entries.getFirst().getKey()));
        assertEquals("hello", decode(entries.getFirst().getValue()));
        assertEquals(42, entries.getFirst().getFlags());
        assertEquals(1_900_000_000_000L, entries.getFirst().getExpiresAtMillis());
    }

    @Test
    void memcachedRestoreSetsItemsWithFlagsAndAbsoluteExptime() throws Exception {
        int port = serve(this::answerMemcached);

        dataPlane.restore("memcached", "127.0.0.1", port, List.of(entry("user:1", "hello", 1_900_000_000_000L, 42)));

        assertEquals(List.of(List.of("set user:1 42 1900000000 5", "hello")), received);
    }

    private static ServerlessCacheSnapshotEntry entry(String key, String value, Long expiresAt, long flags) {
        return new ServerlessCacheSnapshotEntry(encode(key), encode(value), expiresAt, flags);
    }

    private static String encode(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }

    @FunctionalInterface
    private interface Answerer {
        void answer(InputStream in, OutputStream out) throws IOException;
    }

    private int serve(Answerer answerer) throws IOException {
        server = new ServerSocket(0);
        Thread.ofVirtual().start(() -> {
            try (Socket socket = server.accept()) {
                answerer.answer(new BufferedInputStream(socket.getInputStream()), socket.getOutputStream());
            } catch (IOException ignored) {
                // The client closed the connection; the test asserts what it received.
            }
        });
        return server.getLocalPort();
    }

    private void answerResp(InputStream in, OutputStream out) throws IOException {
        while (true) {
            List<String> command = readRespCommand(in);
            if (command == null) {
                return;
            }
            received.add(command);
            String reply = switch (command.getFirst()) {
                case "SCAN" -> command.get(1).equals("0")
                        ? "*2\r\n$1\r\n7\r\n*1\r\n$2\r\nk1\r\n"
                        : "*2\r\n$1\r\n0\r\n*2\r\n$2\r\nk2\r\n$2\r\nk1\r\n";
                case "DUMP" -> bulk("dump-" + command.get(1));
                case "PTTL" -> command.get(1).equals("k2") ? ":5000\r\n" : ":-1\r\n";
                case "RESTORE" -> "+OK\r\n";
                default -> "-ERR unknown\r\n";
            };
            out.write(reply.getBytes(StandardCharsets.US_ASCII));
            out.flush();
        }
    }

    private void answerMemcached(InputStream in, OutputStream out) throws IOException {
        while (true) {
            String line = readLine(in);
            if (line == null) {
                return;
            }
            String reply;
            if (line.equals("lru_crawler metadump all")) {
                reply = "key=user%3A1 exp=1900000000 la=1 cas=2 fetch=no cls=1 size=70\r\nEND\r\n";
            } else if (line.equals("get user:1")) {
                reply = "VALUE user:1 42 5\r\nhello\r\nEND\r\n";
            } else if (line.startsWith("set ")) {
                String data = readLine(in);
                received.add(List.of(line, data));
                reply = "STORED\r\n";
            } else {
                reply = "ERROR\r\n";
            }
            out.write(reply.getBytes(StandardCharsets.US_ASCII));
            out.flush();
        }
    }

    private static String bulk(String value) {
        return "$" + value.length() + "\r\n" + value + "\r\n";
    }

    private static List<String> readRespCommand(InputStream in) throws IOException {
        String header = readLine(in);
        if (header == null) {
            return null;
        }
        int count = Integer.parseInt(header.substring(1));
        List<String> args = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int length = Integer.parseInt(readLine(in).substring(1));
            args.add(new String(in.readNBytes(length), StandardCharsets.UTF_8));
            readLine(in);
        }
        return args;
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int previous = -1;
        while (true) {
            int next = in.read();
            if (next == -1) {
                return null;
            }
            if (previous == '\r' && next == '\n') {
                byte[] bytes = line.toByteArray();
                return new String(bytes, 0, bytes.length - 1, StandardCharsets.UTF_8);
            }
            line.write(next);
            previous = next;
        }
    }
}

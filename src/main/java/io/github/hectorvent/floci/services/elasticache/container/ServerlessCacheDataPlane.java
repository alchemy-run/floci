package io.github.hectorvent.floci.services.elasticache.container;

import io.github.hectorvent.floci.services.elasticache.model.ServerlessCacheSnapshotEntry;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Copies the keyspace of a serverless cache's engine in and out, which is what a serverless
 * snapshot holds. Valkey and Redis OSS keys are read with {@code SCAN} + {@code DUMP} +
 * {@code PTTL} and written back with {@code RESTORE ... REPLACE ABSTTL}, so every data type and
 * expiry survives. Memcached items are enumerated with {@code lru_crawler metadump all} and read
 * and written with {@code get} and {@code set}, keeping their flags and expiry.
 */
@ApplicationScoped
public class ServerlessCacheDataPlane {

    private static final int CONNECT_TIMEOUT_MILLIS = 5_000;
    private static final int READ_TIMEOUT_MILLIS = 30_000;
    private static final String SCAN_BATCH = "1000";

    private final Clock clock;

    public ServerlessCacheDataPlane() {
        this(Clock.systemUTC());
    }

    ServerlessCacheDataPlane(Clock clock) {
        this.clock = clock;
    }

    public List<ServerlessCacheSnapshotEntry> capture(String engine, String host, int port) throws IOException {
        return "memcached".equals(engine) ? captureMemcached(host, port) : captureResp(host, port);
    }

    public void restore(String engine, String host, int port, List<ServerlessCacheSnapshotEntry> entries)
            throws IOException {
        if ("memcached".equals(engine)) {
            restoreMemcached(host, port, entries);
        } else {
            restoreResp(host, port, entries);
        }
    }

    // ── Valkey / Redis OSS ────────────────────────────────────────────────────

    private List<ServerlessCacheSnapshotEntry> captureResp(String host, int port) throws IOException {
        Map<String, ServerlessCacheSnapshotEntry> captured = new LinkedHashMap<>();
        try (Connection connection = Connection.open(host, port)) {
            String cursor = "0";
            do {
                Object reply = connection.command(ascii("SCAN"), ascii(cursor), ascii("COUNT"), ascii(SCAN_BATCH));
                if (!(reply instanceof List<?> page) || page.size() != 2
                        || !(page.get(0) instanceof byte[] nextCursor) || !(page.get(1) instanceof List<?> keys)) {
                    throw new IOException("Unexpected SCAN reply: " + reply);
                }
                for (Object item : keys) {
                    byte[] key = (byte[]) item;
                    String encodedKey = Base64.getEncoder().encodeToString(key);
                    if (captured.containsKey(encodedKey)) {
                        continue;
                    }
                    Object payload = connection.command(ascii("DUMP"), key);
                    Object ttl = connection.command(ascii("PTTL"), key);
                    if (!(payload instanceof byte[] dump) || !(ttl instanceof Long remaining) || remaining == -2) {
                        continue;
                    }
                    Long expiresAt = remaining >= 0 ? clock.millis() + remaining : null;
                    captured.put(encodedKey, new ServerlessCacheSnapshotEntry(encodedKey,
                            Base64.getEncoder().encodeToString(dump), expiresAt, 0));
                }
                cursor = new String(nextCursor, StandardCharsets.US_ASCII);
            } while (!"0".equals(cursor));
        }
        return new ArrayList<>(captured.values());
    }

    private void restoreResp(String host, int port, List<ServerlessCacheSnapshotEntry> entries) throws IOException {
        long now = clock.millis();
        try (Connection connection = Connection.open(host, port)) {
            for (ServerlessCacheSnapshotEntry entry : entries) {
                Long expiresAt = entry.getExpiresAtMillis();
                if (expiresAt != null && expiresAt <= now) {
                    continue;
                }
                connection.command(ascii("RESTORE"), Base64.getDecoder().decode(entry.getKey()),
                        ascii(String.valueOf(expiresAt != null ? expiresAt : 0L)),
                        Base64.getDecoder().decode(entry.getValue()), ascii("REPLACE"), ascii("ABSTTL"));
            }
        }
    }

    // ── Memcached ─────────────────────────────────────────────────────────────

    private List<ServerlessCacheSnapshotEntry> captureMemcached(String host, int port) throws IOException {
        List<ServerlessCacheSnapshotEntry> captured = new ArrayList<>();
        try (Connection connection = Connection.open(host, port)) {
            connection.write(ascii("lru_crawler metadump all\r\n"));
            Map<String, Long> expiries = new LinkedHashMap<>();
            for (String line = connection.readLine(); !"END".equals(line); line = connection.readLine()) {
                if (line.startsWith("ERROR") || line.startsWith("CLIENT_ERROR") || line.startsWith("SERVER_ERROR")
                        || line.startsWith("BUSY")) {
                    throw new IOException("lru_crawler metadump failed: " + line);
                }
                String key = null;
                long exp = -1;
                for (String field : line.split(" ")) {
                    if (field.startsWith("key=")) {
                        key = URLDecoder.decode(field.substring(4), StandardCharsets.UTF_8);
                    } else if (field.startsWith("exp=")) {
                        exp = Long.parseLong(field.substring(4));
                    }
                }
                if (key != null) {
                    expiries.put(key, exp);
                }
            }
            for (Map.Entry<String, Long> item : expiries.entrySet()) {
                connection.write(ascii("get " + item.getKey() + "\r\n"));
                String header = connection.readLine();
                if ("END".equals(header)) {
                    continue;
                }
                String[] parts = header.split(" ");
                if (parts.length < 4 || !"VALUE".equals(parts[0])) {
                    throw new IOException("Unexpected get reply: " + header);
                }
                byte[] data = connection.readExactly(Integer.parseInt(parts[3]));
                connection.readLine();
                String end = connection.readLine();
                if (!"END".equals(end)) {
                    throw new IOException("Unexpected get terminator: " + end);
                }
                Long expiresAt = item.getValue() > 0 ? item.getValue() * 1000 : null;
                captured.add(new ServerlessCacheSnapshotEntry(
                        Base64.getEncoder().encodeToString(item.getKey().getBytes(StandardCharsets.UTF_8)),
                        Base64.getEncoder().encodeToString(data), expiresAt, Long.parseLong(parts[2])));
            }
        }
        return captured;
    }

    private void restoreMemcached(String host, int port, List<ServerlessCacheSnapshotEntry> entries)
            throws IOException {
        long now = clock.millis();
        try (Connection connection = Connection.open(host, port)) {
            for (ServerlessCacheSnapshotEntry entry : entries) {
                Long expiresAt = entry.getExpiresAtMillis();
                if (expiresAt != null && expiresAt <= now) {
                    continue;
                }
                String key = new String(Base64.getDecoder().decode(entry.getKey()), StandardCharsets.UTF_8);
                byte[] data = Base64.getDecoder().decode(entry.getValue());
                long exptime = expiresAt != null ? expiresAt / 1000 : 0;
                ByteArrayOutputStream command = new ByteArrayOutputStream();
                command.writeBytes(ascii("set " + key + " " + entry.getFlags() + " " + exptime + " "
                        + data.length + "\r\n"));
                command.writeBytes(data);
                command.writeBytes(ascii("\r\n"));
                connection.write(command.toByteArray());
                String reply = connection.readLine();
                if (!"STORED".equals(reply)) {
                    throw new IOException("Memcached set of " + key + " failed: " + reply);
                }
            }
        }
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    /** A blocking connection that speaks RESP2 requests and replies plus raw text lines. */
    private static final class Connection implements Closeable {

        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;

        private Connection(Socket socket) throws IOException {
            this.socket = socket;
            this.in = new BufferedInputStream(socket.getInputStream());
            this.out = socket.getOutputStream();
        }

        static Connection open(String host, int port) throws IOException {
            Socket socket = new Socket();
            try {
                socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS);
                socket.setSoTimeout(READ_TIMEOUT_MILLIS);
                socket.setTcpNoDelay(true);
                return new Connection(socket);
            } catch (IOException e) {
                socket.close();
                throw e;
            }
        }

        Object command(byte[]... args) throws IOException {
            ByteArrayOutputStream request = new ByteArrayOutputStream();
            request.writeBytes(ascii("*" + args.length + "\r\n"));
            for (byte[] arg : args) {
                request.writeBytes(ascii("$" + arg.length + "\r\n"));
                request.writeBytes(arg);
                request.writeBytes(ascii("\r\n"));
            }
            write(request.toByteArray());
            return readReply();
        }

        void write(byte[] bytes) throws IOException {
            out.write(bytes);
            out.flush();
        }

        private Object readReply() throws IOException {
            int type = in.read();
            if (type == -1) {
                throw new IOException("Connection closed by the cache engine");
            }
            String line = readLine();
            return switch (type) {
                case '+' -> line;
                case '-' -> throw new IOException("Cache engine error: " + line);
                case ':' -> Long.parseLong(line);
                case '$' -> {
                    int length = Integer.parseInt(line);
                    if (length < 0) {
                        yield null;
                    }
                    byte[] data = readExactly(length);
                    readLine();
                    yield data;
                }
                case '*' -> {
                    int count = Integer.parseInt(line);
                    if (count < 0) {
                        yield null;
                    }
                    List<Object> items = new ArrayList<>(count);
                    for (int i = 0; i < count; i++) {
                        items.add(readReply());
                    }
                    yield items;
                }
                default -> throw new IOException("Unsupported RESP reply type: " + (char) type);
            };
        }

        String readLine() throws IOException {
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            int previous = -1;
            while (true) {
                int next = in.read();
                if (next == -1) {
                    throw new IOException("Connection closed by the cache engine");
                }
                if (previous == '\r' && next == '\n') {
                    byte[] bytes = line.toByteArray();
                    return new String(bytes, 0, bytes.length - 1, StandardCharsets.UTF_8);
                }
                line.write(next);
                previous = next;
            }
        }

        byte[] readExactly(int length) throws IOException {
            byte[] data = in.readNBytes(length);
            if (data.length != length) {
                throw new IOException("Connection closed by the cache engine");
            }
            return data;
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}

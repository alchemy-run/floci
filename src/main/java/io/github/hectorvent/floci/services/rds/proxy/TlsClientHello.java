package io.github.hectorvent.floci.services.rds.proxy;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Reads a TLS ClientHello off a socket without terminating TLS, keeping every byte it consumed so
 * the handshake can be replayed to the server that will actually answer it.
 *
 * <p>Only the {@code server_name} extension (RFC 6066) is interpreted. The hello may be spread over
 * several TLS records; reading stops once the whole handshake message has arrived.
 */
final class TlsClientHello {

    private static final int CONTENT_TYPE_HANDSHAKE = 22;
    private static final int HANDSHAKE_CLIENT_HELLO = 1;
    private static final int EXTENSION_SERVER_NAME = 0;
    private static final int SERVER_NAME_HOST = 0;
    private static final int MAX_RECORD_LENGTH = 16384 + 2048;
    private static final int MAX_HELLO_LENGTH = 65536;

    /** The raw bytes read (to replay) and the requested host name, or null when none was sent. */
    record Result(byte[] consumed, String serverName) {}

    private TlsClientHello() {
    }

    /**
     * @param prefix bytes of the first record already read off {@code in} (may be empty)
     */
    static Result read(InputStream in, byte[] prefix) throws IOException {
        // Every byte read through source is recorded, the replayed prefix included.
        ByteArrayOutputStream consumed = new ByteArrayOutputStream();
        InputStream source = new PrefixedInputStream(prefix, in);
        ByteArrayOutputStream handshake = new ByteArrayOutputStream();
        int expected = -1;
        while (expected < 0 || handshake.size() < expected) {
            byte[] header = readFully(source, 5, consumed);
            if ((header[0] & 0xFF) != CONTENT_TYPE_HANDSHAKE) {
                throw new IOException("Not a TLS handshake record");
            }
            int length = ((header[3] & 0xFF) << 8) | (header[4] & 0xFF);
            if (length == 0 || length > MAX_RECORD_LENGTH) {
                throw new IOException("Invalid TLS record length " + length);
            }
            handshake.write(readFully(source, length, consumed));
            if (expected < 0 && handshake.size() >= 4) {
                byte[] head = handshake.toByteArray();
                if ((head[0] & 0xFF) != HANDSHAKE_CLIENT_HELLO) {
                    throw new IOException("First TLS handshake message is not a ClientHello");
                }
                int helloLength = ((head[1] & 0xFF) << 16) | ((head[2] & 0xFF) << 8) | (head[3] & 0xFF);
                if (helloLength > MAX_HELLO_LENGTH) {
                    throw new IOException("ClientHello too large");
                }
                expected = 4 + helloLength;
            }
        }
        return new Result(consumed.toByteArray(), serverName(handshake.toByteArray(), expected));
    }

    /** The host_name entry of the server_name extension, lower-cased, or null. */
    static String serverName(byte[] hello, int end) {
        Cursor c = new Cursor(hello, 4, Math.min(end, hello.length));
        if (!c.skip(2 + 32)) {                 // legacy_version, random
            return null;
        }
        if (!c.skip(c.u8())) {                 // session id
            return null;
        }
        if (!c.skip(c.u16())) {                // cipher suites
            return null;
        }
        if (!c.skip(c.u8())) {                 // compression methods
            return null;
        }
        if (c.remaining() < 2) {
            return null;                       // no extensions
        }
        int extensionsEnd = Math.min(c.pos + 2 + c.u16(), c.end);
        while (c.pos + 4 <= extensionsEnd) {
            int type = c.u16();
            int length = c.u16();
            int next = c.pos + length;
            if (next > extensionsEnd) {
                return null;
            }
            if (type == EXTENSION_SERVER_NAME) {
                int listEnd = Math.min(c.pos + 2 + c.u16(), next);
                while (c.pos + 3 <= listEnd) {
                    int nameType = c.u8();
                    int nameLength = c.u16();
                    if (c.pos + nameLength > listEnd) {
                        return null;
                    }
                    if (nameType == SERVER_NAME_HOST) {
                        String name = new String(hello, c.pos, nameLength, StandardCharsets.US_ASCII);
                        return normalizeHost(name);
                    }
                    c.pos += nameLength;
                }
                return null;
            }
            c.pos = next;
        }
        return null;
    }

    static String normalizeHost(String host) {
        if (host == null) {
            return null;
        }
        String trimmed = host.trim();
        if (trimmed.endsWith(".")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.isEmpty() ? null : trimmed.toLowerCase(Locale.ROOT);
    }

    private static byte[] readFully(InputStream in, int length, ByteArrayOutputStream consumed) throws IOException {
        byte[] buffer = new byte[length];
        int read = 0;
        while (read < length) {
            int n = in.read(buffer, read, length - read);
            if (n < 0) {
                throw new EOFException("Connection closed during TLS ClientHello");
            }
            read += n;
        }
        consumed.write(buffer, 0, length);
        return buffer;
    }

    private static final class Cursor {
        private final byte[] data;
        private final int end;
        private int pos;

        Cursor(byte[] data, int pos, int end) {
            this.data = data;
            this.pos = pos;
            this.end = end;
        }

        int remaining() {
            return end - pos;
        }

        boolean skip(int n) {
            if (n < 0 || pos + n > end) {
                pos = end;
                return false;
            }
            pos += n;
            return true;
        }

        int u8() {
            return pos < end ? data[pos++] & 0xFF : 0;
        }

        int u16() {
            if (pos + 2 > end) {
                pos = end;
                return 0;
            }
            int value = ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF);
            pos += 2;
            return value;
        }
    }

    /** Replays already-read bytes before the socket's own stream. */
    private static final class PrefixedInputStream extends InputStream {
        private final byte[] prefix;
        private final InputStream rest;
        private int offset;

        PrefixedInputStream(byte[] prefix, InputStream rest) {
            this.prefix = prefix;
            this.rest = rest;
        }

        @Override
        public int read() throws IOException {
            if (offset < prefix.length) {
                return prefix[offset++] & 0xFF;
            }
            return rest.read();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (offset < prefix.length) {
                int n = Math.min(len, prefix.length - offset);
                System.arraycopy(prefix, offset, b, off, n);
                offset += n;
                return n;
            }
            return rest.read(b, off, len);
        }
    }
}

package io.floci.aws.config;

import io.vertx.core.buffer.Buffer;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;

/**
 * Reads the server name (SNI) a TLS ClientHello asks for, without decrypting anything: the
 * ClientHello travels in the clear, in the first record of the connection.
 */
public final class TlsClientHello {

    private static final int HANDSHAKE_CLIENT_HELLO = 1;
    private static final int EXTENSION_SERVER_NAME = 0;
    private static final int NAME_TYPE_HOST = 0;

    private TlsClientHello() {}

    /**
     * The host name in the record's server_name extension, lower-cased; empty when the buffer is
     * not a whole ClientHello or names no host.
     */
    public static Optional<String> serverName(Buffer record) {
        try {
            int length = record.length();
            if (length < 5 || record.getByte(0) != TlsProxyServer.TLS_HANDSHAKE) {
                return Optional.empty();
            }
            int pos = 5;
            if (record.getUnsignedByte(pos) != HANDSHAKE_CLIENT_HELLO) {
                return Optional.empty();
            }
            // handshake type (1) + length (3) + client version (2) + random (32)
            pos += 4 + 2 + 32;
            pos += 1 + record.getUnsignedByte(pos);            // session id
            pos += 2 + record.getUnsignedShort(pos);           // cipher suites
            pos += 1 + record.getUnsignedByte(pos);            // compression methods
            int extensionsEnd = pos + 2 + record.getUnsignedShort(pos);
            pos += 2;
            while (pos + 4 <= extensionsEnd && extensionsEnd <= length) {
                int type = record.getUnsignedShort(pos);
                int size = record.getUnsignedShort(pos + 2);
                pos += 4;
                if (type == EXTENSION_SERVER_NAME) {
                    int listEnd = pos + 2 + record.getUnsignedShort(pos);
                    int entry = pos + 2;
                    while (entry + 3 <= listEnd) {
                        int nameType = record.getUnsignedByte(entry);
                        int nameLength = record.getUnsignedShort(entry + 1);
                        if (nameType == NAME_TYPE_HOST && entry + 3 + nameLength <= length) {
                            String name = record.getString(entry + 3, entry + 3 + nameLength,
                                    StandardCharsets.US_ASCII.name());
                            return Optional.of(name.toLowerCase(Locale.ROOT));
                        }
                        entry += 3 + nameLength;
                    }
                    return Optional.empty();
                }
                pos += size;
            }
            return Optional.empty();
        } catch (IndexOutOfBoundsException truncated) {
            return Optional.empty();
        }
    }
}

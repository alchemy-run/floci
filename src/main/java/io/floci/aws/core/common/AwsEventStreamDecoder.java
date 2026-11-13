package io.floci.aws.core.common;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;

/**
 * Reads {@code application/vnd.amazon.eventstream} messages from a request body, one at a time, the
 * inverse of {@link AwsEventStreamEncoder}. Each message is a 12-byte prelude (total length, header
 * length, prelude CRC), the headers, the payload and a message CRC; both CRCs are checked.
 */
public final class AwsEventStreamDecoder {

    /** One message: its headers (strings for string headers, otherwise the value's string form) and payload. */
    public record Message(Map<String, String> headers, byte[] payload) {
        public String header(String name) {
            return headers.get(name);
        }
    }

    private final DataInputStream in;

    public AwsEventStreamDecoder(InputStream in) {
        this.in = new DataInputStream(in);
    }

    /** The next message, or {@code null} at a clean end of the stream. */
    public Message next() throws IOException {
        byte[] prelude = new byte[12];
        int first = in.read();
        if (first < 0) {
            return null;
        }
        prelude[0] = (byte) first;
        in.readFully(prelude, 1, 11);
        ByteBuffer preludeBuffer = ByteBuffer.wrap(prelude);
        int totalLength = preludeBuffer.getInt();
        int headersLength = preludeBuffer.getInt();
        int preludeCrc = preludeBuffer.getInt();
        if (crc(prelude, 0, 8) != preludeCrc) {
            throw new IOException("Event stream prelude CRC mismatch");
        }
        if (totalLength < 16 || headersLength < 0 || headersLength > totalLength - 16) {
            throw new IOException("Malformed event stream message lengths");
        }
        byte[] rest = new byte[totalLength - 12];
        try {
            in.readFully(rest);
        } catch (EOFException e) {
            throw new IOException("Truncated event stream message", e);
        }
        CRC32 messageCrc = new CRC32();
        messageCrc.update(prelude);
        messageCrc.update(rest, 0, rest.length - 4);
        int expected = ByteBuffer.wrap(rest, rest.length - 4, 4).getInt();
        if ((int) messageCrc.getValue() != expected) {
            throw new IOException("Event stream message CRC mismatch");
        }
        Map<String, String> headers = headers(ByteBuffer.wrap(rest, 0, headersLength));
        byte[] payload = new byte[rest.length - 4 - headersLength];
        System.arraycopy(rest, headersLength, payload, 0, payload.length);
        return new Message(headers, payload);
    }

    private static Map<String, String> headers(ByteBuffer buffer) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        while (buffer.hasRemaining()) {
            int nameLength = buffer.get() & 0xff;
            byte[] name = new byte[nameLength];
            buffer.get(name);
            int type = buffer.get();
            String value = switch (type) {
                case 0 -> "true";
                case 1 -> "false";
                case 2 -> Byte.toString(buffer.get());
                case 3 -> Short.toString(buffer.getShort());
                case 4 -> Integer.toString(buffer.getInt());
                case 5, 8 -> Long.toString(buffer.getLong());
                case 6, 7 -> {
                    byte[] bytes = new byte[buffer.getShort() & 0xffff];
                    buffer.get(bytes);
                    yield type == 7 ? new String(bytes, StandardCharsets.UTF_8)
                            : HexFormat.of().formatHex(bytes);
                }
                case 9 -> {
                    byte[] uuid = new byte[16];
                    buffer.get(uuid);
                    yield HexFormat.of().formatHex(uuid);
                }
                default -> throw new IOException("Unknown event stream header type " + type);
            };
            headers.put(new String(name, StandardCharsets.UTF_8), value);
        }
        return headers;
    }

    private static int crc(byte[] bytes, int offset, int length) {
        CRC32 crc = new CRC32();
        crc.update(bytes, offset, length);
        return (int) crc.getValue();
    }
}

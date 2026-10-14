package io.github.hectorvent.floci.services.docdb.container;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Asks a MongoDB server whether it is a writable primary, with a single unauthenticated
 * {@code hello} command over the wire protocol ({@code OP_MSG}).
 *
 * <p>A DocumentDB cluster is a replica set, so its backend is ready for clients once the set has
 * elected it primary, not when its port first accepts connections.
 */
final class MongoHelloProbe {

    static final int OP_MSG = 2013;
    private static final int MAX_REPLY_BYTES = 16 * 1024 * 1024;

    private MongoHelloProbe() {
    }

    static boolean isWritablePrimary(String host, int port, int timeoutMs) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            OutputStream out = socket.getOutputStream();
            out.write(helloRequest(1));
            out.flush();
            return isWritablePrimary(readReply(socket.getInputStream()));
        }
    }

    /** An {@code OP_MSG} carrying {@code {hello: 1, $db: "admin"}}. */
    static byte[] helloRequest(int requestId) {
        ByteArrayOutputStream document = new ByteArrayOutputStream();
        document.write(0x10);
        writeCString(document, "hello");
        writeInt(document, 1);
        document.write(0x02);
        writeCString(document, "$db");
        byte[] db = "admin".getBytes(StandardCharsets.UTF_8);
        writeInt(document, db.length + 1);
        document.writeBytes(db);
        document.write(0);
        document.write(0);
        byte[] elements = document.toByteArray();

        int documentLength = 4 + elements.length;
        int messageLength = 16 + 4 + 1 + documentLength;
        ByteBuffer message = ByteBuffer.allocate(messageLength).order(ByteOrder.LITTLE_ENDIAN);
        message.putInt(messageLength).putInt(requestId).putInt(0).putInt(OP_MSG);
        message.putInt(0);
        message.put((byte) 0);
        message.putInt(documentLength).put(elements);
        return message.array();
    }

    static byte[] readReply(InputStream in) throws IOException {
        DataInputStream data = new DataInputStream(in);
        byte[] lengthBytes = new byte[4];
        data.readFully(lengthBytes);
        int length = ByteBuffer.wrap(lengthBytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
        if (length < 16 || length > MAX_REPLY_BYTES) {
            throw new IOException("Invalid MongoDB reply length " + length);
        }
        byte[] reply = new byte[length];
        System.arraycopy(lengthBytes, 0, reply, 0, 4);
        data.readFully(reply, 4, length - 4);
        return reply;
    }

    /** Reads {@code isWritablePrimary} from an {@code OP_MSG} reply's body document. */
    static boolean isWritablePrimary(byte[] reply) {
        ByteBuffer buffer = ByteBuffer.wrap(reply).order(ByteOrder.LITTLE_ENDIAN);
        if (reply.length < 26 || buffer.getInt(12) != OP_MSG || reply[20] != 0) {
            return false;
        }
        int documentStart = 21;
        int documentEnd = documentStart + buffer.getInt(documentStart) - 1;
        if (documentEnd >= reply.length) {
            return false;
        }
        int position = documentStart + 4;
        while (position < documentEnd) {
            int type = reply[position++] & 0xFF;
            int nameEnd = position;
            while (nameEnd < documentEnd && reply[nameEnd] != 0) {
                nameEnd++;
            }
            String name = new String(reply, position, nameEnd - position, StandardCharsets.UTF_8);
            position = nameEnd + 1;
            if (type == 0x08 && ("isWritablePrimary".equals(name) || "ismaster".equals(name))) {
                return position < documentEnd && reply[position] == 1;
            }
            int size = valueSize(type, buffer, position);
            if (size < 0) {
                return false;
            }
            position += size;
        }
        return false;
    }

    private static int valueSize(int type, ByteBuffer buffer, int position) {
        return switch (type) {
            case 0x01, 0x09, 0x11, 0x12 -> 8;
            case 0x02 -> 4 + buffer.getInt(position);
            case 0x03, 0x04 -> buffer.getInt(position);
            case 0x05 -> 5 + buffer.getInt(position);
            case 0x07 -> 12;
            case 0x08 -> 1;
            case 0x0A, 0xFF, 0x7F -> 0;
            case 0x10 -> 4;
            case 0x13 -> 16;
            default -> -1;
        };
    }

    private static void writeCString(ByteArrayOutputStream out, String value) {
        out.writeBytes(value.getBytes(StandardCharsets.UTF_8));
        out.write(0);
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array());
    }
}

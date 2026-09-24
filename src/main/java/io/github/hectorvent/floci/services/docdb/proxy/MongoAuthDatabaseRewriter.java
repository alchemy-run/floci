package io.github.hectorvent.floci.services.docdb.proxy;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Points client authentication at the {@code admin} database, which is where DocumentDB keeps
 * every user.
 *
 * <p>DocumentDB users are not scoped to a database: a client that authenticates against the
 * database named in its connection string, as MongoDB drivers do by default, signs in as the
 * user in {@code admin}. MongoDB looks the user up in the named database instead, so the
 * authentication conversation is redirected to {@code admin} on its way to the backend: the
 * {@code $db} of {@code saslStart} and {@code saslContinue}, the database of an
 * {@code OP_QUERY} command namespace carrying them, and the database a handshake names in
 * {@code saslSupportedMechs} and {@code speculativeAuthenticate}. SCRAM proofs do not cover the
 * database, so the conversation completes unchanged. Every other message passes through as is.
 */
final class MongoAuthDatabaseRewriter {

    static final int OP_QUERY = 2004;
    static final int OP_MSG = 2013;
    static final String AUTH_DATABASE = "admin";
    private static final String EXTERNAL_DATABASE = "$external";
    private static final int MAX_MESSAGE_BYTES = 48 * 1024 * 1024;
    private static final int CHECKSUM_PRESENT = 1;
    private static final Set<String> SASL_COMMANDS = Set.of("saslStart", "saslContinue");

    private MongoAuthDatabaseRewriter() {
    }

    /** Relays client messages to the backend, one framed message at a time, until the client stops. */
    static void relay(InputStream client, OutputStream backend) throws IOException {
        DataInputStream in = new DataInputStream(client);
        byte[] header = new byte[16];
        while (true) {
            try {
                in.readFully(header);
            } catch (EOFException closed) {
                return;
            }
            int length = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt(0);
            if (length < 16 || length > MAX_MESSAGE_BYTES) {
                throw new IOException("Invalid MongoDB message length " + length);
            }
            byte[] message = new byte[length];
            System.arraycopy(header, 0, message, 0, 16);
            in.readFully(message, 16, length - 16);
            backend.write(rewrite(message));
            backend.flush();
        }
    }

    static byte[] rewrite(byte[] message) {
        try {
            return switch (littleEndianInt(message, 12)) {
                case OP_MSG -> rewriteOpMsg(message);
                case OP_QUERY -> rewriteOpQuery(message);
                default -> message;
            };
        } catch (IndexOutOfBoundsException malformed) {
            // The backend answers a malformed message the way MongoDB does.
            return message;
        }
    }

    private static byte[] rewriteOpMsg(byte[] message) {
        int flags = littleEndianInt(message, 16);
        if ((flags & CHECKSUM_PRESENT) != 0) {
            return message;
        }
        int position = 20;
        while (position < message.length) {
            int kind = message[position];
            if (kind == 0) {
                int start = position + 1;
                int length = littleEndianInt(message, start);
                byte[] body = slice(message, start, length);
                byte[] rewritten = rewriteCommand(body, true);
                if (rewritten == body) {
                    return message;
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream(message.length + rewritten.length - length);
                out.write(message, 0, start);
                out.writeBytes(rewritten);
                out.write(message, start + length, message.length - start - length);
                return withLength(out.toByteArray());
            }
            if (kind != 1) {
                return message;
            }
            position += 1 + littleEndianInt(message, position + 1);
        }
        return message;
    }

    private static byte[] rewriteOpQuery(byte[] message) {
        int nameStart = 20;
        int nameEnd = nameStart;
        while (message[nameEnd] != 0) {
            nameEnd++;
        }
        String namespace = new String(message, nameStart, nameEnd - nameStart, StandardCharsets.UTF_8);
        int queryStart = nameEnd + 1 + 8;
        int queryLength = littleEndianInt(message, queryStart);
        byte[] query = slice(message, queryStart, queryLength);
        if (!namespace.endsWith(".$cmd")) {
            return message;
        }
        byte[] rewritten = rewriteCommand(query, false);
        String database = namespace.substring(0, namespace.length() - ".$cmd".length());
        String newNamespace = SASL_COMMANDS.contains(firstKey(query)) && redirects(database)
                ? AUTH_DATABASE + ".$cmd" : namespace;
        if (rewritten == query && newNamespace.equals(namespace)) {
            return message;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(message.length + 16);
        out.write(message, 0, nameStart);
        out.writeBytes(newNamespace.getBytes(StandardCharsets.UTF_8));
        out.write(0);
        out.write(message, nameEnd + 1, 8);
        out.writeBytes(rewritten);
        out.write(message, queryStart + queryLength, message.length - queryStart - queryLength);
        return withLength(out.toByteArray());
    }

    /** A command document with its authentication database redirected, or the same array when unchanged. */
    static byte[] rewriteCommand(byte[] document, boolean opMsg) {
        List<Element> elements = elements(document);
        if (elements == null || elements.isEmpty()) {
            return document;
        }
        boolean sasl = SASL_COMMANDS.contains(elements.getFirst().name());
        boolean changed = false;
        List<Element> result = new ArrayList<>(elements.size());
        for (Element element : elements) {
            Element replacement = element;
            if (element.type() == 0x02 && "$db".equals(element.name()) && opMsg && sasl) {
                replacement = redirected(element);
            } else if (element.type() == 0x02 && "saslSupportedMechs".equals(element.name())) {
                String user = stringValue(element);
                int dot = user.indexOf('.');
                if (dot > 0 && redirects(user.substring(0, dot))) {
                    replacement = element.withString(AUTH_DATABASE + user.substring(dot));
                }
            } else if (element.type() == 0x03 && "speculativeAuthenticate".equals(element.name())) {
                byte[] nested = rewriteSpeculativeAuthenticate(element.value());
                if (nested != element.value()) {
                    replacement = new Element(element.type(), element.name(), nested);
                }
            }
            changed |= replacement != element;
            result.add(replacement);
        }
        return changed ? document(result) : document;
    }

    private static byte[] rewriteSpeculativeAuthenticate(byte[] document) {
        List<Element> elements = elements(document);
        if (elements == null) {
            return document;
        }
        boolean changed = false;
        List<Element> result = new ArrayList<>(elements.size());
        for (Element element : elements) {
            Element replacement = element.type() == 0x02 && "db".equals(element.name())
                    ? redirected(element) : element;
            changed |= replacement != element;
            result.add(replacement);
        }
        return changed ? document(result) : document;
    }

    private static Element redirected(Element element) {
        return redirects(stringValue(element)) ? element.withString(AUTH_DATABASE) : element;
    }

    private static boolean redirects(String database) {
        return !AUTH_DATABASE.equals(database) && !EXTERNAL_DATABASE.equals(database);
    }

    private record Element(int type, String name, byte[] value) {

        Element withString(String text) {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            ByteBuffer value = ByteBuffer.allocate(4 + bytes.length + 1).order(ByteOrder.LITTLE_ENDIAN);
            value.putInt(bytes.length + 1).put(bytes).put((byte) 0);
            return new Element(type, name, value.array());
        }
    }

    /** The top-level elements of a document, or null when it holds a type this reader does not know. */
    private static List<Element> elements(byte[] document) {
        int end = littleEndianInt(document, 0) - 1;
        List<Element> elements = new ArrayList<>();
        int position = 4;
        while (position < end) {
            int type = document[position++] & 0xFF;
            int nameEnd = position;
            while (document[nameEnd] != 0) {
                nameEnd++;
            }
            String name = new String(document, position, nameEnd - position, StandardCharsets.UTF_8);
            position = nameEnd + 1;
            int size = valueSize(type, document, position);
            if (size < 0) {
                return null;
            }
            elements.add(new Element(type, name, slice(document, position, size)));
            position += size;
        }
        return elements;
    }

    private static byte[] document(List<Element> elements) {
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        for (Element element : elements) {
            content.write(element.type());
            content.writeBytes(element.name().getBytes(StandardCharsets.UTF_8));
            content.write(0);
            content.writeBytes(element.value());
        }
        byte[] bytes = content.toByteArray();
        ByteBuffer document = ByteBuffer.allocate(4 + bytes.length + 1).order(ByteOrder.LITTLE_ENDIAN);
        document.putInt(4 + bytes.length + 1).put(bytes).put((byte) 0);
        return document.array();
    }

    private static int valueSize(int type, byte[] document, int position) {
        return switch (type) {
            case 0x01, 0x09, 0x11, 0x12 -> 8;
            case 0x02, 0x0D, 0x0E -> 4 + littleEndianInt(document, position);
            case 0x03, 0x04, 0x0F -> littleEndianInt(document, position);
            case 0x05 -> 5 + littleEndianInt(document, position);
            case 0x06, 0x0A, 0x7F, 0xFF -> 0;
            case 0x07 -> 12;
            case 0x08 -> 1;
            case 0x0B -> regexSize(document, position);
            case 0x0C -> 4 + littleEndianInt(document, position) + 12;
            case 0x10 -> 4;
            case 0x13 -> 16;
            default -> -1;
        };
    }

    private static int regexSize(byte[] document, int position) {
        int end = position;
        for (int strings = 0; strings < 2; strings++) {
            while (document[end] != 0) {
                end++;
            }
            end++;
        }
        return end - position;
    }

    private static String firstKey(byte[] document) {
        List<Element> elements = elements(document);
        return elements == null || elements.isEmpty() ? "" : elements.getFirst().name();
    }

    private static String stringValue(Element element) {
        int length = littleEndianInt(element.value(), 0);
        return new String(element.value(), 4, Math.max(0, length - 1), StandardCharsets.UTF_8);
    }

    private static byte[] withLength(byte[] message) {
        ByteBuffer.wrap(message).order(ByteOrder.LITTLE_ENDIAN).putInt(0, message.length);
        return message;
    }

    private static byte[] slice(byte[] bytes, int start, int length) {
        if (start < 0 || length < 0 || start + length > bytes.length) {
            throw new IndexOutOfBoundsException("BSON value runs past its message");
        }
        byte[] copy = new byte[length];
        System.arraycopy(bytes, start, copy, 0, length);
        return copy;
    }

    private static int littleEndianInt(byte[] bytes, int offset) {
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(offset);
    }
}

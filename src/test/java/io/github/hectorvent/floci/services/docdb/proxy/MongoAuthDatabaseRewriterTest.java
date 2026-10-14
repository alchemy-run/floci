package io.github.hectorvent.floci.services.docdb.proxy;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class MongoAuthDatabaseRewriterTest {

    private static final byte[] PAYLOAD = "n,,n=alchemy,r=abc".getBytes(StandardCharsets.US_ASCII);

    @Test
    void saslStartAndSaslContinueAuthenticateAgainstAdmin() {
        for (String command : new String[] {"saslStart", "saslContinue"}) {
            byte[] body = new Bson().int32(command, 1).string("mechanism", "SCRAM-SHA-256")
                    .binary("payload", PAYLOAD).string("$db", "alchemy_test").build();

            byte[] rewritten = MongoAuthDatabaseRewriter.rewrite(opMsg(7, body));

            Map<String, Object> fields = topLevel(opMsgBody(rewritten));
            assertEquals("admin", fields.get("$db"));
            assertEquals("SCRAM-SHA-256", fields.get("mechanism"));
            assertArrayEquals(PAYLOAD, (byte[]) fields.get("payload"));
            assertEquals(rewritten.length, littleEndianInt(rewritten, 0));
            assertEquals(7, littleEndianInt(rewritten, 4), "the request id is kept");
        }
    }

    @Test
    void otherCommandsAndExternalAuthenticationPassThroughUnchanged() {
        byte[] find = opMsg(1, new Bson().string("find", "orders").string("$db", "alchemy_test").build());
        assertSame(find, MongoAuthDatabaseRewriter.rewrite(find));

        byte[] external = opMsg(2, new Bson().int32("saslStart", 1).string("mechanism", "MONGODB-AWS")
                .string("$db", "$external").build());
        assertSame(external, MongoAuthDatabaseRewriter.rewrite(external));

        byte[] admin = opMsg(3, new Bson().int32("saslStart", 1).string("$db", "admin").build());
        assertSame(admin, MongoAuthDatabaseRewriter.rewrite(admin));
    }

    @Test
    void theHandshakeNamesTheAdminUserForMechanismsAndSpeculativeAuthentication() {
        byte[] speculative = new Bson().int32("saslStart", 1).string("mechanism", "SCRAM-SHA-256")
                .binary("payload", PAYLOAD).string("db", "alchemy_test").build();
        byte[] hello = new Bson().int32("hello", 1).string("saslSupportedMechs", "alchemy_test.alchemy")
                .document("speculativeAuthenticate", speculative).string("$db", "admin").build();

        Map<String, Object> fields = topLevel(opMsgBody(MongoAuthDatabaseRewriter.rewrite(opMsg(1, hello))));

        assertEquals("admin.alchemy", fields.get("saslSupportedMechs"));
        Map<String, Object> nested = topLevel((byte[]) fields.get("speculativeAuthenticate"));
        assertEquals("admin", nested.get("db"));
        assertArrayEquals(PAYLOAD, (byte[]) nested.get("payload"));
    }

    @Test
    void aLegacyOpQueryHandshakeAndItsSaslCommandsAreRedirectedToo() {
        byte[] isMaster = opQuery("admin.$cmd", new Bson().int32("isMaster", 1)
                .string("saslSupportedMechs", "alchemy_test.alchemy").build());
        byte[] rewritten = MongoAuthDatabaseRewriter.rewrite(isMaster);
        assertEquals("admin.$cmd", opQueryNamespace(rewritten));
        assertEquals("admin.alchemy", topLevel(opQueryDocument(rewritten)).get("saslSupportedMechs"));
        assertEquals(rewritten.length, littleEndianInt(rewritten, 0));

        byte[] saslStart = opQuery("alchemy_test.$cmd", new Bson().int32("saslStart", 1)
                .binary("payload", PAYLOAD).build());
        byte[] redirected = MongoAuthDatabaseRewriter.rewrite(saslStart);
        assertEquals("admin.$cmd", opQueryNamespace(redirected));
        assertArrayEquals(PAYLOAD, (byte[]) topLevel(opQueryDocument(redirected)).get("payload"));

        byte[] find = opQuery("alchemy_test.$cmd", new Bson().string("find", "orders").build());
        assertSame(find, MongoAuthDatabaseRewriter.rewrite(find));
    }

    @Test
    void aDocumentSequenceBeforeTheBodyIsKept() {
        byte[] body = new Bson().int32("saslContinue", 1).string("$db", "alchemy_test").build();
        byte[] sequence = sequenceSection("documents", new Bson().int32("a", 1).build());
        ByteArrayOutputStream sections = new ByteArrayOutputStream();
        sections.writeBytes(sequence);
        sections.write(0);
        sections.writeBytes(body);

        byte[] rewritten = MongoAuthDatabaseRewriter.rewrite(message(9, MongoAuthDatabaseRewriter.OP_MSG,
                concat(new byte[4], sections.toByteArray())));

        assertArrayEquals(sequence, Arrays.copyOfRange(rewritten, 20, 20 + sequence.length));
        byte[] rewrittenBody = Arrays.copyOfRange(rewritten, 21 + sequence.length, rewritten.length);
        assertEquals("admin", topLevel(rewrittenBody).get("$db"));
    }

    @Test
    void relayRewritesEachFramedMessageOfTheStream() throws IOException {
        byte[] first = opMsg(1, new Bson().int32("saslStart", 1).string("$db", "alchemy_test").build());
        byte[] second = opMsg(2, new Bson().string("find", "orders").string("$db", "alchemy_test").build());
        ByteArrayOutputStream backend = new ByteArrayOutputStream();

        MongoAuthDatabaseRewriter.relay(new ByteArrayInputStream(concat(first, second)), backend);

        byte[] relayed = backend.toByteArray();
        int firstLength = littleEndianInt(relayed, 0);
        assertEquals("admin", topLevel(opMsgBody(Arrays.copyOf(relayed, firstLength))).get("$db"));
        assertArrayEquals(second, Arrays.copyOfRange(relayed, firstLength, relayed.length));
    }

    @Test
    void aMalformedMessageIsPassedOnForTheBackendToRefuse() {
        byte[] truncated = message(1, MongoAuthDatabaseRewriter.OP_MSG, new byte[] {0, 0, 0, 0, 0, 50, 0});
        assertSame(truncated, MongoAuthDatabaseRewriter.rewrite(truncated));
    }

    // ── wire helpers shared with DocDbProxyTest ─────────────────────────────

    static final class Bson {
        private final ByteArrayOutputStream elements = new ByteArrayOutputStream();

        Bson int32(String name, int value) {
            element(0x10, name);
            elements.writeBytes(littleEndian(value));
            return this;
        }

        Bson string(String name, String value) {
            element(0x02, name);
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            elements.writeBytes(littleEndian(bytes.length + 1));
            elements.writeBytes(bytes);
            elements.write(0);
            return this;
        }

        Bson binary(String name, byte[] data) {
            element(0x05, name);
            elements.writeBytes(littleEndian(data.length));
            elements.write(0);
            elements.writeBytes(data);
            return this;
        }

        Bson document(String name, byte[] document) {
            element(0x03, name);
            elements.writeBytes(document);
            return this;
        }

        byte[] build() {
            byte[] content = elements.toByteArray();
            return concat(littleEndian(4 + content.length + 1), content, new byte[] {0});
        }

        private void element(int type, String name) {
            elements.write(type);
            elements.writeBytes(name.getBytes(StandardCharsets.UTF_8));
            elements.write(0);
        }
    }

    static byte[] opMsg(int requestId, byte[] body) {
        return message(requestId, MongoAuthDatabaseRewriter.OP_MSG, concat(new byte[4], new byte[] {0}, body));
    }

    static byte[] opMsgBody(byte[] message) {
        return Arrays.copyOfRange(message, 21, message.length);
    }

    /** Top-level string, binary and document fields of a BSON document, by name. */
    static Map<String, Object> topLevel(byte[] document) {
        Map<String, Object> fields = new LinkedHashMap<>();
        int position = 4;
        int end = littleEndianInt(document, 0) - 1;
        while (position < end) {
            int type = document[position++] & 0xFF;
            int nameEnd = position;
            while (document[nameEnd] != 0) {
                nameEnd++;
            }
            String name = new String(document, position, nameEnd - position, StandardCharsets.UTF_8);
            position = nameEnd + 1;
            int length = littleEndianInt(document, position);
            switch (type) {
                case 0x02 -> {
                    fields.put(name, new String(document, position + 4, length - 1, StandardCharsets.UTF_8));
                    position += 4 + length;
                }
                case 0x05 -> {
                    fields.put(name, Arrays.copyOfRange(document, position + 5, position + 5 + length));
                    position += 5 + length;
                }
                case 0x03 -> {
                    fields.put(name, Arrays.copyOfRange(document, position, position + length));
                    position += length;
                }
                case 0x10 -> {
                    fields.put(name, length);
                    position += 4;
                }
                default -> throw new IllegalArgumentException("type " + type);
            }
        }
        return fields;
    }

    private static byte[] opQuery(String namespace, byte[] query) {
        byte[] name = namespace.getBytes(StandardCharsets.UTF_8);
        return message(1, MongoAuthDatabaseRewriter.OP_QUERY,
                concat(new byte[4], name, new byte[] {0}, littleEndian(0), littleEndian(-1), query));
    }

    private static String opQueryNamespace(byte[] message) {
        int end = 20;
        while (message[end] != 0) {
            end++;
        }
        return new String(message, 20, end - 20, StandardCharsets.UTF_8);
    }

    private static byte[] opQueryDocument(byte[] message) {
        int start = opQueryNamespace(message).length() + 21 + 8;
        return Arrays.copyOfRange(message, start, start + littleEndianInt(message, start));
    }

    private static byte[] sequenceSection(String identifier, byte[] document) {
        byte[] name = identifier.getBytes(StandardCharsets.UTF_8);
        int size = 4 + name.length + 1 + document.length;
        return concat(new byte[] {1}, littleEndian(size), name, new byte[] {0}, document);
    }

    private static byte[] message(int requestId, int opCode, byte[] payload) {
        int length = 16 + payload.length;
        return concat(littleEndian(length), littleEndian(requestId), littleEndian(0), littleEndian(opCode), payload);
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    static int littleEndianInt(byte[] bytes, int offset) {
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(offset);
    }

    private static byte[] littleEndian(int value) {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();
    }
}

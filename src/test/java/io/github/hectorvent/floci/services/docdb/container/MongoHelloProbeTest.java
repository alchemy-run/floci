package io.github.hectorvent.floci.services.docdb.container;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MongoHelloProbeTest {

    @Test
    void helloRequestIsAnOpMsgCarryingHelloAgainstAdmin() {
        byte[] request = MongoHelloProbe.helloRequest(7);
        ByteBuffer buffer = ByteBuffer.wrap(request).order(ByteOrder.LITTLE_ENDIAN);

        assertEquals(request.length, buffer.getInt(0));
        assertEquals(7, buffer.getInt(4));
        assertEquals(0, buffer.getInt(8));
        assertEquals(MongoHelloProbe.OP_MSG, buffer.getInt(12));
        assertEquals(0, buffer.getInt(16), "flag bits");
        assertEquals(0, request[20], "a body section");
        int documentLength = buffer.getInt(21);
        assertEquals(request.length - 21, documentLength);
        String body = new String(request, 25, documentLength - 4, StandardCharsets.ISO_8859_1);
        assertTrue(body.contains("hello\0"), body);
        assertTrue(body.contains("$db\0"), body);
        assertTrue(body.contains("admin\0"), body);
    }

    @Test
    void readsIsWritablePrimaryPastOtherFieldsOfTheReply() {
        assertTrue(MongoHelloProbe.isWritablePrimary(helloReply(true)));
        assertFalse(MongoHelloProbe.isWritablePrimary(helloReply(false)));
    }

    @Test
    void aReplyThatIsNotAnOpMsgIsNotAPrimary() {
        byte[] reply = helloReply(true);
        ByteBuffer.wrap(reply).order(ByteOrder.LITTLE_ENDIAN).putInt(12, 1);
        assertFalse(MongoHelloProbe.isWritablePrimary(reply));
    }

    @Test
    void readReplyReadsExactlyOneFramedMessage() throws IOException {
        byte[] reply = helloReply(true);
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        stream.writeBytes(reply);
        stream.writeBytes(new byte[] {1, 2, 3});

        assertArrayEquals(reply, MongoHelloProbe.readReply(new ByteArrayInputStream(stream.toByteArray())));
        byte[] tooShort = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(3).array();
        assertThrows(IOException.class, () -> MongoHelloProbe.readReply(new ByteArrayInputStream(tooShort)));
    }

    /**
     * An {@code OP_MSG} reply shaped like mongod's answer to {@code hello} from a replica set
     * member: nested documents, arrays, strings and a double around the flag the probe reads.
     */
    static byte[] helloReply(boolean writablePrimary) {
        ByteArrayOutputStream hosts = new ByteArrayOutputStream();
        hosts.write(0x02);
        cString(hosts, "0");
        string(hosts, "docs.cluster-abcdefghijkl.us-east-1.docdb.localhost.floci.io:27017");
        byte[] hostsDocument = document(hosts);

        ByteArrayOutputStream topologyVersion = new ByteArrayOutputStream();
        topologyVersion.write(0x07);
        cString(topologyVersion, "processId");
        topologyVersion.writeBytes(new byte[12]);
        topologyVersion.write(0x12);
        cString(topologyVersion, "counter");
        topologyVersion.writeBytes(new byte[8]);
        byte[] topologyDocument = document(topologyVersion);

        ByteArrayOutputStream elements = new ByteArrayOutputStream();
        elements.write(0x04);
        cString(elements, "hosts");
        elements.writeBytes(hostsDocument);
        elements.write(0x02);
        cString(elements, "setName");
        string(elements, "rs0");
        elements.write(0x03);
        cString(elements, "topologyVersion");
        elements.writeBytes(topologyDocument);
        elements.write(0x10);
        cString(elements, "maxWireVersion");
        elements.writeBytes(littleEndian(21));
        elements.write(0x09);
        cString(elements, "localTime");
        elements.writeBytes(new byte[8]);
        elements.write(0x08);
        cString(elements, "isWritablePrimary");
        elements.write(writablePrimary ? 1 : 0);
        elements.write(0x08);
        cString(elements, "secondary");
        elements.write(writablePrimary ? 0 : 1);
        elements.write(0x01);
        cString(elements, "ok");
        elements.writeBytes(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(1.0).array());
        byte[] body = document(elements);

        int length = 16 + 4 + 1 + body.length;
        return ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(length).putInt(2).putInt(1).putInt(MongoHelloProbe.OP_MSG)
                .putInt(0).put((byte) 0).put(body).array();
    }

    private static byte[] document(ByteArrayOutputStream elements) {
        byte[] content = elements.toByteArray();
        ByteArrayOutputStream document = new ByteArrayOutputStream();
        document.writeBytes(littleEndian(4 + content.length + 1));
        document.writeBytes(content);
        document.write(0);
        return document.toByteArray();
    }

    private static void cString(ByteArrayOutputStream out, String value) {
        out.writeBytes(value.getBytes(StandardCharsets.UTF_8));
        out.write(0);
    }

    private static void string(ByteArrayOutputStream out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeBytes(littleEndian(bytes.length + 1));
        out.writeBytes(bytes);
        out.write(0);
    }

    private static byte[] littleEndian(int value) {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();
    }
}

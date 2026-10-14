package io.github.hectorvent.floci.services.msk;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The few Kafka admin requests the MSK topic APIs need, sent to a cluster's broker over its
 * plaintext listener: {@code Metadata} v4 to read topics and partitions, {@code CreateTopics} v2
 * and {@code DeleteTopics} v1. These are non-flexible protocol versions every Kafka-compatible
 * broker still serves, so Floci needs no Kafka client library.
 */
final class KafkaTopicClient {

    static final short API_METADATA = 3;
    static final short API_CREATE_TOPICS = 19;
    static final short API_DELETE_TOPICS = 20;
    static final short METADATA_VERSION = 4;
    static final short CREATE_TOPICS_VERSION = 2;
    static final short DELETE_TOPICS_VERSION = 1;

    static final short ERROR_NONE = 0;
    static final short ERROR_UNKNOWN_TOPIC_OR_PARTITION = 3;
    static final short ERROR_REQUEST_TIMED_OUT = 7;
    static final short ERROR_INVALID_TOPIC = 17;
    static final short ERROR_TOPIC_ALREADY_EXISTS = 36;
    static final short ERROR_INVALID_PARTITIONS = 37;
    static final short ERROR_INVALID_REPLICATION_FACTOR = 38;
    static final short ERROR_INVALID_CONFIG = 40;
    static final short ERROR_NOT_CONTROLLER = 41;
    static final short ERROR_INVALID_REQUEST = 42;
    static final short ERROR_POLICY_VIOLATION = 44;

    private static final String CLIENT_ID = "floci-msk";
    private static final int CONNECT_TIMEOUT_MILLIS = 5_000;
    private static final int READ_TIMEOUT_MILLIS = 30_000;
    private static final int REQUEST_TIMEOUT_MILLIS = 15_000;
    private static final int MAX_RESPONSE_BYTES = 64 * 1024 * 1024;

    record Partition(int partition, int leader, List<Integer> replicas, List<Integer> isr) {}

    record Topic(String name, short errorCode, boolean internal, List<Partition> partitions) {
        int replicationFactor() {
            return partitions.isEmpty() ? 0 : partitions.getFirst().replicas().size();
        }

        int outOfSyncReplicaCount() {
            int count = 0;
            for (Partition partition : partitions) {
                count += Math.max(0, partition.replicas().size() - partition.isr().size());
            }
            return count;
        }
    }

    /** A per-topic error code the broker answered with. */
    static final class KafkaErrorException extends Exception {
        private final short errorCode;

        KafkaErrorException(short errorCode, String message) {
            super(message);
            this.errorCode = errorCode;
        }

        short errorCode() {
            return errorCode;
        }
    }

    /** The broker could not be reached or answered with something that is not a Kafka response. */
    static final class BrokerUnavailableException extends Exception {
        BrokerUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private int correlationId;

    /** Every topic the broker knows, or only {@code names} when given, in the broker's order. */
    List<Topic> metadata(String bootstrap, List<String> names) throws BrokerUnavailableException {
        return parseMetadataResponse(exchange(bootstrap, API_METADATA, METADATA_VERSION, metadataRequest(names)));
    }

    void createTopic(String bootstrap, String name, int partitions, short replicationFactor,
                     Map<String, String> configs) throws BrokerUnavailableException, KafkaErrorException {
        byte[] response = exchange(bootstrap, API_CREATE_TOPICS, CREATE_TOPICS_VERSION,
                createTopicsRequest(name, partitions, replicationFactor, configs));
        throwOnError(parseCreateTopicsResponse(response), name);
    }

    void deleteTopic(String bootstrap, String name) throws BrokerUnavailableException, KafkaErrorException {
        byte[] response = exchange(bootstrap, API_DELETE_TOPICS, DELETE_TOPICS_VERSION, deleteTopicsRequest(name));
        throwOnError(parseDeleteTopicsResponse(response), name);
    }

    private static void throwOnError(Map<String, KafkaError> errors, String name) throws KafkaErrorException {
        KafkaError error = errors.get(name);
        if (error == null) {
            throw new KafkaErrorException(ERROR_INVALID_REQUEST, "The broker did not answer for topic " + name);
        }
        if (error.code() != ERROR_NONE) {
            throw new KafkaErrorException(error.code(), error.message() != null ? error.message()
                    : "Kafka error " + error.code() + " for topic " + name);
        }
    }

    record KafkaError(short code, String message) {}

    // ── requests ──────────────────────────────────────────────────────────────

    static byte[] metadataRequest(List<String> names) {
        return build(out -> {
            if (names == null) {
                out.writeInt(-1);
            } else {
                out.writeInt(names.size());
                for (String name : names) {
                    writeString(out, name);
                }
            }
            // Never create a topic just because it was looked up.
            out.writeBoolean(false);
        });
    }

    static byte[] createTopicsRequest(String name, int partitions, short replicationFactor,
                                      Map<String, String> configs) {
        return build(out -> {
            out.writeInt(1);
            writeString(out, name);
            out.writeInt(partitions);
            out.writeShort(replicationFactor);
            out.writeInt(0);
            out.writeInt(configs.size());
            for (Map.Entry<String, String> config : configs.entrySet()) {
                writeString(out, config.getKey());
                writeNullableString(out, config.getValue());
            }
            out.writeInt(REQUEST_TIMEOUT_MILLIS);
            out.writeBoolean(false);
        });
    }

    static byte[] deleteTopicsRequest(String name) {
        return build(out -> {
            out.writeInt(1);
            writeString(out, name);
            out.writeInt(REQUEST_TIMEOUT_MILLIS);
        });
    }

    static byte[] requestFrame(short apiKey, short apiVersion, int correlationId, byte[] body) {
        return build(out -> {
            out.writeShort(apiKey);
            out.writeShort(apiVersion);
            out.writeInt(correlationId);
            writeString(out, CLIENT_ID);
            out.write(body);
        });
    }

    // ── responses (body after the correlation id) ─────────────────────────────

    static List<Topic> parseMetadataResponse(byte[] body) {
        ByteBuffer in = ByteBuffer.wrap(body);
        in.getInt(); // throttle_time_ms
        int brokers = in.getInt();
        for (int i = 0; i < brokers; i++) {
            in.getInt();
            readString(in);
            in.getInt();
            readString(in);
        }
        readString(in); // cluster_id
        in.getInt(); // controller_id
        int topicCount = in.getInt();
        List<Topic> topics = new ArrayList<>(Math.max(topicCount, 0));
        for (int i = 0; i < topicCount; i++) {
            short errorCode = in.getShort();
            String name = readString(in);
            boolean internal = in.get() != 0;
            int partitionCount = in.getInt();
            List<Partition> partitions = new ArrayList<>(Math.max(partitionCount, 0));
            for (int p = 0; p < partitionCount; p++) {
                in.getShort(); // partition error_code
                int index = in.getInt();
                int leader = in.getInt();
                List<Integer> replicas = readInt32Array(in);
                List<Integer> isr = readInt32Array(in);
                partitions.add(new Partition(index, leader, replicas, isr));
            }
            partitions.sort((a, b) -> Integer.compare(a.partition(), b.partition()));
            topics.add(new Topic(name, errorCode, internal, List.copyOf(partitions)));
        }
        return topics;
    }

    static Map<String, KafkaError> parseCreateTopicsResponse(byte[] body) {
        ByteBuffer in = ByteBuffer.wrap(body);
        in.getInt(); // throttle_time_ms
        int count = in.getInt();
        Map<String, KafkaError> errors = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            String name = readString(in);
            short code = in.getShort();
            String message = readString(in);
            errors.put(name, new KafkaError(code, message));
        }
        return errors;
    }

    static Map<String, KafkaError> parseDeleteTopicsResponse(byte[] body) {
        ByteBuffer in = ByteBuffer.wrap(body);
        in.getInt(); // throttle_time_ms
        int count = in.getInt();
        Map<String, KafkaError> errors = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            String name = readString(in);
            errors.put(name, new KafkaError(in.getShort(), null));
        }
        return errors;
    }

    // ── transport ─────────────────────────────────────────────────────────────

    private byte[] exchange(String bootstrap, short apiKey, short apiVersion, byte[] body)
            throws BrokerUnavailableException {
        InetSocketAddress address = firstBroker(bootstrap);
        int id;
        synchronized (this) {
            id = ++correlationId;
        }
        try (Socket socket = new Socket()) {
            socket.connect(address, CONNECT_TIMEOUT_MILLIS);
            socket.setSoTimeout(READ_TIMEOUT_MILLIS);
            socket.setTcpNoDelay(true);
            OutputStream out = socket.getOutputStream();
            KafkaSaslServer.writeFrame(out, requestFrame(apiKey, apiVersion, id, body));
            return readResponse(socket.getInputStream(), id);
        } catch (IOException | RuntimeException e) {
            throw new BrokerUnavailableException("Kafka broker " + bootstrap + " did not answer: " + e.getMessage(), e);
        }
    }

    static byte[] readResponse(InputStream stream, int expectedCorrelationId) throws IOException {
        DataInputStream in = new DataInputStream(stream);
        int size = in.readInt();
        if (size < 4 || size > MAX_RESPONSE_BYTES) {
            throw new IOException("Invalid Kafka response size " + size);
        }
        byte[] frame = new byte[size];
        in.readFully(frame);
        int correlation = ByteBuffer.wrap(frame).getInt();
        if (correlation != expectedCorrelationId) {
            throw new IOException("Kafka response correlation id " + correlation + " does not match "
                    + expectedCorrelationId);
        }
        byte[] body = new byte[size - 4];
        System.arraycopy(frame, 4, body, 0, body.length);
        return body;
    }

    static InetSocketAddress firstBroker(String bootstrap) throws BrokerUnavailableException {
        if (bootstrap == null || bootstrap.isBlank()) {
            throw new BrokerUnavailableException("The cluster has no broker address", null);
        }
        String first = bootstrap.split(",")[0].trim();
        int colon = first.lastIndexOf(':');
        if (colon <= 0 || colon == first.length() - 1) {
            throw new BrokerUnavailableException("Invalid broker address " + first, null);
        }
        try {
            return new InetSocketAddress(first.substring(0, colon), Integer.parseInt(first.substring(colon + 1)));
        } catch (IllegalArgumentException e) {
            throw new BrokerUnavailableException("Invalid broker address " + first, e);
        }
    }

    // ── encoding helpers ──────────────────────────────────────────────────────

    @FunctionalInterface
    private interface Writer {
        void write(DataOutputStream out) throws IOException;
    }

    private static byte[] build(Writer writer) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            writer.write(out);
        } catch (IOException e) {
            throw new IllegalStateException("In-memory Kafka request encoding failed", e);
        }
        return bytes.toByteArray();
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeShort(bytes.length);
        out.write(bytes);
    }

    private static void writeNullableString(DataOutputStream out, String value) throws IOException {
        if (value == null) {
            out.writeShort(-1);
            return;
        }
        writeString(out, value);
    }

    private static String readString(ByteBuffer in) {
        short length = in.getShort();
        if (length < 0) {
            return null;
        }
        byte[] value = new byte[length];
        in.get(value);
        return new String(value, StandardCharsets.UTF_8);
    }

    private static List<Integer> readInt32Array(ByteBuffer in) {
        int count = in.getInt();
        List<Integer> values = new ArrayList<>(Math.max(count, 0));
        for (int i = 0; i < count; i++) {
            values.add(in.getInt());
        }
        return List.copyOf(values);
    }
}

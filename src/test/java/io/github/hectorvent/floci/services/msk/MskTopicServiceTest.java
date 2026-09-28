package io.github.hectorvent.floci.services.msk;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.services.msk.model.MskCluster;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The MSK topic APIs answered by the cluster's broker, against an in-process broker that speaks
 * the Kafka wire protocol for Metadata v4, CreateTopics v2 and DeleteTopics v1.
 */
class MskTopicServiceTest {

    private static final String CLUSTER_ARN =
            "arn:aws:kafka:us-east-1:000000000000:cluster/orders-cluster/2f1e8c1a-0000-0000-0000-000000000000-s1";

    private FakeBroker broker;
    private MskService mskService;
    private EmulatorConfig config;
    private MskTopicService topics;

    @BeforeEach
    void setUp() throws IOException {
        broker = new FakeBroker();
        MskCluster cluster = new MskCluster(CLUSTER_ARN, "orders-cluster", "3.6.0");
        cluster.setContainerId("redpanda-container");
        cluster.setBootstrapBrokers("127.0.0.1:" + broker.port());
        mskService = mock(MskService.class);
        when(mskService.describeCluster(CLUSTER_ARN)).thenReturn(cluster);
        when(mskService.describeCluster("arn:aws:kafka:us-east-1:000000000000:cluster/missing/x"))
                .thenThrow(new AwsException("NotFoundException", "Cluster not found", 404));
        config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().msk().mock()).thenReturn(false);
        topics = new MskTopicService(mskService, config, new ObjectMapper());
    }

    @AfterEach
    void tearDown() throws IOException {
        broker.close();
    }

    @Test
    void listTopicsReadsTheBrokerAndLeavesOutInternalTopics() {
        broker.topics.put("orders", 3);
        broker.topics.put("payments", 1);

        PaginatedResult<MskTopicService.TopicView> page = topics.listTopics(CLUSTER_ARN, null, null, null);

        assertEquals(List.of("orders", "payments"), page.items().stream().map(MskTopicService.TopicView::topicName).toList());
        MskTopicService.TopicView orders = page.items().getFirst();
        assertEquals(3, orders.partitionCount());
        assertEquals(1, orders.replicationFactor());
        assertEquals(0, orders.outOfSyncReplicaCount());
        assertEquals("arn:aws:kafka:us-east-1:000000000000:topic/orders-cluster/"
                + "2f1e8c1a-0000-0000-0000-000000000000-s1/orders", orders.topicArn());
    }

    @Test
    void listTopicsFiltersByNamePrefixAndPaginates() {
        broker.topics.put("orders", 1);
        broker.topics.put("orders-dlq", 1);
        broker.topics.put("payments", 1);

        PaginatedResult<MskTopicService.TopicView> first = topics.listTopics(CLUSTER_ARN, 1, null, "orders");
        PaginatedResult<MskTopicService.TopicView> second = topics.listTopics(CLUSTER_ARN, 1, first.nextToken(), "orders");

        assertEquals("orders", first.items().getFirst().topicName());
        assertEquals("orders-dlq", second.items().getFirst().topicName());
        assertNull(second.nextToken());
    }

    @Test
    void createDescribeAndDeleteRoundTripThroughTheBroker() {
        MskTopicService.TopicView created = topics.createTopic(CLUSTER_ARN, "alchemy-probe", 1, 1, null);

        assertEquals("ACTIVE", created.status());
        assertEquals(1, broker.topics.get("alchemy-probe"));
        MskTopicService.TopicView described = topics.describeTopic(CLUSTER_ARN, "alchemy-probe");
        assertEquals(1, described.partitionCount());
        assertEquals(1, described.replicationFactor());

        MskTopicService.TopicView deleted = topics.deleteTopic(CLUSTER_ARN, "alchemy-probe");
        assertEquals("DELETING", deleted.status());
        assertTrue(broker.topics.isEmpty());
        AwsException missing = assertThrows(AwsException.class,
                () -> topics.describeTopic(CLUSTER_ARN, "alchemy-probe"));
        assertEquals("NotFoundException", missing.getErrorCode());
        assertEquals(404, missing.getHttpStatus());
    }

    @Test
    void creatingAnExistingTopicIsTopicExists() {
        broker.topics.put("orders", 1);

        AwsException exists = assertThrows(AwsException.class,
                () -> topics.createTopic(CLUSTER_ARN, "orders", 1, 1, null));

        assertEquals("TopicExistsException", exists.getErrorCode());
        assertEquals(409, exists.getHttpStatus());
    }

    @Test
    void deletingAMissingTopicIsNotFound() {
        AwsException missing = assertThrows(AwsException.class,
                () -> topics.deleteTopic(CLUSTER_ARN, "nope"));

        assertEquals("NotFoundException", missing.getErrorCode());
    }

    @Test
    void topicConfigsAreSentToTheBroker() {
        String configs = Base64.getEncoder().encodeToString(
                "{\"cleanup.policy\":\"compact\",\"retention.ms\":86400000}".getBytes(StandardCharsets.UTF_8));

        topics.createTopic(CLUSTER_ARN, "compacted", 2, 1, configs);

        assertEquals(Map.of("cleanup.policy", "compact", "retention.ms", "86400000"),
                broker.lastConfigs);
    }

    @Test
    void malformedConfigsAreABadRequest() {
        AwsException invalid = assertThrows(AwsException.class,
                () -> topics.createTopic(CLUSTER_ARN, "compacted", 1, 1, "!!not-base64!!"));

        assertEquals("BadRequestException", invalid.getErrorCode());
    }

    @Test
    void anUnreachableBrokerIsServiceUnavailableForReadsAndClusterConnectivityForWrites() throws IOException {
        broker.close();

        AwsException read = assertThrows(AwsException.class, () -> topics.listTopics(CLUSTER_ARN, null, null, null));
        AwsException write = assertThrows(AwsException.class,
                () -> topics.createTopic(CLUSTER_ARN, "orders", 1, 1, null));

        assertEquals("ServiceUnavailableException", read.getErrorCode());
        assertEquals("ClusterConnectivityException", write.getErrorCode());
    }

    @Test
    void aClusterWithoutABrokerDoesNotFabricateTopics() {
        when(config.services().msk().mock()).thenReturn(true);

        AwsException unavailable = assertThrows(AwsException.class,
                () -> topics.listTopics(CLUSTER_ARN, null, null, null));

        assertEquals("UnsupportedOperationException", unavailable.getErrorCode());
        assertEquals(501, unavailable.getHttpStatus());
    }

    @Test
    void aMissingClusterIsNotFound() {
        AwsException missing = assertThrows(AwsException.class,
                () -> topics.listTopics("arn:aws:kafka:us-east-1:000000000000:cluster/missing/x", null, null, null));

        assertEquals("NotFoundException", missing.getErrorCode());
    }

    /** Serves Metadata v4, CreateTopics v2 and DeleteTopics v1 over a topic map, one broker with id 0. */
    private static final class FakeBroker implements AutoCloseable {
        private final ServerSocket server;
        private final Map<String, Integer> topics = new ConcurrentHashMap<>();
        private final List<Socket> clients = new CopyOnWriteArrayList<>();
        private volatile Map<String, String> lastConfigs = Map.of();

        FakeBroker() throws IOException {
            server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            Thread.ofVirtual().start(this::acceptLoop);
        }

        int port() {
            return server.getLocalPort();
        }

        private void acceptLoop() {
            while (!server.isClosed()) {
                try {
                    Socket client = server.accept();
                    clients.add(client);
                    Thread.ofVirtual().start(() -> serve(client));
                } catch (IOException expected) {
                    // The server socket was closed by the test.
                    return;
                }
            }
        }

        private void serve(Socket client) {
            try (client) {
                DataInputStream in = new DataInputStream(client.getInputStream());
                int size = in.readInt();
                byte[] frame = new byte[size];
                in.readFully(frame);
                ByteBuffer request = ByteBuffer.wrap(frame);
                short apiKey = request.getShort();
                short apiVersion = request.getShort();
                int correlationId = request.getInt();
                readString(request);
                byte[] body = switch (apiKey) {
                    case KafkaTopicClient.API_METADATA -> {
                        assertEquals(KafkaTopicClient.METADATA_VERSION, apiVersion);
                        yield metadata(request);
                    }
                    case KafkaTopicClient.API_CREATE_TOPICS -> {
                        assertEquals(KafkaTopicClient.CREATE_TOPICS_VERSION, apiVersion);
                        yield createTopics(request);
                    }
                    case KafkaTopicClient.API_DELETE_TOPICS -> {
                        assertEquals(KafkaTopicClient.DELETE_TOPICS_VERSION, apiVersion);
                        yield deleteTopics(request);
                    }
                    default -> throw new IllegalStateException("Unexpected API key " + apiKey);
                };
                DataOutputStream out = new DataOutputStream(client.getOutputStream());
                out.writeInt(body.length + 4);
                out.writeInt(correlationId);
                out.write(body);
                out.flush();
            } catch (IOException expected) {
                // The client went away; each exchange is one request on its own connection.
            }
        }

        private byte[] metadata(ByteBuffer request) throws IOException {
            int count = request.getInt();
            List<String> requested = null;
            if (count >= 0) {
                requested = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                    requested.add(readString(request));
                }
            }
            assertEquals(0, request.get(), "lookups must never auto-create topics");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(0);
            out.writeInt(1);
            out.writeInt(0);
            writeString(out, "localhost");
            out.writeInt(port());
            out.writeShort(-1);
            writeString(out, "cluster");
            out.writeInt(0);
            List<String> names = new ArrayList<>();
            if (requested == null) {
                names.addAll(topics.keySet().stream().sorted().toList());
                names.add("__consumer_offsets");
            } else {
                names.addAll(requested);
            }
            out.writeInt(names.size());
            for (String name : names) {
                boolean internal = name.startsWith("__");
                Integer partitions = internal ? Integer.valueOf(1) : topics.get(name);
                out.writeShort(partitions == null ? KafkaTopicClient.ERROR_UNKNOWN_TOPIC_OR_PARTITION : 0);
                writeString(out, name);
                out.writeBoolean(internal);
                int partitionCount = partitions == null ? 0 : partitions;
                out.writeInt(partitionCount);
                for (int p = partitionCount - 1; p >= 0; p--) {
                    out.writeShort(0);
                    out.writeInt(p);
                    out.writeInt(0);
                    out.writeInt(1);
                    out.writeInt(0);
                    out.writeInt(1);
                    out.writeInt(0);
                }
            }
            return bytes.toByteArray();
        }

        private byte[] createTopics(ByteBuffer request) throws IOException {
            assertEquals(1, request.getInt());
            String name = readString(request);
            int partitions = request.getInt();
            request.getShort();
            assertEquals(0, request.getInt());
            int configCount = request.getInt();
            Map<String, String> configs = new TreeMap<>();
            for (int i = 0; i < configCount; i++) {
                configs.put(readString(request), readString(request));
            }
            lastConfigs = Map.copyOf(configs);
            short error = topics.putIfAbsent(name, partitions) == null ? 0 : KafkaTopicClient.ERROR_TOPIC_ALREADY_EXISTS;
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(0);
            out.writeInt(1);
            writeString(out, name);
            out.writeShort(error);
            if (error == 0) {
                out.writeShort(-1);
            } else {
                writeString(out, "Topic '" + name + "' already exists.");
            }
            return bytes.toByteArray();
        }

        private byte[] deleteTopics(ByteBuffer request) throws IOException {
            assertEquals(1, request.getInt());
            String name = readString(request);
            short error = topics.remove(name) != null ? 0 : KafkaTopicClient.ERROR_UNKNOWN_TOPIC_OR_PARTITION;
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(0);
            out.writeInt(1);
            writeString(out, name);
            out.writeShort(error);
            return bytes.toByteArray();
        }

        private static String readString(ByteBuffer buffer) {
            short length = buffer.getShort();
            if (length < 0) {
                return null;
            }
            byte[] value = new byte[length];
            buffer.get(value);
            return new String(value, StandardCharsets.UTF_8);
        }

        private static void writeString(DataOutputStream out, String value) throws IOException {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            out.writeShort(bytes.length);
            out.write(bytes);
        }

        @Override
        public void close() throws IOException {
            server.close();
            for (Socket client : clients) {
                client.close();
            }
        }
    }
}

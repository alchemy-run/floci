package io.github.hectorvent.floci.services.msk;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.services.msk.model.MskCluster;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * The MSK topic APIs (ListTopics, DescribeTopic, DescribeTopicPartitions, CreateTopic,
 * DeleteTopic), answered by the cluster's own Kafka broker. Nothing is kept on the control plane:
 * a topic exists exactly when the broker has it, whether it was created here or by a Kafka client.
 */
@ApplicationScoped
public class MskTopicService {

    private static final Logger LOG = Logger.getLogger(MskTopicService.class);
    static final String ACTIVE = "ACTIVE";
    static final String CREATING = "CREATING";
    static final String DELETING = "DELETING";
    private static final int DEFAULT_PAGE_SIZE = 100;
    private static final int VISIBILITY_ATTEMPTS = 50;
    private static final long VISIBILITY_INTERVAL_MILLIS = 100;

    private final MskService mskService;
    private final EmulatorConfig config;
    private final ObjectMapper objectMapper;
    private final KafkaTopicClient client;

    /** A topic as the MSK topic APIs describe it. */
    public record TopicView(String topicArn, String topicName, int partitionCount, int replicationFactor,
                            int outOfSyncReplicaCount, String status, List<KafkaTopicClient.Partition> partitions) {}

    @Inject
    public MskTopicService(MskService mskService, EmulatorConfig config, ObjectMapper objectMapper) {
        this(mskService, config, objectMapper, new KafkaTopicClient());
    }

    MskTopicService(MskService mskService, EmulatorConfig config, ObjectMapper objectMapper,
                    KafkaTopicClient client) {
        this.mskService = mskService;
        this.config = config;
        this.objectMapper = objectMapper;
        this.client = client;
    }

    public PaginatedResult<TopicView> listTopics(String clusterArn, Integer maxResults, String nextToken,
                                                 String topicNameFilter) {
        MskCluster cluster = mskService.describeCluster(clusterArn);
        List<TopicView> topics = readTopics(cluster, null).stream()
                .filter(topic -> !topic.internal() && topic.errorCode() == KafkaTopicClient.ERROR_NONE)
                .filter(topic -> topicNameFilter == null || topicNameFilter.isEmpty()
                        || topic.name().startsWith(topicNameFilter))
                .map(topic -> view(cluster, topic, ACTIVE))
                .toList();
        return Pagination.paginate(topics, TopicView::topicName, maxResults, nextToken,
                DEFAULT_PAGE_SIZE, "BadRequestException");
    }

    public TopicView describeTopic(String clusterArn, String topicName) {
        MskCluster cluster = mskService.describeCluster(clusterArn);
        return view(cluster, requireTopic(cluster, topicName), ACTIVE);
    }

    public PaginatedResult<KafkaTopicClient.Partition> describeTopicPartitions(String clusterArn, String topicName,
                                                                               Integer maxResults, String nextToken) {
        MskCluster cluster = mskService.describeCluster(clusterArn);
        KafkaTopicClient.Topic topic = requireTopic(cluster, topicName);
        return Pagination.paginate(topic.partitions(), partition -> String.format("%010d", partition.partition()),
                maxResults, nextToken, DEFAULT_PAGE_SIZE, "BadRequestException");
    }

    public TopicView createTopic(String clusterArn, String topicName, int partitionCount, int replicationFactor,
                                 String configs) {
        MskCluster cluster = mskService.describeCluster(clusterArn);
        String bootstrap = brokerOf(cluster);
        if (replicationFactor > Short.MAX_VALUE) {
            throw new AwsException("BadRequestException", "replicationFactor is too large.", 400,
                    Map.of("invalidParameter", "replicationFactor"));
        }
        Map<String, String> topicConfigs = decodeConfigs(configs);
        try {
            client.createTopic(bootstrap, topicName, partitionCount, (short) replicationFactor, topicConfigs);
        } catch (KafkaTopicClient.BrokerUnavailableException e) {
            throw clusterConnectivity(cluster, e);
        } catch (KafkaTopicClient.KafkaErrorException e) {
            throw kafkaError(e, topicName);
        }
        KafkaTopicClient.Topic created = awaitVisible(bootstrap, topicName);
        String status = created != null ? ACTIVE : CREATING;
        return new TopicView(topicArn(cluster, topicName), topicName, partitionCount, replicationFactor, 0,
                status, created != null ? created.partitions() : List.of());
    }

    public TopicView deleteTopic(String clusterArn, String topicName) {
        MskCluster cluster = mskService.describeCluster(clusterArn);
        String bootstrap = brokerOf(cluster);
        try {
            client.deleteTopic(bootstrap, topicName);
        } catch (KafkaTopicClient.BrokerUnavailableException e) {
            throw clusterConnectivity(cluster, e);
        } catch (KafkaTopicClient.KafkaErrorException e) {
            throw kafkaError(e, topicName);
        }
        return new TopicView(topicArn(cluster, topicName), topicName, 0, 0, 0, DELETING, List.of());
    }

    /**
     * Waits briefly for a created topic to show up in the broker's metadata with a leader for every
     * partition, so a DescribeTopic right after CreateTopic finds it. Returns null when it has not
     * in that time; the create still happened.
     */
    private KafkaTopicClient.Topic awaitVisible(String bootstrap, String topicName) {
        for (int attempt = 0; attempt < VISIBILITY_ATTEMPTS; attempt++) {
            try {
                for (KafkaTopicClient.Topic topic : client.metadata(bootstrap, List.of(topicName))) {
                    if (topicName.equals(topic.name()) && topic.errorCode() == KafkaTopicClient.ERROR_NONE
                            && !topic.partitions().isEmpty()
                            && topic.partitions().stream().allMatch(partition -> partition.leader() >= 0)) {
                        return topic;
                    }
                }
            } catch (KafkaTopicClient.BrokerUnavailableException e) {
                LOG.debugv("Waiting for topic {0} to become visible: {1}", topicName, e.getMessage());
            }
            try {
                Thread.sleep(VISIBILITY_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    private KafkaTopicClient.Topic requireTopic(MskCluster cluster, String topicName) {
        for (KafkaTopicClient.Topic topic : readTopics(cluster, List.of(topicName))) {
            if (!topicName.equals(topic.name())) {
                continue;
            }
            if (topic.errorCode() == KafkaTopicClient.ERROR_NONE) {
                return topic;
            }
            if (topic.errorCode() != KafkaTopicClient.ERROR_UNKNOWN_TOPIC_OR_PARTITION) {
                throw new AwsException("ServiceUnavailableException", "The broker reported Kafka error "
                        + topic.errorCode() + " for topic " + topicName + ".", 503);
            }
        }
        throw new AwsException("NotFoundException", "Topic " + topicName + " not found.", 404);
    }

    private List<KafkaTopicClient.Topic> readTopics(MskCluster cluster, List<String> names) {
        String bootstrap = brokerOf(cluster);
        try {
            return client.metadata(bootstrap, names);
        } catch (KafkaTopicClient.BrokerUnavailableException e) {
            LOG.warnv("MSK cluster {0} broker is unreachable: {1}", cluster.getClusterName(), e.getMessage());
            throw new AwsException("ServiceUnavailableException",
                    "The cluster's brokers are not reachable.", 503);
        }
    }

    /** The broker's plaintext listener; a cluster without a broker cannot answer for its topics. */
    private String brokerOf(MskCluster cluster) {
        if (config.services().msk().mock() || cluster.getContainerId() == null) {
            // Topic metadata must come from the broker, not an independent control-plane store.
            throw new AwsException("UnsupportedOperationException",
                    "MSK topic control-plane operations need the cluster's Kafka broker, which is not running.",
                    501);
        }
        return cluster.getBootstrapBrokers();
    }

    private static TopicView view(MskCluster cluster, KafkaTopicClient.Topic topic, String status) {
        return new TopicView(topicArn(cluster, topic.name()), topic.name(), topic.partitions().size(),
                topic.replicationFactor(), topic.outOfSyncReplicaCount(), status, topic.partitions());
    }

    /** Topics live in the cluster's {@code topic/} ARN namespace: {@code ...:topic/<cluster>/<uuid>/<topic>}. */
    static String topicArn(MskCluster cluster, String topicName) {
        return cluster.getClusterArn().replace(":cluster/", ":topic/") + "/" + topicName;
    }

    private AwsException clusterConnectivity(MskCluster cluster, Exception cause) {
        LOG.warnv("MSK cluster {0} broker is unreachable: {1}", cluster.getClusterName(), cause.getMessage());
        return new AwsException("ClusterConnectivityException", "The cluster's brokers are not reachable.", 409);
    }

    static AwsException kafkaError(KafkaTopicClient.KafkaErrorException e, String topicName) {
        return switch (e.errorCode()) {
            case KafkaTopicClient.ERROR_TOPIC_ALREADY_EXISTS ->
                    new AwsException("TopicExistsException", "Topic " + topicName + " already exists.", 409);
            case KafkaTopicClient.ERROR_UNKNOWN_TOPIC_OR_PARTITION ->
                    new AwsException("NotFoundException", "Topic " + topicName + " not found.", 404);
            case KafkaTopicClient.ERROR_INVALID_TOPIC, KafkaTopicClient.ERROR_INVALID_PARTITIONS,
                 KafkaTopicClient.ERROR_INVALID_REPLICATION_FACTOR, KafkaTopicClient.ERROR_INVALID_CONFIG,
                 KafkaTopicClient.ERROR_INVALID_REQUEST, KafkaTopicClient.ERROR_POLICY_VIOLATION ->
                    new AwsException("BadRequestException", e.getMessage(), 400);
            case KafkaTopicClient.ERROR_REQUEST_TIMED_OUT ->
                    new AwsException("KafkaTimeoutException", e.getMessage(), 409);
            case KafkaTopicClient.ERROR_NOT_CONTROLLER ->
                    new AwsException("NotControllerException", e.getMessage(), 409);
            default -> new AwsException("KafkaRequestException", e.getMessage(), 400);
        };
    }

    /**
     * CreateTopic's {@code configs} is Base64-encoded topic configuration: a JSON object of
     * config names to values, or {@code name=value} properties.
     */
    Map<String, String> decodeConfigs(String configs) {
        Map<String, String> decoded = new LinkedHashMap<>();
        if (configs == null || configs.isBlank()) {
            return decoded;
        }
        String text;
        try {
            text = new String(Base64.getDecoder().decode(configs.trim()), StandardCharsets.UTF_8).trim();
        } catch (IllegalArgumentException e) {
            throw invalidConfigs("configs must be Base64-encoded.");
        }
        if (text.startsWith("{")) {
            try {
                JsonNode node = objectMapper.readTree(text);
                Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    if (!field.getValue().isValueNode()) {
                        throw invalidConfigs("configs values must be strings, numbers or booleans.");
                    }
                    decoded.put(field.getKey(), field.getValue().asText());
                }
                return decoded;
            } catch (JsonProcessingException e) {
                throw invalidConfigs("configs is not a valid JSON object.");
            }
        }
        Properties properties = new Properties();
        try {
            properties.load(new StringReader(text));
        } catch (IOException | IllegalArgumentException e) {
            throw invalidConfigs("configs is not valid topic configuration.");
        }
        for (String name : properties.stringPropertyNames()) {
            decoded.put(name, properties.getProperty(name));
        }
        return decoded;
    }

    private static AwsException invalidConfigs(String message) {
        return new AwsException("BadRequestException", message, 400, Map.of("invalidParameter", "configs"));
    }
}

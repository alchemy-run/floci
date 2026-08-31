package io.github.hectorvent.floci.services.msk;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.msk.model.ClusterState;
import io.github.hectorvent.floci.services.msk.model.MskCluster;
import io.github.hectorvent.floci.services.msk.model.MskTopic;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class MskService {

    static final String SERVICE = "kafka";
    private static final Logger LOG = Logger.getLogger(MskService.class);
    private static final String DEFAULT_KAFKA_VERSION = "3.6.0";
    private static final String TYPE_SERVERLESS = "SERVERLESS";
    private static final String TYPE_PROVISIONED = "PROVISIONED";
    private final StorageBackend<String, MskCluster> storage;
    private final EmulatorConfig config;
    private final RegionResolver regionResolver;
    private final RedpandaManager redpandaManager;
    private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor();

    @Inject
    public MskService(StorageFactory storageFactory, EmulatorConfig config,
                      RegionResolver regionResolver, RedpandaManager redpandaManager) {
        this.storage = storageFactory.create("msk", "msk-clusters.json", new TypeReference<Map<String, MskCluster>>() {});
        this.config = config;
        this.regionResolver = regionResolver;
        this.redpandaManager = redpandaManager;
    }

    @PostConstruct
    public void init() {
        startReadinessPoller();
    }

    @PreDestroy
    public void shutdown() {
        poller.shutdown();
        if (!config.services().msk().mock()) {
            for (MskCluster cluster : allClusters()) {
                if (!TYPE_SERVERLESS.equals(cluster.getClusterType())) {
                    redpandaManager.stopContainer(cluster);
                }
            }
        }
    }

    public MskCluster createCluster(String clusterName) {
        return createCluster(clusterName, DEFAULT_KAFKA_VERSION);
    }

    public MskCluster createCluster(String clusterName, String kafkaVersion) {
        return persistNewCluster(clusterName, kafkaVersion, TYPE_PROVISIONED, Map.of(), List.of(), false);
    }

    public MskCluster createClusterV2(Map<String, Object> request) {
        String clusterName = text(request, "clusterName");
        if (clusterName == null || clusterName.isBlank()) {
            throw new AwsException("BadRequestException", "ClusterName is required.", 400);
        }
        Map<String, Object> serverless = child(request, "serverless");
        Map<String, Object> provisioned = child(request, "provisioned");
        boolean serverlessCluster = serverless != null;
        String clusterType = serverlessCluster ? TYPE_SERVERLESS : TYPE_PROVISIONED;
        String kafkaVersion = provisioned != null ? text(provisioned, "kafkaVersion") : null;
        Map<String, String> tags = stringMap(request.get("tags"));
        List<Map<String, Object>> vpcConfigs = serverlessCluster
                ? vpcConfigs(serverless.get("vpcConfigs"))
                : List.of();
        boolean iamEnabled = serverlessCluster && iamEnabled(serverless);
        return persistNewCluster(clusterName, kafkaVersion, clusterType, tags, vpcConfigs, iamEnabled);
    }

    private MskCluster persistNewCluster(String clusterName, String kafkaVersion, String clusterType,
                                         Map<String, String> tags, List<Map<String, Object>> vpcConfigs,
                                         boolean iamEnabled) {
        if (storage.scan(k -> true).stream().anyMatch(c -> c.getClusterName().equals(clusterName))) {
            throw new AwsException("ConflictException", "Cluster already exists: " + clusterName, 409);
        }

        String accountId = regionResolver.getAccountId();
        String clusterArn = AwsArnUtils.Arn.of("kafka", config.defaultRegion(), accountId,
                "cluster/" + clusterName + "/" + java.util.UUID.randomUUID()).toString();

        String resolvedKafkaVersion = (kafkaVersion == null || kafkaVersion.isBlank())
                ? DEFAULT_KAFKA_VERSION : kafkaVersion;
        MskCluster cluster = new MskCluster(clusterArn, clusterName, resolvedKafkaVersion);
        cluster.setAccountId(accountId);
        cluster.setVolumeId(String.format("%06x", new SecureRandom().nextInt(0xFFFFFF)));
        cluster.setClusterType(clusterType);
        cluster.setTags(tags);
        boolean serverless = TYPE_SERVERLESS.equals(clusterType);
        cluster.setIamAuthEnabled(iamEnabled || serverless);
        cluster.setVpcConfigs(vpcConfigs);
        if (serverless) {
            Map<String, Object> serverlessInfo = new LinkedHashMap<>();
            serverlessInfo.put("vpcConfigs", vpcConfigs);
            Map<String, Object> iam = new LinkedHashMap<>();
            iam.put("enabled", iamEnabled || true);
            Map<String, Object> sasl = new LinkedHashMap<>();
            sasl.put("iam", iam);
            Map<String, Object> auth = new LinkedHashMap<>();
            auth.put("sasl", sasl);
            serverlessInfo.put("clientAuthentication", auth);
            cluster.setServerless(serverlessInfo);
        }

        // Serverless has no broker nodes; skip Redpanda so CreateClusterV2
        // returns ACTIVE immediately (live AWS takes 5-10 minutes).
        if (serverless || config.services().msk().mock()) {
            cluster.setState(ClusterState.ACTIVE);
            if (serverless) {
                cluster.setBootstrapBrokers("localhost:9098");
                cluster.setBootstrapBrokerStringSaslIam("localhost:9098");
            } else {
                cluster.setBootstrapBrokers("localhost:9092");
            }
        } else {
            redpandaManager.startContainer(cluster);
        }

        storage.put(clusterArn, cluster);
        return cluster;
    }

    public MskCluster describeCluster(String clusterArn) {
        String arn = decode(clusterArn);
        return storage.get(arn)
                .orElseThrow(() -> new AwsException("NotFoundException", "Cluster not found: " + arn, 404));
    }

    public List<MskCluster> listClusters() {
        return listClusters(null, null);
    }

    public List<MskCluster> listClusters(String clusterNameFilter, String clusterTypeFilter) {
        List<MskCluster> clusters = new ArrayList<>();
        for (MskCluster cluster : storage.scan(k -> true)) {
            if (clusterNameFilter != null && !clusterNameFilter.isBlank()
                    && (cluster.getClusterName() == null
                    || !cluster.getClusterName().contains(clusterNameFilter))) {
                continue;
            }
            if (clusterTypeFilter != null && !clusterTypeFilter.isBlank()
                    && !clusterTypeFilter.equalsIgnoreCase(cluster.getClusterType())) {
                continue;
            }
            clusters.add(cluster);
        }
        return clusters;
    }

    public void deleteCluster(String clusterArn) {
        String arn = decode(clusterArn);
        MskCluster cluster = storage.get(arn)
                .orElseThrow(() -> new AwsException("NotFoundException", "Cluster not found: " + arn, 404));

        cluster.setState(ClusterState.DELETING);
        if (!config.services().msk().mock() && !TYPE_SERVERLESS.equals(cluster.getClusterType())) {
            redpandaManager.stopContainer(cluster);
            redpandaManager.removeClusterStorage(cluster);
        }
        storage.delete(arn);
    }

    public String getBootstrapBrokers(String clusterArn) {
        MskCluster cluster = describeCluster(clusterArn);
        return cluster.getBootstrapBrokers();
    }

    public Map<String, Object> bootstrapBrokersResponse(String clusterArn) {
        MskCluster cluster = describeCluster(clusterArn);
        Map<String, Object> body = new LinkedHashMap<>();
        if (TYPE_SERVERLESS.equals(cluster.getClusterType())) {
            String brokers = cluster.getBootstrapBrokerStringSaslIam();
            if (brokers == null) {
                brokers = cluster.getBootstrapBrokers();
            }
            body.put("bootstrapBrokerStringSaslIam", brokers);
        } else {
            body.put("bootstrapBrokerString", cluster.getBootstrapBrokers());
        }
        return body;
    }

    public Map<String, Object> toClusterInfoV2(MskCluster cluster) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("clusterArn", cluster.getClusterArn());
        info.put("clusterName", cluster.getClusterName());
        info.put("clusterType", cluster.getClusterType());
        info.put("state", cluster.getState());
        if (cluster.getCreationTime() != null) {
            info.put("creationTime", cluster.getCreationTime().toString());
        }
        info.put("currentVersion", cluster.getCurrentVersion());
        info.put("tags", cluster.getTags());
        if (TYPE_SERVERLESS.equals(cluster.getClusterType())) {
            info.put("serverless", cluster.getServerless());
        } else {
            Map<String, Object> provisioned = new LinkedHashMap<>();
            if (cluster.getCurrentBrokerSoftwareInfo() != null) {
                provisioned.put("currentBrokerSoftwareInfo", cluster.getCurrentBrokerSoftwareInfo());
            }
            provisioned.put("numberOfBrokerNodes", cluster.getNumberOfBrokerNodes());
            provisioned.put("zookeeperConnectString", cluster.getZookeeperConnectString());
            info.put("provisioned", provisioned);
        }
        return info;
    }

    public MskTopic createTopic(String clusterArn, Map<String, Object> request) {
        String topicName = text(request, "topicName");
        Integer partitionCount = integer(request.get("partitionCount"));
        if (topicName == null || topicName.isBlank() || partitionCount == null || partitionCount < 1) {
            throw new AwsException("BadRequestException",
                    "TopicName and PartitionCount are required.", 400);
        }
        MskCluster cluster;
        try {
            cluster = describeCluster(clusterArn);
        } catch (AwsException e) {
            // Live AWS validates the request body before resolving the cluster, so a
            // well-formed create against a missing cluster is a typed 400.
            if ("NotFoundException".equals(e.getErrorCode())) {
                throw new AwsException("BadRequestException",
                        "Cluster not found: " + decode(clusterArn), 400);
            }
            throw e;
        }
        Map<String, MskTopic> topics = cluster.getTopics();
        if (topics.containsKey(topicName)) {
            throw new AwsException("TopicExistsException", "Topic already exists: " + topicName, 409);
        }
        Integer replicationFactor = integer(request.get("replicationFactor"));
        String topicArn = topicArn(cluster, topicName);
        MskTopic topic = new MskTopic(topicArn, topicName, partitionCount,
                replicationFactor == null ? 1 : replicationFactor);
        topics.put(topicName, topic);
        putCluster(cluster);
        return topic;
    }

    public List<MskTopic> listTopics(String clusterArn, String topicNameFilter) {
        MskCluster cluster = describeCluster(clusterArn);
        List<MskTopic> result = new ArrayList<>();
        for (MskTopic topic : cluster.getTopics().values()) {
            if (topicNameFilter != null && !topicNameFilter.isBlank()
                    && (topic.getTopicName() == null || !topic.getTopicName().contains(topicNameFilter))) {
                continue;
            }
            result.add(topic);
        }
        return result;
    }

    public MskTopic describeTopic(String clusterArn, String topicName) {
        MskCluster cluster = describeCluster(clusterArn);
        MskTopic topic = cluster.getTopics().get(topicName);
        if (topic == null) {
            throw new AwsException("NotFoundException", "Topic not found: " + topicName, 404);
        }
        return topic;
    }

    public MskTopic deleteTopic(String clusterArn, String topicName) {
        MskCluster cluster = describeCluster(clusterArn);
        MskTopic topic = cluster.getTopics().remove(topicName);
        if (topic == null) {
            throw new AwsException("NotFoundException", "Topic not found: " + topicName, 404);
        }
        topic.setStatus("DELETING");
        putCluster(cluster);
        return topic;
    }

    public Map<String, String> listTags(String resourceArn) {
        return new LinkedHashMap<>(describeCluster(resourceArn).getTags());
    }

    public void tagResource(String resourceArn, Map<String, String> tags) {
        MskCluster cluster = describeCluster(resourceArn);
        if (tags != null) {
            cluster.getTags().putAll(tags);
            putCluster(cluster);
        }
    }

    public void untagResource(String resourceArn, List<String> tagKeys) {
        MskCluster cluster = describeCluster(resourceArn);
        if (tagKeys != null) {
            for (String key : tagKeys) {
                cluster.getTags().remove(key);
            }
            putCluster(cluster);
        }
    }

    static String decode(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        try {
            String decoded = value;
            for (int i = 0; i < 2; i++) {
                String next = URLDecoder.decode(decoded, StandardCharsets.UTF_8);
                if (next.equals(decoded)) {
                    break;
                }
                decoded = next;
            }
            return decoded;
        } catch (IllegalArgumentException e) {
            return value;
        }
    }

    private void startReadinessPoller() {
        poller.scheduleAtFixedRate(() -> {
            try {
                for (MskCluster cluster : allClusters()) {
                    if (cluster.getState() == ClusterState.CREATING && !config.services().msk().mock()) {
                        if (redpandaManager.isReady(cluster)) {
                            LOG.infov("MSK Cluster {0} is now ACTIVE", cluster.getClusterName());
                            cluster.setState(ClusterState.ACTIVE);
                            putCluster(cluster);
                        }
                    }
                }
            } catch (Exception e) {
                LOG.error("Error in MSK readiness poller", e);
            }
        }, 1, 2, TimeUnit.SECONDS);
    }

    private List<MskCluster> allClusters() {
        if (storage instanceof AccountAwareStorageBackend<MskCluster> aware) {
            return aware.scanAllAccounts();
        }
        return storage.scan(k -> true);
    }

    private void putCluster(MskCluster cluster) {
        if (cluster.getAccountId() != null && storage instanceof AccountAwareStorageBackend<MskCluster> aware) {
            aware.putForAccount(cluster.getAccountId(), cluster.getClusterArn(), cluster);
        } else {
            storage.put(cluster.getClusterArn(), cluster);
        }
    }

    private static String topicArn(MskCluster cluster, String topicName) {
        String clusterArn = cluster.getClusterArn();
        int clusterIdx = clusterArn.indexOf(":cluster/");
        if (clusterIdx < 0) {
            return clusterArn + "/" + topicName;
        }
        return clusterArn.substring(0, clusterIdx) + ":topic/"
                + clusterArn.substring(clusterIdx + ":cluster/".length()) + "/" + topicName;
    }

    private static String rewritePort(String bootstrap, int port) {
        int colon = bootstrap.lastIndexOf(':');
        if (colon < 0) {
            return bootstrap + ":" + port;
        }
        return bootstrap.substring(0, colon + 1) + port;
    }

    private static boolean iamEnabled(Map<String, Object> serverless) {
        Map<String, Object> auth = child(serverless, "clientAuthentication");
        Map<String, Object> sasl = child(auth, "sasl");
        Map<String, Object> iam = child(sasl, "iam");
        if (iam == null) {
            return true;
        }
        Object enabled = iam.get("enabled");
        if (enabled == null) {
            return true;
        }
        if (enabled instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(String.valueOf(enabled));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> vpcConfigs(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object entry : list) {
            if (entry instanceof Map<?, ?> map) {
                result.add(new LinkedHashMap<>((Map<String, Object>) map));
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> child(Map<String, Object> parent, String key) {
        if (parent == null) {
            return null;
        }
        Object value = parent.get(key);
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> stringMap(Object value) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (!(value instanceof Map<?, ?> map)) {
            return tags;
        }
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                tags.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
            }
        }
        return tags;
    }

    private static String text(Map<String, Object> map, String key) {
        if (map == null) {
            return null;
        }
        Object value = map.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static Integer integer(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

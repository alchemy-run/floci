package io.github.hectorvent.floci.services.docdbelastic;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.docdbelastic.model.Cluster;
import io.github.hectorvent.floci.services.docdbelastic.model.ClusterSnapshot;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Amazon DocumentDB Elastic restJson1 — elastic cluster lifecycle.
 *
 * <p>Clusters become {@code ACTIVE} immediately so local stacks do not wait on
 * the live-AWS asynchronous provisioning window. Tag APIs share {@code /tags/{arn}}
 * and are dispatched by {@code SharedTagsController} using ARN service
 * {@code docdb-elastic}.
 */
@ApplicationScoped
public class DocDbElasticService implements TagHandler {

    static final String SERVICE = "docdb-elastic";
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_DELETING = "DELETING";
    private static final String STATUS_STOPPED = "STOPPED";
    private static final String RESOURCE_CLUSTER = "cluster";
    private static final String RESOURCE_SNAPSHOT = "cluster-snapshot";
    private static final String STATUS_AVAILABLE = "AVAILABLE";
    private static final String DEFAULT_AUTH_TYPE = "PLAIN_TEXT";
    private static final String DEFAULT_MAINTENANCE = "sun:23:00-mon:01:30";
    private static final String DEFAULT_BACKUP_WINDOW = "00:00-00:30";
    private static final int DEFAULT_BACKUP_RETENTION = 1;
    private static final int DEFAULT_SHARD_INSTANCE_COUNT = 1;
    private static final Set<Integer> SHARD_CAPACITIES = Set.of(2, 4, 8, 16, 32, 64);
    private static final Set<String> AUTH_TYPES = Set.of("PLAIN_TEXT", "SECRET_ARN");

    private final StorageBackend<String, Cluster> clusters;
    private final StorageBackend<String, ClusterSnapshot> snapshots;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public DocDbElasticService(
            StorageFactory storageFactory, RegionResolver regionResolver, ObjectMapper objectMapper) {
        this(
                storageFactory.create("docdbelastic", "docdb-elastic-clusters.json",
                        new TypeReference<Map<String, Cluster>>() {
                        }),
                storageFactory.create("docdbelastic", "docdb-elastic-snapshots.json",
                        new TypeReference<Map<String, ClusterSnapshot>>() {
                        }),
                regionResolver,
                objectMapper);
    }

    DocDbElasticService(StorageBackend<String, Cluster> clusters, RegionResolver regionResolver) {
        this(clusters, new InMemoryStorage<>(), regionResolver, new ObjectMapper());
    }

    DocDbElasticService(
            StorageBackend<String, Cluster> clusters,
            RegionResolver regionResolver,
            ObjectMapper objectMapper) {
        this(clusters, new InMemoryStorage<>(), regionResolver, objectMapper);
    }

    DocDbElasticService(
            StorageBackend<String, Cluster> clusters,
            StorageBackend<String, ClusterSnapshot> snapshots,
            RegionResolver regionResolver,
            ObjectMapper objectMapper) {
        this.clusters = clusters;
        this.snapshots = snapshots;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    public synchronized Cluster createCluster(String region, JsonNode request) {
        requireObject(request, "Request body");
        String name = requireText(request, "clusterName");
        if (findByName(region, name) != null) {
            throw conflict(name, RESOURCE_CLUSTER,
                    "Elastic cluster " + name + " already exists.");
        }
        String authType = optionalText(request, "authType");
        if (authType == null) {
            authType = DEFAULT_AUTH_TYPE;
        }
        if (!AUTH_TYPES.contains(authType)) {
            throw validation("authType must be PLAIN_TEXT or SECRET_ARN.", "fieldValidationFailed");
        }
        String adminUserName = requireText(request, "adminUserName");
        String adminUserPassword = requireText(request, "adminUserPassword");
        int shardCapacity = requireInt(request, "shardCapacity");
        if (!SHARD_CAPACITIES.contains(shardCapacity)) {
            throw validation("shardCapacity must be one of 2, 4, 8, 16, 32, 64.", "fieldValidationFailed");
        }
        int shardCount = requireInt(request, "shardCount");
        if (shardCount < 1 || shardCount > 32) {
            throw validation("shardCount must be between 1 and 32.", "fieldValidationFailed");
        }
        Integer shardInstanceCount = optionalInt(request, "shardInstanceCount");
        if (shardInstanceCount == null) {
            shardInstanceCount = DEFAULT_SHARD_INSTANCE_COUNT;
        }
        if (shardInstanceCount < 1 || shardInstanceCount > 16) {
            throw validation("shardInstanceCount must be between 1 and 16.", "fieldValidationFailed");
        }

        String clusterId = UUID.randomUUID().toString();
        String account = regionResolver.getAccountId();
        String now = Instant.now().toString();
        String kmsKeyId = optionalText(request, "kmsKeyId");
        if (kmsKeyId == null) {
            kmsKeyId = "arn:aws:kms:" + region + ":" + account + ":alias/aws/docdb-elastic";
        }

        Cluster cluster = new Cluster();
        cluster.setClusterName(name);
        cluster.setClusterId(clusterId);
        cluster.setClusterArn(clusterArn(region, account, clusterId));
        cluster.setStatus(STATUS_ACTIVE);
        cluster.setClusterEndpoint(name + "." + clusterId + "." + region + ".docdb-elastic.amazonaws.com");
        cluster.setCreateTime(now);
        cluster.setAdminUserName(adminUserName);
        cluster.setAdminUserPassword(adminUserPassword);
        cluster.setAuthType(authType);
        cluster.setShardCapacity(shardCapacity);
        cluster.setShardCount(shardCount);
        cluster.setShardInstanceCount(shardInstanceCount);
        cluster.setVpcSecurityGroupIds(stringList(request.get("vpcSecurityGroupIds")));
        cluster.setSubnetIds(stringList(request.get("subnetIds")));
        String maintenance = optionalText(request, "preferredMaintenanceWindow");
        cluster.setPreferredMaintenanceWindow(maintenance == null ? DEFAULT_MAINTENANCE : maintenance);
        cluster.setKmsKeyId(kmsKeyId);
        Integer retention = optionalInt(request, "backupRetentionPeriod");
        cluster.setBackupRetentionPeriod(retention == null ? DEFAULT_BACKUP_RETENTION : retention);
        String backupWindow = optionalText(request, "preferredBackupWindow");
        cluster.setPreferredBackupWindow(backupWindow == null ? DEFAULT_BACKUP_WINDOW : backupWindow);
        cluster.setShards(buildShards(shardCount, now));
        cluster.setTags(readTags(request.get("tags")));
        cluster.setRegion(region);
        clusters.put(clusterKey(region, clusterId), cluster);
        return cluster;
    }

    public Cluster getCluster(String region, String clusterArn) {
        return requireClusterByArn(region, clusterArn);
    }

    public List<Cluster> listClusters(String region) {
        List<Cluster> items = clusters.scan(key -> key.startsWith(region + "::"));
        items.sort(Comparator.comparing(Cluster::getClusterName, Comparator.nullsLast(String::compareTo)));
        return items;
    }

    public synchronized Cluster updateCluster(String region, String clusterArn, JsonNode request) {
        requireObject(request, "Request body");
        Cluster cluster = requireClusterByArn(region, clusterArn);
        if (request.hasNonNull("shardCapacity")) {
            int shardCapacity = requireInt(request, "shardCapacity");
            if (!SHARD_CAPACITIES.contains(shardCapacity)) {
                throw validation("shardCapacity must be one of 2, 4, 8, 16, 32, 64.", "fieldValidationFailed");
            }
            cluster.setShardCapacity(shardCapacity);
        }
        if (request.hasNonNull("shardCount")) {
            int shardCount = requireInt(request, "shardCount");
            if (shardCount < 1 || shardCount > 32) {
                throw validation("shardCount must be between 1 and 32.", "fieldValidationFailed");
            }
            cluster.setShardCount(shardCount);
            cluster.setShards(buildShards(shardCount, cluster.getCreateTime()));
        }
        if (request.hasNonNull("shardInstanceCount")) {
            int shardInstanceCount = requireInt(request, "shardInstanceCount");
            if (shardInstanceCount < 1 || shardInstanceCount > 16) {
                throw validation("shardInstanceCount must be between 1 and 16.", "fieldValidationFailed");
            }
            cluster.setShardInstanceCount(shardInstanceCount);
        }
        if (request.has("vpcSecurityGroupIds")) {
            cluster.setVpcSecurityGroupIds(stringList(request.get("vpcSecurityGroupIds")));
        }
        if (request.has("subnetIds")) {
            cluster.setSubnetIds(stringList(request.get("subnetIds")));
        }
        if (request.hasNonNull("preferredMaintenanceWindow")) {
            cluster.setPreferredMaintenanceWindow(requireText(request, "preferredMaintenanceWindow"));
        }
        if (request.hasNonNull("backupRetentionPeriod")) {
            cluster.setBackupRetentionPeriod(requireInt(request, "backupRetentionPeriod"));
        }
        if (request.hasNonNull("preferredBackupWindow")) {
            cluster.setPreferredBackupWindow(requireText(request, "preferredBackupWindow"));
        }
        if (request.hasNonNull("authType")) {
            String authType = requireText(request, "authType");
            if (!AUTH_TYPES.contains(authType)) {
                throw validation("authType must be PLAIN_TEXT or SECRET_ARN.", "fieldValidationFailed");
            }
            cluster.setAuthType(authType);
        }
        if (request.hasNonNull("adminUserPassword")) {
            cluster.setAdminUserPassword(requireText(request, "adminUserPassword"));
        }
        clusters.put(clusterKey(region, cluster.getClusterId()), cluster);
        return cluster;
    }

    public synchronized Cluster deleteCluster(String region, String clusterArn) {
        Cluster cluster = requireClusterByArn(region, clusterArn);
        clusters.delete(clusterKey(region, cluster.getClusterId()));
        cluster.setStatus(STATUS_DELETING);
        return cluster;
    }

    public synchronized Cluster startCluster(String region, String clusterArn) {
        Cluster cluster = requireClusterByArn(region, clusterArn);
        cluster.setStatus(STATUS_ACTIVE);
        clusters.put(clusterKey(region, cluster.getClusterId()), cluster);
        return cluster;
    }

    public synchronized Cluster stopCluster(String region, String clusterArn) {
        Cluster cluster = requireClusterByArn(region, clusterArn);
        cluster.setStatus(STATUS_STOPPED);
        clusters.put(clusterKey(region, cluster.getClusterId()), cluster);
        return cluster;
    }

    public synchronized ClusterSnapshot createClusterSnapshot(String region, JsonNode request) {
        requireObject(request, "Request body");
        String clusterArn = requireText(request, "clusterArn");
        String snapshotName = requireText(request, "snapshotName");
        Cluster cluster = requireClusterByArn(region, clusterArn);
        if (findSnapshotByName(region, snapshotName) != null) {
            throw conflict(snapshotName, RESOURCE_SNAPSHOT,
                    "Elastic cluster snapshot " + snapshotName + " already exists.");
        }
        return persistSnapshot(region, snapshotName, cluster, optionalText(request, "kmsKeyId"),
                readTags(request.get("tags")), "MANUAL");
    }

    public ClusterSnapshot getClusterSnapshot(String region, String snapshotArn) {
        return requireSnapshotByArn(region, snapshotArn);
    }

    public List<ClusterSnapshot> listClusterSnapshots(String region) {
        List<ClusterSnapshot> items = snapshots.scan(key -> key.startsWith(region + "::"));
        items.sort(Comparator.comparing(ClusterSnapshot::getSnapshotName, Comparator.nullsLast(String::compareTo)));
        return items;
    }

    public synchronized ClusterSnapshot deleteClusterSnapshot(String region, String snapshotArn) {
        ClusterSnapshot snapshot = requireSnapshotByArn(region, snapshotArn);
        snapshots.delete(snapshotKey(region, snapshot.getSnapshotId()));
        snapshot.setStatus(STATUS_DELETING);
        return snapshot;
    }

    public synchronized ClusterSnapshot copyClusterSnapshot(String region, String snapshotArn, JsonNode request) {
        requireObject(request, "Request body");
        ClusterSnapshot source = requireSnapshotByArn(region, snapshotArn);
        String targetName = requireText(request, "targetSnapshotName");
        if (findSnapshotByName(region, targetName) != null) {
            throw conflict(targetName, RESOURCE_SNAPSHOT,
                    "Elastic cluster snapshot " + targetName + " already exists.");
        }
        Cluster cluster = requireClusterByArn(region, source.getClusterArn());
        Map<String, String> tags = readTags(request.get("tags"));
        if (tags.isEmpty() && Boolean.TRUE.equals(optionalBoolean(request, "copyTags"))) {
            tags = new LinkedHashMap<>(source.getTags() == null ? Map.of() : source.getTags());
        }
        return persistSnapshot(region, targetName, cluster, optionalText(request, "kmsKeyId"), tags, "MANUAL");
    }

    public synchronized Cluster restoreClusterFromSnapshot(String region, String snapshotArn, JsonNode request) {
        requireObject(request, "Request body");
        ClusterSnapshot snapshot = requireSnapshotByArn(region, snapshotArn);
        String name = requireText(request, "clusterName");
        ObjectNode create = objectMapper.createObjectNode();
        create.put("clusterName", name);
        create.put("authType", DEFAULT_AUTH_TYPE);
        create.put("adminUserName", snapshot.getAdminUserName());
        create.put("adminUserPassword", "RestoredPassw0rd");
        create.put("shardCapacity", optionalInt(request, "shardCapacity") == null
                ? 2 : optionalInt(request, "shardCapacity"));
        create.put("shardCount", 1);
        if (optionalInt(request, "shardInstanceCount") != null) {
            create.put("shardInstanceCount", optionalInt(request, "shardInstanceCount"));
        }
        if (request.has("vpcSecurityGroupIds")) {
            create.set("vpcSecurityGroupIds", request.get("vpcSecurityGroupIds"));
        } else {
            putStringList(create, "vpcSecurityGroupIds", snapshot.getVpcSecurityGroupIds());
        }
        if (request.has("subnetIds")) {
            create.set("subnetIds", request.get("subnetIds"));
        } else {
            putStringList(create, "subnetIds", snapshot.getSubnetIds());
        }
        String kms = optionalText(request, "kmsKeyId");
        if (kms != null) {
            create.put("kmsKeyId", kms);
        } else if (snapshot.getKmsKeyId() != null) {
            create.put("kmsKeyId", snapshot.getKmsKeyId());
        }
        if (request.has("tags")) {
            create.set("tags", request.get("tags"));
        }
        return createCluster(region, create);
    }

    public ObjectNode toSnapshotEnvelope(ClusterSnapshot snapshot) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("snapshot", toSnapshot(snapshot));
        return response;
    }

    public ObjectNode toListSnapshots(List<ClusterSnapshot> items) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("snapshots");
        for (ClusterSnapshot snapshot : items) {
            ObjectNode summary = list.addObject();
            summary.put("snapshotName", snapshot.getSnapshotName());
            summary.put("snapshotArn", snapshot.getSnapshotArn());
            summary.put("clusterArn", snapshot.getClusterArn());
            summary.put("status", snapshot.getStatus());
            summary.put("snapshotCreationTime", snapshot.getSnapshotCreationTime());
        }
        return response;
    }

    public ObjectNode listPendingMaintenanceActions() {
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("resourcePendingMaintenanceActions");
        return response;
    }

    public ObjectNode getPendingMaintenanceAction(String region, String resourceArn) {
        Cluster cluster = requireClusterByArn(region, resourceArn);
        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode action = response.putObject("resourcePendingMaintenanceAction");
        action.put("resourceArn", cluster.getClusterArn());
        action.putArray("pendingMaintenanceActionDetails");
        return response;
    }

    public ObjectNode applyPendingMaintenanceAction(String region, JsonNode request) {
        requireObject(request, "Request body");
        String resourceArn = requireText(request, "resourceArn");
        requireText(request, "applyAction");
        requireText(request, "optInType");
        return getPendingMaintenanceAction(region, resourceArn);
    }

    public ObjectNode toClusterEnvelope(Cluster cluster) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("cluster", toCluster(cluster));
        return response;
    }

    public ObjectNode toCluster(Cluster cluster) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("clusterName", cluster.getClusterName());
        node.put("clusterArn", cluster.getClusterArn());
        node.put("status", cluster.getStatus());
        if (cluster.getClusterEndpoint() != null) {
            node.put("clusterEndpoint", cluster.getClusterEndpoint());
        }
        node.put("createTime", cluster.getCreateTime());
        node.put("adminUserName", cluster.getAdminUserName());
        node.put("authType", cluster.getAuthType());
        node.put("shardCapacity", cluster.getShardCapacity());
        node.put("shardCount", cluster.getShardCount());
        putStringList(node, "vpcSecurityGroupIds", cluster.getVpcSecurityGroupIds());
        putStringList(node, "subnetIds", cluster.getSubnetIds());
        node.put("preferredMaintenanceWindow", cluster.getPreferredMaintenanceWindow());
        node.put("kmsKeyId", cluster.getKmsKeyId());
        if (cluster.getBackupRetentionPeriod() != null) {
            node.put("backupRetentionPeriod", cluster.getBackupRetentionPeriod());
        }
        if (cluster.getPreferredBackupWindow() != null) {
            node.put("preferredBackupWindow", cluster.getPreferredBackupWindow());
        }
        if (cluster.getShardInstanceCount() != null) {
            node.put("shardInstanceCount", cluster.getShardInstanceCount());
        }
        if (cluster.getShards() != null && !cluster.getShards().isEmpty()) {
            ArrayNode shards = node.putArray("shards");
            for (Cluster.Shard shard : cluster.getShards()) {
                ObjectNode shardNode = shards.addObject();
                shardNode.put("shardId", shard.getShardId());
                shardNode.put("createTime", shard.getCreateTime());
                shardNode.put("status", shard.getStatus());
            }
        }
        return node;
    }

    public ObjectNode toListClusters(List<Cluster> items) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("clusters");
        for (Cluster cluster : items) {
            ObjectNode summary = list.addObject();
            summary.put("clusterName", cluster.getClusterName());
            summary.put("clusterArn", cluster.getClusterArn());
            summary.put("status", cluster.getStatus());
        }
        return response;
    }

    @Override
    public String serviceKey() {
        return SERVICE;
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        Cluster cluster = requireClusterByArn(region, arn);
        return Map.copyOf(cluster.getTags() == null ? Map.of() : cluster.getTags());
    }

    @Override
    public synchronized void tagResource(String region, String arn, Map<String, String> tags) {
        Cluster cluster = requireClusterByArn(region, arn);
        Map<String, String> current = new LinkedHashMap<>(
                cluster.getTags() == null ? Map.of() : cluster.getTags());
        if (tags != null) {
            current.putAll(tags);
        }
        cluster.setTags(current);
        clusters.put(clusterKey(region, cluster.getClusterId()), cluster);
    }

    @Override
    public synchronized void untagResource(String region, String arn, List<String> tagKeys) {
        Cluster cluster = requireClusterByArn(region, arn);
        Map<String, String> current = new LinkedHashMap<>(
                cluster.getTags() == null ? Map.of() : cluster.getTags());
        if (tagKeys != null) {
            tagKeys.forEach(current::remove);
        }
        cluster.setTags(current);
        clusters.put(clusterKey(region, cluster.getClusterId()), cluster);
    }

    private ClusterSnapshot persistSnapshot(
            String region,
            String snapshotName,
            Cluster cluster,
            String kmsKeyId,
            Map<String, String> tags,
            String snapshotType) {
        String snapshotId = UUID.randomUUID().toString();
        String account = regionResolver.getAccountId();
        String now = Instant.now().toString();
        ClusterSnapshot snapshot = new ClusterSnapshot();
        snapshot.setSnapshotName(snapshotName);
        snapshot.setSnapshotId(snapshotId);
        snapshot.setSnapshotArn(snapshotArn(region, account, snapshotId));
        snapshot.setClusterArn(cluster.getClusterArn());
        snapshot.setClusterCreationTime(cluster.getCreateTime());
        snapshot.setSnapshotCreationTime(now);
        snapshot.setStatus(STATUS_AVAILABLE);
        snapshot.setAdminUserName(cluster.getAdminUserName());
        snapshot.setKmsKeyId(kmsKeyId == null ? cluster.getKmsKeyId() : kmsKeyId);
        snapshot.setSnapshotType(snapshotType);
        snapshot.setSubnetIds(cluster.getSubnetIds());
        snapshot.setVpcSecurityGroupIds(cluster.getVpcSecurityGroupIds());
        snapshot.setTags(tags == null ? new LinkedHashMap<>() : tags);
        snapshot.setRegion(region);
        snapshots.put(snapshotKey(region, snapshotId), snapshot);
        return snapshot;
    }

    private ObjectNode toSnapshot(ClusterSnapshot snapshot) {
        ObjectNode node = objectMapper.createObjectNode();
        putStringList(node, "subnetIds", snapshot.getSubnetIds());
        node.put("snapshotName", snapshot.getSnapshotName());
        node.put("snapshotArn", snapshot.getSnapshotArn());
        node.put("snapshotCreationTime", snapshot.getSnapshotCreationTime());
        node.put("clusterArn", snapshot.getClusterArn());
        node.put("clusterCreationTime", snapshot.getClusterCreationTime());
        node.put("status", snapshot.getStatus());
        putStringList(node, "vpcSecurityGroupIds", snapshot.getVpcSecurityGroupIds());
        node.put("adminUserName", snapshot.getAdminUserName());
        node.put("kmsKeyId", snapshot.getKmsKeyId());
        if (snapshot.getSnapshotType() != null) {
            node.put("snapshotType", snapshot.getSnapshotType());
        }
        return node;
    }

    private ClusterSnapshot findSnapshotByName(String region, String name) {
        for (ClusterSnapshot snapshot : listClusterSnapshots(region)) {
            if (name.equals(snapshot.getSnapshotName())) {
                return snapshot;
            }
        }
        return null;
    }

    private ClusterSnapshot requireSnapshotByArn(String region, String snapshotArn) {
        String decoded = decode(snapshotArn);
        if (decoded == null || decoded.isBlank()) {
            throw validation("snapshotArn is required.", "fieldValidationFailed");
        }
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(decoded);
        } catch (IllegalArgumentException e) {
            throw notFound(decoded, RESOURCE_SNAPSHOT);
        }
        if (!SERVICE.equals(parsed.service()) || parsed.resource() == null
                || !parsed.resource().startsWith("cluster-snapshot/")) {
            throw notFound(decoded, RESOURCE_SNAPSHOT);
        }
        String snapshotId = parsed.resource().substring("cluster-snapshot/".length());
        if (snapshotId.isBlank() || snapshotId.contains("/")) {
            throw notFound(decoded, RESOURCE_SNAPSHOT);
        }
        String lookupRegion = parsed.region() == null || parsed.region().isBlank() ? region : parsed.region();
        return snapshots.get(snapshotKey(lookupRegion, snapshotId))
                .orElseThrow(() -> notFound(decoded, RESOURCE_SNAPSHOT));
    }

    private Cluster findByName(String region, String name) {
        for (Cluster cluster : listClusters(region)) {
            if (name.equals(cluster.getClusterName())) {
                return cluster;
            }
        }
        return null;
    }

    private Cluster requireClusterByArn(String region, String clusterArn) {
        String decoded = decode(clusterArn);
        if (decoded == null || decoded.isBlank()) {
            throw validation("clusterArn is required.", "fieldValidationFailed");
        }
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(decoded);
        } catch (IllegalArgumentException e) {
            throw notFound(decoded, RESOURCE_CLUSTER);
        }
        if (!SERVICE.equals(parsed.service()) || parsed.resource() == null
                || !parsed.resource().startsWith("cluster/")) {
            throw notFound(decoded, RESOURCE_CLUSTER);
        }
        String clusterId = parsed.resource().substring("cluster/".length());
        if (clusterId.isBlank() || clusterId.contains("/")) {
            throw notFound(decoded, RESOURCE_CLUSTER);
        }
        String lookupRegion = parsed.region() == null || parsed.region().isBlank() ? region : parsed.region();
        return clusters.get(clusterKey(lookupRegion, clusterId))
                .orElseThrow(() -> notFound(decoded, RESOURCE_CLUSTER));
    }

    private static List<Cluster.Shard> buildShards(int shardCount, String createTime) {
        List<Cluster.Shard> shards = new ArrayList<>();
        for (int i = 0; i < shardCount; i++) {
            shards.add(new Cluster.Shard("rd-" + (i + 1), createTime, STATUS_ACTIVE));
        }
        return shards;
    }

    private static String clusterArn(String region, String account, String clusterId) {
        return AwsArnUtils.Arn.of(SERVICE, region, account, "cluster/" + clusterId).toString();
    }

    private static String clusterKey(String region, String clusterId) {
        return region + "::" + clusterId;
    }

    private static String snapshotArn(String region, String account, String snapshotId) {
        return AwsArnUtils.Arn.of(SERVICE, region, account, "cluster-snapshot/" + snapshotId).toString();
    }

    private static String snapshotKey(String region, String snapshotId) {
        return region + "::" + snapshotId;
    }

    private static Boolean optionalBoolean(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isBoolean()) {
            throw validation(field + " must be a boolean.", "fieldValidationFailed");
        }
        return value.booleanValue();
    }

    private static void putStringList(ObjectNode node, String field, List<String> values) {
        ArrayNode list = node.putArray(field);
        if (values != null) {
            values.forEach(list::add);
        }
    }

    private static void requireObject(JsonNode value, String field) {
        if (value == null || !value.isObject()) {
            throw validation(field + " must be a JSON object.", "cannotParse");
        }
    }

    private static String requireText(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw validation(field + " is required.", "fieldValidationFailed");
        }
        return value.textValue();
    }

    private static int requireInt(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isNumber()) {
            throw validation(field + " is required.", "fieldValidationFailed");
        }
        return value.asInt();
    }

    private static Integer optionalInt(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isNumber()) {
            throw validation(field + " must be a number.", "fieldValidationFailed");
        }
        return value.asInt();
    }

    private static String optionalText(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw validation(field + " must be a string.", "fieldValidationFailed");
        }
        String text = value.textValue();
        return text.isBlank() ? null : text;
    }

    private static List<String> stringList(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node == null || node.isNull()) {
            return values;
        }
        if (!node.isArray()) {
            throw validation("value must be an array of strings.", "fieldValidationFailed");
        }
        for (JsonNode item : node) {
            if (!item.isTextual() || item.textValue().isBlank()) {
                throw validation("array values must be strings.", "fieldValidationFailed");
            }
            values.add(item.textValue());
        }
        return values;
    }

    private static Map<String, String> readTags(JsonNode tagsNode) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (tagsNode == null || tagsNode.isNull()) {
            return tags;
        }
        if (!tagsNode.isObject()) {
            throw validation("tags must be an object.", "fieldValidationFailed");
        }
        tagsNode.fields().forEachRemaining(entry -> {
            if (!entry.getValue().isTextual()) {
                throw validation("tags values must be strings.", "fieldValidationFailed");
            }
            tags.put(entry.getKey(), entry.getValue().textValue());
        });
        return tags;
    }

    private static String decode(String value) {
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

    private static AwsException validation(String message, String reason) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("reason", reason);
        return new AwsException("ValidationException", message, 400, extra);
    }

    private static AwsException notFound(String resourceId, String resourceType) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("resourceId", resourceId);
        extra.put("resourceType", resourceType);
        return new AwsException(
                "ResourceNotFoundException",
                resourceType + " " + resourceId + " not found.",
                404,
                extra);
    }

    private static AwsException conflict(String resourceId, String resourceType, String message) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("resourceId", resourceId);
        extra.put("resourceType", resourceType);
        return new AwsException("ConflictException", message, 409, extra);
    }
}
